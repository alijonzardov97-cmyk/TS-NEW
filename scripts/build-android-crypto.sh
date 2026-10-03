#!/usr/bin/env bash
# Builds the Rust crypto library for Android and generates the Kotlin bindings.
# Prerequisites: Rust, Android NDK (ANDROID_NDK_HOME set), `cargo install cargo-ndk`.
set -euo pipefail
cd "$(dirname "$0")/.."

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

JNI=clients/android/app/src/main/jniLibs
KOTLIN=clients/android/app/src/main/java

cd crates/ts-crypto-ffi
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o "../../$JNI" build --release

# Library mode reads the UniFFI metadata from the compiled library.
cargo run --release --bin uniffi-bindgen generate \
  --library "../../$JNI/arm64-v8a/libts_crypto_ffi.so" \
  --language kotlin --out-dir "../../$KOTLIN"

echo "Done. Open clients/android in Android Studio and run the app."
