//  Home: continue watching + trending/alltime/upcoming/new-episodes rows
// Android fetches everything in one /api/home call; dev-mode backends that
// lack it fall back to individual endpoints.

async function loadHome() {
  const data = await api.get('/api/home').catch(() => null);
  if (data && (Array.isArray(data.history) || Array.isArray(data.trending))) {
    await Promise.all([
      loadHomeContinue(Array.isArray(data.history) ? data.history : null),
      loadHomeRow('home-trending-section', 'Popular This Month', data.trending),
      loadHomeRow('home-newepisodes-section', 'New Episodes', data.newepisodes),
      loadHomeRow('home-upcoming-section', 'Up & Coming', data.upcoming),
      loadHomeRow('home-alltime-section', 'All-Time Popular', data.alltime),
    ]);
  } else {
    await Promise.all([
      loadHomeContinue(),
      loadHomeRowFallback('home-trending-section', 'Popular This Month', '/api/trending'),
      loadHomeRowFallback('home-newepisodes-section', 'New Episodes', '/api/new-episodes'),
      loadHomeRowFallback('home-upcoming-section', 'Up & Coming', '/api/upcoming'),
      loadHomeRowFallback('home-alltime-section', 'All-Time Popular', '/api/alltime'),
    ]);
  }
}

async function loadHomeContinue(existing) {
  return loadContinueWatching(document.getElementById('home-continue-section'), existing);
}

// Render a horizontal scroll row from pre-loaded data. If data is null or
// empty the section stays hidden — no wasted DOM.
async function loadHomeRow(elId, title, items) {
  const el = document.getElementById(elId);
  if (!el) return;
  if (!Array.isArray(items) || !items.length) { el.innerHTML = ''; return; }
  // Discovery rows never show 18+ — even with Mature content enabled — so a
  // child opening the app can't stumble onto it here.
  el.innerHTML = animeRowHTML(filterDiscoveryItems(items), title);
}

// Fallback: fetch the section from its own endpoint when /api/home is missing.
async function loadHomeRowFallback(elId, title, endpoint) {
  const el = document.getElementById(elId);
  if (!el) return;
  const type = S.settings.sub_lang || 'sub';
  const sep = endpoint.includes('?') ? '&' : '?';
  const items = await api.get(endpoint + sep + 'type=' + type).catch(() => null);
  if (!Array.isArray(items) || !items.length) { el.innerHTML = ''; return; }
  el.innerHTML = animeRowHTML(filterDiscoveryItems(items), title);
}

// Renders the Continue Watching row into the given element. Shared by Home and
// the You hub so the two stay a 1-1 copy of the same history feed.
async function loadContinueWatching(el, existing) {
  if (!el) return;
  const hist = existing || await api.get('/api/history').catch(() => []);
  S.history = hist;
  // One card per series (history is grouped by anime, not episode). The row is
  // capped at 10 cards; the See-more button opens the full History page.
  const groups = groupHistoryByAnime(hist);
  if (!groups.length) { el.innerHTML = ''; return; }
  el.innerHTML = '<div class="section-head"><div class="section-title">Continue Watching</div>' +
    // Always shown: it is the only in-UI route to the full History page.
    '<button class="see-all" onclick="openLibrarySection(\'history\')">See more</button></div>' +
    '<div class="continue-row">' +
    groups.slice(0, 10).map(g => {
      const h = g.latest;
      const pct = h.duration > 0 ? Math.min(100, (h.progress / h.duration) * 100).toFixed(1) : 0;
      return `<div class="continue-card" data-anime="${escapeHTML(g.anime_id)}" onclick="resumeFromHistory(this.dataset.anime)">
        <div class="continue-thumb-wrap">
          <img class="continue-thumb" src="${escapeHTML(h.thumbnail)}" alt="" onerror="this.style.opacity=0">
          <div class="continue-progress"><div class="continue-fill" style="width:${pct}%"></div></div>
        </div>
        <div class="continue-info">
          <div class="continue-title">${escapeHTML(g.anime_title)}</div>
          <div class="continue-ep">Ep ${escapeHTML(h.episode)}</div>
        </div>
      </div>`;
    }).join('') + '</div>';
}

async function loadPopular() {
  const sources = ['/api/popular?type=' + (S.settings.sub_lang || 'sub'), '/api/trending?type=' + (S.settings.sub_lang || 'sub')];
  for (const path of sources) {
    const result = await api.get(path).catch(() => null);
    if (Array.isArray(result) && result.length) return result;
  }
  return [];
}

