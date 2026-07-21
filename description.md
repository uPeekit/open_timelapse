# Android Timelapse Automation App — Implementation Specification

## Overview

A lightweight Android application that automates timelapse photography by controlling the **native camera app** of the device via Android's Accessibility API. The key differentiator from existing timelapse apps is that this app does **not** implement its own camera UI — it automates the manufacturer's native camera app, giving full access to Pro mode settings (ISO, shutter speed, white balance, RAW capture, etc.) that are only available in the native app.

### Core User Flow (Single Cycle)

```
[Phone locked, screen off]
       ↓
Wake screen (PowerManager / Activity flags)
       ↓
Wait: afterWakeDelayMs
       ↓
Swipe to unlock (AccessibilityService gesture)
       ↓
Wait: afterUnlockDelayMs
       ↓
Click shutter button (AccessibilityService click)
       ↓
Wait: afterShutterDelayMs
       ↓
Lock screen (GLOBAL_ACTION_LOCK_SCREEN)
       ↓
Wait: intervalSeconds
       ↓
[Repeat]
```

---

## Architecture

### Components

```
TimeLapseApp
├── TimeLapseAccessibilityService   (AccessibilityService)
│     ├── Handles gesture dispatch (swipe, click)
│     ├── Finds shutter button in camera UI tree
│     ├── Listens for ACTION_TAKE_PHOTO broadcast
│     └── Reports events via LocalBroadcastManager
│
├── TimeLapseService                (Foreground Service)
│     ├── Owns the coroutine-based timer loop
│     ├── Manages WakeLock
│     ├── Sends ACTION_TAKE_PHOTO broadcast
│     └── Emits log events to the debug log LiveData
│
├── WakeActivity                    (Transparent Activity)
│     ├── Used only to wake the screen reliably
│     ├── Sets FLAG_TURN_SCREEN_ON + FLAG_SHOW_WHEN_LOCKED
│     └── Finishes itself immediately after waking
│
├── MainActivity                    (Single-activity UI)
│     ├── Start / Stop timelapse control
│     ├── Settings screen
│     └── Debug log screen
│
└── ConfigRepository                (DataStore<Preferences>)
      └── Stores and provides TimeLapseConfig
```

---

## Data Model

### `TimeLapseConfig`

```kotlin
@Serializable
data class TimeLapseConfig(
    val intervalSeconds: Int = 30,
    val unlock: UnlockConfig = UnlockConfig(),
    val delays: DelayConfig = DelayConfig(),
    val shutter: ShutterConfig = ShutterConfig()
)

@Serializable
data class UnlockConfig(
    // All position values are fractions of screen dimensions (0.0 – 1.0)
    val startXPercent: Float = 0.5f,
    val startYPercent: Float = 0.8f,
    val endXPercent: Float = 0.5f,
    val endYPercent: Float = 0.3f,
    val durationMs: Long = 300L
)

@Serializable
data class DelayConfig(
    val afterWakeMs: Long = 500L,
    val afterUnlockMs: Long = 400L,
    val afterShutterMs: Long = 300L
)

@Serializable
data class ShutterConfig(
    val mode: ShutterMode = ShutterMode.ACCESSIBILITY_ID,
    val packageName: String = "com.sec.android.app.camera",
    val viewId: String = "com.sec.android.app.camera:id/shutter_button",
    val contentDescription: String = "Shutter",
    // Fallback tap position if ID/description lookup fails
    val fallbackXPercent: Float = 0.5f,
    val fallbackYPercent: Float = 0.85f
)

enum class ShutterMode {
    ACCESSIBILITY_ID,       // Find by viewId resource name
    CONTENT_DESCRIPTION,    // Find by contentDescription text
    COORDINATES             // Tap at absolute (percent-based) coordinates
}
```

All fields must have sensible defaults so the app works out of the box on Samsung One UI (the primary target device).

---

## Implementation Details

### 1. Screen Wake

**Primary method** — `WakeActivity` (most reliable on modern Android):

```kotlin
class WakeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }
        // Dismiss activity immediately after waking; do not show any UI
        finish()
    }
}
```

**Fallback method** — `PowerManager.FULL_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP`:

```kotlin
val wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
    "timelapse:wake"
)
wl.acquire(2000L)
```

Try `WakeActivity` first; fall back to `WakeLock` if the screen is still off after `afterWakeDelayMs`.

---

### 2. Swipe Gesture (Unlock)

