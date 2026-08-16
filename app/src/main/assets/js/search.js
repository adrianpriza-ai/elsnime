//  Search: input wiring, genre chips, results rendering 
let searchTimer;
let searchSeq = 0; // bumped on every new search/clear so stale responses are ignored
// Bound in initSearch() once the views/*.html partials are injected (the UI
// is split out of ui.html and loaded at startup, so this element doesn't
// exist at parse time).
let searchInput = null;

// Show/hide the in-field × button based on whether the box has text.
function updateSearchClear() {
  const wrap = document.getElementById('search-bar-wrap');
  if (wrap) wrap.classList.toggle('has-text', !!searchInput.value.trim());
}

//  Recent searches: a row of tappable chips persisted in localStorage so the
//  user can re-run past queries. Capped at MAX_RECENT_SEARCHES, newest first;
//  duplicates and shorter mid-typing prefixes are overwritten (keeps the list
//  small and cache-friendly). Only queries that returned results are stored.
const MAX_RECENT_SEARCHES = 5;
const RECENT_SEARCHES_KEY = 'elsnime_recent_searches';

function loadRecentSearches() {
  try {
    const raw = window.localStorage.getItem(RECENT_SEARCHES_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr)
      ? arr.filter(v => typeof v === 'string' && v.trim())
      : [];
  } catch (_) {
    return [];
  }
}

function saveRecentSearches(list) {
  try {
    window.localStorage.setItem(
      RECENT_SEARCHES_KEY,
      JSON.stringify(list.slice(0, MAX_RECENT_SEARCHES))
    );
  } catch (_) {}
}

function addRecentSearch(q) {
  q = String(q == null ? '' : q).trim();
  if (!q) return;
  const lower = q.toLowerCase();
  // Drop exact duplicates and older queries that are prefixes of this one
  // (debounce fires mid-typing, so "nar" should be replaced by "naruto").
  const recents = loadRecentSearches().filter(r => {
    const rl = r.toLowerCase();
    return rl !== lower && !lower.startsWith(rl);
  });
  recents.unshift(q);
  saveRecentSearches(recents);
  updateRecentVisibility();
}

function runRecentSearch(q) {
  if (!q) return;
  searchInput.value = q;
  updateSearchClear();
  document.querySelectorAll('.chip.active').forEach(chip => chip.classList.remove('active'));
  renderActiveFilter();
  document.getElementById('trending-section').style.display = 'none';
  doSearch(q);
}

function clearRecentSearches() {
  saveRecentSearches([]);
  renderRecentSearches();
}

function renderRecentSearches() {
  const el = document.getElementById('recent-searches');
  if (!el) return;
  const recents = loadRecentSearches();
  if (recents.length) {
    el.innerHTML =
      '<span class="recent-label">Recent</span>' +
      '<div class="recent-chips">' +
      recents
        .map(q =>
          `<button type="button" class="recent-chip" data-query="${escapeGenreName(q)}">${escapeGenreName(q)}</button>`
        )
        .join('') +
      '</div>' +
      '<button type="button" class="recent-clear" onclick="clearRecentSearches()" title="Clear recent searches" aria-label="Clear recent searches">×</button>';
    el.querySelectorAll('.recent-chip').forEach(chip => {
      chip.addEventListener('click', () => runRecentSearch(chip.dataset.query));
    });
  } else {
    el.innerHTML = '';
  }
  updateRecentVisibility();
}

// The recents row only makes sense with an empty box and no genre filter.
function updateRecentVisibility() {
  const el = document.getElementById('recent-searches');
  if (!el) return;
  const hidden =
    !!searchInput.value.trim() || !!activeGenreChip() || !loadRecentSearches().length;
  el.hidden = hidden;
}

// The currently-selected genre chip, if any (used across files so the empty
// search box doesn't lose the active genre filter on navigation/refresh).
function activeGenreChip() {
  return document.querySelector('.chip.active');
}

function escapeGenreName(value) {
  return String(value == null ? '' : value).replace(/[<>&"']/g, m => ({
    '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;'
  })[m]);
}

// Small flat 'Search: X ×' / 'Genre: X ×' tag above the results, synced with
// the active text query or genre chip. No animations — instant and minimal.
function renderActiveFilter() {
  const el = document.getElementById('active-filter');
  if (!el) return;
  const chip = activeGenreChip();
  if (chip) {
    el.innerHTML =
      `<span class="active-filter-label">Genre: <strong>${escapeGenreName(chip.dataset.genre)}</strong></span>` +
      `<button type="button" class="active-filter-clear" onclick="clearGenreFilter()" aria-label="Clear genre filter" title="Clear">×</button>`;
    el.hidden = false;
    return;
  }
  const q = searchInput.value.trim();
  if (q) {
    el.innerHTML =
      `<span class="active-filter-label">Search: <strong>${escapeGenreName(q)}</strong></span>` +
      `<button type="button" class="active-filter-clear" onclick="clearQueryFilter()" aria-label="Clear search" title="Clear">×</button>`;
    el.hidden = false;
    return;
  }
  el.hidden = true;
}

