//  History: progress persistence and watch history 
// On Android, progress goes to a native batcher (throttled + one transaction per
// flush; force=true flushes immediately). Dev-mode browsers POST straight away.
function saveProgress(ep, progress, duration, force) {
  // Downloaded files (Library › Downloads) save under the download's own anime
  // id + title: S.anime may be unset or belong to a different series.
  const local = localPlayback && localPlaybackMeta ? {
    anime_id:    String(localPlaybackMeta.animeId || ''),
    anime_title: String(localPlaybackMeta.animeTitle || 'Downloaded episode'),
    thumbnail:   String(localPlaybackMeta.thumbnail || ''),
  } : null;
  if (!S.anime && !local) return Promise.resolve();
  const payload = {
    anime_id:    local ? local.anime_id : S.anime.id,
    anime_title: local ? local.anime_title : (S.anime.anilist?.title?.english || S.anime.title),
    episode:     ep,
    progress,
    duration,
    thumbnail:   local ? local.thumbnail : (S.anime.anilist?.coverImage?.large || S.anime.thumbnail || ''),
  };
  if (window.AndroidApi) {
    return new Promise(resolve => {
      const id = String(++androidRequestId);
      androidRequests.set(id, resolve);
      window.AndroidApi.saveProgress(id, String(payload.anime_id), String(payload.anime_title), String(payload.episode), Number(payload.progress), Number(payload.duration), String(payload.thumbnail), !!force);
    });
  }
  return api.post('/api/history', payload).catch(() => {});
}

function maybePersistPlaybackProgress(force = false) {
  const video = document.getElementById('video');
  if (!S.currentEp || !S.currentEp.ep || video.currentTime <= 0) return;
  // Local playback has no S.anime (or a stale one) — the download's own
  // metadata is used instead (see saveProgress).
  if (!S.anime && !(localPlayback && localPlaybackMeta)) return;

  const now = Date.now();
  const progress = video.currentTime;
  // For .ts downloads the single-segment VOD playlist's duration is a
  // placeholder that overstates the file; the seekable range reflects what
  // actually buffered, so save that as the true duration. Keeps resume
  // positions inside the real content instead of the placeholder end.
  let duration = video.duration || 0;
  if (localPlayback && localPlaybackMime === 'video/mp2t'
      && video.seekable && video.seekable.length) {
    const end = video.seekable.end(video.seekable.length - 1);
    if (Number.isFinite(end) && end > 0 && end < duration) duration = end;
  }
  const delta = Math.abs(progress - progressSaveState.progress);

  if (!force && now - progressSaveState.at < 5000 && delta < 5) return;

  progressSaveState = { at: now, progress };
  saveProgress(S.currentEp.ep, progress, duration, force);
}

function timeAgo(value) {
  // last_watched is persisted as Unix SECONDS (SQLite strftime('%s','now') and
  // System.currentTimeMillis()/1000 in the Android backend), while new Date()
  // expects milliseconds. Heuristic: epoch-ms timestamps are >= 1e11 (year
  // ~5138), current epoch-seconds are ~1.7e9 — so anything below is seconds.
  let ts = Number(value);
  if (!Number.isFinite(ts)) ts = Date.now();
  if (ts < 1e11) ts *= 1000;
  const date = new Date(ts);
  const now = new Date();
  const seconds = Math.floor((now - date) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 4) return `${weeks}w ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months}mo ago`;
  return `${Math.floor(months / 12)}y ago`;
}

// History is stored one row per episode, but it is displayed grouped by
// series: one card per anime showing the latest episode state.

// Leading number of an episode label ("12.5" -> 12), used to break ties when
// several rows share a timestamp (a "mark watched" press writes them at once).
function episodeNum(ep) {
  const m = String(ep == null ? '' : ep).match(/^\d+/);
  return m ? parseFloat(m[0]) : 0;
}

// True when a history row counts as fully watched (same 0.9 progress ratio
// the episode grid uses to label rows "Watched").
function isWatchedRow(h) {
  return !!h && h.duration > 0 && h.progress / h.duration >= 0.9;
}

// Rows with no real state (episode opened but never played) are skipped.
function hasProgress(h) {
  return !!h && (h.duration > 0 || h.progress > 0);
}

