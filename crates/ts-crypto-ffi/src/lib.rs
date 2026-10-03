//! Kotlin-facing wrapper around `ts-crypto`.
//!
//! The wire formats are identical to the web client's `ts-crypto-wasm` module, so keys
//! generated here are interchangeable with the server's expectations.
//!
//! Secret material (private keys) crosses the FFI boundary as `ByteArray` on the Kotlin
//! side. The Kotlin layer must encrypt it with an Android Keystore key straight away and
//! overwrite the arrays with zeros.

use ed25519_dalek::{Signer, SigningKey, VerifyingKey};
use rand::rngs::OsRng;
use ts_crypto::identity;
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
