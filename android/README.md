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

NEXORA is preparing to replace the legacy PRoot-based Ubuntu userspace with a full ARM64 virtual machine using QEMU System Emulation. UI and service code now use a runtime-engine interface, with the existing implementation isolated behind `ProotUbuntuRuntime` and selected by `LinuxRuntimeFactory`.

**Important:** QEMU is not yet bundled or bootable in this revision. The active engine remains PRoot until the Android/Bionic QEMU build, Ubuntu ARM64 boot image, and physical-device acceptance tests are complete. See [`../docs/QEMU_ANDROID_MIGRATION.md`](../docs/QEMU_ANDROID_MIGRATION.md).

## Current scope
The app provides a terminal-style native interface with `help`, `version`, `clear`, `echo`, `pwd`, `ls`, `cd`, `date`, and `exit`.

Filesystem commands are restricted to the app-private files directory. Arbitrary shell execution is disabled. This first Kotlin prototype does not yet call the shared Rust `nexora-core`; JNI integration is the next engineering step.
