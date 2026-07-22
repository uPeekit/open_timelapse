# v3 scope

Current shipped version is **0.2**: it shoots unattended timelapses, names and files frames,
records sessions, and renders them on the device with a bundled ffmpeg.

v3 adds **live monitoring**, **video that is ready when you stop**, and finishes the
half-built pieces of v2. It deliberately does not add a second camera backend.

---

## 0. Prerequisite — finish v2 first

None of the v3 features matter if a long shoot dies at hour two, so this comes first and
ships as **0.2.x**.

| Item | Why |
|---|---|
| **Long-run test, 4+ hours** | The only real unknown left. Nothing has run beyond ~2 minutes. Measures drift, battery drain, thermal throttling, frame loss. |
| **Stop conditions in Settings** | `AFTER_DURATION` / `AT_TIME` work in the engine and are unit-tested, but are not exposed — "shoot for 4 hours" is currently impossible. |
| **Calibrate on OnePlus + OPPO** | Calibration has only ever run on a Galaxy S20. A second platform is where its device-agnostic claims get tested. |
| **Retire `SpikeLog` from the production path** | `LogRepository` logs through a Phase 0 diagnostic object. Fine for development, wrong as a dependency. |

Anything the long run reveals is fixed here, not deferred into v3.

---

## 1. Incremental encoding — video ready when you press Stop

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

**Risks, in order of concern**

1. **A long-lived process across doze.** The CPU sleeps between frames with the wakelock
   released; the ffmpeg process is suspended and must resume cleanly hours later. **Needs a
   spike before committing to the design** — this is the one assumption that could sink it.
2. fps and resolution are fixed when the session starts. Acceptable: the JPEGs survive, so
   a re-render at different settings is always possible.
3. A crash leaves a truncated mp4. Harmless, but it must be deleted rather than shown.

**Config.** `capture.buildVideoWhileShooting: Boolean = false`, plus the fps/size to use.
Off by default — the safe path stays the default path.

---

## 2. Local network control — monitor and control an unattended phone

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

## 3. Explicitly deferred — Camera2 capture backend

A second backend using Camera2/CameraX would allow **background capture with no camera app
on screen**, which is how TimeLapseCam works.

**Not in v3.** It is a whole camera implementation, and it loses the thing that motivated
this project: the OEM's computational pipeline. Manual controls are available through
Camera2, but Samsung's multi-frame stacking, HDR and night processing are not — a Camera2
capture on the same phone, same scene, is visibly worse.

Worth revisiting only if unattended-with-screen-on turns out to be a practical problem.

---

## 4. Not yet specified — charging control and sync

Smart-charger webhooks ("stop at 80%, resume at 40%") and photo sync, from the existing
MacroDroid setup. Battery is the binding constraint on multi-hour shoots, so this is
promising and connects to the stop-conditions work above.

**Blocked on a conversation**, not on code.

---

## Order of work

1. Long-run test → fix whatever it finds → stop conditions in Settings → **0.2.x**
2. Doze spike for a long-lived ffmpeg process
3. Incremental encoding, if the spike passes
4. Local network control
5. Charging control, once specified

Sizing is deliberately absent: the doze spike and the long-run test can both change the
plan, and estimating past them would be guessing.