async function findPlayableAnime(title) {
  const variants = [title, title.split(':')[0].trim(), title.replace(/\s*\([^)]*\)\s*/g, '').trim()]
    .filter((value, index, all) => value && all.indexOf(value) === index);
  const candidates = [];
  for (const variant of variants) {
    const results = await api.get('/api/search?q=' + encodeURIComponent(variant) + '&type=' + (S.settings.sub_lang || 'sub')).catch(() => []);
    if (Array.isArray(results)) candidates.push(...results.filter(result => result && result.id));
  }
  if (!candidates.length) return null;
  const wanted = variants.map(normalizeTitle);
  const scored = candidates.map(candidate => ({
    candidate,
    score: Math.max(...wanted.map(query => titleMatchScore(candidate, query)))
  })).sort((a, b) => b.score - a.score);
  const asksForFilm = wanted.some(query => /\b(movie|film|special|ova)\b/.test(query));
  const series = asksForFilm ? scored : scored.filter(item => !isFilmCandidate(item.candidate));
  return series.length && series[0].score >= 100 ? series[0].candidate : null;
}

function openCatalogCard(cardId) {
  const anime = S.catalogCards[cardId];
  if (anime && anime.id) openAnime(anime);
}

function normalizeTitle(value) {
  // Lowercase, strip accents, keep letters/digits from any script (CJK native
  // titles matter) — mirrors the native AniDB scraper's normalization.
  return String(value || '').toLowerCase().normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

function escapeHTML(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function titleMatchScore(candidate, wanted) {
  const al = candidate.anilist || {};
  const names = [candidate.title, candidate.raw_title, al.title?.english, al.title?.romaji, al.title?.native]
    .filter(Boolean).map(normalizeTitle);
  let score = 0;
  names.forEach(name => {
    if (name === wanted) score = Math.max(score, 1000);
    else if (name.startsWith(wanted)) score = Math.max(score, 700);
    else if (name.includes(wanted)) score = Math.max(score, 500);
    else {
      const wantedWords = wanted.split(' ').filter(word => word.length > 2);
      const overlap = wantedWords.filter(word => name.includes(word)).length;
      score = Math.max(score, overlap * 20);
    }
  });
  // Franchise searches often put films before the canonical TV entry.
  // Prefer the series unless the requested title explicitly asks for a film.
  const asksForFilm = /\b(movie|film|special|ova)\b/.test(wanted);
  const format = String(al.format || '').toUpperCase();
  const name = names.join(' ');
  if (!asksForFilm && format === 'MOVIE') score -= 600;
  if (!asksForFilm && /\b(movie|film|special|ova)\b/.test(name)) score -= 300;
  return score;
}

function isFilmCandidate(candidate) {
  const al = candidate.anilist || {};
  const format = String(al.format || '').toUpperCase();
  const names = [candidate.title, al.title?.english, al.title?.romaji].filter(Boolean).join(' ').toLowerCase();
  return format === 'MOVIE' || /\b(movie|film|special|ova)\b/.test(names);
}

//  Title resolution: catalog cards without a playable AniDB id carry the full
//  AniList context (canonical aliases + format + media id). We resolve through
//  /api/resolve, which scores AniDB candidates against those aliases and
//  prefers the requested format, and show a picker when several entries tie.

let resolveCandidates = [];
let resolveCard = null;

async function openByTitle(cardId) {
  const card = S.catalogCards[cardId];
  if (!card) { showToast('Could not open this title', 'error'); return; }
  const al = card.anilist || {};
  const label = card.title || al.title?.english || al.title?.romaji || 'anime';

  // Open the detail view instantly with the card's AniList data — no waiting
  // on AniDB. The episode list shows a static skeleton while the playable
  // entry is resolved in the background below.
  openAnime({ id: null, title: label, thumbnail: card.thumbnail, anilist: al });
  const token = openAnimeToken;
  // Drop the background resolution if the user opened something else or left
  // the detail view while it was running.
  const stillCurrent = () =>
    token === openAnimeToken &&
    document.getElementById('view-detail').classList.contains('active');

  const titles = [card.title, al.title?.english, al.title?.romaji, al.title?.native, ...(al.synonyms || [])]
    .filter(Boolean);
  const resolved = await api.post('/api/resolve', {
    titles,
    format: al.format || '',
    media_id: al.id || 0,
    type: S.settings.sub_lang || 'sub'
  }).catch(() => null);

  if (resolved) {
    const alternatives = Array.isArray(resolved.alternatives) ? resolved.alternatives : [];
    const strong = alternatives.filter(c => (c.match_score || 0) >= 700);
    if (resolved.best && strong.length <= 1) {
      if (!stillCurrent()) return;
      openAnime({ ...resolved.best, anilist: resolved.best.anilist || al });
      return;
    }
    if (strong.length > 1 && strong[0].match_score - strong[1].match_score <= 250) {
      if (!stillCurrent()) return;
      showResolvePicker(strong, card);
      return;
    }
  }

  // Fallbacks: Jikan detail (card has a MAL id), then plain title search.
  if (card.jikan_id) {
    const entry = await api.get('/api/anime?id=' + encodeURIComponent(card.jikan_id) + '&type=' + (S.settings.sub_lang || 'sub')).catch(() => null);
    if (entry && entry.id) {
      if (!stillCurrent()) return;
      openAnime({ ...entry, anilist: entry.anilist || al });
      return;
    }
  }
  const playable = await findPlayableAnime(label);
  if (playable) {
    if (!stillCurrent()) return;
    openAnime({ ...playable, anilist: playable.anilist || al });
    return;
  }
  // No AniDB entry came back from any lookup — the title resolved to null.
  // Keep the AniList-only detail view but surface it as an error instead of
  // leaving the skeleton or a bare "unavailable" line.
  if (stillCurrent()) {
    showEpisodeError('No AniDB entry could be matched for this title. It may not be available there yet — try searching for it manually.');
    showToast('Could not find this title on AniDB', 'error');
  }
}

function showResolvePicker(candidates, card) {
  resolveCandidates = candidates;
  resolveCard = card;
  const subtitle = document.getElementById('resolve-subtitle');
  if (subtitle) subtitle.textContent = (card.title || 'This title') + ' matched several entries — pick the one you meant.';
  const list = document.getElementById('resolve-list');
  list.innerHTML = candidates.map((c, i) => {
    const al = c.anilist || {};
    const meta = [al.format, c.eps_avail ? c.eps_avail + ' episodes' : '', al.seasonYear]
      .filter(Boolean).map(escapeHTML).join(' · ');
    const thumb = escapeHTML(c.thumbnail || al.coverImage?.large || '');
    return `<button class="resolve-row" onclick="pickResolvedAnime(${i})">
      <img class="resolve-thumb" src="${thumb}" alt="" onerror="this.style.visibility='hidden'">
      <span class="resolve-info">
        <span class="resolve-name">${escapeHTML(c.title)}</span>
        ${meta ? `<span class="resolve-meta">${meta}</span>` : ''}
      </span>
      <svg class="resolve-chevron" viewBox="0 0 24 24"><polyline points="9 18 15 12 9 6"></polyline></svg>
    </button>`;
  }).join('');
  document.getElementById('resolve-modal').hidden = false;
}

function pickResolvedAnime(index) {
  const candidate = resolveCandidates[index];
  const card = resolveCard;
  closeResolvePicker();
  if (!candidate) return;
  openAnime({ ...candidate, anilist: candidate.anilist || (card && card.anilist) || {} });
}

function closeResolvePicker() {
  document.getElementById('resolve-modal').hidden = true;
  resolveCandidates = [];
  resolveCard = null;
  // Cancel without picking: the detail view was opened with an episode
  // skeleton before resolution — swap it for the real empty state instead of
  // leaving it spinning. (pickResolvedAnime re-opens right after, so the
  // swap is never painted there.)
  if (episodesPending) stopEpisodeSkeleton();
}

// Popular feed shown on the Search tab (vertical list, top-to-bottom)
async function loadTrending() {
  const el = document.getElementById('trending-section');
  if (!el) return;
  el.innerHTML = '<div class="section-title searching"><span class="searching-dot"></span>Loading…</div>' + skeletonListHTML(8);
  const trending = await loadPopular();
  if (!Array.isArray(trending) || !trending.length) {
    el.innerHTML = '';
    return;
  }
  // Same child-safe rule as the Home rows: the Search tab's default feed is
  // discovery content, so it never shows 18+ either.
  el.innerHTML = animeListHTML(filterDiscoveryItems(trending), 'Popular This Month');
}
