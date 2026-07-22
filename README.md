# OpenTimelapse

Shoots timelapses by driving your phone's **own camera app** — so Pro mode, RAW, manual
shutter and white balance all work, because it is the manufacturer's camera taking the
photo, not a reimplementation of it.

Between frames the phone locks and sleeps. It wakes itself, unlocks, raises the camera,
finds the shutter, presses it, confirms the photo actually landed, and locks again.

Nothing is hardcoded to a manufacturer: the camera app is whichever one answers
`IMAGE_CAPTURE`, and the shutter is found by shape and position. Verified on Samsung
One UI, OnePlus and OPPO ColorOS.

**Not distributable on Google Play.** It uses `AccessibilityService` for automation, which
Play policy reserves for accessibility. Sideload it, or publish through F-Droid.

---

## Prerequisites

- **JDK 21.** Android Studio's bundled JBR works; a newer system JDK will not. Every
  command below assumes:

  ```sh
  export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # adjust for your machine
  ```

- **Android SDK** with platform 35.
- **The ffmpeg binary**, which is *not* in git — it is a build artefact. Without it the app
  still shoots and exports an ffmpeg command; it just cannot render on the phone.

  ```sh
  NDK=~/android-ndk-r27c ./tools/build-ffmpeg.sh arm64-v8a armeabi-v7a
  ```

  Needs a Linux environment (WSL is fine) with `build-essential`, `nasm`, `pkg-config`.
  Takes a few minutes per ABI and only has to be done once per ffmpeg version.

---

## Testing a build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Three APKs are produced — `arm64-v8a`, `armeabi-v7a`, and a `universal` containing both.
Any phone from the last several years wants **arm64-v8a**.

### The accessibility service

An **in-place update** (`adb install -r`, or installing a newer APK signed with the same
key) keeps the service bound - measured on OnePlus updating 0.2 to 0.2.1.

An **uninstall followed by install** does not. Neither Samsung nor ColorOS rebinds it, so
re-enable it by hand:

> Settings → Accessibility → Installed apps → OpenTimelapse → on

Android can also switch it off after a crash. The Setup checklist asks the service itself
whether it is connected rather than trusting the settings entry, so it shows this red when
it happens - which is the first thing to check if frames start failing.

Avoid enabling the *accessibility shortcut*: it binds a volume-key hold to **toggle** the
service, which can switch it off mid-session.

---

## Releasing

### One-time setup

```sh
cp keystore.properties.example keystore.properties
keytool -genkeypair -v -keystore opentimelapse.jks -alias opentimelapse \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then fill in `keystore.properties`. Both files are git-ignored.

> **Back up the `.jks` and its password.** Android identifies an app by its signing key.
> Lose it and you can never update an installed copy — users have to uninstall first,
> losing their config and session history.

Release builds work without a keystore; they are just left unsigned and cannot be
installed.

### Each release

1. **Bump the version** in `app/build.gradle.kts`:

   ```kotlin
   versionCode = 3        // must increase every time; Android refuses a downgrade
   versionName = "0.3"    // what humans see
   ```

2. **Build and verify:**

   ```sh
   ./gradlew :core:test          # 90 tests; the engine's behaviour lives here
   ./gradlew :app:assembleRelease
   ```

3. **Check it is really signed:**

   ```sh
   apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
   ```

4. **Ship** `app/build/outputs/apk/release/`:
   - `app-arm64-v8a-release.apk` — for essentially every modern phone
   - `app-armeabi-v7a-release.apk` — older 32-bit devices
   - `app-universal-release.apk` — both, ~2 MB larger; use it if you would rather not
     explain ABIs to anyone

**Samsung needs a second battery setting.** The checklist's exemption is necessary but not
sufficient on One UI: also set Battery → **Unrestricted** in the app's own system settings,
or long sessions get frozen between frames.

Installing over an existing copy keeps config and sessions, as long as the signing key and
`applicationId` have not changed.

### GPL obligation

Release APKs bundle a GPL-2.0-or-later ffmpeg binary. Distributing one means also making
its source available — satisfied by shipping `tools/build-ffmpeg.sh`, which pins the exact
upstream versions. See [LICENSES/README.md](LICENSES/README.md). The app's own code is
Apache-2.0.

---

## First run

Calibration is offered before the first session. It shoots a few real frames to measure
*this* phone — how long the screen takes to accept a tap after waking, how long the camera
takes to save a photo — and derives the **shortest interval the device can sustain**.

It matters: on a Galaxy S20 a 12 s interval against a ~10 s cycle silently dropped four
frames in six. Calibration measured that phone's floor at 11 s and the interval field now
warns below it.

It can be postponed, but the defaults are guesses from three phones and may drop frames on
a fourth.

---

## Layout

```
core/   pure Kotlin — config, cycle engine, shutter detection, ffmpeg command building.
        No Android dependency, so all of it is unit-tested on the JVM.
app/    Android — accessibility service, foreground services, storage, Compose UI.
tools/  build-ffmpeg.sh
```

The split is enforced by the compiler: `:core` cannot reach for an Android API, which is
what keeps the interesting logic testable without a device.
