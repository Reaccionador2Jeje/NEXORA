# NEXORA Android

Native Android frontend prototype.

## Open and build
Open this directory in Android Studio with JDK 17 and Android SDK Platform 35.
With Gradle 8.9 installed, run:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Runtime migration status

NEXORA is moving from the legacy PRoot-based Ubuntu userspace to a full ARM64 virtual machine using QEMU System Emulation. The standalone Android NDK build, QEMU asset packaging, UEFI firmware, Ubuntu cloud-image installer, NoCloud first-boot seed, and serial VM controller are being integrated.

**Current status:** the active legacy command engine remains PRoot as a safe fallback. QEMU is experimental until its standalone build succeeds, the resulting APK passes the on-device `qemu-check`, and Ubuntu 24.04 reaches a working serial shell on a physical ARM64 Android device. The QEMU workflow hands its verified artifact to Android CI, which packages the runtime into a follow-up APK.

In an APK containing the QEMU artifact, the terminal commands are:
- `qemu-check`: verify the extracted Android executable, checksum manifest, and Bionic linker startup.
- `qemu-install`: download and verify the official Ubuntu 24.04 ARM64 cloud disk, then prepare UEFI and first-boot seed media.
- `qemu-start` / `qemu-stop`: start or stop the headless VM.
- `qemu-status`: show VM and boot-media preparation state.

These commands do not imply that Ubuntu has booted successfully. Networking is not enabled in the initial QEMU build. See [`../docs/QEMU_ANDROID_MIGRATION.md`](../docs/QEMU_ANDROID_MIGRATION.md).

## Current scope
The app provides a terminal-style native interface with `help`, `version`, `clear`, `echo`, `pwd`, `ls`, `cd`, `date`, and `exit`.

Filesystem commands are restricted to the app-private files directory. Arbitrary shell execution is disabled. This first Kotlin prototype does not yet call the shared Rust `nexora-core`; JNI integration is the next engineering step.
