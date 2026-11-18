package dev.atdev.nexora

/**
 * Commands for the NEXORA XFCE desktop running inside Ubuntu PRoot.
 * TigerVNC is restricted to loopback and is not exposed to Wi-Fi.
 */
object DesktopCommands {
    // Install XFCE, VNC, icon assets and NEXORA's original desktop theme.
    val INSTALL = """export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y xfce4 xfce4-terminal dbus-x11 tigervnc-standalone-server tigervnc-tools papirus-icon-theme librsvg2-common && mkdir -p "${'$'}HOME/.vnc" && printf '%s\n' '$session = "xfce";' '$localhost = "yes";' '$geometry = "1280x720";' '$depth = 24;' > "${'$'}HOME/.vnc/tigervnc.conf" && printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "${'$'}HOME/.vnc/xstartup" && chmod 700 "${'$'}HOME/.vnc/xstartup" """ + DesktopTheme.SETUP

    // TigerVNC asks for a password interactively; it is not embedded in NEXORA.
    const val SET_PASSWORD = "tigervncpasswd"

    // Keep PRoot alive while the VNC server runs; Ctrl+C stops the server.
    val START = """set -e; command -v tigervncserver >/dev/null 2>&1 || { echo 'TigerVNC is not installed. Run desktop install first.'; exit 1; }; command -v startxfce4 >/dev/null 2>&1 || { echo 'XFCE4 is not installed. Run desktop install first.'; exit 1; }; test -s "${'/.vnc/passwd" || { echo 'VNC password is not configured. Run desktop password first.'; exit 1; }; mkdir -p "${'$'}HOME/.vnc"; printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "${'$'}HOME/.vnc/xstartup"; chmod 700 "${'$'}HOME/.vnc/xstartup"; tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'NEXORA desktop active at 127.0.0.1:5901'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""}HOME/.vnc/passwd" || { echo 'VNC password is not configured. Run desktop password first.'; exit 1; }; mkdir -p "${'/.vnc"; printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "${'$'}HOME/.vnc/xstartup"; chmod 700 "${'$'}HOME/.vnc/xstartup"; tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'NEXORA desktop active at 127.0.0.1:5901'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""}HOME/.vnc"; printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "${'/.vnc/xstartup"; chmod 700 "${'$'}HOME/.vnc/xstartup"; tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'NEXORA desktop active at 127.0.0.1:5901'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""}HOME/.vnc/xstartup"; chmod 700 "${'/.vnc/xstartup"; tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'NEXORA desktop active at 127.0.0.1:5901'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""}HOME/.vnc/xstartup"; tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'NEXORA desktop active at 127.0.0.1:5901'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "echo 'XFCE4:'; if command -v startxfce4 >/dev/null 2>&1; then echo 'installed'; else echo 'not installed'; fi; echo 'TigerVNC:'; if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'not installed'; fi; if [ -s \"\$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi; if [ -x \"\$HOME/.vnc/xstartup\" ] && [ -f \"\$HOME/.vnc/tigervnc.conf\" ]; then echo 'Desktop config: ready'; else echo 'Desktop config: missing'; fi"
}
