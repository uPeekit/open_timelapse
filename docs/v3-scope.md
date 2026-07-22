# v3 scope

Current shipped version is **0.2**: it shoots unattended timelapses, names and files frames,
records sessions, and renders them on the device with a bundled ffmpeg.

v3 adds **live monitoring**, **video that is ready when you stop**, and finishes the
half-built pieces of v2. It deliberately does not add a second camera backend.

---

## 0. Prerequisite — mostly done

Shipped in 0.2.1 to 0.2.5, all found by real use rather than by tests:

| Defect | Threshold that hid it |
|---|---|
| Stop crashed the app (`startForegroundService` without `startForeground`) | any press |
| Setup checklist reported accessibility green while it was off | — |
| Wakelock expired 10 minutes in, frames drifted then stalled | sessions > 10 min |
| Alarms deferred by app-standby, frames arrived in pairs | intervals > 60 s |
| Encoder grew past 1.1 GB, low-memory killer took the app | renders ~50 frames |

**Soak result, OnePlus, 0.2.4:** 21 frames at a 150 s interval over 50 minutes, every gap
within ±1 s, **2 seconds total drift**, screen off. The scheduling paths are sound.

Still outstanding, carried into v3 rather than blocking it:

| Item | Why |
|---|---|
| **Stop conditions in Settings** | Work in the engine and are unit-tested, but unreachable — "shoot for 4 hours" is still impossible. |
| **Multi-hour soak** | 50 minutes is good evidence, not proof. Every defect so far appeared past a threshold, and doze deepens over hours. |
| **Retire `SpikeLog` from the production path** | `LogRepository` logs through a Phase 0 diagnostic object. |

**The lesson worth carrying into v3:** every one of those five defects was invisible to a
test that finished quickly. Each v3 feature below should be asked the same question - what
threshold hides its failure - before it is called done.

---

## 1. Start trigger: the first manual shot begins the timelapse

**What.** Instead of a countdown, Start opens the camera and then waits. The user sets the
mode, frames the shot, and **takes one photo by hand**. The app sees that photo appear, keeps
it as frame 1, and schedules everything from that moment.

**Why it is better than a timer.** A countdown is a guess about how long setup takes: too
short and it starts before Pro mode is dialled in, too long and the user waits for nothing.
A manual shot has no wrong answer - it happens exactly when the user is ready, and the first
frame is one they composed rather than whatever the camera was pointing at when a clock ran
out.

**Cost: almost nothing.** `MediaStoreWatcher.awaitNewMedia` already does exactly this
detection, and it is how every frame is confirmed. The trigger is the same call with a long
timeout.

**Design**

```kotlin
enum class StartTrigger { TIMER, FIRST_MANUAL_SHOT }
```

- `FIRST_MANUAL_SHOT` becomes the default; `TIMER` stays for unattended restarts.
- Timeout of a few minutes, after which the session gives up rather than waiting forever.
- The notification says "waiting for your first photo", so a phone left on a windowsill does
  not look broken.
- Interacts with renaming: the manual frame is frame 1 and gets renamed like any other.

---

## 2. Incremental encoding — video ready when you press Stop

**What.** Optionally build the mp4 *during* the shoot instead of rendering afterwards.

**Why it fits.** At a 20 s interval the phone is idle for ~15 s of every cycle, and encoding
one frame takes a few seconds. The cost hides in time that is currently wasted, and the
video is finished the moment the session ends.

**How.** Keep one ffmpeg process alive for the session, reading frames from its stdin:

```
ffmpeg -f image2pipe -framerate <fps> -i pipe:0  <filters> -c:v libx264 … out.mp4
```

Each confirmed frame is written to the pipe as it is captured. `FfmpegRunner` already owns
process lifecycle and stderr draining; this adds a long-lived variant with a stdin writer.

**The frames remain the source of truth.** If the pipe dies, the session keeps shooting and
logs it — a lost video is recoverable by rendering afterwards, a lost frame is not.

**Risks, in order of concern** — reassessed after the 0.2.5 memory finding

1. **Memory held for hours, not seconds.** A render peaked over 1.1 GB and Samsung's
   low-memory killer terminated the app; capping preset, threads and lookahead fixed it for
   a 46 second render. Incremental encoding holds an encoder open for the *entire session*.
   Even at the capped footprint, several hundred megabytes resident for four hours is a
   standing invitation to the same killer - and losing the app mid-shoot now costs the
   frames too, not just the video. **This is now the biggest risk, ahead of doze.**
2. **A long-lived process across doze.** The CPU sleeps between frames with the wakelock
   released; the process is suspended and must resume cleanly hours later. Still needs a
   spike.
3. fps and resolution are fixed when the session starts. Acceptable: the JPEGs survive.
4. A crash leaves a truncated mp4. Harmless, but it must be deleted rather than shown.

**Consequence: this drops down the order.** The whole point was to save a render pass, and
a render now costs 46 seconds. Trading that against a process that could get the app killed
during a shoot is a poor bargain. Do it only if the spike shows the resident footprint
staying small between frames.

**Config.** `capture.buildVideoWhileShooting: Boolean = false`, plus the fps/size to use.
Off by default — the safe path stays the default path.

---

## 2b. Editable ffmpeg command

**What.** Show the generated command, let it be edited, keep the generated one as the
baseline and offer a reset.

**Why.** The whole reason ffmpeg was bundled rather than MediaCodec is flexibility, and
right now every render is whatever `FfmpegCommandBuilder` decided. Deflicker, a different
CRF, `-vf` chains, two-pass, a crop - all of it is a text edit away and none of it is
reachable.

**Design**

- Generated command shown in full; the fields (fps, size, quality) keep working and
  regenerate it, until the text is edited by hand.
