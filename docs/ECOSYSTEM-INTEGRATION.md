# NEXORA — Ecosystem Integration

Status: planning document; desktop, Android and shared-engine capabilities must be tracked separately.

## Architecture boundary
Keep the terminal command model independent from platform-specific UI. Do not claim the Android prototype uses the Rust engine until the JNI bridge is implemented and tested.

## Integration rules
- Separate built-in safe commands from any future host command execution.
- Keep filesystem access within an explicitly selected workspace.
- Report unsupported commands rather than silently simulating success.
- Keep QEMU host-tool validation distinct from guest provisioning and lifecycle support.
- Document platform-specific capability differences.

## Next tasks
1. Add command conformance tests shared by desktop and Android.
2. Document the Android-to-Rust bridge contract before implementing JNI.
3. Add tests for path boundaries and command parsing.
4. Track QEMU adapter, guest provisioning and lifecycle as separate milestones.