# NEXORA

**Your Terminal. Your Universe.**

NEXORA is an independent, cross-platform terminal environment project targeting **Android, Windows, and Linux desktop systems**.

The project is developed as a modular terminal engine first, so its command system can be reused by platform-specific interfaces without tying the core to one UI framework.

## Android application\n\nA native Kotlin Android frontend prototype now lives in `android/`. It provides a terminal-style interface with built-in commands and an app-private filesystem workspace. Arbitrary shell execution is not enabled, and the Kotlin prototype is not yet connected to the shared Rust engine; JNI integration remains planned.\n\n## Continuous integration

GitHub Actions validates every push and pull request targeting `main` on Ubuntu and Windows. The workflow checks Rust formatting, compiles the Rust workspace, runs its tests on Ubuntu and Windows, and builds the Android debug APK.

## PC QEMU integration

The desktop QEMU integration has started. A dedicated GitHub Actions workflow installs and validates native `qemu-system-x86_64` on Windows and Linux. This is the host-toolchain validation stage only: the Rust QEMU process adapter, Ubuntu guest provisioning, and terminal lifecycle commands are still pending. See [QEMU PC integration](docs/QEMU_PC_INTEGRATION.md).

## PC QEMU integration

The desktop CLI includes `qemu-check` (checks `qemu-system-x86_64` and `qemu-img`), `qemu-version`, `qemu-install` (downloads and SHA-256 verifies the Ubuntu 24.04 x86_64 cloud disk, then prepares a persistent 20 GiB QCOW2 disk), and `qemu-start <disk.qcow2>` (foreground serial-console boot). Set `NEXORA_QEMU_BIN` to override QEMU discovery. First-boot cloud-init setup and managed VM lifecycle remain in progress; see [QEMU PC integration](docs/QEMU_PC_INTEGRATION.md).

## Android runtime migration

The Android app now selects QEMU System Emulation as its Linux runtime. Android CI builds the standalone ARM64 QEMU executable, packages its shared libraries and AAVMF firmware into the APK, and then builds the APK from that verified artifact. Ubuntu 24.04 is provisioned as a persistent VM and exposed through the serial terminal. Physical-device boot, persistence, and recovery acceptance tests are still required; PRoot is no longer part of the Android installer or APK.

## Project status

**Early development — version 0.1.0**

Current foundation:
- Rust workspace with a reusable command-engine library.
- Interactive CLI named `nex`.
- Built-in commands: `help`, `version`, `clear`, `echo`, `pwd`, `ls`, `cd`, `history`, and `complete`.
- Persistent command history (up to 500 entries).
- History recall using `!N`.
- Command suggestions from built-ins and executables found in `PATH`.
- External program launching through the operating system, without passing input through a shell.
- Platform-neutral core with no third-party Rust dependencies.

History and completion are currently command-based. Interactive Tab-key editing, arrow-key history navigation, and the full Windows desktop application are not implemented yet.

## Repository layout

```text
NEXORA/
├── Cargo.toml
├── README.md
├── LICENSE
├── .gitignore
├── crates/
│   ├── nexora-core/
│   │   ├── Cargo.toml
│   │   └── src/lib.rs
│   └── nexora-cli/
│       ├── Cargo.toml
│       └── src/main.rs
└── docs/
    └── ARCHITECTURE.md
```

## Requirements

- Rust stable toolchain (1.75 or newer recommended).
- Windows, Linux, or macOS for developing the CLI.
- Android builds use a dedicated application frontend and Android-targeted builds.
- Desktop QEMU currently requires a native host installation; automated validation is being added for Windows and Linux.

## Build and run

```bash
cargo run -p nexora-cli
```

Show version and help:

```bash
cargo run -p nexora-cli -- --version
cargo run -p nexora-cli -- --help
```

Run tests:

```bash
cargo test --workspace
```

## Command examples

```text
nexora> help
nexora> pwd
nexora> ls
nexora> echo Hello from NEXORA
nexora> history
nexora> complete git
nexora> !2
nexora> exit
```

Unknown commands are launched as external programs using the operating system process API. NEXORA does not interpret shell operators, pipes, redirects, or shell scripts in this initial version.

## Development principles

1. **Modular by design:** keep reusable terminal logic in `nexora-core`.
2. **Platform-aware, not platform-locked:** Android and Windows frontends should share the same command engine.
3. **No unnecessary dependencies:** add crates only when they solve a clear problem.
4. **Transparent capabilities:** document what works today and what is still planned.
5. **Safe process handling:** launch programs directly with argument separation instead of silently evaluating user input as shell code.

## License

NEXORA is distributed under the MIT License. See [LICENSE](LICENSE).

## Walletie compatibility

NEXORA's terminal and shell workflows remain independent of Walletie. Optional ecosystem features may integrate after AtDev identity compatibility is available.

Walletie V1 currently accepts AtDev Cloud HS256 tokens only; other identity systems need the documented SSO bridge first. See the [Walletie integration matrix](https://github.com/Reaccionador2Jeje/Walletie/blob/main/docs/INTEGRATION_MATRIX.md).
