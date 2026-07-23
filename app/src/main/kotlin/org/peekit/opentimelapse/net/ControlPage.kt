package org.peekit.opentimelapse.net

/**
 * The one page the server hands a browser, so controlling the phone needs nothing installed.
 *
 * Self-contained by necessity - it is served over plain http on a LAN with no way to fetch
 * anything else. It asks for the token once (remembered in this tab), then polls /status and
 * sends it as a Bearer header on every request. The token is never in the page as shipped;
 * the user pastes it or arrives via a ?token= link from the QR code.
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
  body { font-family: system-ui, sans-serif; margin: 0; padding: 1.5rem; max-width: 640px; }
  h1 { font-size: 1.3rem; }
  .card { border: 1px solid rgba(128,128,128,.35); border-radius: 12px; padding: 1rem; margin: 1rem 0; }
  .row { display: flex; justify-content: space-between; padding: .25rem 0; }
  .k { opacity: .7; }
  input { width: 100%; padding: .6rem; font-size: 1rem; box-sizing: border-box; border-radius: 8px;
          border: 1px solid rgba(128,128,128,.5); background: transparent; color: inherit; }
  button { padding: .7rem 1.2rem; font-size: 1rem; border-radius: 8px; border: none; cursor: pointer;
           margin-right: .5rem; }
  .start { background: #5b4bd6; color: #fff; }
  .stop { background: transparent; border: 1px solid #5b4bd6; color: #5b4bd6; }
  img { max-width: 100%; border-radius: 8px; margin-top: .5rem; display: none; }
  .muted { opacity: .6; font-size: .85rem; }
</style>
</head>
<body>
<h1>OpenTimelapse</h1>

<div class="card" id="auth">
  <label class="k" for="token">Access token</label>
  <input id="token" placeholder="paste the token from the app" autocomplete="off">
  <p class="muted">Shown in the app as a QR code when the server is on. Kept only in this tab.</p>
</div>

<div class="card">
  <div class="row"><span class="k">State</span><span id="state">-</span></div>
  <div class="row"><span class="k">Session</span><span id="session">-</span></div>
  <div class="row"><span class="k">Frames</span><span id="frames">-</span></div>
  <div class="row"><span class="k">Next frame</span><span id="next">-</span></div>
  <div class="row"><span class="k">Battery</span><span id="battery">-</span></div>
  <div class="row"><span class="k">Free storage</span><span id="storage">-</span></div>
</div>

<div class="card">
  <button class="start" onclick="control('start')">Start</button>
  <button class="stop" onclick="control('stop')">Stop</button>
  <div><img id="preview" alt="last frame"></div>
  <p class="muted" id="msg"></p>
</div>

<script>
function tok() { return document.getElementById('token').value.trim(); }
function h() { return tok() ? { 'Authorization': 'Bearer ' + tok() } : {}; }

// A token arriving in the URL (from the QR code) pre-fills the field, then is dropped from
// the visible address so it is not left in history or shoulder-surfed.
(function () {
  var m = location.search.match(/token=([^&]+)/);
  if (m) {
    document.getElementById('token').value = decodeURIComponent(m[1]);
    history.replaceState(null, '', location.pathname);
  }
})();

function fmtNext(s) { return s == null ? '-' : (s <= 0 ? 'now' : s + 's'); }

async function poll() {
  if (!tok()) { document.getElementById('msg').textContent = 'Enter the token to connect.'; return; }
  try {
    var r = await fetch('/status', { headers: h() });
    if (r.status === 401) { document.getElementById('msg').textContent = 'Token rejected.'; return; }
    var s = await r.json();
    document.getElementById('state').textContent = s.state;
    document.getElementById('session').textContent = s.sessionName || '-';
    document.getElementById('frames').textContent = s.framesCaptured;
    document.getElementById('next').textContent = fmtNext(s.nextFrameInSeconds);
    document.getElementById('battery').textContent = s.batteryPercent + '%' + (s.charging ? ' (charging)' : '');
    document.getElementById('storage').textContent = s.freeStorageMb + ' MB';
    document.getElementById('msg').textContent = '';
    if (s.framesCaptured > 0) {
      var img = document.getElementById('preview');
      img.src = '/preview?token=' + encodeURIComponent(tok()) + '&t=' + Date.now();
      img.style.display = 'block';
    }
  } catch (e) {
    document.getElementById('msg').textContent = 'Cannot reach the phone.';
  }
}

async function control(action) {
  if (!tok()) { document.getElementById('msg').textContent = 'Enter the token first.'; return; }
  await fetch('/' + action, { method: 'POST', headers: h() });
  setTimeout(poll, 300);
}

poll();
setInterval(poll, 2000);
</script>
</body>
</html>
    """.trimIndent()
}