// Group per-episode rows by series. Returns [{anime_id, anime_title,
// thumbnail, last_watched, latest}] with `latest` the newest row (ties broken
// by highest episode number), i.e. the episode shown on the card.
function groupHistoryByAnime(rows) {
  const groups = [];
  const byAnime = new Map();
  (rows || []).forEach(h => {
    if (!hasProgress(h)) return;
    let g = byAnime.get(h.anime_id);
    if (!g) {
      g = { anime_id: h.anime_id, anime_title: h.anime_title, thumbnail: h.thumbnail, last_watched: 0, latest: null };
      byAnime.set(h.anime_id, g);
      groups.push(g);
    }
    if (h.last_watched > g.last_watched) g.last_watched = h.last_watched;
    if (!g.latest || h.last_watched > g.latest.last_watched ||
        (h.last_watched === g.latest.last_watched && episodeNum(h.episode) > episodeNum(g.latest.episode))) {
      g.latest = h;
    }
  });
  return groups;
}

// Drop a series from the raw history cache (used by deleteHistory).
function dropAnimeFromHistory(animeId) {
  S.history = (S.history || []).filter(h => h.anime_id !== animeId);
}

function emptyHistoryHTML() {
  return '<div class="empty"><div class="empty-icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg></div>No watch history yet.</div>';
}

async function loadHistory() {
  const list = document.getElementById('history-list');
  list.innerHTML = '<div style="color:var(--text-secondary);font-size:14px;padding:20px 0">Loading...</div>';
  const history = await api.get('/api/history').catch(() => []);
  S.history = history;
  const groups = groupHistoryByAnime(history);
  if (!groups.length) {
    list.innerHTML = emptyHistoryHTML();
    return;
  }
  list.innerHTML = groups.map(g => {
    const h = g.latest;
    const pct = h.duration > 0 ? Math.min(100, (h.progress / h.duration) * 100).toFixed(1) : 0;
    const ago = timeAgo(g.last_watched);
    return `<div class="history-card" data-anime="${escapeHTML(g.anime_id)}" onclick="resumeFromHistory(this.dataset.anime)">
      <img class="history-thumb" src="${escapeHTML(h.thumbnail)}" alt="" onerror="this.style.background='var(--bg-secondary)'">
      <div class="history-info">
        <div>
          <div class="history-title">${escapeHTML(g.anime_title)}</div>
          <div class="history-ep">Episode ${escapeHTML(h.episode)}</div>
        </div>
        <div>
          <div class="progress-bar"><div class="progress-fill" style="width:${pct}%"></div></div>
          <div class="history-date" style="margin-top:8px">${ago}</div>
        </div>
      </div>
      <button class="del-btn" onclick="event.stopPropagation();deleteHistory(this.closest('.history-card').dataset.anime, this)"><svg viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
    </div>`;
  }).join('');
}

async function deleteHistory(animeId, btn) {
  await api.del('/api/history/anime/' + encodeURIComponent(animeId));
  dropAnimeFromHistory(animeId);
  const card = btn.closest('.history-card');
  if (card) card.remove();
  const list = document.getElementById('history-list');
  if (list && !list.querySelector('.history-card')) list.innerHTML = emptyHistoryHTML();
  showToast('Removed from history', 'success');
}

async function resumeFromHistory(animeId) {
  const rows = (S.history || []).filter(h => h.anime_id === animeId && hasProgress(h));
  if (!rows.length) { showToast('Could not resume', 'error'); return; }
  const latest = rows.reduce((a, b) =>
    b.last_watched > a.last_watched ||
    (b.last_watched === a.last_watched && episodeNum(b.episode) > episodeNum(a.episode)) ? b : a);

  showToast('Loading ' + latest.anime_title + '...', 'info');
  // Search AniDB by title to get the anime object with a playable ID
  const playable = await findPlayableAnime(latest.anime_title);
  if (!playable) { showToast('Anime not found. Try searching manually.', 'error'); return; }

  await openAnime(playable);

  // Resume the latest episode; when it was marked as watched (a watched-through
  // boundary), continue from the next episode instead.
  let epIdx = S.episodes.indexOf(latest.episode);
  if (epIdx >= 0 && isWatchedRow(latest) && epIdx < S.episodes.length - 1) epIdx += 1;
  if (epIdx >= 0) {
    playEpisode(S.episodes[epIdx], epIdx);
  }
}

async function clearHistory() {
  const ok = await showConfirm({
    title: 'Clear watch history?',
    message: 'This removes all watch progress and continue-watching entries. This cannot be undone.',
    confirmLabel: 'Clear',
    danger: true,
  });
  if (!ok) return;
  // Dedicated clear-all route: wipes the table server-side in one statement,
  // so it can't fail just because the GET that used to list rows failed.
  const res = await api.del('/api/history').catch(() => null);
  if (!res || res.error) {
    showToast('Could not clear history', 'error');
    return;
  }
  S.history = [];
  loadHistory();
  showToast('History cleared', 'success');
}
