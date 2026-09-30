# Google Play submission

What the `play` flavor needs beyond the code, checked against the policies in force in
September 2026. Build the upload with `./gradlew :app:bundlePlayRelease`.

## Before the first upload

- [ ] Closed test: a new personal developer account needs 12 testers opted in for 14 days
      before production access is granted.
- [ ] Privacy policy at a public URL. Required because of the accessibility service and the
      photo permission. It can be short: nothing is collected, nothing leaves the phone
      except the webhook calls the user configures themselves.
- [ ] Data safety form: no data collected, no data shared.
- [ ] Record the accessibility video (below).
- [ ] Decide how the ffmpeg source offer is honoured once the repository is private (below).
- [ ] Test on an Android 16 phone, and install the play build itself once. The 16 KB
      ffmpeg, target API 36 and the disclosure dialog were run on Android 15 (OnePlus
      CPH2591) with the foss build: test shot, render and both dialog paths passed.

## Declarations in Play Console

### Accessibility API

`isAccessibilityTool` stays unset: the app is not an assistive tool.

> OpenTimelapse shoots timelapses by operating the phone's own camera app, so that the
> photos get the manufacturer's full image processing. The accessibility service presses the
> camera app's shutter button at the interval the user sets and, in lock-cycle mode, swipes
> away a non-secure lock screen and locks the screen again between frames. This is the app's
> core function; there is no other API that lets one app press another app's shutter.
>
> The automation is deterministic and rule-based: a fixed, user-defined schedule (every N
> seconds, press the shutter) that runs only during a session the user starts and can stop
> from the notification at any time. The app does not plan or decide actions on its own.
>
> The service reads the window layout of the foreground app only to locate the shutter
> control and to confirm the camera is in front. No screen content is stored, transmitted
> or shared.

Video, in this order: open the app → tap **Fix** on "Accessibility service" → the disclosure
dialog, scrolled slowly to the end → **No thanks** (nothing happens) → **Fix** again →
**Agree and continue** → enable the service in Settings → back in the app, start a session
and show it taking a few frames → stop it.

### Foreground service: special use

> Subtype: timelapse_camera_automation. The service runs for the length of a timelapse
> session the user starts, which lasts hours with the screen off. It keeps the interval
> timer, wakes the device and triggers each frame. None of the standard types fits: the app
> does not hold the camera itself (the phone's camera app does), and the work is
> user-initiated and must not be deferred. The user sees an ongoing notification with a Stop
> button throughout.

The rendering service is `mediaProcessing` and needs only the standard declaration.

### Photo and video permissions (`READ_MEDIA_IMAGES`)

> Core function. After each shutter press the app reads the photo the camera app has just
> saved to confirm the frame was really taken, and later reads every frame of a session to
> render the timelapse video on the device. The frames are created by another app,
> continuously and unattended, so the photo picker cannot be used.

### Battery optimisation exemption

The app asks for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Policy allows it only where the
core function breaks without it; if review objects, the justification is that a session
frozen by doze drops frames, and the fallback is to open the battery settings list instead
of the direct dialog.

## Things that differ from the foss build

| | foss | play |
|---|---|---|
| Donation button | yes | no - Play Billing rules |
| Source and issue links | yes | no - closed source |
| All-files access | optional | not declared - frames keep the camera's names |
| `USE_EXACT_ALARM` | declared | not declared - the user grants exact alarms in Setup |

## The bundled ffmpeg is still GPL

Closing the app's source does not change the licence of the ffmpeg binary it ships. It is a
separate executable the app runs, so the app itself need not be GPL, but anyone who receives
the binary must be able to get its source. The play build's licence screen makes a written
offer: source on request, for three years. Honour it by keeping `tools/build-ffmpeg.sh`
and the pinned versions available - the simplest way is a small public repository holding
just that script, linked from the store listing. This is not legal advice; have it checked
before charging for the app.
