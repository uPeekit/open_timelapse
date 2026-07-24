# v5 — Multi-device monitor

## Problem

The control page monitors one phone: it is served by that phone, talks to it over relative
URLs (`/status`, `/preview`, `/stop`), and holds one token. Someone running several phones on
windowsills has to open a separate tab per phone. They want **one page showing all their
phones at once**, adding each by pasting its pairing link.

## Approach

No device-side changes. The server already sends `Access-Control-Allow-Origin: *` and accepts
the token as a `?token=` query parameter, so one browser page can poll many phones
cross-origin. The whole feature is a rewrite of the served page (`app/.../net/ControlPage.kt`)
from a single-device view into a client-side multi-device dashboard.

Why this works without server changes:
- **CORS.** Responses already carry `Access-Control-Allow-Origin: *`.
- **No preflight.** Every request uses the token as a query parameter and sets no custom
  headers, so each is a "simple" cross-origin request (`GET`, or `POST /stop` with no body or
  custom content-type). A preflight would need an `OPTIONS` handler the server does not have;
  query-param tokens avoid it entirely.
- **Public shell.** `GET /` is already public (`ALLOW_PUBLIC`), so the dashboard loads from any
  running phone without a token; tokens are only needed for the data endpoints.

## Behaviour

- The page holds a list of **device cards**. Each card is today's single-phone view: state,
  session name, frames, next-frame countdown, battery, free storage, last-frame preview, and a
  **Stop** button.
- The phone the page was opened from is **auto-added** as the first card — address from
  `location.origin`, token from `?token=`. The token is then stripped from the visible URL
  (`history.replaceState`), exactly as the current page does.
- A **"+ Add"** box takes a pasted **pairing link** such as
  `http://192.168.0.55:8787/?token=Y`. The page parses `origin` (address) and `token` from it
  and adds a card. (A bare token cannot be added — a phone is unreachable without its address,
  and the pairing link is where the user already gets both.)
- Each card has a **×** to remove it; a **Forget all** clears the fleet.
- The fleet (list of `{base, token}`) is remembered in `localStorage`, so reopening the page
  shows every phone again without re-pasting. This is inherently per-page-origin: open the page
  from the same phone habitually and the list sticks; open it from a different phone and that
  origin has its own list. Accepted as the simple, pure-client behaviour.

### Per-card polling

Each card polls its own phone independently, every ~2 s, using **absolute** URLs built from the
card's `base`:

- `GET {base}/status?token={token}` → the status JSON.
- `GET {base}/preview?token={token}&t={now}` → the last frame (cache-busted).
- `POST {base}/stop?token={token}` → stop that phone's session.

A card whose phone is unreachable (fetch error) shows "unreachable" and keeps polling; it
recovers on its own when the phone returns. A `401` shows "token rejected".

### Layout

- A CSS grid: `repeat(auto-fill, minmax(200px, 1fr))`. Wide laptop screen → several columns;
  narrow phone → a single scrolling column.
- The preview image is **~30 % smaller** than today (so a card is narrower and more fit across
  a wide screen). The backend still serves the same downscaled JPEG; the card just displays it
  smaller via CSS (`max-width` on the image sized to the narrower card). The `minmax` min of
  200 px (down from ~280) matches the narrower card.

## Out of scope (deliberately)

- No per-device rename, custom labels beyond the address.
- No auth or endpoint changes; no new endpoints.
- No fleet sharing across different phone-origins (browser storage cannot, without a server).
- No standalone/exported dashboard file — the served page, opened from any running phone, is
  enough. (Could revisit later if a phone being up to host becomes a problem.)

## Verification

It is HTML/JS with no Kotlin logic beyond the served string, so it is verified by serving it
and opening it against the three real phones:

1. Open the QR link from phone A → phone A's card appears and updates (status, preview).
2. Paste phone B's and phone C's pairing links → their cards appear and update.
3. Wide window shows columns; narrow window shows a single scrollable column; previews are
   visibly smaller than the current page.
4. Stop on one card stops only that phone.
5. Turn one phone's session off → its card shows "unreachable"; the others keep updating.
6. Reload the page → all cards return from `localStorage`. `×` removes one; Forget all clears.
7. A phone with a running session that was never opened-from can still be added and controlled
   (cross-origin path exercised).
