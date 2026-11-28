# MiniDroid — Android 1.6 (Donut) ARM Emulator App

Port of the classic **Android 1.6 (Donut) ARM Goldfish** virtual platform onto modern **QEMU 11.1.3** (`arm-softmmu` + `aarch64` TCG JIT), packaged as a multi-flavor Android app (`id.neotica.minidroid.<flavor>`, e.g. `id.neotica.minidroid.donut`) with live shared storage for moving and installing `.apk` files.

<img src="docs/home-unlocked.png" alt="Android 1.6 Home Screen running on QEMU 11.1.3 Goldfish" width="200" />
## Architecture

- **Guest OS:** Official Android 1.6 (`android-1.6_r03`, API 4) ARM Goldfish SDK images (`kernel-qemu`, `ramdisk.img`, `system.img` with **HoloMarket v1.5.3** pre-installed in `/system/app/HoloMarket.apk` and pinned on the stock Home screen next to Browser, 512 MiB `/data` NAND) + resizable **256 MB – 2 GB** sparse FAT32 virtual SD card (`sdcard.img`, default 512 MB) — runs both pure-Java and `armeabi` native NDK `.apk`s.
- **Goldfish Virtual Board (`goldfish/hw/arm/goldfish/`):** Clean modern QEMU 11 port of the Goldfish ARM926EJ-S platform (`-M goldfish`, `board_id=1441`):
  - `goldfish_pic` (32-IRQ controller) + `goldfish_pdev_bus` (platform device bus)
  - `goldfish_timer` + `goldfish_rtc`
  - `goldfish_tty` (serial console)
  - `goldfish_nand` (MTD YAFFS2 2048+64 B OOB for `system`, `userdata`, `cache`)
  - `goldfish_fb` (320×480 RGB565 → QEMU `DisplaySurface`/VNC with non-stalling 16 ms frame completion)
  - `goldfish_audio` (`/dev/eac`, double-buffered 44.1 kHz 16-bit stereo PCM streamed over local `audio0` chardev → Android `AudioTrack`)
  - `goldfish_events` (`qwerty` keypad + 1:1 absolute touchscreen + `KEY_CAMERA` shutter)
  - `goldfish_mmc` (SD card controller mounting `sdcard.img` at `/sdcard`)
  - `goldfish-battery` + `goldfish_memlog` + upstream `smc91c111` Ethernet (`eth0 10.0.2.15`, stock root `adbd` listening on TCP `5555`)
- **Android Host Engine (`scripts/build-android-deps.sh`, `scripts/build-android-qemu.sh`):**
  - Cross-compiled directly against Android NDK 27 (`aarch64-linux-android28`) with static `glib2`, `pixman`, `libslirp`, `libfdt`, `pcre2`, `libffi`, and Termux's `setjmp-aarch64` shim into a single self-contained 12 MB `libqemu.so` (linking only Android system `libc`, `libm`, `libdl`, `libz`).
- **MiniDroid Android App (`android-app/`):**
  - Runs `libqemu.so` in a foreground service (`useLegacyPackaging = true` for Android 10+ W^X compatibility).
  - Pure-Kotlin RFB 3.8 VNC client (`VncClient.kt` + `EmulatorView.kt`) for crisp 320×480 display, 1:1 touch input, soft keyboard bridge, **Fullscreen mode**, quick top-bar **Logcat** button, and two-row classic Android 1.6 hardware keys (**Back / Home / Menu** right below the screen, **Search / Keys / Fullscreen** beneath).
  - **Live Internet, Audio & Motion Sensors (`QemudServer.kt`, `AudioPlaybackServer.kt`, Android `libslirp` DNS patch):**
    - Multiplexes Android 1.6's `qemud` (`/dev/ttyS1`, `android.qemud=ttyS1`) for **3G Mobile Data** (`gsm` + `%CTZV` clock sync), live host **Accelerometer / Magnetic Field / Orientation** (`sensors` via Android `SensorManager`, with an in-app **Auto-Rotation / Portrait Lock** toggle), `boot-properties`, and `hw-control`.
    - Streams 44.1 kHz stereo PCM audio from Goldfish `/dev/eac` straight into Android's `AudioTrack`.
    - Dynamically syncs the host phone's active Wi-Fi or Mobile Data DNS servers into `<filesDir>/resolv.conf` (`SLIRP_RESOLV_CONF`, with `8.8.8.8`/`1.1.1.1` fallback) so all guest traffic rides directly over your phone's real internet connection.
  - **Storage, Logcat & APK Manager (`Fat32Formatter.kt`, `SharedStorageActivity.kt`, `AdbClient.kt`, `SharedHttpServer.kt`):**
    - **512 MiB** internal `/data` storage + on-device pure-Kotlin FAT32 formatter (`256 MB`, `512 MB`, `1 GB`, or `2 GB` virtual `/sdcard`, resizable from `⋮` → **Storage / SD Card Size**).
    - Built-in **ADB Logcat** viewer (`⋮` → **Logcat (AndroidRuntime)** with `Runtime`, `Errors`, `All`, `Refresh`, `Clear`, and `Copy`).
    - Pick any shared folder on your modern phone via Storage Access Framework (or use the default `Shared/` folder).
    - **One-tap Import & Install APK:** copies into your shared folder, pushes to Android 1.6 `/sdcard/Shared/` via local ADB (`127.0.0.1:15555`), stages to `/data/local/tmp/`, and installs live via `pm install -r`.
    - **Two-way file sync:** push files into `/sdcard/Shared/` or pull files out of Android 1.6 back to your phone while Donut is running.
    - **Browser bridge:** open Android 1.6's Browser at `http://10.0.2.2:18080/` to browse & download files directly from your shared folder.

