# Builds the Rust crypto library for Android and generates the Kotlin bindings (Windows).
# Prerequisites: Rust, Android NDK (ANDROID_NDK_HOME set), `cargo install cargo-ndk`.
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
Set-Location $root

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

$jni = "$root\clients\android\app\src\main\jniLibs"
$kotlin = "$root\clients\android\app\src\main\java"

Set-Location "$root\crates\ts-crypto-ffi"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o $jni build --release

cargo run --release --bin uniffi-bindgen generate `
  --library "$jni\arm64-v8a\libts_crypto_ffi.so" `
  --language kotlin --out-dir $kotlin

Write-Host "Done. Open clients/android in Android Studio and run the app."
