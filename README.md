# NEXORA

**Your Terminal. Your Universe.**

NEXORA is an independent, cross-platform terminal environment project targeting **Android and Windows**.

The project is being developed as a modular terminal engine first, so its command system can be reused by platform-specific interfaces without tying the core to one UI framework.

## Project status

**Early development — version 0.1.0**

The first milestone provides:
- A Rust workspace with a reusable command-engine library.
- A small interactive terminal application named `nex`.
- Built-in commands: `help`, `version`, `clear`, `echo`, `pwd`, `ls`, `cd`, and `exit`.
- External program launching through the operating system, without passing input through a shell.
- A platform-neutral core with no third-party Rust dependencies.

The Android graphical application and the Windows graphical application are planned platform frontends; they are **not yet included** in this initial milestone.

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
│   │   └── src/
│   │       └── lib.rs
│   └── nexora-cli/
│       ├── Cargo.toml
│       └── src/
│           └── main.rs
└── docs/
    └── ARCHITECTURE.md
```

## Requirements

- Rust stable toolchain (1.75 or newer recommended).
- Windows, Linux, or macOS for developing the CLI.
- Android support is planned through a dedicated application frontend and Android-targeted builds.

## Build and run

From the repository root:

```bash
cargo run -p nexora-cli
```

Show version and help:

```bash
cargo run -p nexora-cli -- --version
cargo run -p nexora-cli -- --help
```

Run the test suite:

```bash
cargo test --workspace
```

## Command examples

Inside NEXORA:

```text
nexora> help
nexora> pwd
nexora> ls
nexora> echo Hello from NEXORA
nexora> cd ..
nexora> version
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
