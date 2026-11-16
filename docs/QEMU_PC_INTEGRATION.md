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

### PC-1 — host toolchain validation
- Add Windows and Linux CI jobs that install native QEMU and verify `qemu-system-x86_64 --version`.
- Verify NEXORA can locate a configured executable or one on `PATH`.
- Keep the existing Android ARM64 QEMU workflow independent.

### PC-2 — shared Rust QEMU host adapter (in progress)
- [x] Add a platform-neutral Rust module for native executable discovery.
- [x] Support `NEXORA_QEMU_BIN` as an explicit executable override.
- [x] Add `qemu-check` and `qemu-version` diagnostics to the NEXORA CLI.
- [x] Add Windows/Linux CI validation that installs QEMU, runs the CLI diagnostics, and builds/tests the Rust workspace.
- [ ] Expand the adapter with validated VM argument construction and process lifecycle management.
- Do not execute shell commands or accept arbitrary shell fragments as QEMU arguments.

### PC-3 — Ubuntu guest provisioning
- Provision an x86_64 Ubuntu guest image with checksum verification.
- Store the QCOW2 disk, UEFI variables, and cloud-init seed in a persistent per-user NEXORA data directory.
- Make provisioning idempotent and refuse to replace an existing disk.

### PC-4 — terminal and lifecycle integration
- Add `qemu-check`, `qemu-install`, `qemu-start`, `qemu-stop`, and `qemu-status` to the PC CLI.
- Bridge the VM serial console to the interactive terminal.
- Support graceful shutdown and report abnormal exits clearly.

### PC-5 — acceptance
- Verify first boot, login, persistent files across restarts, graceful stop, and recovery on Windows and Linux.
- Measure resource use and document host requirements.
- Do not describe PC guest boot as supported until these tests pass.

## Current CLI commands

- `qemu-check` locates the native desktop QEMU executable and runs its version probe.
- `qemu-version` prints the executable path and QEMU version.
- Set `NEXORA_QEMU_BIN` to an explicit executable path if QEMU is not on `PATH`.

These commands verify host QEMU availability only. They do not create, start, or stop a guest VM.

## Initial limitation

The PC workflow in PC-1 validates that native QEMU is available on CI hosts. It does not yet provision or boot Ubuntu. Android's ARM64 guest pipeline remains separate.
