# Rakhsh Android

This directory is the initial Android application shell. It is not yet a working VPN client.

## Current foundation

- Android application module (Kotlin, min SDK 26).
- Launcher activity that clearly reports that native integration is pending.
- Android `VpnService` declaration and guarded service class (it intentionally does not establish a VPN yet).
- Rust in-memory TOML parser and Android-only L3 entry point that adopts the VpnService TUN FD via `tun-rs::AsyncDevice::from_fd`.
- JNI registration of the live `VpnService` object so Rust can call `VpnService.protect(fd)`.
- Android TCP/TLS/WS/WSS carrier sockets are created first, protected before `connect()`, then connected. Android currently rejects QUIC, KCP, Rama QUIC and raw carriers until their UDP/raw socket paths can be protected.
- `SecretStore.kt` uses Android Keystore AES-256-GCM for the `token` and `static_secret` values.
- Debug APK build is provided by the repository's Android Actions workflow.

## Required before enabling VPN traffic

1. Complete build and runtime validation of the Rust Android `cdylib` target and JNI bridge with managed Tokio runtime and reliable start/stop cancellation.
2. Complete and test the initial L3 FD-adoption path against the pinned `tun-rs = 2.8.13` version; Android must configure addresses, routes and MTU in `VpnService.Builder` and must not create a second TUN device.
3. Extend socket protection to UDP, QUIC, KCP, Rama QUIC and raw carriers. Only TCP-based carriers are currently allowed on Android; all other carrier types are rejected in the Android build path.
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


## JNI milestone

The Android-only Rust library now exports JNI entry points declared by
`NativeBridge.kt`:

- `nativeStart(configToml, token, staticSecret, tunFd)` creates a shared Tokio
  runtime and starts the Rust engine task.
- `nativeStop(taskId)` aborts the corresponding task.

This is a partial integration milestone. `RakhshVpnService` registers its live service instance with JNI, and Rust TCP carrier sockets call `VpnService.protect(fd)` before connecting. The service still intentionally does not establish a TUN or call `nativeStart`: Android VPN permission flow, config import, foreground notification, Builder address/routes/MTU, full service lifecycle, and device testing remain incomplete. UDP/QUIC/KCP/Rama/raw carriers are rejected on Android until their socket paths are protected.


## Android socket protection milestone

- Rust registers the live `VpnService` instance through JNI.
- Android TCP/TLS/WS/WSS outbound sockets are created with `TcpSocket`, passed to `VpnService.protect(fd)`, and only then connected.
- Android builds reject QUIC, KCP, Rama QUIC and raw carriers until their UDP/raw sockets receive equivalent protection.
- The service remains guarded: it does not establish the TUN or start the engine until VPN permission, config import, foreground notification, routes/MTU and device lifecycle tests are complete.
