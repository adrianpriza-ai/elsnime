//  Downloads: native HLS downloader (Java) + queue UI
//  Episodes are saved to Movies/Elsnime/<anime name>/ as MP4 (raw .ts when a
//  stream can't be remuxed). Java queues the work on its own worker and pushes
//  every state change here through window.__downloadEvent; this file owns the
//  queue list, the episode-row download buttons and the player button.

const DOWNLOADS_KEY = 'elsnime.downloads.v1';
const DOWNLOAD_ACTIVE = ['queued', 'resolving', 'downloading', 'remuxing'];
const ICON_DL = '<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>';
const ICON_OK = '<svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg>';

function downloadKey(animeId, episode) { return String(animeId) + '|' + String(episode); }

// Mirrors Downloader.sanitizeTitle (Java) so the frontend can predict the
// folder/file names the native side will use.
function sanitizeTitle(t) {
  let s = String(t == null ? '' : t).replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim();
  if (s.length > 80) s = s.slice(0, 80).trim();
  return s || 'Anime';
}
function episodeFileName(animeTitle, episode) { return sanitizeTitle(animeTitle) + ' - Episode ' + episode; }

function animeDisplayTitle() {
  const al = (S.anime && S.anime.anilist) || {};
  return (al.title && (al.title.english || al.title.romaji)) || (S.anime && S.anime.title) || 'Anime';
}

//  State (persisted so the queue survives restarts and view switches)
function loadDownloadsState() {
  try { return JSON.parse(localStorage.getItem(DOWNLOADS_KEY) || '[]'); } catch (_) { return []; }
}
function saveDownloadsState() {
  try { localStorage.setItem(DOWNLOADS_KEY, JSON.stringify(S.downloads)); } catch (_) {}
}
function findDownload(animeId, episode) {
  const k = downloadKey(animeId, episode);
  return S.downloads.find(d => downloadKey(d.animeId, d.episode) === k);
}
function upsertDownload(entry) {
  const k = downloadKey(entry.animeId, entry.episode);
  const i = S.downloads.findIndex(d => downloadKey(d.animeId, d.episode) === k);
  if (i >= 0) S.downloads[i] = { ...S.downloads[i], ...entry };
  else S.downloads.unshift(entry);
  saveDownloadsState();
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
}

//  Native bridge
function startDownloadNative(entry) {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.startDownload(id, entry.uid, String(entry.animeId), entry.animeTitle, String(entry.episode), entry.type || 'sub', entry.quality || 'auto');
  });
}
function cancelDownloadNative(animeId, episode) {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.cancelDownload(id, String(animeId), String(episode));
  });
}
function removeDownloadNative(animeId, animeTitle, episode) {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.removeDownload(id, String(animeId), animeTitle, String(episode));
  });
}
function listDownloadsNative() {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.listDownloads(id);
  });
}
function clearDownloadsNative() {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.clearDownloads(id);
  });
}

