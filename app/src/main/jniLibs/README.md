# jniLibs

Native binaries live here but are **not committed** - see `.gitignore`.

## Why this directory exists

Since Android 10 an app may only `exec()` files from its native library directory, which is
read-only and populated from the APK at install time. Putting an executable here (named
`lib*.so`, with `useLegacyPackaging = true`) is the only way to run one.

## libffmpeg.so

The bundled ffmpeg, built by `tools/build-ffmpeg.sh`. It is a build artefact, not a source,
so it is git-ignored; build it before assembling a release:

```sh
NDK=~/android-ndk-r27c ./tools/build-ffmpeg.sh arm64-v8a armeabi-v7a
```

See `LICENSES/README.md` for the GPL obligations that come with shipping it.

The `exec` diagnostic in the Phase 0 probe harness runs this same binary (`ffmpeg -version`)
to confirm execution from `nativeLibraryDir` works on a given device.
