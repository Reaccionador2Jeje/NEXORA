# NEXORA Android

Native Android terminal running Ubuntu 24.04 ARM64 through PRoot.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK Platform 35. The distributable APK must be built through GitHub Actions so it includes the ARM64 PRoot executable and its companion loader.

The Android CI pipeline:
1. Builds PRoot from the pinned Android build scripts with Android NDK r23c.
2. Packages `libproot.so` and `libproot-loader.so` in the APK's `arm64-v8a` native-library directory.
3. Runs Android unit tests and assembles the debug APK.

## Ubuntu environment

Run `install-ubuntu` once. NEXORA downloads Canonical's Ubuntu Base 24.04.5 ARM64 archive, checks it against the official SHA256SUMS manifest, and extracts it into app-private storage. Keep at least 700 MB free during setup.

After installation, Linux commands such as `pwd`, `ls`, `apt`, and `bash` run inside Ubuntu through PRoot. PRoot uses Android's kernel; it is not a virtual machine and does not boot a separate Ubuntu kernel.

Current limitation: each entered command starts a separate Ubuntu process. A persistent interactive shell, terminal input streaming, and working-directory persistence are not implemented yet.

See [the Android PRoot architecture and acceptance criteria](../docs/PROOT_ANDROID.md).
