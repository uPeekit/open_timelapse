# Changelog

## Unreleased

An About card at the bottom of the main screen: version and build number, licence summary,
links to the source and the issue tracker, the open-source notice, and a "Buy me a coffee"
button. The licences link moved in from the loose button below the log. All outbound links
live in one place (`AboutLinks`), so changing the donation page is a one-line edit.

Two store flavors. `foss` is the app as before. `play` is the same code without the donation
button, the links to the repository, all-files access and `USE_EXACT_ALARM`. APKs now land
under `apk/foss/…` and are named `app-foss-…`. `docs/play-submission.md` has the checklist
and the declaration texts for a Google Play release.

The `play` build has a free tier and a one-time Pro purchase through Google Play Billing.
Shooting and the default render are free; control over wi-fi, smart-plug charging, the
custom render command and stopping at a set date and time are Pro. The `foss` build has
everything unlocked and carries no billing code.

Turning on the accessibility service now starts with an explanation of what it does and
what it reads, which has to be agreed to before Settings opens.

Targets Android 16 (API 36). The bundled ffmpeg is linked for 16 KB pages, so it runs on
devices that use them; rebuild it with `tools/build-ffmpeg.sh` before the next release.

## 0.5.2

The camera-launch timeout gets the same repair 0.5.1 gave the capture window. Found in a real
session log on a OnePlus: calibration had walked it from 12s down to 6819ms, and the camera
then failed to reach the foreground within exactly 6819ms, twice, dropping both frames. A
stored value below 12s is now raised on load — a ceiling that is too wide costs nothing, since
the wait ends the moment the camera appears.

## 0.5.1

### Waiting for things instead of guessing at them

The cycle now waits for the shutter control to actually appear before pressing it, rather
than sleeping a calibrated two seconds and hoping. A cold camera start that used to drop the
frame now just takes a moment longer, and the blind settle afterwards is down to 800ms. The
wait is capped at 6s, so a camera app that exposes no usable control at all falls back to
coordinates quickly rather than paying a full timeout on every frame.

The unlock polls the keyguard instead of sleeping a fixed settle after each swipe, so it
proceeds the moment the lock screen clears, and it now retries four times rather than two — a
swallowed swipe used to cost the frame. The one remaining timer is the wait between the screen
reporting itself on and it actually accepting touches, which no API exposes.

Stop lets a frame that is already being taken finish, instead of throwing away a shutter press
that had already happened. Pressed between frames — where it usually is — it still stops at
once.

### Timeouts no longer shrink

A step that timed out was excluded from calibration, so its slow timing never counted and the
timeout was fitted to the fast cycles alone — shorter timeout, more timeouts, still ignored,
shorter again. A Galaxy S20 walked from 15s to 5s while genuinely needing more than 8s. Now a
timed-out step counts at its limit (it certainly took no less), and recalibration can only
ever widen a ceiling, never narrow one. The capture default is 30s.

The camera settle had the same fault in the opposite direction: it was derived from a
measurement that included the previous settle, so every calibration multiplied it by 1.2 until
it pinned at the 5s ceiling. It is now measured outside the settle it produces.

### The capture window follows the light

The wait for a frame widens during a session as it sees slower captures, so a sunset shot in
auto mode — where exposures lengthen as the light goes — keeps landing frames instead of
failing exactly when the shots get good. Switching to Night mode or a long Pro exposure no
longer needs a recalibration.

### Calibration ends where you can see it

Calibration used to end in silence: its last cycle locks the screen, so the camera app was
left in front and the result was only visible to someone who thought to navigate back.

- Calibration and Test shot now bring the app back to the front when they finish, waking and
  unlocking the screen the way a cycle already does. A full session deliberately does not —
  it can end at four in the morning.
- Every run now posts a dismissible result notification ("Calibrated: shortest safe interval
  11s...", "Finished: 240 frames", "Stopped after 12 frames"). Previously the ongoing
  notification was just removed, which announced nothing at all.
- The notification during calibration counts progress ("Calibrating - 2 of 3 frames") rather
  than repeating "Running - frame 1 captured", which is what every calibration cycle shoots.

## 0.5.0

### Added
- The control page is a multi-device monitor: open it from one phone, then paste other phones'
  pairing links to watch and stop them all on one page. Columns on a wide screen, a single
  scroll on a narrow one. The fleet is remembered in the browser. No device-side changes.
- "Stop at" now takes a full date and time, not just a time of day — a shoot can be scheduled
  days out instead of being capped at the next 24 hours.

### Changed
- Setup and calibration are one collapsible section; once everything is green it folds to a
  single "Setup - OK" line, and re-opens if anything needs attention.
- "One frame" is now "Test shot".
- The per-session "Copy ffmpeg" button is gone — the editable command in the Render command
  section is the general one; the per-session command was too phone-specific to be useful.
- A "Delete photos" button removes a session's frames as well as its record (0.4.x had only
  metadata delete).

### Fixed
- **Render out-of-memory on large 4K timelapses.** `scale=W:-2` set the *width*, so a portrait
  frame's short edge was blown up to a 3840x5120 (20 MP) frame and ffmpeg peaked near 2 GB — on
  a budget phone the low-memory killer took the render with no error logged. The long edge is
  now fit inside a box (scaling down only), the default is 1920, and `sliced-threads` stops
  x264's memory scaling with the thread count. A 2845-frame render that failed now peaks
  ~430 MB. Originals are untouched for a higher-resolution pass on a PC.
- A session ended by the phone dying now shows a duration (estimated from its frames), and
  durations read as "2h 19m 32s", not "8372s".
- The log no longer records every successful frame — only failures and unexpected events, with
  a heartbeat every 25 frames. A long shoot's log stays legible and its persisted history is no
  longer flushed out by per-frame noise.

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
