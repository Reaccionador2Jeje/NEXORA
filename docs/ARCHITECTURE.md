# NEXORA Architecture

## Current workspace

NEXORA uses a Rust Cargo workspace with two crates.

### `nexora-core`

The platform-neutral library owns:
- Version information.
- Command-line tokenization.
- Command classification.
- A small, reusable command model.

It intentionally avoids terminal UI dependencies and platform-specific windowing APIs.

### `nexora-cli`

The initial desktop CLI frontend owns:
- The interactive prompt.
- Terminal input and output.
- Built-in command execution.
- Current working-directory state.
- Launching external programs with `std::process::Command`.

## Planned platform frontends

### Android

A dedicated Android application will provide the mobile interface and connect to the shared engine. The Android frontend will need explicit decisions for filesystem access, app-private storage, permissions, process lifetime, and packaging.

### Windows

A Windows desktop frontend will host the shared command engine and provide a native desktop experience. Windows-specific process and filesystem behavior will remain behind platform adapters where practical.

## Design boundaries

- The core must not depend on a graphical UI framework.
- Frontends should not duplicate command parsing.
- Platform-specific behavior belongs in adapters, not in shared command definitions.
- External processes are started directly; command text is not implicitly evaluated by a shell.
- Features must be tested on each target platform before being described as supported.

## Near-term roadmap

1. Establish the shared command engine and CLI baseline.
2. Add command history, completion, and structured command errors.
3. Define platform adapter interfaces.
4. Build the Windows desktop frontend.
5. Build the Android frontend and package pipeline.
6. Add a package/environment manager after the platform storage model is defined.
