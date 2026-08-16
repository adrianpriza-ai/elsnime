//  Watch later: series saved for later, opened from the Library tab
//  There is no account/server — this is a purely local list persisted in
//  localStorage, so a saved series can be reopened without searching.
const WATCHLATER_KEY = 'elsnime.watchlater.v1';

function loadWatchLater() {
  try { return JSON.parse(localStorage.getItem(WATCHLATER_KEY) || '[]'); } catch (_) { return []; }
}
function saveWatchLater(list) {
  try { localStorage.setItem(WATCHLATER_KEY, JSON.stringify(list)); } catch (_) {}
}

// The current series's display title (mirrors animeDisplayTitle from
// downloads.js but doesn't depend on its file having loaded first).
function watchLaterTitle() {
  const al = (S.anime && S.anime.anilist) || {};
  return (al.title && (al.title.english || al.title.romaji)) || (S.anime && S.anime.title) || 'Anime';
}

function findSavedLater(animeId) {
  return loadWatchLater().find(w => String(w.animeId) === String(animeId));
}

//  Toggle save state for the currently-open series (detail page button).
//  Stores a trimmed copy of the anime object so the series opens instantly
//  later (no title search needed) without bloating localStorage with the
//  full AniList payload (descriptions, synonyms, relations, ...).
function toggleSaveLater() {
  if (!S.anime || !S.anime.id) {
    showToast('Could not save this title', 'error');
    return;
  }
  // Strip apostrophes so the id is safe to interpolate in single-quoted
  // onclick args when the Watch later grid renders.
  const id = String(S.anime.id).replace(/'/g, '');
  const list = loadWatchLater();
  const i = list.findIndex(w => String(w.animeId) === id);
  if (i >= 0) {
    list.splice(i, 1);
    showToast('Removed from saved for later', 'success');
  } else {
    const al = S.anime.anilist || {};
    list.unshift({
      animeId: id,
      title: watchLaterTitle(),
      thumbnail: al.coverImage?.extraLarge || al.coverImage?.large || S.anime.thumbnail || '',
      savedAt: Date.now(),
      anime: {
        id: S.anime.id,
        title: S.anime.title,
        thumbnail: S.anime.thumbnail,
        anilist: {
          title: al.title,
          coverImage: al.coverImage,
          bannerImage: al.bannerImage,
          description: al.description,
          genres: al.genres,
          averageScore: al.averageScore,
          seasonYear: al.seasonYear,
          status: al.status,
          idMal: al.idMal,
          format: al.format,
          synonyms: al.synonyms,
        },
      },
    });
    showToast('Saved for later', 'success');
  }
  saveWatchLater(list);
  updateSaveLaterButton();
  renderWatchLater();
}

//  Detail-page button state (enabled once an anime is open, shows saved state)
function updateSaveLaterButton() {
  const btn = document.getElementById('btn-save-later');
  if (!btn) return;
  if (!S.anime || !S.anime.id) {
    btn.disabled = true;
    btn.classList.remove('is-saved');
    return;
  }
  btn.disabled = false;
  const saved = !!findSavedLater(S.anime.id);
  btn.classList.toggle('is-saved', saved);
  const label = btn.querySelector('.btn-save-label');
  if (label) label.textContent = saved ? 'Saved' : 'Save for later';
}

//  Remove a saved entry (Watch later tab, from its × button)
function removeSavedLater(animeId) {
  const list = loadWatchLater().filter(w => String(w.animeId) !== String(animeId));
  saveWatchLater(list);
  renderWatchLater();
  updateSaveLaterButton();
  showToast('Removed from saved for later', 'success');
}

//  Open a saved series straight from the list — the stored anime object is
//  already playable, so no search is needed.
function openSavedLater(animeId) {
  const w = findSavedLater(animeId);
  if (!w || !w.anime) { showToast('This title is no longer available', 'error'); return; }
  showToast('Loading ' + (w.title || 'anime') + '...', 'info');
  openAnime(w.anime);
}

function watchLaterEmptyHTML() {
  return '<div class="watchlater-empty">'
    + '<svg viewBox="0 0 24 24" style="width:42px;height:42px;fill:none;stroke:currentColor;stroke-width:1.5;opacity:.5;margin-bottom:10px"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>'
    + '<div>Nothing saved yet.</div>'
    + '<p style="font-size:13px;margin-top:6px;color:var(--text-muted)">Tap <b>Save for later</b> on a series page to keep it here — open it any time without searching.</p>'
    + '</div>';
}

function renderWatchLater() {
  const list = document.getElementById('watchlater-list');
  if (!list) return;
  const saved = loadWatchLater();
  if (!saved.length) {
    list.innerHTML = watchLaterEmptyHTML();
    return;
  }
  // animeId is stored sanitized (no apostrophes) when saving, so it is safe
  // to interpolate in the single-quoted onclick args below.
  list.innerHTML = '<div class="section-title">Saved for later</div><div class="watchlater-grid">' +
    saved.map(w => {
      const id = String(w.animeId).replace(/'/g, '');
      return `<div class="watchlater-card" onclick="openSavedLater('${id}')">
        <img src="${escapeHTML(w.thumbnail || '')}" alt="" loading="lazy" onerror="this.style.background='var(--surface-2)'">
        <button class="watchlater-remove" onclick="event.stopPropagation();removeSavedLater('${id}')" title="Remove" aria-label="Remove from watch later">
          <svg viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
        </button>
        <div class="watchlater-card-info">
          <div class="watchlater-card-title">${escapeHTML(w.title || 'Unknown')}</div>
        </div>
      </div>`;
    }).join('') + '</div>';
}
