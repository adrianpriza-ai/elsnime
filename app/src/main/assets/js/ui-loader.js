//  UI loader: the app's HTML is split into views/*.html partials so ui.html
//  stays a thin shell. This script loads each partial and injects it:
//    #main        <- home, search, detail, player, you (in order)
//    #bottom-nav  <- nav
//    #overlays    <- toasts, pull-to-refresh indicator, dialogs
//  On Android the page reads the partials through the AndroidApi.readAsset
//  bridge — fetch() to file:// URLs is blocked by WebView's CORS handling of
//  file origins even with allowUniversalAccessFromFileURLs enabled, while the
//  bridge reads the assets directly and always works. fetch()/XHR remain as
//  fallbacks for a local dev server (http). boot.js waits on
//  window.__UI_READY before wiring anything up, so every later script can
//  assume the full DOM exists once the app starts.
const UI_PARTIALS = {
  main: [
    'views/home.html',
    'views/search.html',
    'views/detail.html',
    'views/player.html',
    'views/you.html',
  ],
  'bottom-nav': ['views/nav.html'],
  overlays: ['views/overlays.html'],
};

// Read one partial, trying the most reliable transport first:
//   1. AndroidApi.readAsset — native bridge, works on every WebView/APK
//   2. fetch              — dev server over http(s)
//   3. XHR                — last resort (some WebViews allow XHR on file://)
async function loadPartial(file) {
  if (window.AndroidApi && typeof window.AndroidApi.readAsset === 'function') {
    const text = window.AndroidApi.readAsset(file);
    if (text != null) return text;
  }
  try {
    const res = await fetch(file);
    if (res.ok) return await res.text();
  } catch (_) {}
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', file, true);
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve(xhr.responseText) : reject(new Error('Failed to load ' + file + ' (' + xhr.status + ')')));
    xhr.onerror = () => reject(new Error('Failed to load ' + file));
    xhr.send();
  });
}

window.__UI_READY = (async () => {
  const jobs = Object.entries(UI_PARTIALS).map(async ([target, files]) => {
    const chunks = await Promise.all(files.map(loadPartial));
    const el = document.getElementById(target);
    if (el) el.innerHTML = chunks.join('\n');
  });
  await Promise.all(jobs);
})();

// If the partials can't load (e.g. the page was opened straight from disk in a
// desktop browser, where file:// cross-file requests are blocked), surface it
// instead of showing a blank screen.
window.__UI_READY.catch(err => {
  console.error('[ui-loader]', err);
  const main = document.getElementById('main');
  if (main) {
    main.innerHTML = '<div class="view active" style="padding:40px 20px;line-height:1.6">' +
      '<div class="page-title">Elsnime</div>' +
      'Could not load the UI parts. On Android, rebuild the APK so the views/*.html ' +
      'files are bundled (the Java bridge reads them from the assets); in a browser, ' +
      'serve the app over HTTP.<br><small style="color:var(--text-muted)">' +
      String(err && err.message ? err.message : err) + '</small></div>';
  }
});