- Once edited, it is used verbatim. A **Reset** returns to generated.
- The output path is substituted rather than typed, so a render always lands somewhere the
  app can then publish.
- Saved per session or as a default template - a command that worked for one shoot is
  usually the one wanted for the next.

**Risk.** A hand-edited command can fail in ways the app cannot anticipate. That is
acceptable: ffmpeg's stderr tail is already surfaced on failure, and the frames are never
touched by a render.

---

## 3. Local network control — monitor and control an unattended phone

**What.** A small HTTP server in the existing foreground service, so a phone on a windowsill
can be checked from a laptop.

| Endpoint | Purpose |
|---|---|
| `GET /status` | JSON: state, frames captured, next frame in, session name, battery, free storage |
| `GET /preview` | Last captured frame, downscaled |
| `POST /start`, `POST /stop` | Control |
| `GET /events` | Server-sent events, so a browser page updates live |

Plus a single self-contained HTML page served from `/`, so no client needs installing.

**Discovery.** mDNS via `NsdManager` (`_opentimelapse._tcp`) so the phone is found by name
rather than by hunting for an IP.

### Security is the design, not a footnote

The app currently holds **no network permission at all** — a genuinely valuable property for
something that drives your camera and reads your photo library. v3 spends that, so it must
be spent carefully:

- **Off by default.** Enabled per session, never implicitly.
- **Token required.** Generated when enabled, carried as a header or query parameter. Shown
  in-app as a **QR code** so pairing is a scan, not a typed secret.
- **Loud.** The foreground notification states plainly when the server is live.
- **No writes without the token**, including `/preview` — a frame preview is camera output.

An unauthenticated port on shared wifi would let anyone on the network stop a shoot or watch
previews. That is not acceptable for a phone left alone for hours.

**Implementation note.** A hand-rolled server over `ServerSocket` is ~150 lines for four
endpoints and adds no dependency. NanoHTTPD would save a little work at the cost of an
unmaintained dependency in an app whose dependency list is currently very short. Leaning
hand-rolled; decide when writing it.

**New permission:** `INTERNET`. Documented in the README and the licences screen.

---

## 4. Explicitly deferred — Camera2 capture backend

A second backend using Camera2/CameraX would allow **background capture with no camera app
on screen**, which is how TimeLapseCam works.

**Not in v3.** It is a whole camera implementation, and it loses the thing that motivated
this project: the OEM's computational pipeline. Manual controls are available through
Camera2, but Samsung's multi-frame stacking, HDR and night processing are not — a Camera2
capture on the same phone, same scene, is visibly worse.

Worth revisiting only if unattended-with-screen-on turns out to be a practical problem.

---

## 5. Charging control

**The problem being replaced.** MacroDroid watched the battery, called an IFTTT webhook,
which called SmartLife, which switched a socket. Three apps, and IFTTT's free tier allowed
only enough applets for one socket - which does not scale to several phones.

**What the app should do: nothing clever.** Emit a webhook when the battery crosses a
threshold, with a user-configured URL, method and body. That is a small feature which works
with whatever the user already runs, and keeps the orchestration out of a timelapse app.

```
Battery ≤ 40%  →  POST <url>   (start charging)
Battery ≥ 80%  →  POST <url>   (stop charging)
              [ Test ]
```

**Only while a session is running.** Decided rather than assumed: it means no permanently
running battery watcher, no background power cost between shoots, and the listener simply
registers and unregisters with the foreground service that already exists for the session.

**Four details that decide whether this works at all**

1. **Fire on the crossing, not the level.** `if level <= 40 → POST` fires every cycle while
   the battery sits at 39%. Edge-triggered with re-arming: send once crossing down through
   the low mark, and not again until it has been back above the high one.
2. **Charging state, not just level.** "≥ high *and charging*" → stop; "≤ low *and not
   charging*" → start. Otherwise an already-unplugged phone is repeatedly told to stop
   charging.
3. **Method, headers and body configurable.** IFTTT accepts a bare GET, Home Assistant
   wants a POST, a REST endpoint may need a token header. URL-only would exclude half the
   plausible integrations.
4. **A Test button.** Otherwise verifying a setup means waiting for a real battery to reach
   40%, turning a three-second check into a three-hour one.

**Failures are logged and never interrupt the session.** A socket that did not switch must
not cost frames.

**The battery-floor stop condition is the backstop.** Webhooks are best effort - the socket
may be unplugged, the hub down, the wifi gone. Stopping the session at a floor needs no
network and is what actually protects the phone.

**Recommended orchestrator: Home Assistant.** Unlimited automations, no per-applet cap, and
Tuya/SmartLife sockets work through the Tuya integration or LocalTuya - the latter entirely
on the LAN, so a socket keeps switching even when the internet is down. One system for many
phones, replacing all three apps. Node-RED works equally well for anyone already running it.

Keeping IFTTT is possible - the webhook URL is just a different string - but the free-tier
cap is exactly the limitation being escaped.

**Also worth doing in-app, with no network at all:** a battery-floor stop condition. The app
already knows the level, and stopping a session at 15% rather than shooting until the phone
dies is useful on its own. This was in the original design and never built.

**Why the 40-80% band is the right instinct:** holding a lithium cell at 100% while it sits
on a charger for days is what wears it out. A phone that lives on a windowsill shooting
timelapses is exactly the case where this matters.

---

## Order of work

1. Long-run test → fix whatever it finds → stop conditions in Settings → **0.2.x**
2. Start trigger on first manual shot - small, and removes a guess from the flow
3. Doze spike for a long-lived ffmpeg process
4. Incremental encoding, if the spike passes
5. Local network control
6. Charging webhooks + battery-floor stop condition, sharing the network work above

Sizing is deliberately absent: the doze spike and the long-run test can both change the
plan, and estimating past them would be guessing.
