# NEXORA Android

Native Android frontend prototype.

## Open and build
Open this directory in Android Studio with JDK 17 and Android SDK Platform 35.
With Gradle 8.9 installed, run:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Current scope
The app provides a terminal-style native interface with `help`, `version`, `clear`, `echo`, `pwd`, `ls`, `cd`, `date`, and `exit`.

Filesystem commands are restricted to the app-private files directory. Arbitrary shell execution is disabled. This first Kotlin prototype does not yet call the shared Rust `nexora-core`; JNI integration is the next engineering step.
