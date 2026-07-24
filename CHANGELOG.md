# Changelog

## 0.4.0

Hardening release: everything found in the project's first external code review, fixed.

### Fixed
- Renders no longer fail on phones set to a comma-decimal locale (German, Russian, ...):
  the ffmpeg concat list always uses a decimal point now.
- Tapping Render while a render was already running could crash the app with the
  `startForegroundService` timeout; the render service now goes foreground unconditionally,
  the same fix the session service got in 0.2.x.
- The control server leaked four threads per session; its worker pool is now shut down with
  the session.
- Capture confirmation prefers files the camera app actually owns, so a messenger
  auto-download or a screenshot landing mid-cycle is no longer counted as a frame, renamed
  into the session, or deleted with it.
- A shutter click that was never dispatched is reported as the failure it is, instead of
  success.
- The battery floor is re-checked after the interval wait, so an hours-long interval can no
  longer shoot a frame on a reading taken hours ago.
- Charging-webhook settings apply on the next battery reading, like every other setting —
  not on the next session.
- Session manifest writes are serialized, closing a race between the periodic flush and the
  final write at session end.
- Text fields follow externally-changed values again once unfocused (calibration raising the
  interval now shows up), while still owning the text during typing.

### Changed
- `QUERY_ALL_PACKAGES` is no longer requested — resolving the camera via `IMAGE_CAPTURE`
  never needed it. One less red flag for F-Droid.
- The web page's Start button and the `/start` and `/events` endpoints are gone: the server
  only exists while a session runs, so remote start could never do anything. Stop, status
  and preview remain.
- The app has its own launcher and notification icons instead of borrowing a system drawable
  that varies per manufacturer.
- The exported ffmpeg command for an unrenamed session now suggests an output path beside
  the actual frames rather than in a folder that may not exist.

### Internal
- HTTP request parsing moved to :core and unit-tested; new tests cover the locale bug,
  the stale battery read, capture attribution and live webhook config.
- Dead code removed (`/start` plumbing, unused probes and helpers left from early phases).
- A GitHub Actions workflow runs :core:test and assembles the debug APK on every push.

## 0.3.0

First public release.

OpenTimelapse shoots timelapses by driving your phone's **own camera app** through an
accessibility service — so Pro mode, RAW, and manual settings all work, because the
manufacturer's camera takes the photo. Between frames the phone locks and sleeps; the app
wakes it, unlocks, raises the camera, finds the shutter by shape and position, presses it,
confirms the frame landed, and locks again. Nothing is hardcoded to a manufacturer.

Verified on Samsung One UI, OnePlus, and OPPO ColorOS.

### Shooting
- Two cycle modes: lock between frames (battery-saving) or stay awake.
- Automatic per-device calibration — measures the shortest interval this phone can sustain,
  and warns if you set the interval below it.
- Start on your first manual photo (so you frame and set up the camera yourself) or after a
  fixed delay.
- Confirms every frame against MediaStore rather than trusting the tap — the frame counter
  matches the folder, and numbered output never skips.
- Optional frame renaming into `prefix00000001.jpg` sequences for ffmpeg.

### Unattended
- Stop conditions: after a duration, at a wall-clock time, or when the battery hits a floor.
- Charging control: cross a low/high battery threshold and the app calls a URL you configure
  (per-direction method and body) to switch a smart plug and keep the battery in a healthy
  band. Off by default.
- Check and stop a running shoot from a laptop over wi-fi: a token-protected local page with
  status, a live preview, and a Stop button, paired once by QR. Off by default.

### Rendering
- On-device rendering with a bundled ffmpeg — no PC needed.
- The exact ffmpeg command is shown and editable, or copyable for a desktop render.
- A progress bar with percentage under the session while it renders, not only in the shade.
- Optionally open the finished video automatically, to check a render at a glance.
- A renamed session's folder and video take the prefix (`oppo/`, `oppo.mp4`), not a timestamp.
- "Delete photos" removes a session's frames as well as its record (with confirmation);
  plain "Delete" still only forgets the record.

### Diagnostics
- Durable log: every line is written to a file that survives a crash, a restart, or a flat
  battery, so you can see what happened to an unattended shoot afterwards. An uncaught crash
  writes its stack trace there too. Reopening the app shows the persisted log, and a Share
  button sends it off the device.

### Notes
- **Not distributable on Google Play** (accessibility-service policy). Sideload, or install
  from F-Droid.
- Release APKs bundle a GPL-2.0-or-later ffmpeg binary; source is reproduced by
  `tools/build-ffmpeg.sh`. The app's own code is Apache-2.0.
- The app makes no network calls of its own — only the URLs you configure for charging
  control, and requests from a laptop you point at the local page.
