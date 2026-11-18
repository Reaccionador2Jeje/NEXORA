# NEXORA — Interface design system

## Product
NEXORA is an Android-first Linux workspace for running Ubuntu ARM64 through PRoot. The primary screen is a working terminal, not a marketing dashboard. The main job is to let a person read shell output, enter commands, and operate interactive terminal programs comfortably on a phone.

## Visual direction: Pocket Workstation
A compact field instrument: dark graphite-green surfaces, readable terminal canvas, and a restrained signal-lime accent. The interface should feel like a focused developer tool rather than a generic blue admin panel.

### Color tokens
- Canvas: `#080F12`
- Terminal surface: `#0B1413`
- Raised surface: `#111C1A`
- Dock surface: `#1B2C25`
- Signal lime: `#B7F36B`
- Mint secondary: `#65D6B5`
- Main text: `#F0F7F2`
- Muted text: `#7E9A8D`
- Divider: `#263A32`
- Error: warm coral, never lime

### Typography
- Android UI: system sans; compact uppercase labels use restrained tracking.
- Terminal and shell prompt: monospace, 13sp baseline.
- Keep command output monospaced and preserve ANSI color meaning.

### Layout
- Header and environment strip establish product and runtime context.
- Terminal owns the largest flexible region.
- Command input is anchored below the terminal for thumb reach.
- Special keys live in a horizontally scrollable rail.
- When the Android IME opens, resize the workspace; never hide the prompt or keyboard controls deliberately.

### Signature
The lime `N/` mark and signal-lime command affordance identify the Pocket Workstation. Keep lime for active/primary states, not every decorative element.

## Interaction contracts
- Enter and the run affordance execute the current command.
- While a foreground process is active, committed input is sent to its PTY; IME composing text must not be cleared.
- Ctrl, Alt, Escape, Tab, arrows, Home/End, and common Ctrl shortcuts remain reachable from the mobile key rail.
- Clear only resets the visible terminal screen; it does not imply killing a process or deleting shell history.
- Incoming output must not force-scroll a user who is reading older output.
- Preserve focus and the Android keyboard during interactive command execution.

## Accessibility and responsive behavior
- Every icon-like action has a content description.
- Touch controls retain comfortable minimum heights and visible contrast.
- Keep text legible on narrow Android screens; allow key rail horizontal scrolling.
- Do not use color alone to communicate process or runtime state.
