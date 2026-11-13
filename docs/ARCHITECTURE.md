# NEXORA Architecture

## Current workspace

NEXORA uses a Rust Cargo workspace with two crates.

### `nexora-core`

The platform-neutral library owns:
- Version information.
- Command-line tokenization.
- Command classification for built-ins and external programs.
- A small, reusable command model.

It intentionally avoids terminal UI dependencies and platform-specific windowing APIs.

### `nexora-cli`

The initial CLI frontend owns:
- The interactive prompt.
- Terminal input and output.
- Built-in command execution.
- Current working-directory state.
- Persistent command history (up to 500 entries).
- History recall through `!N`.
- Command suggestions from built-ins and executables found in `PATH`.
- Launching external programs with `std::process::Command`.

History is stored under the user's application data directory:
- Windows: `%APPDATA%/nexora/history.txt`.
- Unix-like systems: `$XDG_DATA_HOME/nexora/history.txt`, falling back to `~/.local/share/nexora/history.txt`.

## Android frontend (prototype)\n\nThe `android/` directory contains a native Kotlin application with a terminal-style UI. The first preview implements basic commands and confines filesystem navigation to the app-private files directory. It deliberately does not execute arbitrary shell commands. It currently runs independently of `nexora-core`; JNI integration and shared command execution are the next step.\n\n## Planned platform frontends

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
2. Add persistent history and command suggestions.
3. Add interactive line editing and Tab-key completion.
4. Define platform adapter interfaces.
5. Build the Windows desktop frontend.
6. Integrate the Android Kotlin frontend with `nexora-core` through JNI and build the APK pipeline.
7. Add a package/environment manager after the platform storage model is defined.