//  Start (or skip if already present/active)
async function requestDownload(animeId, animeTitle, episode, type, opts = {}) {
  const quality = S.settings.quality || '720';
  const file = episodeFileName(animeTitle, episode);
  const existing = findDownload(animeId, episode);
  if (S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts')) {
    if (!opts.silent) showToast('Episode already downloaded', 'info');
    return;
  }
  if (existing && DOWNLOAD_ACTIVE.includes(existing.state)) {
    if (!opts.silent) showToast('Already downloading', 'info');
    return;
  }
  const al = (S.anime && S.anime.anilist) || {};
  const entry = {
    uid: 'dl' + Date.now() + Math.random().toString(36).slice(2, 8),
    animeId: String(animeId),
    animeTitle: String(animeTitle),
    episode: String(episode),
    type: type || 'sub',
    quality,
    state: 'queued',
    progress: 0,
    segmentsDone: 0,
    segmentsTotal: 0,
    bytesDone: 0,
    error: '',
    fileName: '',
    thumb: (al.coverImage && (al.coverImage.large || al.coverImage.extraLarge)) || (S.anime && S.anime.thumbnail) || '',
    addedAt: Date.now(),
  };
  upsertDownload(entry);
  if (!window.AndroidApi) {
    entry.state = 'error';
    entry.error = 'Downloads are available in the Android app';
    upsertDownload(entry);
    if (!opts.silent) showToast('Downloads are available in the Android app', 'error');
    return;
  }
  try {
    const res = await startDownloadNative(entry);
    if (res && res.exists) {
      entry.state = 'exists';
      entry.fileName = res.fileName || file;
      S.downloadFiles.add(entry.fileName);
      upsertDownload(entry);
      if (!opts.silent) showToast('Episode already downloaded', 'info');
    } else if (res && res.error) {
      entry.state = 'error';
      entry.error = res.error;
      upsertDownload(entry);
      if (!opts.silent) showToast('Download failed: ' + res.error, 'error');
    } else if (res && res.active) {
      if (!opts.silent) showToast('Already downloading', 'info');
    } else {
      if (!opts.silent) showToast('Download started', 'success');
    }
  } catch (_) {
    entry.state = 'error';
    entry.error = 'Could not reach the downloader';
    upsertDownload(entry);
    if (!opts.silent) showToast('Could not reach the downloader', 'error');
  }
}

