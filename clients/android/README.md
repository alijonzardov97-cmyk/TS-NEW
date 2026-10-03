# TS for Android (Kotlin / Jetpack Compose)

Native Android client for TS. Security is the first requirement: the app is a thin, hardened
shell around the same Rust cryptography the web client uses.

**Status:** milestone 1 — connect to a server, sign in, register, 2FA code entry, recovery code.
Chats, files, push and calls come in later milestones (see Roadmap).

## Security model

| Area | What the app does |
|---|---|
| Secrets at rest | Tokens and E2EE private keys are encrypted with an AES-256-GCM key that lives in the Android Keystore (StrongBox when available, otherwise TEE), is non-exportable and unusable while the phone is locked. |
| App lock | Biometrics or device PIN/pattern is required on every launch and every time the app returns from the background. Without a device lock the app refuses to open. |
| Screen | `FLAG_SECURE`: no screenshots, no screen recording, blank preview in recent apps. Touches are ignored when another window overlays the app. |
| Transport | HTTPS only (cleartext disabled in manifest, network config and code), TLS 1.2+ with modern ciphers, system CAs only (user-installed CAs are **not** trusted), redirects never followed. |
| Server pinning | On first connect the app shows the certificate fingerprint; after you confirm, the certificate chain keys are pinned. A different certificate is refused (fail closed). |
| Backups | `allowBackup=false`; cloud backup and device transfer excluded; secrets live in `noBackupFilesDir`. |
| Logging | No HTTP logging, and all `android.util.Log` calls are stripped from release builds. |
| Crypto | Key generation uses `crates/ts-crypto` (Rust) through UniFFI — the same code as the web client. No cryptography is re-implemented in Kotlin. Private key bytes are zeroed after being stored. |
| Sign out | Wipes every stored value and destroys the Keystore key. |

Honest limits: no software can promise absolute security. A rooted or compromised phone, a
malicious keyboard, or a lost phone with a weak lock can still expose data. Use a strong device
PIN, keep the OS updated, and verify safety numbers with your contacts.

**Pinning trade-off:** pins cover the keys of the certificates the server presents (leaf and
intermediate). When your certificate is renewed with the same issuer chain, the app keeps
working; if the chain changes, the app refuses to connect and you must re-confirm the new
fingerprint ("Change server"). That is intentional.

## Build

Requirements: Android Studio (Koala or newer), Android SDK 35, Android NDK, Rust, and
`cargo install cargo-ndk`.

1. Build the crypto library and generate Kotlin bindings:
   - Windows: `powershell -File scripts/build-android-crypto.ps1`
   - Linux/macOS: `scripts/build-android-crypto.sh`
2. Open `clients/android` in Android Studio, let it sync (it offers to create the Gradle
   wrapper), and run on a device or emulator with a screen lock set.

The generated `jniLibs/` and `uniffi/` folders are git-ignored; regenerate after changing
`crates/ts-crypto-ffi`.

## Build in GitHub Actions

The workflow `.github/workflows/android.yml` builds the Rust library, generates the Kotlin
bindings and produces a signed release APK (plus a SHA-256 checksum). Run it from
**Actions → Android APK → Run workflow**, or push a tag like `android-v0.1.0` to also publish a
GitHub Release.

### Signing (one-time setup)

1. Create a release key on your computer (keep the file and passwords somewhere safe and
   **offline** — if you lose them you can never publish an update that installs over the old app):

   ```
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias ts
   ```

2. Encode it: `base64 -w0 release.jks` (Linux/macOS) or
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))` (PowerShell).
3. In the repository go to **Settings → Secrets and variables → Actions** and add:
   `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
   (here `ts`) and `ANDROID_KEY_PASSWORD`.
4. Never commit the keystore. `.gitignore` already excludes `*.jks` and `*.keystore`.

After downloading the APK, verify it: `sha256sum -c ts-android.apk.sha256`.

First run note: `crates/ts-crypto-ffi` has no `Cargo.lock` yet. After the first successful build,
generate it locally (`cargo generate-lockfile` in that folder) and commit it so builds are
reproducible.

## Roadmap

1. ✅ Connect + pinning, login, registration, app lock, encrypted storage
2. Token refresh, session list, chat list and text messages with X3DH + Double Ratchet
   (extend `ts-crypto-ffi` with the session functions from `ts-crypto-wasm`)
3. Files and images, Sender Keys for groups, safety-number screen
4. Push notifications via UnifiedPush (no Google dependency) and calls (libwebrtc)
5. Release signing, reproducible build, third-party security review
