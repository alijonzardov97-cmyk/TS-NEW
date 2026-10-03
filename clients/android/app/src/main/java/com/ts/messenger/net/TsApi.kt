package com.ts.messenger.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException

class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** Raised when the server certificate does not match the saved pins. */
class CertificateChangedException : Exception("certificate changed")

/** Raised when the device cannot reach the server at all. */
class NetworkException(cause: Throwable) : Exception(cause)

class ServerProbe(val baseUrl: String, val leafFingerprint: String, val pins: List<String>)

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Normalises user input into an https base URL, or null if it is not acceptable. */
fun parseServerUrl(input: String): HttpUrl? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (s.startsWith("http://", ignoreCase = true)) return null // cleartext is never allowed
    if (!s.startsWith("https://", ignoreCase = true)) s = "https://$s"
    val url = s.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.encodedPath != "/" || url.query != null || url.fragment != null) return null
    return url
}

/**
 * Talks to the TS server. All requests go through a pinned client; there is deliberately no
 * way to disable certificate validation.
 */
class TsApi(private val baseUrl: HttpUrl, private val client: OkHttpClient) {

    /** Large transfers: no overall call limit, generous per-read/write limits. */
    private val bulkClient by lazy {
        client.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun url(path: String) = baseUrl.newBuilder().encodedPath("/api$path").build()

    private suspend fun <T> call(request: Request, http: OkHttpClient = client, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val response: Response = try {
                http.newCall(request).execute()
            } catch (e: SSLPeerUnverifiedException) {
                throw CertificateChangedException()
            } catch (e: IOException) {
                throw NetworkException(e)
            }
            response.use { r ->
                // Cap the body so a hostile server cannot exhaust memory.
                val body = r.body?.source()?.let { src ->
                    src.request(MAX_BODY_BYTES + 1)
                    src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY_BYTES))
                } ?: ""
                if (r.isSuccessful) return@use parse(body)
                val err = runCatching { AppJson.decodeFromString<ErrorEnvelope>(body).error }.getOrNull()
                throw ApiException(r.code, err?.code ?: "", err?.message ?: "")
            }
        }

    suspend fun health(): HealthResponse =
        call(Request.Builder().url(url("/health")).get().build()) {
            AppJson.decodeFromString(it)
        }

    suspend fun config(): ServerConfig =
        call(Request.Builder().url(url("/auth/config")).get().build()) {
            AppJson.decodeFromString(it)
        }

    suspend fun login(req: LoginRequest): AuthResponse =
        call(post("/auth/login", AppJson.encodeToString(req))) { AppJson.decodeFromString(it) }

    suspend fun register(req: RegisterRequest): AuthResponse =
        call(post("/auth/register", AppJson.encodeToString(req))) { AppJson.decodeFromString(it) }

    suspend fun refresh(refreshToken: String): TokenResponse =
        call(post("/auth/refresh", AppJson.encodeToString(RefreshRequest(refreshToken)))) {
            AppJson.decodeFromString(it)
        }

    // ── Authenticated calls (bearer token, one transparent refresh on 401) ──

    /** Set by the app once a session exists. */
    var session: Session? = null

    private suspend fun <T> authed(make: (token: String) -> Request, http: OkHttpClient = client, parse: (String) -> T): T {
        val s = session ?: throw ApiException(401, "", "")
        val token = s.accessToken() ?: throw ApiException(401, "", "")
        return try {
            call(make(token), http, parse)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            val fresh = s.forceRefresh() ?: throw e
            call(make(fresh), http, parse)
        }
    }

    private fun bearer(url: HttpUrl, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun checkedId(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "bad id" }
        return id
    }

    suspend fun listDms(): List<DmChannel> =
        authed({ bearer(url("/dms"), it).get().build() }) { AppJson.decodeFromString(it) }

    suspend fun createDm(userId: String): DmChannel =
        authed({
            bearer(url("/dms"), it)
                .post(AppJson.encodeToString(CreateDmRequest(checkedId(userId))).toRequestBody(JSON_MEDIA)).build()
        }) { AppJson.decodeFromString(it) }

    suspend fun messages(channelId: String, limit: Int = 50): List<MessageDto> =
        authed({
            val u = url("/channels/${checkedId(channelId)}/messages").newBuilder()
                .addQueryParameter("limit", limit.toString()).build()
            bearer(u, it).get().build()
        }) { AppJson.decodeFromString(it) }

    suspend fun keyBundle(userId: String): KeyBundle =
        authed({ bearer(url("/keys/${checkedId(userId)}/bundle"), it).get().build() }) { AppJson.decodeFromString(it) }

    suspend fun registerKeys(req: KeyRegistrationRequest) =
        authed({
            bearer(url("/keys/register"), it)
                .post(AppJson.encodeToString(req).toRequestBody(JSON_MEDIA)).build()
        }) { }

    suspend fun prekeyCount(): Int =
        authed({ bearer(url("/keys/prekeys/count"), it).get().build() }) {
            AppJson.parseToJsonElement(it).let { e ->
                (e as kotlinx.serialization.json.JsonObject)["count"]
                    ?.let { c -> (c as kotlinx.serialization.json.JsonPrimitive).content.toIntOrNull() } ?: 0
            }
        }

    suspend fun uploadOneTimePrekeys(keys: List<OneTimePrekeyUpload>) =
        authed({
            val body = AppJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(OneTimePrekeyUpload.serializer()), keys,
            )
            bearer(url("/keys/prekeys/one-time"), it).post(body.toRequestBody(JSON_MEDIA)).build()
        }) { }

    suspend fun searchUsers(query: String): List<UserPublic> =
        authed({
            val u = url("/users/search").newBuilder().addQueryParameter("q", query).build()
            bearer(u, it).get().build()
        }) { AppJson.decodeFromString(it) }

    /** Public: the server's VAPID key, or an ApiException(404) when push is not configured. */
    suspend fun vapidKey(): String =
        call(Request.Builder().url(url("/push/vapid-key")).get().build()) {
            AppJson.decodeFromString<VapidKeyResponse>(it).publicKey
        }

    suspend fun subscribePush(endpoint: String, p256dh: String, auth: String) =
        authed({
            bearer(url("/push/subscribe"), it)
                .post(AppJson.encodeToString(PushSubscribeRequest(endpoint, p256dh, auth)).toRequestBody(JSON_MEDIA)).build()
        }) { }

    suspend fun unsubscribePush(endpoint: String) =
        authed({
            bearer(url("/push/unsubscribe"), it)
                .post(AppJson.encodeToString(PushUnsubscribeRequest(endpoint)).toRequestBody(JSON_MEDIA)).build()
        }) { }

    /** Uploads an already encrypted blob; the server stores it as opaque bytes. */
    suspend fun uploadEncrypted(channelId: String, blob: java.io.File): FileUploadResponse =
        authed({
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("encrypted", "1")
                .addFormDataPart("channel_id", checkedId(channelId))
                .addFormDataPart("name", "blob") // the real file name is only in the E2E message
                .addFormDataPart("file", "blob", blob.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            bearer(url("/files/upload"), it).post(body).build()
        }, bulkClient) { AppJson.decodeFromString(it) }

    /** Best effort removal of one of our own uploads. */
    suspend fun deleteFile(fileId: String) =
        authed({ bearer(url("/files/${checkedId(fileId)}"), it).delete().build() }) { }

    /** Streams a stored blob to [dest]; fails if it grows beyond [maxBytes]. */
    suspend fun downloadFile(fileId: String, dest: java.io.File, maxBytes: Long) {
        val s = session ?: throw ApiException(401, "", "")
        suspend fun once(token: String) = withContext(Dispatchers.IO) {
            val response = try {
                bulkClient.newCall(bearer(url("/files/${checkedId(fileId)}"), token).get().build()).execute()
            } catch (e: SSLPeerUnverifiedException) {
                throw CertificateChangedException()
            } catch (e: IOException) {
                throw NetworkException(e)
            }
            response.use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) throw ApiException(r.code, "", "")
                try {
                    var total = 0L
                    dest.outputStream().buffered().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw ApiException(0, "too_large", "")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                } catch (e: IOException) {
                    throw NetworkException(e)
                }
            }
        }
        val token = s.accessToken() ?: throw ApiException(401, "", "")
        try {
            once(token)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            once(s.forceRefresh() ?: throw e)
        }
    }

    private fun post(path: String, json: String) =
        Request.Builder().url(url(path)).post(json.toRequestBody(JSON_MEDIA)).build()

    companion object {
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024

        /**
         * First contact with a server: performs a normally validated TLS handshake (system trust
         * store only), checks that it speaks the TS API and returns the certificate fingerprints
         * so the user can compare them before trusting the server.
         */
        suspend fun probe(baseUrl: HttpUrl): ServerProbe = withContext(Dispatchers.IO) {
            val client = HttpClientFactory.create(null, emptyList())
            val request = Request.Builder()
                .url(baseUrl.newBuilder().encodedPath("/api/health").build())
                .get().build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw NetworkException(e)
            }
            response.use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "", "")
                val body = r.body?.string().orEmpty().take(4096)
                val health = runCatching { AppJson.decodeFromString<HealthResponse>(body) }.getOrNull()
                if (health == null || health.status.isEmpty()) throw ApiException(0, "not_ts", "")
                val certs = r.handshake?.peerCertificates.orEmpty()
                if (certs.isEmpty()) throw ApiException(0, "no_cert", "")
                val pins = certs.map { CertificatePinner.pin(it) }
                ServerProbe(baseUrl.toString().trimEnd('/'), pins.first().removePrefix("sha256/"), pins)
            }
        }
    }
}
