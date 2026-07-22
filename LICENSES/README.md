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

### Why the LGPL option is not actually available

The obvious way to avoid the GPL would be to drop `--enable-gpl --enable-libx264` and
encode through `h264_mediacodec` instead. **That does not work here.** ffmpeg reaches
MediaCodec through JNI, which needs a `JavaVM` supplied by the hosting app; a standalone
executable run with `ProcessBuilder` has none, and the encoder aborts with
`stack corruption detected` (measured on a Galaxy S20).

So libx264 is the only encoder that functions in this design, and the GPL obligations
above are unavoidable while rendering happens on the device. Hardware encoding would
require driving Android's MediaCodec API from Kotlin rather than using ffmpeg for the
encode step - a different implementation, not a build flag.

### Reproducing the binary

```sh
NDK=~/android-ndk-r27c ./tools/build-ffmpeg.sh arm64-v8a armeabi-v7a
```

The binary is **not** committed - it is a build artefact (see `.gitignore`). Build it
before assembling a release.

## Licence texts

- `ffmpeg-COPYING.GPLv2` - FFmpeg, GPL-2.0
- `x264-COPYING` - x264, GPL-2.0
