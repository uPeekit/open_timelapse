# Multi-device Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-phone control page into one dashboard that monitors and stops several phones at once, each added by pasting its pairing link.

**Architecture:** Client-side only. The server already sends `Access-Control-Allow-Origin: *` and accepts the token as a `?token=` query parameter, so one browser page can poll many phones cross-origin with no device-side change. The entire feature is a rewrite of the served HTML/JS string in `app/.../net/ControlPage.kt`.

**Tech Stack:** Kotlin (a raw-string HTML page served by `ControlServer`), plain browser HTML/CSS/JS (no framework, no external assets — the page is served over plain http on a LAN and can fetch nothing else).

## Global Constraints

- No device-side changes: no new endpoints, no auth changes, no server code changes. Existing endpoints are `GET /` (public shell), `GET /status`, `GET /preview`, `POST /stop`, all gated by `ControlAuth`.
- Every cross-origin request must stay a "simple" CORS request: token as `?token=` query parameter, **no custom request headers** (no `Authorization`), so no preflight is needed and the existing `Access-Control-Allow-Origin: *` suffices.
- The page is self-contained: inline CSS/JS only, no external URLs, no `$` or `${}` (it lives in a Kotlin `"""` raw string where `$` means interpolation) — use string concatenation, not JS template literals.
- Preview image displayed ~30% smaller than the current page (`max-width: 70%` of the card), and the grid column min is `200px`, so more phones fit across a wide screen.
- Verification is manual (build → serve → browser against the three real phones); there is no Kotlin unit test for a served HTML string.

---

### Task 1: Rewrite the served page as a multi-device dashboard

**Files:**
- Modify (replace the `HTML` value): `app/src/main/kotlin/org/peekit/opentimelapse/net/ControlPage.kt`

**Interfaces:**
- Consumes (unchanged, from the running server): `GET {base}/status?token=T` → JSON `{state, sessionName, framesCaptured, nextFrameInSeconds, intervalSeconds, batteryPercent, charging, freeStorageMb, runningForMs}`; `GET {base}/preview?token=T` → JPEG or 204; `POST {base}/stop?token=T` → `{"ok":true}`.
- Produces: the served page. No Kotlin API change — `ControlPage.HTML` stays a `String` used by `ControlServer.route` for `Access.ALLOW_PUBLIC`.

- [ ] **Step 1: Replace the page content**

Replace the whole `HTML` string in `ControlPage.kt` with the dashboard below. Keep the `object ControlPage { val HTML = """ ... """.trimIndent() }` wrapper and the file's package/KDoc; only the string body changes. Update the KDoc first line to describe the multi-device page.

