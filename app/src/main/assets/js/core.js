//  Core: Toast system, app state, navigation, API bridge, settings 
// Settings persist through /api/settings (SQLite on Android, app.py in dev);
// the old localStorage mirror layer was dropped — Java owns the data.

//  Toast System 
function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  let iconSvg = '';
  if (type === 'success') {
    iconSvg = '<svg class="toast-icon" viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg>';
  } else if (type === 'error') {
    iconSvg = '<svg class="toast-icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><line x1="15" y1="9" x2="9" y2="15"></line><line x1="9" y1="9" x2="15" y2="15"></line></svg>';
  } else {
    iconSvg = '<svg class="toast-icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>';
  }
  toast.innerHTML = `${iconSvg}<span>${message}</span>`;	container.appendChild(toast);
	setTimeout(() => {
		toast.classList.add('out');
		setTimeout(() => toast.remove(), 300);
	}, 3000);
}

//  Confirmation dialog 
// window.confirm() is a silent no-op inside the Android WebView (the app sets
// no WebChromeClient, so the call just returns false), which made the settings
// Clear-history / Reset buttons do nothing. Destructive actions now use this
// in-app dialog instead — it works identically in the WebView and in dev.
let confirmResolve = null;
let confirmPrevFocus = null;
const CONFIRM_ICONS = {
	danger: '<svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>',
	action: '<svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg>',
};

function showConfirm({ title = 'Are you sure?', message = '', confirmLabel = 'Confirm', danger = false } = {}) {
	const modal = document.getElementById('confirm-modal');
	if (!modal) return Promise.resolve(false);
	document.getElementById('confirm-title').textContent = title;
	document.getElementById('confirm-message').textContent = message;
	const ok = document.getElementById('confirm-ok');
	ok.classList.toggle('danger-btn', danger);
	ok.classList.toggle('confirm-btn', !danger);
	document.getElementById('confirm-ok-label').textContent = confirmLabel;
	document.querySelector('.confirm-ok-icon').innerHTML = danger ? CONFIRM_ICONS.danger : CONFIRM_ICONS.action;
	confirmPrevFocus = document.activeElement;
	modal.hidden = false;
	// Focus the least destructive choice: Cancel for destructive actions, the
	// confirm button otherwise, so Enter never trips a destructive action.
	(danger ? document.getElementById('confirm-cancel') : ok).focus();
	return new Promise(resolve => { confirmResolve = resolve; });
}

function closeConfirm(result) {
	const modal = document.getElementById('confirm-modal');
	if (!modal || modal.hidden) return;
	modal.hidden = true;
	if (confirmResolve) {
		const settle = confirmResolve;
		confirmResolve = null;
		settle(!!result);
	}
	// Return focus to the button that opened the dialog (a11y).
	if (confirmPrevFocus && typeof confirmPrevFocus.focus === 'function') {
		confirmPrevFocus.focus();
	}
	confirmPrevFocus = null;
}

//  State  
const S = {
  anime:       null,
  episodes:    [],
  translation: 'sub',
  currentEp:   null,
  mpv:         { available: false, cli: false, app: false },
  settings:    {},
  history:     [],
  catalogCards: {},
};
let catalogCardId = 0;
let progressSaveState = { at: 0, progress: 0 };
const systemThemeQuery = window.matchMedia('(prefers-color-scheme: dark)');

//  Navigation 
const viewStack = [];

function showView(name) {
  const prevActive = document.querySelector('.view.active');
  const prevName = prevActive ? prevActive.id.replace('view-', '') : '';
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
  document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
  const targetView = document.getElementById('view-' + name);
  if (targetView) targetView.classList.add('active');
  const navBtn = document.getElementById('nav-' + name);
  if (navBtn) navBtn.classList.add('active');
  // Leaving the player tears the stream down so it stops downloading data.
  if (prevName === 'player' && name !== 'player') stopPlayback();
  if (name === 'history') loadHistory();
  if (name === 'home') loadHome();
  if (name === 'search') {
    renderRecentSearches();
    if (!document.getElementById('search-input').value.trim() && !activeGenreChip()) loadTrending();
  }
  if (name === 'settings') refreshPillSliders();
}