// Returns the Search tab to the trending grid and forgets any in-flight or
// pending search so a stale response can't render over the fresh state.
function resetSearchToTrending() {
  clearTimeout(searchTimer);
  searchSeq++;
  updateSearchClear();
  // Re-render here (not just toggle visibility) so searches recorded this
  // session show up in the chips — the row is hidden while typing.
  renderRecentSearches();
  const results = document.getElementById('search-results');
  if (results) results.innerHTML = '';
  const trending = document.getElementById('trending-section');
  if (trending) trending.style.display = 'block';
  loadTrending();
  renderActiveFilter();
}

// Drops the genre filter: un-highlights the chip, hides the tag, and returns
// the Search tab to the trending grid. Shared by the × button, chip re-tap,
// and the system back button.
function clearGenreFilter() {
  document.querySelectorAll('.chip.active').forEach(chip => chip.classList.remove('active'));
  resetSearchToTrending();
}

// Clears an active text search: empties the box, hides the tag, and returns
// the Search tab to the trending grid.
function clearQueryFilter() {
  searchInput.value = '';
  resetSearchToTrending();
}

// Clears every active filter at once: the text box and any selected genre
// chip. Used by the "Clear search" button in the no-results state, which can
// be reached from either a text or a genre search.
function clearAllFilters() {
  searchInput.value = '';
  document.querySelectorAll('.chip.active').forEach(chip => chip.classList.remove('active'));
  resetSearchToTrending();
}

function bindGenreChip(chip) {
  chip.addEventListener('click', () => {
    const genre = chip.dataset.genre;
    const wasActive = chip.classList.contains('active');
    document.querySelectorAll('.chip.active').forEach(active => active.classList.remove('active'));
    if (wasActive) {
      clearGenreFilter();
      return;
    }
    chip.classList.add('active');
    // Genre search keeps the input box empty — the tag and chip mark the filter.
    renderActiveFilter();
    updateRecentVisibility();
    document.getElementById('trending-section').style.display = 'none';
    doSearch('', genre);
  });
}

//  Browse by genre: a 2-column grid of genre cards (name + anime count)
//  sorted by popularity, replacing the old sideways chip rail. The default
//  view is capped at GENRE_PREVIEW_COUNT cards; "Show all" expands to the
//  full grouped list (Genres / Demographics / Themes).
const GENRE_CATEGORIES = ['genre', 'demographic', 'theme'];
const GENRE_CATEGORY_LABELS = { genre: 'Genres', demographic: 'Demographics', theme: 'Themes' };
const GENRE_PREVIEW_COUNT = 8;
// MAL genre ids that are inherently adult (Hentai, Erotica). Their cards are
// hidden from the browse grid while "Show 18+ content" is off, so adult
// genres aren't even discoverable as browse options.
const ADULT_GENRE_IDS = new Set([12, 49]);
let genreTags = [];
let genreBrowseExpanded = false;

function genreCardHTML(tag) {
  const count = tag.count > 0 ? `<span class="genre-count">${tag.count}</span>` : '';
  return `<button type="button" class="chip genre-card" data-genre="${escapeGenreName(tag.name)}">` +
    `<span class="genre-name">${escapeGenreName(tag.name)}</span>${count}</button>`;
}

function renderGenreGrid() {
  const grid = document.getElementById('genre-grid');
  if (!grid) return;
  // Keep the active filter across re-renders (the toggle rebuilds the grid).
  const prevActive = activeGenreChip() ? activeGenreChip().dataset.genre : null;
  const tags = showAdultContent()
    ? genreTags
    : genreTags.filter(t => !ADULT_GENRE_IDS.has(Number(t.id)));
  const byCat = {};
  GENRE_CATEGORIES.forEach(cat => (byCat[cat] = []));
  tags.forEach(tag => {
    const cat = GENRE_CATEGORIES.includes(tag.category) ? tag.category : 'genre';
    byCat[cat].push(tag);
  });
  GENRE_CATEGORIES.forEach(cat => byCat[cat].sort((a, b) => (b.count || 0) - (a.count || 0)));
  // No point in a "Show all" toggle when there's nothing beyond the preview.
  const toggle = document.getElementById('browse-toggle');
  if (toggle) {
    toggle.hidden = tags.length <= GENRE_PREVIEW_COUNT;
    toggle.textContent = genreBrowseExpanded ? 'Show less' : 'Show all';
  }
  let html;
  if (genreBrowseExpanded) {
    html = GENRE_CATEGORIES
      .filter(cat => byCat[cat].length)
      .map(cat =>
        `<div class="genre-group-label">${GENRE_CATEGORY_LABELS[cat]}</div>` + byCat[cat].map(genreCardHTML).join('')
      )
      .join('');
  } else {
    html = byCat.genre.slice(0, GENRE_PREVIEW_COUNT).map(genreCardHTML).join('');
  }
  grid.innerHTML = html;
  grid.querySelectorAll('.chip').forEach(chip => {
    bindGenreChip(chip);
    if (chip.dataset.genre === prevActive) chip.classList.add('active');
  });
}