//  Queue the whole series (silently — one toast at the end)
async function downloadSeries() {
  if (!S.anime || !S.episodes.length) return;
  const title = animeDisplayTitle();
  const ok = await showConfirm({
    title: 'Download full series?',
    message: 'Download all ' + S.episodes.length + ' episodes of “' + title + '” to Movies/Elsnime/' + sanitizeTitle(title) + '/? Episodes already on the device are skipped.',
    confirmLabel: 'Download all',
  });
  if (!ok) return;
  let started = 0;
  for (const ep of S.episodes) {
    const file = episodeFileName(title, ep);
    if (S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts')) continue;
    const dl = findDownload(S.anime.id, ep);
    if (dl && DOWNLOAD_ACTIVE.includes(dl.state)) continue;
    await requestDownload(S.anime.id, title, ep, S.translation, { silent: true });
    started++;
    await new Promise(r => setTimeout(r, 150));
  }
  showToast(started ? 'Queued ' + started + ' episode(s)' : 'Everything is already downloaded', started ? 'success' : 'info');
  navigate('downloads');
}

//  Row button (detail grid + Up next sidebar): start / cancel / delete
async function toggleEpisodeDownload(e, ep) {
  e.stopPropagation();
  if (!S.anime) return;
  const title = animeDisplayTitle();
  const dl = findDownload(S.anime.id, ep);
  const file = episodeFileName(title, ep);
  const isDone = S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts')
    || (dl && (dl.state === 'done' || dl.state === 'exists'));
  if (isDone) {
    const ok = await showConfirm({
      title: 'Delete download?',
      message: 'Remove “' + file + '” from Movies/Elsnime?',
      confirmLabel: 'Delete',
      danger: true,
    });
    if (ok) deleteDownloadByKey(S.anime.id, title, ep);
  } else if (dl && DOWNLOAD_ACTIVE.includes(dl.state)) {
    cancelDownloadByKey(S.anime.id, ep);
  } else {
    await requestDownload(S.anime.id, title, ep, S.translation);
  }
}

//  Actions
async function cancelDownloadByKey(animeId, episode) {
  const entry = findDownload(animeId, episode);
  if (entry) { entry.state = 'cancelling'; upsertDownload(entry); }
  if (window.AndroidApi) await cancelDownloadNative(animeId, episode).catch(() => null);
}
async function deleteDownloadByKey(animeId, animeTitle, episode) {
  const entry = findDownload(animeId, episode);
  if (entry) { entry.state = 'deleting'; upsertDownload(entry); }
  let ok = true;
  if (window.AndroidApi) {
    const res = await removeDownloadNative(animeId, animeTitle, episode).catch(() => null);
    // "File not found" means it's already gone — that's a success for cleanup.
    if (!res) ok = false;
    else if (res.error && !/not found/i.test(res.error)) ok = false;
  }
  if (!ok) {
    if (entry) { entry.state = 'error'; entry.error = 'Could not delete the file'; upsertDownload(entry); }
    showToast('Could not delete the file', 'error');
    return;
  }
  const file = episodeFileName(animeTitle, episode);
  S.downloadFiles.delete(file + '.mp4');
  S.downloadFiles.delete(file + '.ts');
  if (entry) S.downloads = S.downloads.filter(d => d.uid !== entry.uid);
  saveDownloadsState();
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
  showToast('Download deleted', 'success');
}
function cancelDownloadByUid(uid) {
  const d = S.downloads.find(x => x.uid === uid);
  if (d) cancelDownloadByKey(d.animeId, d.episode);
}
function deleteDownloadByUid(uid) {
  const d = S.downloads.find(x => x.uid === uid);
  if (!d) return;
  showConfirm({
    title: 'Delete download?',
    message: 'Remove “' + (d.fileName || episodeFileName(d.animeTitle, d.episode)) + '” from Movies/Elsnime?',
    confirmLabel: 'Delete',
    danger: true,
  }).then(ok => { if (ok) deleteDownloadByKey(d.animeId, d.animeTitle, d.episode); });
}
async function retryDownloadByUid(uid) {
  const d = S.downloads.find(x => x.uid === uid);
  if (!d) return;
  S.downloads = S.downloads.filter(x => x.uid !== uid);
  saveDownloadsState();
  renderDownloads();
  await requestDownload(d.animeId, d.animeTitle, d.episode, d.type || 'sub');
}
function removeEntryByUid(uid) {
  S.downloads = S.downloads.filter(x => x.uid !== uid);
  saveDownloadsState();
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
}

//  Settings > Remove all downloads: cancel active downloads, delete every file
//  in Movies/Elsnime, and empty the queue.
async function clearAllDownloads() {
  if (!S.downloads.length && !S.downloadFiles.size) {
    showToast('No downloads to remove', 'info');
    return;
  }
  const ok = await showConfirm({
    title: 'Remove all downloads?',
    message: 'Delete every downloaded file from Movies/Elsnime? This cannot be undone.',
    confirmLabel: 'Remove all',
    danger: true,
  });
  if (!ok) return;
  if (window.AndroidApi) {
    const res = await clearDownloadsNative().catch(() => null);
    if (!res || res.error) {
      showToast('Could not clear downloads', 'error');
      return;
    }
  }
  S.downloads = [];
  S.downloadFiles = new Set();
  saveDownloadsState();
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
  showToast('All downloads removed', 'success');
}

//  Native → JS progress events (raw JSON object, not a quoted string)
window.__downloadEvent = ev => {
  if (!ev || !ev.uid) return;
  const i = S.downloads.findIndex(d => d.uid === ev.uid);
  if (i < 0) return;
  const entry = S.downloads[i];
  entry.state = ev.state;
  entry.progress = ev.progress != null ? ev.progress : entry.progress;
  entry.segmentsDone = ev.segmentsDone || 0;
  entry.segmentsTotal = ev.segmentsTotal || 0;
  entry.bytesDone = ev.bytesDone || 0;
  entry.error = ev.error || '';
  if (ev.fileName) entry.fileName = ev.fileName;
  if (ev.state === 'done' || ev.state === 'exists') S.downloadFiles.add(ev.fileName);
  if (ev.state === 'cancelled') S.downloads.splice(i, 1);
  saveDownloadsState();
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
  if (ev.state === 'done') showToast('Downloaded: ' + ev.fileName, 'success');
  else if (ev.state === 'error') showToast('Download failed: ' + (ev.error || 'unknown error'), 'error');
};

//  Boot: restore the queue and reconcile with what's actually on disk
async function initDownloads() {
  S.downloads = loadDownloadsState();
  S.downloadFiles = new Set();
  if (window.AndroidApi) {
    try {
      const res = await listDownloadsNative();
      const files = (res && res.files) || [];
      files.forEach(f => S.downloadFiles.add(f.fileName));
      const present = new Set(files.map(f => f.fileName));
      S.downloads.forEach(d => {
        if (DOWNLOAD_ACTIVE.includes(d.state) || d.state === 'cancelling' || d.state === 'deleting') {
          // The process died mid-download (no worker is running anymore).
          d.state = 'error';
          d.error = 'Interrupted — tap Retry';
        } else if ((d.state === 'done' || d.state === 'exists') && d.fileName && !present.has(d.fileName)) {
          d.state = 'removed';
        }
      });
      saveDownloadsState();
    } catch (_) {}
  }
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
}

//  Render the Downloads tab
const DOWNLOAD_STATE_LABELS = {
  queued: 'Queued',
  resolving: 'Looking up stream',
  downloading: 'Downloading',
  remuxing: 'Converting to MP4',
  done: 'Downloaded',
  exists: 'On device',
  error: 'Failed',
  removed: 'File missing',
  cancelling: 'Cancelling',
  deleting: 'Deleting',
};

function dlEsc(v) {
  return String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function fmtBytes(n) {
  n = Number(n) || 0;
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}

function downloadMeta(d) {
  if (d.state === 'downloading' && d.segmentsTotal > 0) {
    const pct = Math.round((d.progress || 0) * 100);
    return pct + '% · ' + fmtBytes(d.bytesDone) + ' · ' + d.segmentsDone + '/' + d.segmentsTotal + ' segments';
  }
  if (d.state === 'downloading') return fmtBytes(d.bytesDone);
  if (d.state === 'resolving') return 'Fetching stream…';
  if (d.state === 'remuxing') return 'Combining ' + d.segmentsTotal + ' segments…';
  if (d.state === 'queued') return 'Waiting for the downloader…';
  if (d.state === 'cancelling') return 'Stopping…';
  if (d.state === 'deleting') return 'Deleting…';
  if (d.state === 'error') return d.error || 'Download failed';
  if (d.state === 'done' || d.state === 'exists') return 'Saved to Movies/Elsnime/' + sanitizeTitle(d.animeTitle) + '/';
  if (d.state === 'removed') return 'This file is no longer in storage';
  return '';
}

function downloadProgressHTML(d) {
  if (d.state === 'done' || d.state === 'exists') {
    return '<div class="dl-bar is-full"><div class="dl-bar-fill" style="width:100%"></div></div>';
  }
  const pct = Math.max(0, Math.min(1, d.progress || 0));
  const indeterminate = pct === 0 ? ' is-indeterminate' : '';
  return '<div class="dl-bar' + indeterminate + '"><div class="dl-bar-fill" style="width:' + Math.round(pct * 100) + '%"></div></div>';
}

function downloadActionsHTML(d) {
  if (d.state === 'deleting') {
    return '<button class="dl-btn" disabled>Deleting…</button>';
  }
  if (DOWNLOAD_ACTIVE.includes(d.state) || d.state === 'cancelling') {
    return '<button class="dl-btn" onclick="cancelDownloadByUid(\'' + d.uid + '\')">Cancel</button>';
  }
  if (d.state === 'error') {
    return '<button class="dl-btn" onclick="retryDownloadByUid(\'' + d.uid + '\')">Retry</button>'
      + '<button class="dl-btn danger" onclick="removeEntryByUid(\'' + d.uid + '\')">Remove</button>';
  }
  if (d.state === 'done' || d.state === 'exists') {
    return '<button class="dl-btn danger" onclick="deleteDownloadByUid(\'' + d.uid + '\')">Delete</button>';
  }
  if (d.state === 'removed') {
    return '<button class="dl-btn" onclick="removeEntryByUid(\'' + d.uid + '\')">Dismiss</button>';
  }
  return '';
}

function downloadItemHTML(d) {
  const thumb = d.thumb
    ? '<div class="dl-thumb"><img src="' + d.thumb + '" alt="" loading="lazy" onerror="this.style.display=\'none\'"></div>'
    : '';
  const stateCls = (d.state === 'done' || d.state === 'exists') ? ' is-done'
    : (d.state === 'error' ? ' is-error' : '');
  return '<div class="dl-item' + stateCls + '">'
    + thumb
    + '<div class="dl-main">'
    + '<div class="dl-top"><div class="dl-title">' + dlEsc(d.animeTitle) + '</div>'
    + '<span class="dl-state state-' + d.state + '">' + (DOWNLOAD_STATE_LABELS[d.state] || d.state) + '</span></div>'
    + '<div class="dl-sub">Episode ' + dlEsc(d.episode) + ' · ' + dlEsc(d.quality) + (d.fileName ? ' · ' + dlEsc(d.fileName) : '') + '</div>'
    + downloadProgressHTML(d)
    + '<div class="dl-meta">' + downloadMeta(d) + '</div>'
    + '</div>'
    + '<div class="dl-actions">' + downloadActionsHTML(d) + '</div>'
    + '</div>';
}

function renderDownloads() {
  const list = document.getElementById('downloads-list');
  if (!list) return;
  if (!S.downloads.length) {
    list.innerHTML = '<div class="downloads-empty">'
      + '<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>'
      + '<div>No downloads yet.</div>'
      + '<p>Tap the download icon on an episode, or use <b>Download All</b> on a series page.</p>'
      + '</div>';
    return;
  }
  list.innerHTML = S.downloads.map(downloadItemHTML).join('');
}

//  Episode-row + player-button state
function episodeDownloadState(animeId, animeTitle, ep) {
  const file = episodeFileName(animeTitle, ep);
  if (S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts')) return 'done';
  const dl = findDownload(animeId, ep);
  if (dl && DOWNLOAD_ACTIVE.includes(dl.state)) return 'active';
  if (dl && (dl.state === 'done' || dl.state === 'exists')) return 'done';
  return 'none';
}

function refreshEpisodeDownloadButtons() {
  document.querySelectorAll('.yt-ep-dl').forEach(btn => {
    const ep = btn.dataset.ep;
    const state = S.anime ? episodeDownloadState(S.anime.id, animeDisplayTitle(), ep) : 'none';
    btn.classList.toggle('is-done', state === 'done');
    btn.classList.toggle('is-active', state === 'active');
    btn.innerHTML = state === 'done' ? ICON_OK : ICON_DL;
    btn.title = state === 'done'
      ? 'Downloaded — tap to delete'
      : (state === 'active' ? 'Downloading — tap to cancel' : 'Download episode');
  });
}

function refreshPlayerDownloadButton() {
  const btn = document.getElementById('btn-download-ep');
  const label = document.getElementById('btn-download-ep-label');
  if (!btn || !label) return;
  if (!S.anime || !S.currentEp) { btn.style.display = 'none'; return; }
  btn.style.display = '';
  const state = episodeDownloadState(S.anime.id, animeDisplayTitle(), S.currentEp.ep);
  const done = state === 'done';
  const active = state === 'active';
  btn.classList.toggle('is-done', done);
  const icon = btn.querySelector('.btn-icon');
  if (icon) icon.innerHTML = done ? ICON_OK : ICON_DL;
  label.textContent = done ? 'Downloaded' : (active ? 'Downloading…' : 'Download');
}

function refreshDownloadAllButton() {
  const btn = document.getElementById('btn-download-all');
  if (!btn) return;
  btn.disabled = !S.episodes || !S.episodes.length;
  const label = btn.querySelector('.btn-dl-all-label');
  if (label && S.episodes && S.episodes.length) label.textContent = 'Download All (' + S.episodes.length + ')';
}

initDownloads();