Dispatched from `TimeLapseAccessibilityService`:

```kotlin
fun performUnlockSwipe(config: UnlockConfig) {
    val dm = Resources.getSystem().displayMetrics
    val path = Path().apply {
        moveTo(dm.widthPixels * config.startXPercent,
               dm.heightPixels * config.startYPercent)
        lineTo(dm.widthPixels * config.endXPercent,
               dm.heightPixels * config.endYPercent)
    }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, config.durationMs))
        .build()
    dispatchGesture(gesture, null, null)
}
```

---

### 3. Shutter Button Click

```kotlin
fun clickShutter(config: ShutterConfig): Boolean {
    val root = rootInActiveWindow ?: return false

    val node: AccessibilityNodeInfo? = when (config.mode) {
        ShutterMode.ACCESSIBILITY_ID ->
            root.findAccessibilityNodeInfosByViewId(config.viewId).firstOrNull()

        ShutterMode.CONTENT_DESCRIPTION ->
            root.findAccessibilityNodeInfosByText(config.contentDescription).firstOrNull()

        ShutterMode.COORDINATES -> null  // handled below
    }

    if (node != null) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return true
    }

    // Coordinate fallback (also used when mode == COORDINATES)
    val dm = Resources.getSystem().displayMetrics
    val x = dm.widthPixels * config.fallbackXPercent
    val y = dm.heightPixels * config.fallbackYPercent
    val path = Path().apply { moveTo(x, y); lineTo(x, y) }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
        .build()
    dispatchGesture(gesture, null, null)
    return false  // indicates fallback was used
}
```

---

### 4. Screen Lock

```kotlin
// API 28+, no root required
service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
```

For devices below API 28, use `DevicePolicyManager.lockNow()` as a fallback (requires the app to be set as a Device Admin).

---

### 5. Timer Loop

Runs inside `TimeLapseService` as a Kotlin coroutine:

```kotlin
private suspend fun runLoop(config: TimeLapseConfig) {
    while (isActive) {
        log("Waking screen...")
        wakeScreen()
        delay(config.delays.afterWakeMs)

        log("Swiping to unlock...")
        sendBroadcast(Intent(ACTION_SWIPE_UNLOCK))
        delay(config.delays.afterUnlockMs)

        log("Taking photo...")
        sendBroadcast(Intent(ACTION_TAKE_PHOTO))
        delay(config.delays.afterShutterMs)

        log("Locking screen...")
        sendBroadcast(Intent(ACTION_LOCK_SCREEN))

        log("Waiting ${config.intervalSeconds}s until next frame...")
        delay(config.intervalSeconds * 1000L)
    }
}
```

`TimeLapseService` must run as a **Foreground Service** with a persistent notification showing current status and a Stop button.

---

### 6. Broadcast Protocol

Communication between `TimeLapseService` and `TimeLapseAccessibilityService` uses `LocalBroadcastManager`:

| Action constant | Direction | Effect |
|---|---|---|
| `ACTION_SWIPE_UNLOCK` | Service → AccessibilityService | Dispatch unlock swipe gesture |
| `ACTION_TAKE_PHOTO` | Service → AccessibilityService | Click shutter button |
| `ACTION_LOCK_SCREEN` | Service → AccessibilityService | Call GLOBAL_ACTION_LOCK_SCREEN |
| `ACTION_STEP_RESULT` | AccessibilityService → Service | Reports success/failure of each step for debug log |

---

### 7. Event-Based Readiness (Recommended Enhancement)

