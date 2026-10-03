package com.ts.messenger.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server serialises byte arrays as JSON arrays of unsigned numbers (0..255), while Kotlin
 * bytes are signed. These helpers convert between the two.
 */
fun ByteArray.toU8List(): List<Int> = map { it.toInt() and 0xFF }

@Serializable
data class ServerConfig(
    @SerialName("registration_mode") val registrationMode: String = "invite_only",
    @SerialName("oidc_enabled") val oidcEnabled: Boolean = false,
    @SerialName("oidc_disable_password_login") val oidcDisablePasswordLogin: Boolean = false,
    @SerialName("e2e_enabled") val e2eEnabled: Boolean = true,
)

@Serializable
data class HealthResponse(val status: String = "")

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("totp_code") val totpCode: String? = null,
)

@Serializable
data class SignedPrekeyUpload(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: List<Int>,
    val signature: List<Int>,
)

@Serializable
data class OneTimePrekeyUpload(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: List<Int>,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("identity_key") val identityKey: List<Int>,
    @SerialName("signed_prekey") val signedPrekey: SignedPrekeyUpload,
    @SerialName("one_time_prekeys") val oneTimePrekeys: List<OneTimePrekeyUpload>,
    @SerialName("invite_code") val inviteCode: String? = null,
)

@Serializable
data class UserPublic(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String = "offline",
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_owner") val isOwner: Boolean = false,
)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val user: UserPublic,
    @SerialName("recovery_code") val recoveryCode: String? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(val code: String = "", val message: String = "")
