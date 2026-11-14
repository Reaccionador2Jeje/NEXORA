# NEXORA Android

Native Android terminal powered by a standalone ARM64 Ubuntu virtual machine.

## Open and build

Open this directory in Android Studio with JDK 17 and Android SDK Platform 35. The distributable APK must be built by the GitHub Actions pipeline so it includes the verified Android/Bionic QEMU runtime and matching AAVMF firmware.

## QEMU Ubuntu runtime

QEMU System Emulation is the only active Android Linux engine. PRoot is no longer downloaded, packaged, or selected by the Android installer.

The build pipeline works in two stages:
1. **Build QEMU for Android ARM64** with the official Android NDK and package its shared libraries plus AAVMF UEFI firmware.
2. **Android CI** downloads that exact workflow artifact, verifies its checksums, places the runtime in APK assets, runs the Android unit tests, and builds the debug APK.

The terminal commands are:
- `qemu-check`: extract and verify QEMU, its shared libraries, and Android linker startup.
- `install-ubuntu` (alias: `qemu-install`): install the bundled QEMU runtime, download and verify the official Ubuntu 24.04 ARM64 cloud disk, and prepare UEFI plus NoCloud first-boot media.
- `qemu-start` / `qemu-stop`: start or stop the persistent headless VM.
- `qemu-status` / `ubuntu-status`: show runtime and VM preparation state.

After `qemu-start`, the terminal input and output are connected to Ubuntu's serial console. The initial VM build has networking disabled. A successful APK build verifies packaging and compilation; Ubuntu boot and persistence must still be tested on a physical ARM64 Android device.

See [the QEMU Android architecture and acceptance criteria](../docs/QEMU_ANDROID_MIGRATION.md).
