//  Home: continue watching + popular 
// Android fetches both in one /api/home call; dev-mode backends that lack it
// fall back to the individual /api/history + /api/trending requests.

async function loadHome() {
  const data = await api.get('/api/home').catch(() => null);
  if (data && (Array.isArray(data.history) || Array.isArray(data.trending))) {
    await Promise.all([
      loadHomeContinue(Array.isArray(data.history) ? data.history : null),
      loadHomeTrending(Array.isArray(data.trending) && data.trending.length ? data.trending : null)
    ]);
  } else {
    await Promise.all([loadHomeContinue(), loadHomeTrending()]);
  }
}

async function loadHomeContinue(existing) {
  const el = document.getElementById('home-continue-section');
  if (!el) return;
  const hist = existing || await api.get('/api/history').catch(() => []);
  S.history = hist;
  if (!hist.length) { el.innerHTML = ''; return; }
  el.innerHTML = '<div class="section-head"><div class="section-title">Continue Watching</div>' +
    '<button class="see-all" onclick="pushView(\'history\')">See all</button></div>' +
    '<div class="continue-row">' +
    hist.slice(0, 10).map(h => {
      const pct = h.duration > 0 ? Math.min(100, (h.progress / h.duration) * 100).toFixed(1) : 0;
      return `<div class="continue-card" onclick="resumeFromHistory(${h.id})">
        <div class="continue-thumb-wrap">
          <img class="continue-thumb" src="${h.thumbnail}" alt="" onerror="this.style.opacity=0">
          <div class="continue-progress"><div class="continue-fill" style="width:${pct}%"></div></div>
        </div>
        <div class="continue-info">
          <div class="continue-title">${h.anime_title}</div>
          <div class="continue-ep">Ep ${h.episode}</div>
        </div>
      </div>`;
    }).join('') + '</div>';
}

async function loadHomeTrending(existing) {
  const el = document.getElementById('home-trending-section');
  if (!el) return;
  let trending = Array.isArray(existing) && existing.length ? existing : null;
  if (!trending) {
    el.innerHTML = '<div class="section-title">Popular This Month</div>' + skeletonGridHTML(8);
    trending = await loadPopular();
  }
  if (!Array.isArray(trending) || !trending.length) {
    el.innerHTML = '<div class="section-title">Popular This Month</div><div class="empty"><div class="empty-icon">!</div>Popular anime could not be loaded. Tap Home to retry.</div>';
    return;
  }
  el.innerHTML = animeGridHTML(trending, 'Popular This Month');
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
  if (anime && anime.id) {
    showToast('Loading ' + (anime.title || 'anime') + '...', 'info');
    openAnime(anime);
  }
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
  const label = card.title || card.anilist?.title?.english || card.anilist?.title?.romaji || 'anime';
  showToast('Loading ' + label + '...', 'info');

  const al = card.anilist || {};
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
      openAnime({ ...resolved.best, anilist: resolved.best.anilist || al });
      return;
    }
    if (strong.length > 1 && strong[0].match_score - strong[1].match_score <= 250) {
      showResolvePicker(strong, card);
      return;
    }
  }

  // Fallbacks: Jikan detail (card has a MAL id), then plain title search.
  if (card.jikan_id) {
    const entry = await api.get('/api/anime?id=' + encodeURIComponent(card.jikan_id) + '&type=' + (S.settings.sub_lang || 'sub')).catch(() => null);
    if (entry && entry.id) { openAnime({ ...entry, anilist: entry.anilist || al }); return; }
  }
  const playable = await findPlayableAnime(label);
  if (playable) {
    openAnime({ ...playable, anilist: playable.anilist || al });
    return;
  }
  showToast('Not found. Try searching manually.', 'error');
  document.querySelectorAll('.chip.active').forEach(chip => chip.classList.remove('active'));
  searchInput.value = label;
  updateSearchClear();
  renderActiveFilter();
  showView('search');
  doSearch(label);
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
}

// Popular grid shown on the Search tab
async function loadTrending() {
  const el = document.getElementById('trending-section');
  if (!el) return;
  el.innerHTML = '<div class="section-title">Popular This Month</div>' + skeletonGridHTML(6);
  const trending = await loadPopular();
  if (!Array.isArray(trending) || !trending.length) {
    el.innerHTML = '';
    return;
  }
  el.innerHTML = animeGridHTML(trending, 'Popular This Month');
}