// Bottom-nav tabs: jump to a root view and reset the back stack
function navigate(name) {
  viewStack.length = 0;
  showView(name);
}

function pushView(name) {
  const current = document.querySelector('.view.active');
  const curId = current ? current.id.replace('view-', '') : null;
  // Never push the view that is already active (e.g. Next/Previous re-enters player)
  if (curId === name) return;
  if (current) viewStack.push(curId);
  showView(name);
}

function popView() {
  const prev = viewStack.pop() || 'home';
  showView(prev);
}

//  API helpers 
// Android uses the native scraper/database bridge. The browser path remains
// available so this same UI can still be run by app.py during development.
let androidRequestId = 0;
const androidRequests = new Map();
window.__androidResponse = (id, payload) => {
  const pending = androidRequests.get(id);
  if (!pending) return;
  androidRequests.delete(id);
  try { pending(JSON.parse(payload)); } catch (_) { pending({}); }
};
function androidRequest(method, path, body) {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.request(id, method, path, body ? JSON.stringify(body) : '');
  });
}
const api = {
  get: path => window.AndroidApi ? androidRequest('GET', path) : fetch(path).then(r => r.json()),
  post: (path, body) => window.AndroidApi ? androidRequest('POST', path, body) : fetch(path, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body) }).then(r => r.json()),
  del: path => window.AndroidApi ? androidRequest('DELETE', path) : fetch(path, { method:'DELETE' }),
};

// MPV: native AndroidApi call on device (stream fetch + launch in one go);
// dev-mode browsers fall back to app.py's /api/stream + /api/mpv endpoints.
// Resolves to { ok, url, raw, type } or { error, url?, raw?, type? } so the
// caller can still fall back to the web player when only MPV fails.
// The stream CDN expects a real browser UA + the embed page as referrer — the
// WebView's own UA string ("Elsnime Android") and the app origin get mpv's
// request blocked, so both are overridden here (the scraper stamps the embed
// referrer onto the stream result; see AniDbScraper.stream()).
// Same UA the scraper uses, so the CDN sees an identical fingerprint.
const MPV_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36';
function playInMpvNative(animeId, episode, type) {
  const referer = 'https://anidb.app';
  if (window.AndroidApi) {
    return new Promise(resolve => {
      const id = String(++androidRequestId);
      androidRequests.set(id, resolve);
      window.AndroidApi.playInMpv(id, String(animeId), String(episode), type, referer, MPV_UA);
    });
  }
  return api.get(`/api/stream?id=${encodeURIComponent(animeId)}&episode=${encodeURIComponent(episode)}&type=${type}`)
    .then(stream => {
      if (!stream || !stream.url) return { error: 'No stream available' };
      return api.post('/api/mpv', { url: stream.raw || stream.url, referer: stream.referer || referer, user_agent: MPV_UA })
        .then(res => ({ url: stream.url, raw: stream.raw || stream.url, type: stream.type, ...res }));
    })
    .catch(() => ({ error: 'Failed to launch MPV' }));
}

// Detect mpv on the device: CLI binary (Termux/root) and/or the mpv-android app
function getMpvStatus() {
  if (window.AndroidApi) {
    return new Promise(resolve => {
      const id = String(++androidRequestId);
      androidRequests.set(id, resolve);
      window.AndroidApi.mpvStatus(id);
    });
  }
  // Dev mode: app.py shells out to mpv, so treat it as available
  return Promise.resolve({ available: true, cli: true, app: false });
}

//  Shared rendering helpers 

