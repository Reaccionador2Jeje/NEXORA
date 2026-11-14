# NEXORA Android: QEMU migration plan

## Decision

NEXORA is moving from a PRoot-based Ubuntu userspace toward a full ARM64 virtual machine powered by **QEMU System Emulation**.

This is a staged migration. The current PRoot implementation remains isolated as a fallback until the QEMU engine has passed real-device boot and command tests. The app must not silently switch users to an unbootable or incomplete VM.

## Target architecture

- Host: native Android application, initially targeting `arm64-v8a`.
- Emulator: `qemu-system-aarch64`, built for Android/Bionic and packaged with its required shared libraries.
- Machine: QEMU `virt` machine with a generic ARM64 virtual CPU.
- Guest: Ubuntu 24.04 ARM64.
- Console: serial console connected to NEXORA's terminal UI.
- Storage: private app storage containing a persistent virtual disk; never use a shared SAF folder as the live VM disk.
- Initial boot: serial-only, no graphics. Add networking and shared-folder support only after stable boot.
- Acceleration: attempt only capabilities that Android actually exposes. TCG must be treated as the compatibility fallback; do not assume KVM is available to an ordinary Android app.

## Why this is a full replacement, not a binary swap

PRoot runs guest userspace programs against the Android host kernel. QEMU System Emulation boots a separate guest kernel. Therefore, the existing rootfs extraction, `useradd`, fake-root handling, command-per-process execution, and PRoot bind mounts cannot be reused as the VM boot mechanism.

The QEMU engine needs its own:
1. Verified emulator binary and complete runtime-library bundle.
2. Ubuntu ARM64 bootable image and boot firmware/kernel assets.
3. VM lifecycle controller (start, stop, restart, process output, exit status).
4. Serial terminal bridge with persistent shell session.
5. Persistent virtual disk and safe first-boot provisioning.
6. Resource checks and clear diagnostics for RAM, storage, ABI, and unsupported acceleration.

## Migration stages

### Stage 1 — isolate the engine boundary
- Define one Android runtime contract for status, installation/provisioning, start, command input, and stop.
- Keep PRoot behind a legacy adapter; UI and service code must stop depending directly on PRoot implementation details.
- Add QEMU architecture documentation and CI checks before changing the active engine.

### Stage 2 — build and verify the Android QEMU runtime

NEXORA must not depend on Termux at build time or runtime. The QEMU workflow is being migrated to an official standalone Android NDK toolchain and upstream QEMU sources. It must produce an Android/Bionic `arm64-v8a` executable and bundle only the shared libraries built for NEXORA. No Termux package builder, Termux prefix, or Termux runtime files may enter the APK.

The standalone workflow pins QEMU v10.1.2, uses the official Android NDK r30, and now has a cross-build script for Android ARM64. That script builds libffi, GNU libiconv, PCRE2, GLib, and Pixman for the Android target before configuring QEMU with TCG and a serial/headless feature set. GLib's Meson cross-file explicitly exposes the NDK-built dependency headers and static libraries, while relying on the NDK Clang driver's embedded sysroot to avoid double-prefixing absolute include paths. The standalone Meson cross file uses the `pkgconfig` binary key and explicitly disables Xen probing, which is not part of NEXORA's Android guest target. A successful QEMU workflow publishes a checksummed runtime artifact; the Android CI then downloads that exact artifact and packages only the executable, its shared libraries, and checksum manifest into APK assets. `QemuBinaryInstaller` extracts and verifies those files on-device and runs a `--version` linker smoke test. `QemuVmController` owns a headless process and serial byte stream, but it is not yet selected as the active Linux engine. No QEMU runtime artifact is considered ready until the binary, dependency closure, and physical-device execution are verified.

- Produce a reproducible `arm64-v8a` QEMU System build for Android/Bionic.
- Package all required shared libraries and verify the executable's ELF interpreter and dependencies in CI.
- Confirm that the emulator starts on a real Android device and accepts a serial console. The app's `qemu-check` command verifies extraction, checksums, Android linker startup, and the QEMU version response; it does not claim that Ubuntu has booted.
- Do not package a desktop GUI build; NEXORA owns the terminal UI.

### Stage 3 — boot a minimal ARM64 guest
- Boot a disposable test guest with serial output.
- Verify guest kernel startup, virtual block storage, shutdown, and restart.
- Measure memory use and startup time on a physical Android ARM64 device.
- No user data migration or PRoot removal at this stage.

### Stage 4 — Ubuntu 24.04 VM
- Download the official Ubuntu 24.04 LTS ARM64 cloud image and verify it against Canonical's published `SHA256SUMS` before activation.
- Bundle the matching AAVMF UEFI code and variable-store templates from Ubuntu's `qemu-efi-aarch64` package; keep a writable private copy of the variable store for the VM.
- Generate a small FAT12 NoCloud seed disk with the `CIDATA` volume label. It provisions the selected Linux user and enables serial-console auto-login on first boot without requiring a network service.
- Keep the QCOW2 disk, seed disk, and writable UEFI variables in app-private storage. Never overwrite the legacy PRoot rootfs.
- Add network access only after the offline boot path is reliable. The current QEMU build intentionally disables SLIRP, so guest networking is not yet available.
- The app exposes `qemu-install`, `qemu-start`, `qemu-stop`, `qemu-status`, and `qemu-check`. `qemu-check` tests the Android executable/linker; `qemu-install` prepares the guest image; `qemu-start` boots it. These are separate stages and must not be confused with proof of successful Ubuntu boot.

### Stage 5 — terminal integration and cutover
- Connect NEXORA's terminal UI to the guest serial console using the new process/serial controller, including login provisioning and exit-status framing.
- Keep one persistent shell session so `cd`, shell variables, jobs, and interactive programs behave naturally.
- Add graceful stop/recovery and disk-space diagnostics.
- Make QEMU the default only after real-device acceptance tests pass. Remove PRoot only in a later cleanup release.

## Acceptance criteria

The QEMU engine is not considered ready because the APK merely compiles. It must pass all of the following on a physical Android ARM64 device:

- QEMU binary starts without missing-library or linker errors.
- Guest kernel reaches a login prompt through the serial console.
- Ubuntu 24.04 ARM64 boots repeatedly from the persistent disk and discovers the NoCloud seed on first boot.
- A normal user can run commands, use `cd`, and retain files after VM restart.
- App stop/restart does not corrupt the virtual disk.
- Unsupported acceleration or insufficient resources produce actionable messages, not generic `Permission denied` errors.

## Constraints and user-data safety

- Do not delete the existing Ubuntu/PRoot data during migration.
- Do not automatically convert or overwrite the old rootfs.
- Keep the VM disk in app-private storage; a user-selected shared folder can be added later as an explicit file-transfer feature.
- A full-system emulator can be significantly slower and more memory-intensive than a userspace environment. Performance must be measured on target devices before promising support.
- Android may stop background work. NEXORA must provide visible start/stop state and must not claim it can keep a VM alive after the operating system force-stops the app.
