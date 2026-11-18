package dev.atdev.nexora

/**
 * Commands for the optional XFCE desktop running inside the Ubuntu PRoot.
 *
 * The VNC server is deliberately bound to loopback only. A VNC client on the
 * same Android device can connect to 127.0.0.1:5901; the server is not exposed
 * to the local Wi-Fi network.
 */
object DesktopCommands {
    const val INSTALL =
        "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y xfce4 dbus-x11 tigervnc-standalone-server tigervnc-tools"

    const val SET_PASSWORD = "vncpasswd"

    const val START = """mkdir -p "$HOME/.vnc" && printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "$HOME/.vnc/xstartup" && chmod 700 "$HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