// "Show all" toggles between just the main genres and the full grouped list
// (genres + demographics + themes).
function toggleGenreBrowse() {
  genreBrowseExpanded = !genreBrowseExpanded;
  renderGenreGrid();
}

async function loadTags(force = false) {
  const grid = document.getElementById('genre-grid');
  if (!grid || (!force && grid.dataset.loaded)) return;
  const tags = await api.get('/api/tags').catch(() => []);
  if (!Array.isArray(tags) || !tags.length) return;
  genreTags = tags;
  grid.dataset.loaded = 'true';
  const section = document.getElementById('browse-section');
  if (section) section.hidden = false;
  renderGenreGrid();
}

async function doSearch(q, tag = '') {
  if (!q && !tag) return;
  const seq = ++searchSeq;
  const el = document.getElementById('search-results');
  el.innerHTML =
    '<div class="section-title searching"><span class="searching-dot"></span>Searching…</div>' +
    skeletonGridHTML(12);
  const query = tag ? `tag=${encodeURIComponent(tag)}` : `q=${encodeURIComponent(q)}&type=${S.settings.sub_lang||'sub'}`;
  const results = await api.get(`/api/search?${query}`).catch(() => null);
  if (seq !== searchSeq) return; // superseded by a newer search or a clear
  // A failed request (e.g. Cloudflare challenge) must say so instead of being
  // silently shown as "No results found".
  if (results && !Array.isArray(results) && results.error) {
    el.innerHTML = `<div class="empty"><div class="empty-icon">!</div>Search failed: ${escapeGenreName(results.error)}</div>`;
    return;
  }
  // Only record queries that actually matched something — a zero-result or
  // failed query isn't a useful shortcut (and keeps the list cache-friendly).
  if (q && Array.isArray(results) && results.length) addRecentSearch(q);
  renderResults(Array.isArray(results) ? results : [], tag ? `Tag: ${escapeGenreName(tag)}` : 'Results', tag ? '' : q, !!tag);
}

function renderResults(results, heading = 'Results', highlight = '', isGenre = false) {
  const el = document.getElementById('search-results');
  const visible = filterAdultItems(results);
  if (!visible.length) {
    // Empty is empty as far as the user can tell: filtered mature results
    // (Mature content is off by default) render the exact same plain no-match
    // state as a real empty result, so nothing hints that a filter exists.
    // Only a truly empty genre result (source failure) gets the outage hint.
    const hint = isGenre && !results.length
      ? 'No titles for this genre right now — its catalog source (MAL) is temporarily unreachable. Try again in a moment, or pick another genre below.'
      : 'Try a different spelling, or browse by genre below.';
    el.innerHTML =
      '<div class="empty"><div class="empty-icon"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg></div>' +
      'No results found.' +
      `<div class="empty-hint">${hint}</div>` +
      '<button type="button" class="empty-action" onclick="clearAllFilters()">Clear search</button></div>';
    return;
  }
  el.innerHTML = animeGridHTML(visible, `${heading} (${visible.length})`, highlight);
}

// Wire the search box and chip rows. Runs from boot() once the UI partials
// are injected, so the elements are guaranteed to exist.
window.__initSearch = function initSearch() {
  searchInput = document.getElementById('search-input');
  searchInput.addEventListener('input', e => {
    clearTimeout(searchTimer);
    updateSearchClear();
    updateRecentVisibility();
    const q = e.target.value.trim();
    document.querySelectorAll('.chip.active').forEach(chip => chip.classList.remove('active'));
    if (!q) {
      resetSearchToTrending();
      return;
    }
    renderActiveFilter();
    document.getElementById('trending-section').style.display = 'none';
    searchTimer = setTimeout(() => doSearch(q), 400);
  });

  searchInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      clearTimeout(searchTimer);
      const q = e.target.value.trim();
      if (q) {
        document.getElementById('trending-section').style.display = 'none';
        doSearch(q);
      }
    }
  });

  document.querySelectorAll('.chip').forEach(bindGenreChip);
  // Show the persisted recent searches row on first load.
  renderRecentSearches();
};