```kotlin
package org.peekit.opentimelapse.net

/**
 * The dashboard the server hands a browser: one page that monitors several phones at once.
 *
 * Self-contained by necessity - served over plain http on a LAN with nothing else to fetch.
 * Each phone is a card; the phone the page was opened from is added automatically from the
 * ?token= link, and more are added by pasting their pairing links. The fleet is kept in this
 * page's localStorage. Every request carries the token as a query parameter and sets no custom
 * header, so cross-origin polling of other phones stays a "simple" CORS request.
 */
object ControlPage {
    val HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>OpenTimelapse</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, sans-serif; margin: 0; padding: 1rem; }
  h1 { font-size: 1.2rem; margin: .2rem 0 1rem; }
  .add { display: flex; gap: .5rem; margin-bottom: 1rem; max-width: 640px; flex-wrap: wrap; }
  input { flex: 1; min-width: 12rem; padding: .55rem; font-size: 1rem; box-sizing: border-box;
          border-radius: 8px; border: 1px solid rgba(128,128,128,.5); background: transparent; color: inherit; }
  button { padding: .55rem 1rem; font-size: 1rem; border-radius: 8px; border: none; cursor: pointer; }
  .primary { background: #5b4bd6; color: #fff; }
  .ghost { background: transparent; border: 1px solid rgba(128,128,128,.5); color: inherit; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 1rem; }
  .card { border: 1px solid rgba(128,128,128,.35); border-radius: 12px; padding: .8rem; }
  .bar { display: flex; justify-content: space-between; align-items: center; }
  .bar h2 { font-size: .95rem; margin: 0; word-break: break-all; }
  .row { display: flex; justify-content: space-between; padding: .15rem 0; font-size: .9rem; }
  .k { opacity: .7; }
  .card img { max-width: 70%; border-radius: 8px; margin-top: .5rem; display: none; }
  .muted { opacity: .6; font-size: .8rem; }
  .x { background: transparent; border: none; color: inherit; opacity: .6; cursor: pointer; font-size: 1.2rem; }
  .stop { background: #5b4bd6; color: #fff; margin-top: .5rem; }
</style>
</head>
<body>
<h1>OpenTimelapse</h1>

<div class="add">
  <input id="link" placeholder="paste a phone's pairing link" autocomplete="off">
  <button class="primary" onclick="addFromInput()">+ Add</button>
  <button class="ghost" onclick="forgetAll()">Forget all</button>
</div>

<div class="grid" id="grid"></div>

<script>
var STORE = 'otl.devices';

function load() {
  try { return JSON.parse(localStorage.getItem(STORE)) || []; } catch (e) { return []; }
}
function save(list) { localStorage.setItem(STORE, JSON.stringify(list)); }

// "http://host:port/?token=X" -> {base, token}
function parseLink(text) {
  try {
    var u = new URL(text.trim());
    return { base: u.origin, token: u.searchParams.get('token') || '' };
  } catch (e) { return null; }
}

function add(device) {
  if (!device || !device.base || !device.token) return false;
  var list = load();
  var existing = list.find(function (d) { return d.base === device.base; });
  if (existing) { existing.token = device.token; } else { list.push(device); }
  save(list);
  render();
  return true;
}

function addFromInput() {
  var input = document.getElementById('link');
  if (add(parseLink(input.value))) { input.value = ''; input.placeholder = 'paste a phone\'s pairing link'; }
  else { input.placeholder = 'not a valid pairing link'; input.value = ''; }
}

function remove(base) {
  save(load().filter(function (d) { return d.base !== base; }));
  render();
}

function forgetAll() {
  if (confirm('Forget all phones on this page?')) { save([]); render(); }
}

function fmtNext(s) { return s == null ? '-' : (s <= 0 ? 'now' : s + 's'); }

function card(device) {
  var el = document.createElement('div');
  el.className = 'card';
  el.innerHTML =
    '<div class="bar"><h2>' + device.base.replace(/^https?:\/\//, '') + '</h2>' +
    '<button class="x" title="remove">&times;</button></div>' +
    '<div class="row"><span class="k">State</span><span data-f="state">-</span></div>' +
    '<div class="row"><span class="k">Session</span><span data-f="session">-</span></div>' +
    '<div class="row"><span class="k">Frames</span><span data-f="frames">-</span></div>' +
    '<div class="row"><span class="k">Next</span><span data-f="next">-</span></div>' +
    '<div class="row"><span class="k">Battery</span><span data-f="battery">-</span></div>' +
    '<div class="row"><span class="k">Storage</span><span data-f="storage">-</span></div>' +
    '<img data-f="preview" alt="last frame">' +
    '<div><button class="stop">Stop</button></div>' +
    '<p class="muted" data-f="msg"></p>';
  el.querySelector('.x').onclick = function () { remove(device.base); };
  el.querySelector('.stop').onclick = function () { stop(device); };
  return el;
}

function field(el, name) { return el.querySelector('[data-f="' + name + '"]'); }

async function pollCard(device, el) {
  try {
    var r = await fetch(device.base + '/status?token=' + encodeURIComponent(device.token));
    if (r.status === 401) { field(el, 'msg').textContent = 'token rejected'; return; }
    var s = await r.json();
    field(el, 'state').textContent = s.state;
    field(el, 'session').textContent = s.sessionName || '-';
    field(el, 'frames').textContent = s.framesCaptured;
    field(el, 'next').textContent = fmtNext(s.nextFrameInSeconds);
    field(el, 'battery').textContent = s.batteryPercent + '%' + (s.charging ? ' (charging)' : '');
    field(el, 'storage').textContent = s.freeStorageMb + ' MB';
    field(el, 'msg').textContent = '';
    if (s.framesCaptured > 0) {
      var img = field(el, 'preview');
      img.src = device.base + '/preview?token=' + encodeURIComponent(device.token) + '&t=' + Date.now();
      img.style.display = 'block';
    }
  } catch (e) {
    field(el, 'msg').textContent = 'unreachable';
  }
}

async function stop(device) {
  try { await fetch(device.base + '/stop?token=' + encodeURIComponent(device.token), { method: 'POST' }); }
  catch (e) {}
}

var cards = {};

function render() {
  var grid = document.getElementById('grid');
  grid.innerHTML = '';
  cards = {};
  load().forEach(function (device) {
    var el = card(device);
    grid.appendChild(el);
    cards[device.base] = { el: el, device: device };
  });
}

function pollAll() {
  Object.keys(cards).forEach(function (base) { pollCard(cards[base].device, cards[base].el); });
}

// The phone the page was opened from: add it, then strip the token from the visible URL.
(function () {
  var m = location.search.match(/token=([^&]+)/);
  if (m) {
    add({ base: location.origin, token: decodeURIComponent(m[1]) });
    history.replaceState(null, '', location.pathname);
  } else {
    render();
  }
})();

pollAll();
setInterval(pollAll, 2000);
</script>
</body>
</html>
    """.trimIndent()
}
```

