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

    // Keep the PRoot process alive while the VNC server is running. PRoot's
    // --kill-on-exit option would otherwise terminate the detached Xvnc child.
    // Ctrl+C from the terminal triggers the trap and cleanly stops the server.
    const val START = """mkdir -p "${'/.vnc" && printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "$HOME/.vnc/xstartup" && chmod 700 "$HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit ${'; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc" && printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "${'/.vnc/xstartup" && chmod 700 "$HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit $?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && chmod 700 "${'/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit $?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit $?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"${'/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc" && printf '%s\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec dbus-launch --exit-with-session startxfce4' > "${'/.vnc/xstartup" && chmod 700 "$HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit ${'; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && chmod 700 "${'/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit $?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit $?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"${'/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && chmod 700 "${'/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit ${'; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit $?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"${'/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"$HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/xstartup" && tigervncserver :1 -localhost yes -geometry 1280x720 -depth 24 || exit ${'; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"${'/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}?; trap 'tigervncserver -kill :1 >/dev/null 2>&1 || true' EXIT INT TERM; echo 'XFCE desktop active on 127.0.0.1:5901. Use Stop Desktop to end the session.'; while tigervncserver -list 2>/dev/null | grep -q ':1'; do sleep 2; done"""

    const val STOP = "tigervncserver -kill :1"

    const val STATUS =
        "if command -v tigervncserver >/dev/null 2>&1; then tigervncserver -list; else echo 'TigerVNC is not installed.'; fi; if [ -f \"${'/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
}HOME/.vnc/passwd\" ]; then echo 'VNC password: configured'; else echo 'VNC password: not configured'; fi"
}
