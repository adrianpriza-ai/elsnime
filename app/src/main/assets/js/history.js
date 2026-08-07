//  History: progress persistence and watch history 
// On Android, progress goes to a native batcher (throttled + one transaction per
// flush; force=true flushes immediately). Dev-mode browsers POST straight away.
function saveProgress(ep, progress, duration, force) {
  if (!S.anime) return Promise.resolve();
  const payload = {
    anime_id:    S.anime.id,
    anime_title: S.anime.anilist?.title?.english || S.anime.title,
    episode:     ep,
    progress,
    duration,
    thumbnail:   S.anime.anilist?.coverImage?.large || S.anime.thumbnail || '',
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
  if (!S.currentEp || !S.anime || video.currentTime <= 0) return;

  const now = Date.now();
  const progress = video.currentTime;
  const duration = video.duration || 0;
  const delta = Math.abs(progress - progressSaveState.progress);

  if (!force && now - progressSaveState.at < 5000 && delta < 5) return;

  progressSaveState = { at: now, progress };
  saveProgress(S.currentEp.ep, progress, duration, force);
}

function timeAgo(dateStr) {
  const date = new Date(dateStr);
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

async function loadHistory() {
  const list = document.getElementById('history-list');
  list.innerHTML = '<div style="color:var(--text-secondary);font-size:14px;padding:20px 0">Loading...</div>';
  const history = await api.get('/api/history').catch(() => []);
  S.history = history;
  if (!history.length) {
    list.innerHTML = '<div class="empty"><div class="empty-icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg></div>No watch history yet.</div>';
    return;
  }
  list.innerHTML = history.map(h => {
    const pct = h.duration > 0 ? Math.min(100, (h.progress / h.duration) * 100).toFixed(1) : 0;
    const ago = timeAgo(h.last_watched);
    return `<div class="history-card" onclick="resumeFromHistory(${h.id})">
      <img class="history-thumb" src="${h.thumbnail}" alt="" onerror="this.style.background='var(--bg-secondary)'">
      <div class="history-info">
        <div>
          <div class="history-title">${h.anime_title}</div>
          <div class="history-ep">Episode ${h.episode}</div>
        </div>
        <div>
          <div class="progress-bar"><div class="progress-fill" style="width:${pct}%"></div></div>
          <div class="history-date" style="margin-top:8px">${ago}</div>
        </div>
      </div>
      <button class="del-btn" onclick="event.stopPropagation();deleteHistory(${h.id}, this)"><svg viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg></button>
    </div>`;
  }).join('');
}

async function deleteHistory(id, btn) {
  await api.del('/api/history/' + id);
  btn.closest('.history-card').remove();
  showToast('Removed from history', 'success');
}

async function resumeFromHistory(id) {
  const h = S.history.find(x => x.id === id);
  if (!h) { showToast('Could not resume', 'error'); return; }

  showToast('Loading ' + h.anime_title + '...', 'info');
  // Search AniDB by title to get the anime object with a playable ID
  const playable = await findPlayableAnime(h.anime_title);
  if (!playable) { showToast('Anime not found. Try searching manually.', 'error'); return; }

  await openAnime(playable);

  // Find and play the saved episode
  const epIdx = S.episodes.indexOf(h.episode);
  if (epIdx >= 0) {
    playEpisode(h.episode, epIdx);
  }
}

async function clearHistory() {
  if (!confirm('Clear all watch history?')) return;
  const history = await api.get('/api/history').catch(() => []);
  await Promise.all(history.map(h => api.del('/api/history/' + h.id)));
  loadHistory();
  showToast('History cleared', 'success');
}
