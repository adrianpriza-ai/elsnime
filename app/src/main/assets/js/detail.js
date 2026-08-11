//  Detail: anime info, episodes, sub/dub control 
async function openAnime(anime) {
  S.anime = anime;
  S.translation = S.settings.sub_lang || 'sub';
  const al = anime.anilist || {};

  const hero = document.getElementById('detail-hero');
  const banner = document.getElementById('detail-banner');
  if (al.bannerImage) {
    banner.src = al.bannerImage;
    banner.style.display = 'block';
    hero.classList.remove('no-banner');
  } else {
    banner.style.display = 'none';
    hero.classList.add('no-banner');
  }

  document.getElementById('detail-cover').src = al.coverImage?.extraLarge || al.coverImage?.large || anime.thumbnail || '';
  document.getElementById('detail-title').textContent = al.title?.english || anime.title;
  document.getElementById('detail-subtitle').textContent = al.title?.romaji || '';

  const badges = [];
  if (al.averageScore) badges.push(`<span class="badge accent">${al.averageScore/10}/10</span>`);
  if (al.seasonYear)   badges.push(`<span class="badge">${al.seasonYear}</span>`);
  if (al.status)       badges.push(`<span class="badge">${al.status.replace(/_/g,' ')}</span>`);
  (al.genres || []).slice(0,4).forEach(g => badges.push(`<span class="badge">${g}</span>`));
  document.getElementById('detail-badges').innerHTML = badges.join('');

  // Description: reset state. The Show-more toggle is measured after the
  // view becomes visible (hidden elements report zero layout size).
  const descEl = document.getElementById('detail-desc');
  descEl.textContent = (al.description || '').replace(/<[^>]+>/g,'');
  descEl.classList.remove('expanded');
  document.getElementById('detail-more-btn').textContent = 'Show more';

  // Update seg buttons
  updateSegPills(S.translation);

  pushView('detail');
  updateDescToggle();
  await loadEpisodes();
}

// Only show the "Show more" toggle when the description overflows its box
function updateDescToggle() {
  const descEl = document.getElementById('detail-desc');
  const moreBtn = document.getElementById('detail-more-btn');
  if (!descEl || !moreBtn) return;
  moreBtn.style.display = descEl.scrollHeight > descEl.clientHeight ? 'block' : 'none';
}

// Banner failed to load -> fall back to the flat no-banner hero layout
function hideDetailBanner(img) {
  img.style.display = 'none';
  const hero = document.getElementById('detail-hero');
  if (hero) hero.classList.add('no-banner');
}

function toggleDesc() {
  const el = document.getElementById('detail-desc');
  const btn = document.getElementById('detail-more-btn');
  const expanded = el.classList.toggle('expanded');
  btn.textContent = expanded ? 'Show less' : 'Show more';
}

function playFirstEp() {
  if (!S.episodes.length) return;
  playEpisode(S.episodes[0], 0);
}

// Shared history lookup used by the episode grid and the Up next sidebar
async function getHistoryMap() {
  let historyMap = {};
  try {
    // The backend filters by anime_id so the episode grid stays fast even with
    // a large history table; the client re-checks for dev backends.
    const allHistory = await api.get('/api/history?anime_id=' + encodeURIComponent(S.anime.id));
    if (Array.isArray(allHistory)) {
      allHistory.forEach(h => {
        if (h.anime_id === S.anime.id) {
          historyMap[h.episode] = h;
        }
      });
    }
  } catch(e) {}
  return historyMap;
}

async function loadEpisodes() {
  if (!S.anime) return;
  document.getElementById('eps-grid').innerHTML = '<div style="color:var(--text-secondary);font-size:14px;padding:20px 0">Loading episodes...</div>';
  const data = await api.get(`/api/episodes?id=${S.anime.id}&type=${S.translation}`).catch(() => ({episodes:[]}));
  S.episodes = data.episodes || [];
  await renderEpisodes();
}

async function renderEpisodes() {
  const grid = document.getElementById('eps-grid');
  const playBtn = document.getElementById('btn-play-first');
  const countEl = document.getElementById('ep-count');
  if (countEl) countEl.textContent = S.episodes.length ? `(${S.episodes.length})` : '';
  if (playBtn) playBtn.disabled = !S.episodes.length;

  if (!S.episodes.length) {
    grid.innerHTML = '<div style="color:var(--text-secondary);font-size:14px;padding:20px 0">No episodes available for this language.</div>';
    return;
  }

  // Fetch history for this anime to show progress dots
  const historyMap = await getHistoryMap();
  grid.innerHTML = S.episodes.map((ep, i) => episodeRowHTML(ep, i, historyMap, false, i === 0)).join('');
  refreshEpisodeDownloadButtons();
  refreshDownloadAllButton();
}

// One YouTube-style suggestion row: thumbnail + "Ep N" chip + title + state.
// Shared between the detail episode grid and the player's Up next sidebar.
function episodeRowHTML(ep, i, historyMap, isActive, isUpNext) {
  const h = historyMap && historyMap[ep];
  let stateClass = '';
  let stateLabel = '';
  if (h && h.duration > 0) {
    const ratio = h.progress / h.duration;
    if (ratio >= 0.9) { stateClass = 'watched'; stateLabel = 'Watched'; }
    else if (ratio > 0.05) { stateClass = 'partial'; stateLabel = 'In progress'; }
  }
  const al = S.anime?.anilist || {};
  const img = al.coverImage?.extraLarge || al.coverImage?.large || S.anime?.thumbnail || '';
  const meta = [];
  if (isActive) meta.push('Now playing');
  if (stateLabel) meta.push(stateLabel);
  const upNext = isUpNext ? '<span class="upnext-tag">UP NEXT</span>' : '';
  // Row + download button wrapped in a flex container (a <button> can't nest
  // another <button>). The download state is refreshed by downloads.js.
  return `<div class="yt-ep-wrap">
  <button class="yt-ep-row ${stateClass} ${isActive ? 'active' : ''}" onclick="playEpisode('${ep}', ${i})">
    <span class="yt-ep-thumb">
      <img src="${img}" alt="" loading="lazy" onerror="this.style.display='none'">
      <span class="yt-ep-chip">Ep ${ep}</span>
      ${stateClass === 'watched' ? '<span class="yt-ep-check"><svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg></span>' : ''}
    </span>
    <span class="yt-ep-info">
      <span class="yt-ep-title">Episode ${ep}</span>
      <span class="yt-ep-meta">${meta.join(' · ')}</span>
    </span>
    ${upNext}
  </button>
  <button class="yt-ep-dl" data-ep="${ep}" onclick="toggleEpisodeDownload(event, '${ep}')" aria-label="Download episode" title="Download episode">
    <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
  </button>
  </div>`;
}

async function switchTranslation(lang, btn) {
  S.translation = lang;
  updateSegPills(lang);
  await loadEpisodes();
  // While in the player, re-load the current episode in the new language.
  // Clamp the index in case the new language has fewer episodes.
  const playerActive = document.getElementById('view-player').classList.contains('active');
  if (playerActive && S.currentEp && S.episodes.length) {
    const idx = Math.min(S.currentEp.index, S.episodes.length - 1);
    playEpisode(S.episodes[idx], idx);
  }
}

function updateSegPills(lang) {
  document.querySelectorAll('#detail-seg-ctrl .seg-btn, #player-seg-ctrl .seg-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.lang === lang);
  });
}

// Keep the sub/dub control in sync after layout settles
window.addEventListener('load', () => updateSegPills(S.translation || 'sub'));