Instead of relying solely on fixed `delay()` values, listen for `AccessibilityEvent` to detect when the camera is in the foreground before clicking:

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        event.packageName == currentConfig?.shutter?.packageName) {
        // Camera is now in foreground — safe to click shutter
        pendingShutterClick = true
    }
}
```

This makes timing more robust across devices and should be used as an enhancement on top of (not a replacement for) configurable delays.

---

## Configuration UI

### Settings Screen Sections

#### Camera
- **Camera package name** — text field (e.g., `com.sec.android.app.camera`)
- **Shutter detection mode** — radio: By View ID / By Content Description / By Coordinates
- **View ID** — text field (visible when mode is "By View ID")
- **Content description** — text field (visible when mode is "By Content Description")
- **Fallback position** — X% and Y% sliders (always visible as fallback)

#### Unlock Gesture
- **Visual gesture editor** — a phone-silhouette canvas where the user drags a start point and end point to define the swipe path. Internally stored as `startXPercent`, `startYPercent`, `endXPercent`, `endYPercent`
- **Swipe duration** — slider, 100–800 ms

#### Delays
- **After wake** — slider or number input, 0–2000 ms
- **After unlock** — slider or number input, 0–2000 ms
- **After shutter** — slider or number input, 0–2000 ms

#### Interval
- **Shoot every N seconds** — number input, minimum 5 s

### Known Camera Packages (Presets)

Provide a preset picker to populate `packageName` and `viewId` automatically:

| Manufacturer | Package | Shutter View ID |
|---|---|---|
| Samsung One UI | `com.sec.android.app.camera` | `com.sec.android.app.camera:id/shutter_button` |
| Google Pixel | `com.google.android.GoogleCamera` | `com.google.android.GoogleCamera:id/shutter_button` |
| Xiaomi / MIUI | `com.android.camera` | `com.android.camera:id/shutter_button` |
| Sony | `com.sonyericsson.android.camera` | (use content description fallback) |
| Generic AOSP | `com.android.camera2` | `com.android.camera2:id/shutter_button` |

---

## Debug Mode

A dedicated Debug screen accessible from the main UI. Allows the user to run a single cycle and observe a real-time log:

```
▶ Run single cycle

[12:01:00.100] Waking screen...
[12:01:00.640] ✓ Screen is active
[12:01:00.641] Dispatching unlock swipe...
[12:01:01.050] ✓ Camera package detected in foreground
[12:01:01.051] Searching for shutter button (mode: ACCESSIBILITY_ID)...
[12:01:01.190] ✓ Found: com.sec.android.app.camera:id/shutter_button
[12:01:01.191] Performing click...
[12:01:01.480] ✓ Click dispatched
[12:01:01.481] Locking screen...
[12:01:01.820] ✓ Locked
```

Each step should emit an `ACTION_STEP_RESULT` broadcast with a timestamp, step name, success flag, and optional detail string.

---

## Permissions & Manifest Requirements

```xml
<!-- AndroidManifest.xml -->

<!-- Foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- Wake lock -->
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- Lock screen (API < 28 fallback) -->
<uses-permission android:name="android.permission.BIND_DEVICE_ADMIN" />

<!-- WakeActivity — must be declared with these flags -->
<activity
    android:name=".WakeActivity"
    android:exported="false"
    android:showOnLockScreen="true"
    android:turnScreenOn="true"
    android:theme="@style/Theme.Transparent" />

<!-- AccessibilityService declaration -->
<service
    android:name=".TimeLapseAccessibilityService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

`accessibility_service_config.xml`:
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:packageNames="" />
    <!-- Leave packageNames empty to monitor all apps, including the camera -->
```

---

## Dependencies (build.gradle)

```kotlin
dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore for config persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Serialization (for TimeLapseConfig)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // ViewModel + LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // UI
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
```

Minimum SDK: **26** (Android 8.0). Target SDK: **34**.

---

## Play Store Considerations

This app uses `AccessibilityService` for non-accessibility purposes (automation), which Google's policy restricts for Play Store distribution. Options:

- **Recommended: distribute as a sideloaded APK** — no policy restrictions apply; this is the intended distribution method for personal use.
- If Store distribution is desired in the future: position as a general automation/productivity tool, describe the accessibility usage transparently in the Play Store listing, and expect manual review. Not guaranteed to pass.

The app does not require root access.

---

## Implementation Notes for the Agent

- The primary target device is **Samsung One UI**; use Samsung package names and view IDs as defaults throughout.
- All delay and timing values must come from `TimeLapseConfig` — no hardcoded `Thread.sleep()` or `delay()` literals in business logic.
- The `TimeLapseAccessibilityService` and `TimeLapseService` communicate **only** via `LocalBroadcastManager` — do not use shared singletons or static state.
- Every step in the cycle must emit a log event so the Debug screen can display it. Use a `SharedFlow` or `LiveData` in a singleton repository that both the service and the UI observe.
- The visual gesture editor in Settings is a `View` subclass that draws a phone outline and responds to touch drag for start/end point selection; it updates `UnlockConfig` percent values on drag end.
- Config changes must take effect on the **next cycle** — do not interrupt a running cycle mid-way.
- The foreground service notification must include: current status ("Running — next frame in 28s"), total frames captured in current session, and a Stop action.