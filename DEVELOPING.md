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
# (usbip list works from M3; usbip attach + enumeration from M4; from M5 the
#  fake also streams scripted input reports, so evtest shows live axis/button
#  events with nothing plugged in.)
./gradlew :desktop:run
#   in another shell, on the same box:
sudo modprobe vhci-hcd
usbip list -r 127.0.0.1                 # read-only, no root needed
sudo usbip attach -r 127.0.0.1 -b 1-1  # writes the vhci sysfs node -> needs root
lsusb                                   # the fake device is present locally
sudo evtest                             # pick the fake's event node -> scripted button/axis events
#   detach when done (usbip port prints the port number):
sudo usbip detach -p <port>

# Build / install the TV app (needs the Android SDK).
./gradlew :android:assembleDebug
./gradlew :android:installDebug
```

### Measuring latency and jitter (M9, §6)

Manual procedure, no committed script. Compare the same device and session back to
back on Ethernet and WiFi (TV on each in turn).

1. Baseline: `./gradlew :desktop:run` (fake device), attach on the same box via
   `127.0.0.1`, and record steps 2-3 as the loopback baseline.
2. Attach the real device (F310 is the ~1 kHz-class cross-check):
   `sudo usbip attach -r <tv-ip> -b 1-1`; find the node with `sudo evtest`.
3. With input flowing, sample arrival cadence on `/dev/input/eventN` (`evhz`, or
   timestamp `evtest` output) for >= 60 s; compute rate, median inter-arrival, std-dev
   and p99 jitter.
4. Attach to first report: read `t_first_ok` from `adb logcat -s IoTowerBackend`.
5. Optional (G29): time an FFB OUT command to its physical effect; else note the omission.
6. Record the figures in `architecture.md` §6. Budget (M9 gate 4): Ethernet median
   within ~+3 ms of baseline, p99 jitter <= 16 ms, no sustained spikes.

CI note: `core` and `desktop` build and test with only a JDK — no Android SDK,
no emulator, no hardware. `settings.gradle` includes `:android` only when an SDK
is present (`ANDROID_HOME`/`ANDROID_SDK_ROOT` set, or a `local.properties` file),
so on a bare runner the Android module is simply omitted and the build needs just
a JDK. The GitHub Actions workflow in `.github/workflows/ci.yml` has two jobs, both on
JDK 17: `build` runs `:core:test` + `:desktop:build` with no Android SDK (the
fast logic/protocol gate), and `android` installs the Android SDK (platform 35,
build-tools 35.0.0) and runs `:android:assembleDebug` -- a compile-only gate that
catches Android wiring breaks the JDK-only job cannot see (there are no `:android`
unit tests; the real M6+ gates are manual L3 runs on the TV). Android Studio always
has an SDK, so it sees all three modules normally.
