//  Downloads: native HLS downloader (Java) + queue UI
//  Episodes are saved to Movies/Elsnime/<anime name>/ as .ts (MPEG-TS is
//  stored as-is; fMP4 streams as .mp4). Java queues the work on its own
//  worker and pushes
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
function activeDownloadsNative() {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.activeDownloads(id);
  });
}
function clearDownloadsNative() {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.clearDownloads(id);
  });
}
function playDownloadNative(animeTitle, episode) {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.playDownload(id, String(animeTitle), String(episode));
  });
}

//  Play a completed download in the in-app player (mp4 plays directly; .ts
//  goes through hls.js via the app's loopback file server).
async function playDownloadByUid(uid) {
  const d = S.downloads.find(x => x.uid === uid);
  if (!d || !d.animeTitle || !d.episode) return;
  if (!window.AndroidApi) { showToast('Downloads are available in the Android app', 'error'); return; }
  const res = await playDownloadNative(d.animeTitle, d.episode).catch(() => null);
  if (!res || res.error) { showToast((res && res.error) || 'Could not open this download', 'error'); return; }
  if (window.playLocalFile) window.playLocalFile(res, d);
  else showToast('Player is not available', 'error');
}

//  Start (or skip if already present/active)
async function requestDownload(animeId, animeTitle, episode, type, opts = {}) {
  const quality = downloadQuality(); // honors the separate download quality setting
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
    thumb: opts.thumb || (al.coverImage && (al.coverImage.large || al.coverImage.extraLarge)) || (S.anime && S.anime.thumbnail) || '',
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
  openLibrarySection('downloads');
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

//  Native → JS progress events (raw JSON object, not a quoted string). The
//  boot reconciliation feeds the same handler (silently) to restore the state
//  of downloads that were resumed after a process death.
function applyDownloadEvent(ev, silent) {
  if (!ev || !ev.uid) return;
  const i = S.downloads.findIndex(d => d.uid === ev.uid);
  let entry;
  if (i < 0) {
    // Unknown uid (e.g. a resumed download whose queue entry was lost):
    // rebuild it from the event so the download stays visible.
    entry = {
      uid: ev.uid,
      animeId: String(ev.anime_id != null ? ev.anime_id : ''),
      animeTitle: String(ev.anime_title || ''),
      episode: String(ev.episode != null ? ev.episode : ''),
      type: ev.type || 'sub',
      quality: ev.quality || 'auto',
      thumb: '',
      addedAt: Date.now(),
      state: ev.state,
      progress: 0,
      segmentsDone: 0,
      segmentsTotal: 0,
      bytesDone: 0,
      error: '',
      fileName: '',
    };
    S.downloads.unshift(entry);
  } else {
    entry = S.downloads[i];
  }
  entry.state = ev.state;
  entry.progress = ev.progress != null ? ev.progress : entry.progress;
  entry.segmentsDone = ev.segmentsDone || 0;
  entry.segmentsTotal = ev.segmentsTotal || 0;
  entry.bytesDone = ev.bytesDone || 0;
  entry.error = ev.error || '';
  if (ev.fileName) entry.fileName = ev.fileName;
  if (ev.state === 'done' || ev.state === 'exists') S.downloadFiles.add(ev.fileName);
  if (ev.state === 'cancelled') {
    const j = S.downloads.findIndex(x => x.uid === ev.uid);
    if (j >= 0) S.downloads.splice(j, 1);
  }
  saveDownloadsState();
  renderDownloads();
  refreshEpisodeDownloadButtons();
  refreshPlayerDownloadButton();
  refreshDownloadAllButton();
  if (!silent) {
    if (ev.state === 'done') showToast('Downloaded: ' + ev.fileName, 'success');
    else if (ev.state === 'error') showToast('Download failed: ' + (ev.error || 'unknown error'), 'error');
  }
}
window.__downloadEvent = ev => applyDownloadEvent(ev, false);

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
      // Downloads that survived a process death are resumed natively on the
      // next launch; fetch their live state so the queue doesn't mark them
      // dead (events emitted before the page loaded are lost).
      let activeEvents = [];
      try {
        const act = await activeDownloadsNative();
        activeEvents = (act && act.downloads) || [];
      } catch (_) {}
      const activeUids = new Set(activeEvents.map(ev => ev.uid));
      activeEvents.forEach(ev => applyDownloadEvent(ev, true));
      S.downloads.forEach(d => {
        if (activeUids.has(d.uid)) return; // still downloading — native events keep it fresh
        if (DOWNLOAD_ACTIVE.includes(d.state) || d.state === 'cancelling' || d.state === 'deleting') {
          // Not being resumed: the partial file or record is gone. It may
          // still have finished before the page loaded — check the disk.
          const file = episodeFileName(d.animeTitle, d.episode);
          if (S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts')) {
            d.state = 'done';
            d.fileName = S.downloadFiles.has(file + '.mp4') ? file + '.mp4' : file + '.ts';
          } else {
            d.state = 'error';
            d.error = 'Interrupted — tap Retry';
          }
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

//  Series grouping (persisted expanded state so re-renders keep the UI open)
S.expandedSeries = new Set();
//  Registry of series groups (anime titles can contain quotes, so picker
//  handlers reference a generated id instead of interpolating the title).
S.seriesRegistry = {};
let seriesGroupId = 0;

//  Episode picker state (series whose episodes the user is choosing)
let epPickerCtx = null;

function toggleSeriesExpanded(head) {
  const series = head.closest('.dl-series');
  if (!series) return;
  const animeId = series.dataset.anime;
  series.classList.toggle('open');
  if (S.expandedSeries.has(animeId)) S.expandedSeries.delete(animeId);
  else S.expandedSeries.add(animeId);
}

function seriesGroupHTML(g) {
  const activeCount = g.entries.filter(d => DOWNLOAD_ACTIVE.includes(d.state)).length;
  const subBits = [];
  if (activeCount) subBits.push(activeCount + ' downloading');
  if (g.entries.length) subBits.push(g.entries.length + ' episode' + (g.entries.length === 1 ? '' : 's') + ' here');
  const thumb = g.thumb
    ? '<img class="dl-series-thumb" src="' + dlEsc(g.thumb) + '" alt="" loading="lazy" onerror="this.style.visibility=\'hidden\'">'
    : '<div class="dl-series-thumb" style="background:var(--surface-2)"></div>';
  const open = S.expandedSeries.has(g.animeId) ? ' open' : '';
  const sid = 'dlg' + (++seriesGroupId);
  S.seriesRegistry[sid] = g;
  return `<div class="dl-series${open}" data-anime="${dlEsc(g.animeId)}">
  <div class="dl-series-head" onclick="toggleSeriesExpanded(this)">
    ${thumb}
    <div class="dl-series-meta">
      <div class="dl-series-title">${dlEsc(g.animeTitle)}</div>
      <div class="dl-series-sub">${subBits.join(' · ')}</div>
    </div>
    <div class="dl-series-actions">
      <button class="dl-pick-btn" onclick="event.stopPropagation();openEpPicker('${sid}')">
        <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
        Pick episodes
      </button>
    </div>
  </div>
  <div class="dl-series-eps">${g.entries.map(downloadItemHTML).join('')}</div>
  </div>`;
}

//  Render the Downloads section: queue grouped by series
function renderDownloads() {
  const list = document.getElementById('downloads-list');
  if (!list) return;
  if (!S.downloads.length) {
    list.innerHTML = '<div class="downloads-empty">'
      + '<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>'
      + '<div>No downloads yet.</div>'
      + '<p>Open a series and tap <b>Pick episodes</b> here, or use the download button on an episode.</p>'
      + '</div>';
    return;
  }
  // Group the flat queue by series (order of first appearance), keeping the
  // per-series episode rows underneath the series header.
  const groups = [];
  const byAnime = new Map();
  S.downloads.forEach(d => {
    let g = byAnime.get(d.animeId);
    if (!g) {
      g = { animeId: d.animeId, animeTitle: d.animeTitle, type: d.type || 'sub', thumb: d.thumb || '', entries: [] };
      byAnime.set(d.animeId, g);
      groups.push(g);
    }
    g.entries.push(d);
  });
  list.innerHTML = groups.map(seriesGroupHTML).join('');
}

//  Episode picker (series → episodes): fetch the series' episode list and let
//  the user tick which episodes to queue, mirroring the detail page's rows.
async function openEpPicker(sid) {
  const g = S.seriesRegistry[sid];
  if (!g) return;
  const titleEl = document.getElementById('ep-picker-title');
  if (titleEl) titleEl.textContent = g.animeTitle || 'Series';
  const list = document.getElementById('ep-picker-list');
  list.innerHTML = '<div class="ep-picker-loading">Loading episodes…</div>';
  document.getElementById('ep-picker').hidden = false;
  epPickerCtx = { animeId: g.animeId, animeTitle: g.animeTitle, type: g.type || 'sub', thumb: g.thumb || '' };
  const data = await api.get(`/api/episodes?id=${encodeURIComponent(g.animeId)}&type=${encodeURIComponent(g.type || 'sub')}`).catch(() => null);
  const eps = (data && Array.isArray(data.episodes)) ? data.episodes : [];
  if (!eps.length) {
    list.innerHTML = '<div class="ep-picker-error">Could not load this series\' episodes. Open the series page and use its episode list instead.</div>';
    return;
  }
  epPickerCtx.episodes = eps;
  renderEpPickerList();
}

function renderEpPickerList() {
  const ctx = epPickerCtx;
  if (!ctx || !ctx.episodes) return;
  const list = document.getElementById('ep-picker-list');
  list.innerHTML = ctx.episodes.map(ep => {
    const file = episodeFileName(ctx.animeTitle, ep);
    const onDevice = S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts');
    const dl = findDownload(ctx.animeId, ep);
    const active = dl && DOWNLOAD_ACTIVE.includes(dl.state);
    const done = onDevice || (dl && (dl.state === 'done' || dl.state === 'exists'));
    const stateTag = done
      ? '<span class="ep-picker-state on-device">On device</span>'
      : (active ? '<span class="ep-picker-state downloading">Downloading</span>' : '');
    return `<label class="ep-picker-item${done ? ' is-done' : ''}">
      <input type="checkbox" data-ep="${dlEsc(ep)}" ${done || active ? 'disabled' : 'checked'}>
      <span class="ep-picker-ep"><span class="ep-picker-ep-name">Episode ${dlEsc(ep)}</span></span>
      ${stateTag}
    </label>`;
  }).join('');
}

function closeEpPicker() {
  document.getElementById('ep-picker').hidden = true;
  epPickerCtx = null;
}

//  Queue every checked episode of the picker series
async function downloadPickedEpisodes() {
  const ctx = epPickerCtx;
  if (!ctx) return;
  const picked = [...document.querySelectorAll('#ep-picker-list input[type=checkbox]:checked')].map(c => c.dataset.ep);
  closeEpPicker();
  if (!picked.length) { showToast('No episodes selected', 'info'); return; }
  let started = 0;
  for (const ep of picked) {
    const file = episodeFileName(ctx.animeTitle, ep);
    if (S.downloadFiles.has(file + '.mp4') || S.downloadFiles.has(file + '.ts')) continue;
    const dl = findDownload(ctx.animeId, ep);
    if (dl && DOWNLOAD_ACTIVE.includes(dl.state)) continue;
    await requestDownload(ctx.animeId, ctx.animeTitle, ep, ctx.type, { silent: true, thumb: ctx.thumb });
    started++;
  }
  showToast(started ? 'Queued ' + started + ' episode(s)' : 'Selected episodes are already downloaded', started ? 'success' : 'info');
  renderDownloads();
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
  if (d.state === 'remuxing') return 'Combining ' + d.segmentsTotal + ' segments…';
  if (d.state === 'resolving') return 'Fetching stream…';
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
    return '<button class="dl-btn" onclick="playDownloadByUid(\'' + d.uid + '\')">Play</button>'
      + '<button class="dl-btn danger" onclick="deleteDownloadByUid(\'' + d.uid + '\')">Delete</button>';
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
    + '<div class="dl-sub">Episode ' + dlEsc(d.episode) + ' · ' + dlEsc(d.quality === 'auto' ? 'Best' : d.quality) + (d.fileName ? ' · ' + dlEsc(d.fileName) : '') + '</div>'
    + downloadProgressHTML(d)
    + '<div class="dl-meta">' + downloadMeta(d) + '</div>'
    + '</div>'
    + '<div class="dl-actions">' + downloadActionsHTML(d) + '</div>'
    + '</div>';
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
  if (localPlayback || !S.anime || !S.currentEp || !S.currentEp.ep) { btn.style.display = 'none'; return; }
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