// Escapes text for innerHTML and, when a search term is given, wraps the first
// case-insensitive match in <mark> so search results show why they matched.
function highlightTitle(title, term) {
  const esc = v => String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const raw = String(title == null ? '' : title);
  if (!term) return esc(raw);
  const idx = raw.toLowerCase().indexOf(String(term).toLowerCase());
  if (idx < 0) return esc(raw);
  return esc(raw.slice(0, idx)) +
    '<mark>' + esc(raw.slice(idx, idx + term.length)) + '</mark>' +
    esc(raw.slice(idx + term.length));
}

function animeCardHTML(r, highlight) {
  const al = r.anilist || {};
  const img = al.coverImage?.extraLarge || al.coverImage?.large || r.thumbnail || '';
  const title = al.title?.english || al.title?.romaji || r.title || 'Unknown';
  const score = al.averageScore ? `${al.averageScore/10}/10` : '';
  // encodeURIComponent intentionally leaves apostrophes untouched; escape
  // them because this value is passed through a single-quoted onclick arg.
  const cardId = 'catalog-' + (++catalogCardId);
  S.catalogCards[cardId] = r;
  // Cards with a playable AniDB id open directly; everything else is resolved
  // by title+format through openByTitle, which keeps the full anilist context.
  const action = r.id ? `openCatalogCard('${cardId}')` : `openByTitle('${cardId}')`;
  return `<div class="anime-card" onclick="${action}">
    <img src="${img}" alt="${String(title).replace(/"/g, '&quot;')}" loading="lazy" onerror="this.src='';this.style.background='var(--bg-elevated)'">
    <div class="card-info">
      <div class="card-title">${highlightTitle(title, highlight)}</div>
      <div class="card-score">${score}</div>
    </div>
  </div>`;
}

function animeGridHTML(items, title, highlight) {
  return `<div class="section-title">${title}</div><div class="anime-grid">` +
    items.map(r => animeCardHTML(r, highlight)).join('') + '</div>';
}

function skeletonGridHTML(count) {
  return '<div class="anime-grid">' +
    Array(count).fill('<div class="skeleton" style="aspect-ratio:2/3;border-radius:var(--radius-lg)"></div>').join('') + '</div>';
}

//  Settings 
const DEFAULT_SETTINGS = {
  theme: 'auto',
  player: 'web',
  sub_lang: 'sub',
  performance_mode: 'auto',
  hw_accel: false,
};

async function loadSettings() {
  // /api/settings (SQLite on Android) is authoritative — no localStorage layer.
  try {
    const remote = await api.get('/api/settings');
    S.settings = { ...DEFAULT_SETTINGS, ...remote };
  } catch(_) {
    S.settings = { ...DEFAULT_SETTINGS };
  }

  setPillValue('pill-theme',  S.settings.theme    || DEFAULT_SETTINGS.theme);
  setPillValue('pill-player', S.settings.player   || DEFAULT_SETTINGS.player);
  setPillValue('pill-lang',   S.settings.sub_lang || DEFAULT_SETTINGS.sub_lang);

  applyTheme(S.settings.theme || DEFAULT_SETTINGS.theme);
  initPillSliders();
}

// Native system theme detection: the Android WebView can't always report
// prefers-color-scheme, so read it straight from Java when available.
function systemIsDark() {
  if (window.AndroidApi && typeof window.AndroidApi.systemTheme === 'function') {
    try { return window.AndroidApi.systemTheme() === 'dark'; } catch (_) {}
  }
  return systemThemeQuery.matches;
}

// Called by Java when the system theme changes (uiMode config change).
window.__systemThemeChanged = () => {
  if ((S.settings.theme || DEFAULT_SETTINGS.theme) === 'auto') applyTheme('auto');
};

