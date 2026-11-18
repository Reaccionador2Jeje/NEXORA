package dev.atdev.nexora

/**
 * NEXORA's original XFCE visual identity: vector wallpaper, GTK styling,
 * window decorations and first-login desktop preferences.
 */
object DesktopTheme {
    val SETUP = """mkdir -p "${'$'}HOME/.local/share/backgrounds" "${'$'}HOME/.themes/NEXORA/gtk-3.0" "${'$'}HOME/.themes/NEXORA/xfwm4" "${'$'}HOME/.config/gtk-3.0" "${'$'}HOME/.config/autostart" && cat > "${'$'}HOME/.local/share/backgrounds/nexora-wallpaper.svg" <<'NEXORA_WALLPAPER'
<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" viewBox="0 0 1920 1080">
 <defs>
  <linearGradient id="bg" x2="1" y2="1"><stop stop-color="#080817"/><stop offset=".55" stop-color="#10102b"/><stop offset="1" stop-color="#030817"/></linearGradient>
  <linearGradient id="neon" x2="1" y2="1"><stop stop-color="#8b35ff"/><stop offset=".52" stop-color="#394cff"/><stop offset="1" stop-color="#00c9ff"/></linearGradient>
  <radialGradient id="glow"><stop stop-color="#4420a0" stop-opacity=".48"/><stop offset="1" stop-color="#080817" stop-opacity="0"/></radialGradient>
  <filter id="blur"><feGaussianBlur stdDeviation="55"/></filter>
 </defs>
 <rect width="1920" height="1080" fill="url(#bg)"/>
 <ellipse cx="960" cy="510" rx="660" ry="480" fill="url(#glow)" filter="url(#blur)"/>
 <g fill="none" stroke="url(#neon)" stroke-width="5" opacity=".48"><path d="M-80 850 520 250 720 250 980 0"/><path d="M-80 980 580 320"/><path d="M1120 1080 1920 280"/><path d="M1380 1080 1920 540"/></g>
 <g fill="#6b35ff" opacity=".7"><circle cx="280" cy="190" r="3"/><circle cx="420" cy="780" r="4"/><circle cx="1500" cy="220" r="3"/></g>
 <g transform="translate(550 250)"><path d="M40 245 205 20 315 155 455 0 455 300 340 300 340 185 205 330 120 225 40 330Z" fill="url(#neon)"/></g>
 <text x="960" y="690" fill="#f3f2ff" font-family="sans-serif" font-size="116" font-weight="800" letter-spacing="18" text-anchor="middle">NEXORA</text>
 <text x="960" y="760" fill="#a88bff" font-family="sans-serif" font-size="30" letter-spacing="8" text-anchor="middle">YOUR TERMINAL. YOUR UNIVERSE.</text>
 <g transform="translate(1510 500)">
  <ellipse cx="120" cy="335" rx="170" ry="32" fill="#17134a"/><path d="M35 130 Q5 40 90 10 Q175 -20 220 60 Q255 120 225 220 L260 300 Q175 365 50 300 L80 220Z" fill="#10152f" stroke="url(#neon)" stroke-width="9"/>
  <ellipse cx="130" cy="150" rx="84" ry="100" fill="#111a3b" stroke="#514bff" stroke-width="7"/><ellipse cx="102" cy="135" rx="22" ry="30" fill="#f4f4ff"/><ellipse cx="164" cy="135" rx="22" ry="30" fill="#f4f4ff"/>
  <circle cx="108" cy="140" r="10" fill="#11152d"/><circle cx="158" cy="140" r="10" fill="#11152d"/><path d="M100 190 Q132 215 168 190 Q155 245 125 238Z" fill="#ffd34f"/>
  <path d="M42 92 Q-12 40 28 0 Q55 -20 82 4 M206 8 Q260 -20 270 42" fill="none" stroke="url(#neon)" stroke-width="22" stroke-linecap="round"/><path d="M105 290 130 250 160 290" fill="url(#neon)"/>
 </g>
 <text x="90" y="1000" fill="#74729b" font-family="sans-serif" font-size="22" letter-spacing="4">NEXORA DESKTOP / XFCE</text>
</svg>
NEXORA_WALLPAPER
cat > "${'$'}HOME/.themes/NEXORA/index.theme" <<'NEXORA_INDEX'
[Desktop Entry]
Type=X-GNOME-Metatheme
Name=NEXORA
Comment=NEXORA dark neon desktop theme
Encoding=UTF-8
[X-GNOME-Metatheme]
GtkTheme=NEXORA
IconTheme=Papirus-Dark
NEXORA_INDEX
cat > "${'$'}HOME/.themes/NEXORA/xfwm4/themerc" <<'NEXORA_XFWM'
active_color=#211b42
inactive_color=#11111f
active_text_color=#ffffff
inactive_text_color=#aaa6c8
button_offset=5
button_spacing=5
NEXORA_XFWM
cat > "${'$'}HOME/.themes/NEXORA/gtk-3.0/gtk.css" <<'NEXORA_GTK'
@define-color theme_bg_color #10101d;
@define-color theme_fg_color #ecebff;
@define-color theme_base_color #090914;
@define-color theme_text_color #ecebff;
@define-color theme_selected_bg_color #6635dc;
@define-color theme_selected_fg_color #ffffff;
@define-color accent_color #7650ff;
@define-color accent_bg_color #6035d6;
@define-color accent_fg_color #ffffff;
window, .background { background-color: #10101d; color: #ecebff; }
headerbar, .titlebar { background-image: linear-gradient(110deg,#17152c,#101a30); color: #f4f1ff; border-bottom: 1px solid #423276; }
button { background-image: linear-gradient(#292442,#1b1a30); color: #f2efff; border: 1px solid #51418a; border-radius: 7px; }
button:hover { background-image: linear-gradient(#49318a,#29204f); }
entry, textview, list, treeview { background-color: #0b0b17; color: #eeeaff; }
selection { background-color: #6635dc; color: #ffffff; }
NEXORA_GTK
cat > "${'$'}HOME/.config/gtk-3.0/settings.ini" <<'NEXORA_SETTINGS'
[Settings]
gtk-theme-name=NEXORA
gtk-icon-theme-name=Papirus-Dark
gtk-font-name=Sans 10
gtk-application-prefer-dark-theme=1
NEXORA_SETTINGS
cat > "${'$'}HOME/.config/gtkrc-2.0" <<'NEXORA_GTK2'
gtk-theme-name="NEXORA"
gtk-icon-theme-name="Papirus-Dark"
gtk-font-name="Sans 10"
NEXORA_GTK2
cat > "${'$'}HOME/.local/bin-nexora-theme.sh" <<'NEXORA_AUTOSTART'
#!/bin/sh
xfconf-query -c xsettings -p /Net/ThemeName -s NEXORA --create -t string
xfconf-query -c xsettings -p /Net/IconThemeName -s Papirus-Dark --create -t string
xfconf-query -c xfwm4 -p /general/theme -s NEXORA --create -t string || true
xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitor0/workspace0/last-image -s "${' --create -t string
xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitor0/workspace0/image-style -s 5 --create -t int
NEXORA_AUTOSTART
chmod 700 "${'$'}HOME/.local/bin-nexora-theme.sh" && cat > "${'$'}HOME/.config/autostart/nexora-theme.desktop" <<'NEXORA_DESKTOP'
[Desktop Entry]
Type=Application
Name=NEXORA Desktop Theme
Comment=Apply NEXORA's dark neon desktop identity
Exec=sh -c "${'
OnlyShowIn=XFCE;
X-GNOME-Autostart-enabled=true
NEXORA_DESKTOP"""
}
}HOME/.local/share/backgrounds/nexora-wallpaper.svg" --create -t string
xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitor0/workspace0/image-style -s 5 --create -t int
NEXORA_AUTOSTART
chmod 700 "${'$'}HOME/.local/bin-nexora-theme.sh" && cat > "${'$'}HOME/.config/autostart/nexora-theme.desktop" <<'NEXORA_DESKTOP'
[Desktop Entry]
Type=Application
Name=NEXORA Desktop Theme
Comment=Apply NEXORA's dark neon desktop identity
Exec=sh -c "$HOME/.local/bin-nexora-theme.sh"
OnlyShowIn=XFCE;
X-GNOME-Autostart-enabled=true
NEXORA_DESKTOP"""
}
}HOME/.local/bin-nexora-theme.sh"
OnlyShowIn=XFCE;
X-GNOME-Autostart-enabled=true
NEXORA_DESKTOP"""
}
}HOME/.local/share/backgrounds/nexora-wallpaper.svg" --create -t string
xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitor0/workspace0/image-style -s 5 --create -t int
NEXORA_AUTOSTART
chmod 700 "${'$'}HOME/.local/bin-nexora-theme.sh" && cat > "${'$'}HOME/.config/autostart/nexora-theme.desktop" <<'NEXORA_DESKTOP'
[Desktop Entry]
Type=Application
Name=NEXORA Desktop Theme
Comment=Apply NEXORA's dark neon desktop identity
Exec=sh -c "${'
OnlyShowIn=XFCE;
X-GNOME-Autostart-enabled=true
NEXORA_DESKTOP"""
}
}HOME/.local/bin-nexora-theme.sh"
OnlyShowIn=XFCE;
X-GNOME-Autostart-enabled=true
NEXORA_DESKTOP"""
}
