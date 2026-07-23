# Changelog

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
