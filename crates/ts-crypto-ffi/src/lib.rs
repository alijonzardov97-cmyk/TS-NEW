//! Kotlin-facing wrapper around `ts-crypto`.
//!
//! The wire formats are identical to the web client's `ts-crypto-wasm` module, so keys
//! generated here are interchangeable with the server's expectations.
//!
//! Secret material (private keys) crosses the FFI boundary as `ByteArray` on the Kotlin
//! side. The Kotlin layer must encrypt it with an Android Keystore key straight away and
//! overwrite the arrays with zeros.

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use rand::rngs::OsRng;
use ts_crypto::double_ratchet::{EncryptedMessage, MessageHeader, RatchetSession};
use ts_crypto::identity;
use ts_crypto::x3dh::{self, PrekeyBundle};
use x25519_dalek::{PublicKey as X25519Public, StaticSecret};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CryptoError {
    #[error("invalid input: {msg}")]
    InvalidInput { msg: String },
}

fn invalid(msg: &str) -> CryptoError {
    CryptoError::InvalidInput { msg: msg.to_string() }
}

#[derive(uniffi::Record)]
pub struct IdentityKeys {
    /// Ed25519 signing (private) key, 32 bytes.
    pub signing_key: Vec<u8>,
    /// Ed25519 verifying (public) key, 32 bytes.
    pub verifying_key: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct SignedPrekey {
    pub key_id: i32,
    pub public_key: Vec<u8>,
    pub private_key: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct OneTimePrekey {
    pub key_id: i32,
    pub public_key: Vec<u8>,
    pub private_key: Vec<u8>,
}

/// Generate a new Ed25519 identity keypair.
#[uniffi::export]
pub fn generate_identity_key() -> IdentityKeys {
    let key = identity::generate_identity_key();
    IdentityKeys {
        signing_key: key.to_bytes().to_vec(),
        verifying_key: key.verifying_key().to_bytes().to_vec(),
    }
}

/// Generate a signed prekey pair; the public key is signed with the identity signing key.
#[uniffi::export]
pub fn generate_signed_prekey(
    identity_signing_key: Vec<u8>,
    key_id: i32,
) -> Result<SignedPrekey, CryptoError> {
    let sk_bytes: [u8; 32] = identity_signing_key
        .as_slice()
        .try_into()
        .map_err(|_| invalid("signing key must be 32 bytes"))?;
    let signing_key = SigningKey::from_bytes(&sk_bytes);

    let secret = StaticSecret::random_from_rng(OsRng);
    let public = X25519Public::from(&secret);
    let signature = signing_key.sign(public.as_bytes());

    Ok(SignedPrekey {
        key_id,
        public_key: public.as_bytes().to_vec(),
        private_key: secret.to_bytes().to_vec(),
        signature: signature.to_bytes().to_vec(),
    })
}

/// Generate a batch of one-time prekeys with consecutive ids starting at `start_key_id`.
#[uniffi::export]
pub fn generate_one_time_prekeys(start_key_id: i32, count: u32) -> Vec<OneTimePrekey> {
    (0..count)
        .map(|i| {
            let secret = StaticSecret::random_from_rng(OsRng);
            let public = X25519Public::from(&secret);
            OneTimePrekey {
                key_id: start_key_id + i as i32,
                public_key: public.as_bytes().to_vec(),
                private_key: secret.to_bytes().to_vec(),
            }
        })
        .collect()
}

/// SHA-256 fingerprint of a 32-byte Ed25519 public key.
#[uniffi::export]
pub fn compute_fingerprint(public_key: Vec<u8>) -> Result<String, CryptoError> {
    let bytes: [u8; 32] = public_key
        .as_slice()
        .try_into()
        .map_err(|_| invalid("public key must be 32 bytes"))?;
    let vk = VerifyingKey::from_bytes(&bytes).map_err(|_| invalid("invalid public key"))?;
    Ok(identity::fingerprint(&vk).0)
}

/// Safety number for two identity keys (order independent on the Rust side).
#[uniffi::export]
pub fn compute_safety_number(key_a: Vec<u8>, key_b: Vec<u8>) -> Result<String, CryptoError> {
    let a: [u8; 32] = key_a
        .as_slice()
        .try_into()
        .map_err(|_| invalid("key_a must be 32 bytes"))?;
    let b: [u8; 32] = key_b
        .as_slice()
        .try_into()
        .map_err(|_| invalid("key_b must be 32 bytes"))?;
    let vk_a = VerifyingKey::from_bytes(&a).map_err(|_| invalid("invalid key_a"))?;
    let vk_b = VerifyingKey::from_bytes(&b).map_err(|_| invalid("invalid key_b"))?;
    Ok(identity::safety_number(&vk_a, &vk_b))
}

// ─── X3DH + Double Ratchet (1:1 conversations) ─────────────────────
//
// Same construction as the web client's `ts-crypto-wasm`: X3DH establishes the shared secret,
// then a Double Ratchet session is initialised with the peer's signed prekey as the first
// ratchet key. Session state is an opaque JSON string that contains secret key material: the
// Kotlin layer must keep it only in Keystore-encrypted storage.

fn arr32(v: &[u8], what: &str) -> Result<[u8; 32], CryptoError> {
    v.try_into().map_err(|_| invalid(&format!("{what} must be 32 bytes")))
}

fn session_to_json(session: &RatchetSession) -> Result<String, CryptoError> {
    let bytes = session.serialize().map_err(|_| invalid("session serialize failed"))?;
    String::from_utf8(bytes).map_err(|_| invalid("session is not valid UTF-8"))
}

#[derive(uniffi::Record)]
pub struct X3dhInitiated {
    pub session_json: String,
    /// Our ephemeral public key; goes into the first message's X3DH header.
    pub ephemeral_public_key: Vec<u8>,
}

/// Initiator side. `their_*` come from the peer's key bundle on the server. The signed prekey
/// signature is verified inside `x3dh::initiate`; a bad signature is an error.
#[uniffi::export]
pub fn x3dh_initiate(
    our_identity_signing_key: Vec<u8>,
    their_identity_key: Vec<u8>,
    their_signed_prekey: Vec<u8>,
    their_signed_prekey_signature: Vec<u8>,
    their_one_time_prekey: Option<Vec<u8>>,
) -> Result<X3dhInitiated, CryptoError> {
    let our_signing_key = SigningKey::from_bytes(&arr32(&our_identity_signing_key, "signing key")?);
    let identity_key = VerifyingKey::from_bytes(&arr32(&their_identity_key, "identity key")?)
        .map_err(|_| invalid("invalid identity key"))?;
    let signed_prekey = X25519Public::from(arr32(&their_signed_prekey, "signed prekey")?);
    let sig: [u8; 64] = their_signed_prekey_signature
        .as_slice()
        .try_into()
        .map_err(|_| invalid("signature must be 64 bytes"))?;
    let one_time_prekey = match their_one_time_prekey {
        Some(v) => Some(X25519Public::from(arr32(&v, "one-time prekey")?)),
        None => None,
    };
    let bundle = PrekeyBundle {
        identity_key,
        signed_prekey,
        signed_prekey_signature: Signature::from_bytes(&sig),
        one_time_prekey,
    };
    let result = x3dh::initiate(&our_signing_key, &bundle).map_err(|_| invalid("X3DH failed"))?;
    let session = RatchetSession::init_initiator(&result.shared_secret, &bundle.signed_prekey)
        .map_err(|_| invalid("ratchet init failed"))?;
    Ok(X3dhInitiated {
        session_json: session_to_json(&session)?,
        ephemeral_public_key: result.ephemeral_public_key.as_bytes().to_vec(),
    })
}

/// Responder side: build a session from the X3DH header of a peer's first message.
/// Returns the session JSON.
#[uniffi::export]
pub fn x3dh_respond(
    our_identity_signing_key: Vec<u8>,
    our_signed_prekey_private: Vec<u8>,
    our_one_time_prekey_private: Option<Vec<u8>>,
    their_identity_key: Vec<u8>,
    their_ephemeral_key: Vec<u8>,
) -> Result<String, CryptoError> {
    let our_signing_key = SigningKey::from_bytes(&arr32(&our_identity_signing_key, "signing key")?);
    let spk = StaticSecret::from(arr32(&our_signed_prekey_private, "signed prekey private")?);
    let otp = match our_one_time_prekey_private {
        Some(v) => Some(StaticSecret::from(arr32(&v, "one-time prekey private")?)),
        None => None,
    };
    let their_ik = VerifyingKey::from_bytes(&arr32(&their_identity_key, "their identity key")?)
        .map_err(|_| invalid("invalid their identity key"))?;
    let their_ek = X25519Public::from(arr32(&their_ephemeral_key, "their ephemeral key")?);
    let result = x3dh::respond(&our_signing_key, &spk, otp.as_ref(), &their_ik, &their_ek)
        .map_err(|_| invalid("X3DH respond failed"))?;
    let session = RatchetSession::init_responder(&result.shared_secret, &spk);
    session_to_json(&session)
}

#[derive(uniffi::Record)]
pub struct RatchetEncrypted {
    pub session_json: String,
    pub ratchet_key: Vec<u8>,
    pub previous_chain_length: u32,
    pub message_number: u32,
    pub ciphertext: Vec<u8>,
    pub nonce: Vec<u8>,
}

#[uniffi::export]
pub fn ratchet_encrypt(session_json: String, plaintext: Vec<u8>) -> Result<RatchetEncrypted, CryptoError> {
    let mut session = RatchetSession::deserialize(session_json.as_bytes())
        .map_err(|_| invalid("bad session"))?;
    let enc = session.encrypt(&plaintext).map_err(|_| invalid("encrypt failed"))?;
    Ok(RatchetEncrypted {
        session_json: session_to_json(&session)?,
        ratchet_key: enc.header.ratchet_key.to_vec(),
        previous_chain_length: enc.header.previous_chain_length,
        message_number: enc.header.message_number,
        ciphertext: enc.ciphertext,
        nonce: enc.nonce.to_vec(),
    })
}

#[derive(uniffi::Record)]
pub struct RatchetDecrypted {
    pub session_json: String,
    pub plaintext: Vec<u8>,
}

/// Decrypt one message. On error the caller must keep its previous session state.
#[uniffi::export]
pub fn ratchet_decrypt(
    session_json: String,
    ratchet_key: Vec<u8>,
    previous_chain_length: u32,
    message_number: u32,
    ciphertext: Vec<u8>,
    nonce: Vec<u8>,
) -> Result<RatchetDecrypted, CryptoError> {
    let mut session = RatchetSession::deserialize(session_json.as_bytes())
        .map_err(|_| invalid("bad session"))?;
    let msg = EncryptedMessage {
        header: MessageHeader {
            ratchet_key: arr32(&ratchet_key, "ratchet key")?,
            previous_chain_length,
            message_number,
        },
        ciphertext,
        nonce: nonce.as_slice().try_into().map_err(|_| invalid("nonce must be 12 bytes"))?,
    };
    let plaintext = session.decrypt(&msg).map_err(|_| invalid("decrypt failed"))?;
    Ok(RatchetDecrypted { session_json: session_to_json(&session)?, plaintext })
}
