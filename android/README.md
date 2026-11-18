# Rakhsh Android

This directory is the initial Android application shell. It is not yet a working VPN client.

## Current foundation

- Android application module (Kotlin, min SDK 26).
- Launcher activity that clearly reports that native integration is pending.
- Android `VpnService` declaration and guarded service class (it intentionally does not establish a VPN yet).
- Rust in-memory TOML parser and Android-only L3 entry point that adopts the VpnService TUN FD via `tun-rs::AsyncDevice::from_fd`.
- `SecretStore.kt` uses Android Keystore AES-256-GCM for the `token` and `static_secret` values.
- Debug APK build is provided by the repository's Android Actions workflow.

## Required before enabling VPN traffic

1. Add a Rust Android `cdylib` target and a JNI bridge with a managed Tokio runtime and reliable start/stop cancellation.
2. Complete and test the initial L3 FD-adoption path against the pinned `tun-rs = 2.8.13` version; Android must configure addresses, routes and MTU in `VpnService.Builder` and must not create a second TUN device.
3. Add a socket-protection abstraction and ensure every outbound TCP, UDP, QUIC, KCP and other carrier socket is protected with `VpnService.protect()` before connecting or binding as appropriate. The current native carriers create sockets in multiple modules, so this must be audited end to end.
4. Pass credentials from `SecretStore` to Rust only in memory; never write plaintext secrets to a TOML file or logs.
5. Add device tests for VPN permission approval, reconnect, stop, IPv4/IPv6, DNS, traffic routing, and failure recovery.
6. Only after those tests pass should the service call `Builder.establish()` and start the native tunnel.

## Build

From the repository root:

```sh
gradle -p android assembleDebug
```

The generated debug APK is expected at:

`android/app/build/outputs/apk/debug/app-debug.apk`

A successful Gradle build only proves that the Android shell compiles. It does not prove that VPN traffic is tunneled.
