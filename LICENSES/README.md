# Third-party licences

OpenTimelapse's own source is Apache-2.0 (see `/LICENSE`).

## The bundled ffmpeg binary is GPL-2.0-or-later

Release APKs contain an `ffmpeg` executable at `lib/<abi>/libffmpeg.so`, built from:

| Component | Version | Licence |
|---|---|---|
| FFmpeg | `n7.1` | GPL-2.0-or-later *(as configured here)* |
| x264 | `stable` branch | GPL-2.0-or-later |

FFmpeg is LGPL by default. This build enables `--enable-gpl --enable-libx264`, which makes
the **resulting binary GPL-2.0-or-later**. That was a deliberate choice: libx264 provides
`-crf`, so a render on the phone can match a desktop ffmpeg command exactly.

### What this means when distributing a build

The app's Kotlin source remains Apache-2.0. ffmpeg runs as a **separate process** invoked
via `exec`, not linked into the app, so the GPL does not reach the application code. The
binary itself is still covered, so any APK you hand to someone else must be accompanied by:

1. **The corresponding source.** Satisfied by `tools/build-ffmpeg.sh`, which pins the exact
   upstream versions above and reproduces the binary. Keep it with the release.
2. **The licence texts** in this directory.
3. **A notice** that the app bundles GPL software - shown in-app under "Open source
   licences", and stated in the README.

If you would rather avoid GPL obligations entirely, drop `--enable-gpl --enable-libx264`
from the build script. The binary becomes LGPL and still renders through
`h264_mediacodec`; you lose `-crf` (hardware encoding is bitrate-controlled) and the
`Archival` quality mode.

### Reproducing the binary

```sh
NDK=~/android-ndk-r27c ./tools/build-ffmpeg.sh arm64-v8a armeabi-v7a
```

The binary is **not** committed - it is a build artefact (see `.gitignore`). Build it
before assembling a release.

## Licence texts

- `ffmpeg-COPYING.GPLv2` - FFmpeg, GPL-2.0
- `x264-COPYING` - x264, GPL-2.0
