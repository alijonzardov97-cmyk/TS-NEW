package com.ts.messenger.net

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Builds hardened OkHttp clients.
 *
 * - TLS 1.2+ with modern cipher suites only (ConnectionSpec.RESTRICTED_TLS).
 * - Redirects are never followed, so a credentials request cannot be bounced to another host.
 * - No HTTP logging interceptor exists anywhere in the app: requests carry passwords and tokens.
 * - When pins are known, the connection is refused unless the server presents a certificate
 *   whose public key matches one of them.
 */
object HttpClientFactory {
    fun create(host: String?, pins: List<String>): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))

        if (host != null && pins.isNotEmpty()) {
            val pinner = CertificatePinner.Builder().apply {
                pins.forEach { add(host, it) }
            }.build()
            builder.certificatePinner(pinner)
        }
        return builder.build()
    }
}
