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

// ── Conversations, keys and messages ──

@Serializable
data class ChannelInfo(
    val id: String,
    val name: String? = null,
    @SerialName("channel_type") val channelType: String = "dm",
)

@Serializable
data class DmChannel(
    val channel: ChannelInfo,
    @SerialName("other_user") val otherUser: UserPublic,
)

@Serializable
data class CreateDmRequest(@SerialName("target_user_id") val targetUserId: String)

@Serializable
data class SignedPrekeyBundle(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: List<Int>,
    val signature: List<Int>,
)

@Serializable
data class OneTimePrekeyBundle(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: List<Int>,
)

@Serializable
data class KeyBundle(
    @SerialName("identity_key") val identityKey: List<Int>,
    @SerialName("signed_prekey") val signedPrekey: SignedPrekeyBundle,
    @SerialName("one_time_prekey") val oneTimePrekey: OneTimePrekeyBundle? = null,
)

@Serializable
data class KeyRegistrationRequest(
    @SerialName("identity_key") val identityKey: List<Int>,
    @SerialName("signed_prekey") val signedPrekey: SignedPrekeyUpload,
    @SerialName("one_time_prekeys") val oneTimePrekeys: List<OneTimePrekeyUpload>,
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("sender_id") val senderId: String? = null,
    val ciphertext: List<Int>,
    val nonce: List<Int>,
    @SerialName("message_type") val messageType: String = "text",
    @SerialName("created_at") val createdAt: String,
)

/** Decrypted message as kept in the local encrypted chat log. */
@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    val text: String,
    @SerialName("created_at") val createdAt: String,
    /** False when the message could not be decrypted (text then holds a placeholder). */
    val ok: Boolean = true,
)

/** Wire format of an encrypted 1:1 message; identical to the web client's `WireMessage`. */
@Serializable
data class WireX3dh(
    @SerialName("identity_key") val identityKey: List<Int>,
    @SerialName("ephemeral_key") val ephemeralKey: List<Int>,
    @SerialName("signed_prekey_id") val signedPrekeyId: Int,
    @SerialName("one_time_prekey_id") val oneTimePrekeyId: Int? = null,
)

@Serializable
data class WireHeader(
    @SerialName("ratchet_key") val ratchetKey: List<Int>,
    @SerialName("previous_chain_length") val previousChainLength: Long,
    @SerialName("message_number") val messageNumber: Long,
)

@Serializable
data class WireMessage(
    val v: Int = 1,
    val x3dh: WireX3dh? = null,
    val header: WireHeader,
    val ciphertext: List<Int>,
    val nonce: List<Int>,
)

// ── Push notifications ──

@Serializable
data class VapidKeyResponse(@SerialName("public_key") val publicKey: String)

@Serializable
data class PushSubscribeRequest(
    val endpoint: String,
    @SerialName("p256dh_key") val p256dhKey: String,
    @SerialName("auth_key") val authKey: String,
)

@Serializable
data class PushUnsubscribeRequest(val endpoint: String)

/** Metadata-only payload the server sends; it never contains message text. */
@Serializable
data class PushPayload(
    @SerialName("notification_type") val notificationType: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("channel_name") val channelName: String = "",
)
