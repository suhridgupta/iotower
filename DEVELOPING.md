# Developing I/O Tower

## Layout

- `core/` — **pure Java, no Android.** USB/IP protocol framing, descriptor
  parsing, endpoint mapping, the transfer engine, and the socket server. The
  `UsbBackend` interface is the single seam to real hardware. Fast JUnit tests
  live here.
- `android/` — the Android TV app. `AndroidUsbBackend` (Host API), the
  foreground `ServerService`, and a minimal launcher UI. Depends on `:core`.
- `desktop/` — a runnable local harness: runs the real `core` server against a
  `FakeUsbBackend`, so you can validate the protocol with stock `usbip` and no
  physical device.
- `companion/` — optional Python PC-side daemon (architecture.md §9). Not part of
  the Gradle build.
- `testdata/` — descriptor dumps and usbmon captures (§12).

The point of this split: everything hard (protocol, out-of-order URB
correlation, the concurrency model) lives in `core` as plain Java and is
testable on a JVM with no device. Only `AndroidUsbBackend` touches the Host API.

## Toolchain

- **JDK 17** to build — the Android Gradle Plugin requires it. `core` and
  `desktop` are source-compatible with Java 11, but the Gradle build itself runs
  on 17.
- **Android SDK**, `compileSdk 35`. Create a (git-ignored) `local.properties`
  with `sdk.dir=/path/to/Android/Sdk`, or just open the project in Android
  Studio and it writes that file for you.
- No third-party libraries; JUnit 5 is the only test dependency.

## Build & test

```bash
# Fast: protocol + engine unit tests. No SDK, no emulator, no device.
./gradlew :core:test

# Local end-to-end: start a fake-device server, then attach with stock tools.
./gradlew :desktop:run
#   in another shell, on the same box:
sudo modprobe vhci-hcd
usbip list -r 127.0.0.1
usbip attach -r 127.0.0.1 -b 1-1

# Build / install the TV app (needs the Android SDK).
./gradlew :android:assembleDebug
./gradlew :android:installDebug
```

CI note: `core` and `desktop` build and test with only a JDK — no Android SDK,
no emulator, no hardware. `settings.gradle` includes `:android` only when an SDK
is present (`ANDROID_HOME`/`ANDROID_SDK_ROOT` set, or a `local.properties` file),
so on a bare runner the Android module is simply omitted and the build needs just
a JDK. The GitHub Actions workflow in `.github/workflows/ci.yml` runs
`:core:test` + `:desktop:build` on JDK 17. Android Studio always has an SDK, so
it sees all three modules normally.
