# jniLibs

Native binaries live here but are **not committed** - see `.gitignore`.

## Why this directory exists

Since Android 10 an app may only `exec()` files from its native library directory, which is
read-only and populated from the APK at install time. Putting an executable here (named
`lib*.so`, with `useLegacyPackaging = true`) is the only way to run one. Phase 7 ships
`ffmpeg` this way; Phase 0 used it to prove the mechanism works.

## Regenerating the Phase 0 probe binary

`Probes.execNativeBinary` runs `libshprobe.so`, which is just a device's own shell renamed -
a real ARM64 ELF, so the probe tests the actual path ffmpeg will take:

```sh
adb pull /system/bin/sh app/src/main/jniLibs/arm64-v8a/libshprobe.so
```

Verified on OnePlus CPH2465 (Android 15), Samsung SM-G980F (Android 13) and OPPO CPH2591
(Android 15) - and the *same* binary pulled from one device ran on the other two, which is
the portability ffmpeg will rely on.

Toybox (`/system/bin/toybox`) does **not** work as a probe: it dispatches on `argv[0]`, which
`ProcessBuilder` cannot set independently of the path, so it exits 127 without running the
requested command.
