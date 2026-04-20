# Android Build Machine — Guide for Claude Code

This machine is preconfigured to build signed **debug** APKs for any Android project placed in `/home/ubuntu/apps/`. Follow this guide when asked to build an app.

## Environment

| Tool | Version | Path |
|------|---------|------|
| OS | Ubuntu 24.04 LTS | — |
| JDK | OpenJDK 21 | `/usr/lib/jvm/java-21-openjdk-amd64` |
| Gradle (system) | 9.4.1 | `/opt/gradle/gradle-9.4.1/bin/gradle` |
| Android SDK | — | `/home/ubuntu/android-sdk` |
| Build-tools | 35.0.1 | `$ANDROID_HOME/build-tools/35.0.1` |
| Platform-tools | 37.0.0 | `$ANDROID_HOME/platform-tools` |
| Platform | android-35 | `$ANDROID_HOME/platforms/android-35` |

Environment variables are set **system-wide** in `/etc/environment` and the PATH is extended in `/etc/profile.d/android.sh`, so they are available in every login shell — including non-interactive shells spawned by build tools, CI, and Claude Code itself:

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ANDROID_HOME=/home/ubuntu/android-sdk
ANDROID_SDK_ROOT=/home/ubuntu/android-sdk
```

Tools on PATH: `java`, `gradle`, `adb`, `sdkmanager`, `avdmanager`, `apksigner`, `zipalign`, `aapt2`, `zip`, `unzip`, `git`.

## Project layout convention

Place each app under its own directory in `/home/ubuntu/apps/<project-name>/`. Built APKs land in `app/build/outputs/apk/debug/`.

## Build a debug APK

From the project root:

```bash
# Use the Gradle wrapper if the project ships one (preferred — matches the project's pinned Gradle version)
./gradlew assembleDebug

# Otherwise fall back to the system Gradle
gradle assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` — already signed with the auto-generated debug keystore (`~/.android/debug.keystore`, created on first build).

## Common tasks

```bash
./gradlew tasks                 # list all available tasks
./gradlew clean assembleDebug   # clean rebuild
./gradlew lint                  # static analysis
./gradlew test                  # unit tests
./gradlew installDebug          # install to a connected device (needs adb + device)
```

## Troubleshooting checklist

1. **Wrapper not executable** → `chmod +x gradlew`
2. **`SDK location not found`** → create `local.properties` in the project root with `sdk.dir=/home/ubuntu/android-sdk` (do not commit this file)
3. **Wrong compileSdk / build-tools** → only `android-35` and `build-tools;35.0.1` are installed. Install missing components with:
   ```bash
   sdkmanager "platforms;android-XX" "build-tools;XX.X.X"
   ```
4. **Native code (NDK) needed** → no NDK is installed by default. Install on demand:
   ```bash
   sdkmanager "ndk;<version>"
   ```
5. **Wrong AGP / Gradle pairing** → the Android Gradle Plugin in `build.gradle(.kts)` must be compatible with Gradle 9.4.1 and JDK 21. Bump AGP if the project pins an old version, or downgrade Gradle in the wrapper to match the project's AGP.
6. **Out of memory** → add to `gradle.properties`:
   ```
   org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
   ```
7. **License not accepted** → `yes | sdkmanager --licenses`

## Signed *release* APK (when explicitly asked)

Debug APKs are signed automatically. For a release build you need a keystore + signing config in `app/build.gradle`. Ask the user for the keystore path and credentials before generating one — never commit secrets.

Manual sign + align (when not using AGP signing config):
```bash
zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks <keystore.jks> --out app-release.apk app-release-aligned.apk
apksigner verify app-release.apk
```

## Notes for Claude Code

- Always prefer `./gradlew` over the system `gradle` so the project's pinned version is used.
- Run builds from the **project root** (the directory containing `settings.gradle[.kts]`), not from `app/`.
- The first build downloads dependencies and may take several minutes; subsequent builds are cached.
- Do **not** commit `local.properties`, `*.keystore`, `*.jks`, or `app/build/`.
- Report the absolute path of the produced APK back to the user when the build succeeds.