## Roadmap

- [x] **Phase 1:** Fetch official Android 1.6 ARM SDK images & upstream QEMU 11.1.3
- [x] **Phase 2:** Port Goldfish board into QEMU 11.1.3 & boot Android 1.6 to portrait Home screen on macOS (`arm64`)
- [x] **Phase 3:** Cross-compile lean Goldfish-only `libqemu.so` for Android `arm64-v8a` (NDK 27)
- [x] **Phase 4:** Build MiniDroid Kotlin Android app with VNC display, Donut nav bar, live shared storage & APK installer

## Building From Scratch (macOS Apple Silicon)

### 1. Fetch Upstream QEMU 11.1.3 & Android 1.6 Images
```bash
curl -fSL -o qemu-11.1.3.tar.xz https://download.qemu.org/qemu-11.1.3.tar.xz
tar -xf qemu-11.1.3.tar.xz

mkdir -p images
curl -fSL -o images/android-1.6_r03-linux.zip https://dl.google.com/android/repository/android-1.6_r03-linux.zip
unzip -j images/android-1.6_r03-linux.zip "android-1.6_r03-linux/images/*" -d images/
~/Library/Android/sdk/emulator/mksdcard -l SDCARD 64M images/sdcard.img
```

### 2. Optional: Build & Run Natively on macOS
```bash
brew install meson ninja libslirp dtc glib pixman
./scripts/apply-goldfish.sh ./qemu-11.1.3
mkdir -p build-mac && cd build-mac
../qemu-11.1.3/configure --target-list=arm-softmmu --enable-fdt=system --enable-tcg --enable-vnc --enable-slirp --enable-cocoa
ninja qemu-system-arm
cd .. && ./scripts/run-mac.sh
```

### 3. Build the Android `arm64-v8a` Binary & MiniDroid Flavor APK
```bash
./scripts/fetch-android-deps.sh
./scripts/build-android-deps.sh
./scripts/build-android-qemu.sh
./scripts/build-apk.sh donut
```
Install the resulting flavor APK (`id.neotica.minidroid.donut`) on any modern `arm64-v8a` Android 9+ phone:
```bash
adb install -r android-app/app/build/outputs/apk/donut/debug/app-donut-debug.apk
```

### Adding Another Guest Flavor (e.g. `gingerbread`)
1. Uncomment or add a flavor entry in `android-app/app/build.gradle.kts` (`applicationIdSuffix = ".gingerbread"` → `id.neotica.minidroid.gingerbread`, plus its `GUEST_*` BuildConfig fields).
2. Place that flavor's ROM images (`kernel-qemu`, `ramdisk.img`, `system.img.z`, `userdata.img.z`) in `android-app/app/src/<flavor>/assets/images/`.
3. Build with `./scripts/build-apk.sh <flavor>` (or `gradle assemble<Flavor>Debug`). Each flavor installs side-by-side with its own app label and package ID while sharing the same QEMU engine and Kotlin codebase.