- [ ] **Step 2: Confirm the Kotlin still compiles (the raw string is valid)**

Run: `./gradlew :app:compileReleaseKotlin`
Expected: `BUILD SUCCESSFUL`. A stray `$` or `"""` inside the HTML would fail here — if it does, find the offending character and escape it (`${'$'}` for a literal `$`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/org/peekit/opentimelapse/net/ControlPage.kt
git commit -m "v5: multi-device monitor page

One dashboard polling several phones at once. Each phone is a card;
the one the page was opened from is added from its ?token= link, more
are added by pasting pairing links, and the fleet is kept in the page's
localStorage. Cross-origin polling uses query-param tokens and no custom
header, so it stays a simple CORS request the existing ACAO:* covers.
Smaller previews and a 200px grid min so more cards fit across a wide
screen. No device-side changes."
```

---

### Task 2: Bump version and changelog

**Files:**
- Modify: `app/build.gradle.kts` (the `versionCode`/`versionName` lines)
- Modify: `CHANGELOG.md` (add a `0.5.0` section at the top)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing code-facing; a releasable version.

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts`, increase `versionCode` by one (it must exceed the last installed build; check the current value and add 1) and set `versionName = "0.5.0"`.

- [ ] **Step 2: Add the changelog entry**

Add at the top of `CHANGELOG.md`, above the `## 0.4.0` section:

```markdown
## 0.5.0

### Added
- The control page is now a multi-device monitor: open it from one phone, then paste other
  phones' pairing links to watch and stop them all on one page. Columns on a wide screen, a
  single scroll on a narrow one. Smaller previews so more phones fit. The fleet is remembered
  in the browser. No device-side changes — it uses the same status/preview/stop endpoints.

```

- [ ] **Step 3: Confirm it builds**

Run: `./gradlew :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`, APKs produced under `app/build/outputs/apk/release/`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "0.5.0: multi-device monitor"
```

---

## Manual verification (on the three phones)

Not a commit step — run after building and installing on at least two phones with the network
control feature enabled and a session running on each (so their servers are up). Use a laptop
browser on the same wi-fi.

1. **Auto-add + live update.** Open phone A's QR link. Its card appears; State/Frames/Battery
   update every ~2s and a preview shows once it has a frame. The token disappears from the
   address bar.
2. **Add more.** Paste phone B's pairing link into the Add box → B's card appears and updates.
   Repeat for phone C. All three update independently.
3. **Layout.** Widen the browser → cards flow into columns. Narrow it → a single scrolling
   column. Previews are visibly smaller than the old single-phone page.
4. **Cross-origin.** Confirm phone B/C (added by link, not the origin the page came from) still
   fetch status and preview and can be stopped — this is the cross-origin path.
5. **Stop is per-card.** Press Stop on phone B only → B's session ends (its card goes to
   `IDLE`/unreachable as its server shuts down); A and C keep running.
6. **Unreachable.** Turn one phone's session off → its card shows "unreachable"; the others
   keep updating. Turn it back on → it recovers.
7. **Persistence.** Reload the page → all cards return from localStorage. `×` removes one card;
   **Forget all** clears the list.
8. **Bad input.** Paste a non-link into Add → the field shows "not a valid pairing link" and
   adds nothing.

## Self-review notes (author)

- **Spec coverage:** cards/per-phone view (Task 1), auto-add from `?token=` + strip (Task 1),
  Add by pairing link (Task 1), ×/Forget all (Task 1), localStorage fleet (Task 1), responsive
  grid + 30%-smaller preview (Task 1 CSS + constraints), query-param tokens / no preflight
  (Task 1 fetches), unreachable/401 handling (Task 1 `pollCard`), no device changes (whole
  plan). Version/changelog (Task 2). Verification maps to the spec's 7 points plus bad input.
- **Placeholders:** none — full page content is inline.
- **Type consistency:** the JS reads exactly the `StatusSnapshot` field names the server emits
  (`state`, `sessionName`, `framesCaptured`, `nextFrameInSeconds`, `batteryPercent`,
  `charging`, `freeStorageMb`); `ControlPage.HTML: String` is unchanged as seen by
  `ControlServer`.