function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === 'light') {
    root.style.colorScheme = 'light';
    root.style.setProperty('--bg-primary', '#f2f3f7');
    root.style.setProperty('--surface', '#ffffff');
    root.style.setProperty('--surface-2', '#e9ebf1');
    root.style.setProperty('--bg-hover', 'rgba(0,0,0,0.05)');
    root.style.setProperty('--border', 'rgba(0,0,0,0.1)');
    root.style.setProperty('--track-bg', 'rgba(0,0,0,0.12)');
    root.style.setProperty('--text-primary', '#111318');
    root.style.setProperty('--text-secondary', '#4c5464');
    root.style.setProperty('--text-muted', '#8a93a3');
  } else if (theme === 'auto') {
    applyTheme(systemIsDark() ? 'dark' : 'light');
  } else {
    root.style.colorScheme = 'dark';
    // YouTube-dark palette: neutral blacks/grays, no blue tint.
    root.style.setProperty('--bg-primary', '#0f0f0f');
    root.style.setProperty('--surface', '#212121');
    root.style.setProperty('--surface-2', '#272727');
    root.style.setProperty('--bg-hover', 'rgba(255,255,255,0.1)');
    root.style.setProperty('--border', 'rgba(255,255,255,0.1)');
    root.style.setProperty('--track-bg', 'rgba(255,255,255,0.12)');
    root.style.setProperty('--text-primary', '#f1f1f1');
    root.style.setProperty('--text-secondary', '#aaaaaa');
    root.style.setProperty('--text-muted', '#717171');
  }
}

async function saveSetting(key, value) {
  S.settings[key] = value;
  api.post('/api/settings', { [key]: value }).catch(() => {});
  if (key === 'theme') applyTheme(value);
  showToast('Setting saved', 'success');
}

// Segmented option controls
function initPillSliders() {
  document.querySelectorAll('.pill-slider').forEach(slider => {
    const highlight = slider.querySelector('.pill-highlight');
    const active = slider.querySelector('.pill-option.active');
    if (active) movePillHighlight(highlight, active);
  });
}

function selectPillOption(btn) {
  const slider = btn.closest('.pill-slider');
  if (!slider) return;
  const setting = slider.dataset.setting;
  const buttons = slider.querySelectorAll('.pill-option');
  const highlight = slider.querySelector('.pill-highlight');

  buttons.forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  movePillHighlight(highlight, btn);
  if (setting === 'theme') applyTheme(btn.dataset.value);
  saveSetting(setting, btn.dataset.value);
}

function movePillHighlight(highlight, btn) {
  highlight.style.width = btn.offsetWidth + 'px';
  highlight.style.left = btn.offsetLeft + 'px';
}

function setPillValue(sliderId, value) {
  const slider = document.getElementById(sliderId);
  if (!slider) return;
  const buttons = slider.querySelectorAll('.pill-option');
  const highlight = slider.querySelector('.pill-highlight');
  buttons.forEach(b => {
    b.classList.toggle('active', b.dataset.value === value);
    if (b.dataset.value === value) movePillHighlight(highlight, b);
  });
}

function refreshPillSliders() {
  document.querySelectorAll('.pill-slider').forEach(slider => {
    const active = slider.querySelector('.pill-option.active');
    const highlight = slider.querySelector('.pill-highlight');
    if (active && highlight) movePillHighlight(highlight, active);
  });
}

async function resetSettings() {
  const ok = await showConfirm({
    title: 'Reset settings?',
    message: 'All settings will be restored to their defaults.',
    confirmLabel: 'Reset',
  });
  if (!ok) return;

  S.settings = { ...DEFAULT_SETTINGS };
  try {
    await api.post('/api/settings', DEFAULT_SETTINGS);
  } catch(_) {}

  setPillValue('pill-theme',  DEFAULT_SETTINGS.theme);
  setPillValue('pill-player', DEFAULT_SETTINGS.player);
  setPillValue('pill-lang',   DEFAULT_SETTINGS.sub_lang);
  applyTheme(DEFAULT_SETTINGS.theme);
  refreshPillSliders();
  showToast('Settings reset', 'success');
}
