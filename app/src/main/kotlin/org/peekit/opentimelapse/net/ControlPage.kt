package org.peekit.opentimelapse.net

/**
 * The dashboard the server hands a browser: one page that monitors several phones at once.
 *
 * Self-contained by necessity - served over plain http on a LAN with nothing else to fetch.
 * Each phone is a card; the phone the page was opened from is added automatically from the
 * ?token= link, and more are added by pasting their pairing links. The fleet is kept in this
 * page's localStorage. Every request carries the token as a query parameter and sets no custom
 * header, so cross-origin polling of other phones stays a "simple" CORS request the servers'
 * Access-Control-Allow-Origin: * already covers.
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
    '<p class="muted">Stopping ends the session - this phone drops off the page (its server ' +
    'only runs while shooting).</p>' +
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
