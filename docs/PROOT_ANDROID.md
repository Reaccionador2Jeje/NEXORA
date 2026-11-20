# NEXORA Android PRoot runtime

## Runtime decision

NEXORA Android uses **PRoot** to run an ARM64 Ubuntu userspace on Android's Linux kernel. It does not boot a separate guest kernel and is not a virtual machine.

## Build and packaging

Android CI builds the PRoot ARM64 executable and companion loader using the Android NDK and a pinned build-script revision:

- Builder: `green-green-avk/build-proot-android`
- Pinned revision: `01f83b8841358450c78333d1b33ab30d4943bec4`
- ABI: `arm64-v8a`
- Android API target for the PRoot binary: 21
- APK native-library payload: `libproot.so` and `libproot-loader.so`

The APK must be built by the Android CI job so these executable files are placed in the APK's native-library directory. Android's app-private data directory is not used as an executable location.

## Ubuntu installation

The first `install-ubuntu` run:

1. Reads Canonical's official `SHA256SUMS` manifest.
2. Downloads `ubuntu-base-24.04.5-base-arm64.tar.gz` from Ubuntu Base 24.04.
3. Verifies the archive with SHA-256 before extraction.
4. Extracts to a staging directory inside app-private storage.
5. Configures DNS from Android's active network and activates the root filesystem only after checking its essential files.

The installer requires approximately 700 MB of free space. The Ubuntu root filesystem and download remain private to NEXORA.

## Command execution

Commands are launched using the packaged PRoot executable with Ubuntu's root filesystem and Android system mounts. The current implementation starts a new `/bin/bash -lc` process for each entered command.

Known limitations:

- There is no persistent interactive shell process yet.
- `cd` does not persist between separate commands.
- Ctrl+C and interactive full-screen terminal applications are not supported yet.
- The environment uses Android's kernel and system-call behavior; it is not equivalent to booting Ubuntu on a VM or a physical Linux machine.

## Acceptance checks

Before calling the Android runtime production-ready, verify on a physical ARM64 Android device:

- PRoot starts from the APK's native-library directory and finds its companion loader.
- Ubuntu Base installs and passes the official checksum check.
- `cat /etc/os-release`, `uname -m`, and `pwd` run successfully.
- DNS and package downloads work on Wi-Fi and mobile data.
- App restart preserves the installed root filesystem.
- Insufficient storage and interrupted downloads leave no partially activated rootfs.

Desktop QEMU integration remains independent and is documented in [QEMU PC integration](QEMU_PC_INTEGRATION.md).
