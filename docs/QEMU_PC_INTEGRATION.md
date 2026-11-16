# NEXORA PC: QEMU integration

## Goal

Bring the QEMU Linux-VM capability to NEXORA desktop hosts without reusing the Android QEMU binary. The first desktop targets are Windows x64 and Linux x64.

## Host strategy

- Use the host-native `qemu-system-x86_64` executable for x64 PCs.
- Discover QEMU from the system `PATH`; allow an explicit executable path through `NEXORA_QEMU_BIN`.
- Do not bundle Android/Bionic binaries or AAVMF firmware into desktop builds.
- Keep VM disks and writable firmware state in a NEXORA-owned data directory, separate from the application install directory.
- Never overwrite or delete an existing VM disk during setup.
- Launch QEMU with an argument list (never through a shell string).
- Capture QEMU process status and serial output so the NEXORA terminal can later become the VM console.

## Stages

### PC-1 — host toolchain validation (implemented; CI confirmation pending)
- [x] Add Windows and Linux CI jobs that install native QEMU and verify `qemu-system-x86_64 --version`.
- [x] Verify NEXORA can locate a configured executable or one on `PATH`.
- [x] Keep the existing Android ARM64 QEMU workflow independent.
- [ ] Confirm both hosted runners pass the end-to-end `qemu-check` validation.

### PC-2 — shared Rust QEMU host adapter (in progress)
- [x] Add a platform-neutral Rust module for native executable discovery.
- [x] Support `NEXORA_QEMU_BIN` as an explicit executable override.
- [x] Add `qemu-check` and `qemu-version` diagnostics to the NEXORA CLI.
- [x] Add Windows/Linux CI validation that installs QEMU, runs the CLI diagnostics, and builds/tests the Rust workspace.
- [x] Add a foreground VM launcher using a native QEMU process, TCG, and a serial terminal.
- [ ] Add managed background lifecycle, QMP shutdown, and VM status reporting.
- Do not execute shell commands or accept arbitrary shell fragments as QEMU arguments.

### PC-3 — Ubuntu guest provisioning (initial disk stage implemented)
- [x] Download the official Ubuntu 24.04 Noble x86_64 cloud image.
- [x] Verify the downloaded image against Ubuntu's published SHA-256 manifest.
- [x] Store the disk in a per-user NEXORA data directory and expand its sparse QCOW2 capacity to 20 GiB.
- [x] Refuse to overwrite an existing Ubuntu disk.
- [ ] Generate NoCloud cloud-init seed with a secure first-login credential.
- [ ] Validate first boot and persistent writes on Windows and Linux.

### PC-4 — terminal and lifecycle integration (in progress)
- [x] Add `qemu-check`, `qemu-version`, and `qemu-start <disk.qcow2>` to the PC CLI.
- [x] Attach the guest serial console to the active NEXORA terminal session.
- [ ] Add `qemu-install`, `qemu-stop`, and `qemu-status`.
- [ ] Add managed VM process tracking and graceful QMP shutdown.

### PC-5 — acceptance
- Verify first boot, login, persistent files across restarts, graceful stop, and recovery on Windows and Linux.
- Measure resource use and document host requirements.
- Do not describe PC guest boot as supported until these tests pass.

## Current CLI commands

- `qemu-check` locates the native desktop QEMU executable and `qemu-img`, then runs both version probes.
- `qemu-install` downloads Ubuntu 24.04 x86_64, verifies SHA-256, and prepares a persistent 20 GiB QCOW2 disk under the user data directory.
- `qemu-version` prints the executable path and QEMU version.
- `qemu-start <disk.qcow2>` boots an existing QCOW2 disk using x86_64 TCG and attaches the serial console to the current terminal. Press Ctrl+C to stop the foreground QEMU process.
- Set `NEXORA_QEMU_BIN` to an explicit executable path if QEMU is not on `PATH`.

`qemu-install` prepares the Ubuntu cloud disk, but first-boot cloud-init user setup is not ready yet. `qemu-start <disk.qcow2>` remains a foreground serial-console launcher. Managed background lifecycle and graceful stop/status commands are still pending.

## Initial limitation

The PC workflow in PC-1 validates that native QEMU is available on CI hosts. It does not yet provision or boot Ubuntu. Android's ARM64 guest pipeline remains separate.
