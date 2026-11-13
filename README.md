# NEXORA

**Your Terminal. Your Universe.**

NEXORA is an independent, cross-platform terminal environment project targeting **Android and Windows**.

The project is developed as a modular terminal engine first, so its command system can be reused by platform-specific interfaces without tying the core to one UI framework.

## Continuous integration

GitHub Actions validates every push and pull request targeting `main` on Ubuntu and Windows. The workflow checks formatting, compiles the workspace, and runs all tests.

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

History and completion are currently command-based. Interactive Tab-key editing, arrow-key history navigation, and the Android/Windows graphical applications are not implemented yet.

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
- Android support is planned through a dedicated application frontend and Android-targeted builds.

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
