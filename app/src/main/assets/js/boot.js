//  Boot: keyboard shortcuts, global listeners, app start 
// This file is loaded last so every function used at boot is already defined.

//  Keyboard Shortcuts 
document.addEventListener('keydown', e => {
  const tag = document.activeElement?.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA') {
    if (e.key === 'Escape') {
      // Escape clears the search box first (back to trending), then blurs.
      if (document.activeElement === searchInput && searchInput.value.trim()) {
        clearQueryFilter();
      } else {
        document.activeElement.blur();
      }
    }
    return;
  }

  switch(e.key.toLowerCase()) {
    case 'g':
      e.preventDefault();
      navigate('home');
      break;
    case 'k':
      e.preventDefault();
      navigate('search');
      setTimeout(() => searchInput.focus(), 100);
      break;
    case 'h':
      e.preventDefault();
      pushView('history');
      break;
    case 's':
      e.preventDefault();
      navigate('settings');
      break;
    case 'r':
      e.preventDefault();
      refreshCurrentView();
      break;
    case 'm':
      if (document.getElementById('view-player').classList.contains('active')) {
        e.preventDefault();
        playInMpv();
      }
      break;
    case 'f':
      if (document.getElementById('view-player').classList.contains('active')) {
        e.preventDefault();
        toggleFullscreen();
      }
      break;
    case 'escape':
      e.preventDefault();
      handleAppBack();
      break;
  }
});

//  Back navigation 
// Single shared back action used by the Escape key and the Android system back
// button (MainActivity routes onBackPressed through window.handleAppBack).
// Returns true when the press was consumed by the app, false when the user is on
// a root tab and Android should exit.
window.handleAppBack = function() {
  const resolveModal = document.getElementById('resolve-modal');
  if (resolveModal && !resolveModal.hidden) {
    closeResolvePicker();
    return true;
  }
  const active = document.querySelector('.view.active');
  const activeId = active ? active.id.replace('view-', '') : '';
  if (activeId === 'player') {
    // Back while fullscreen leaves fullscreen first, then backs out of the player.
    if (document.body.classList.contains('video-fullscreen')) {
      exitFullscreen();
      return true;
    }
    maybePersistPlaybackProgress(true);
    popView();
    return true;
  }
  if (activeId === 'detail' || activeId === 'history') {
    popView();
    return true;
  }
  if (activeId === 'search' && (searchInput.value.trim() || activeGenreChip())) {
    searchInput.value = '';
    clearGenreFilter();
    return true;
  }
  return false;
};

//  Pull-to-refresh 
// Drag down from the top of Home/Search/History to drop the backend cache and
// reload the active view. On Android the cache lives in Java (SQLite), cleared
// through AndroidApi.refresh(); in dev mode there is nothing to drop and the
// reload alone re-fetches.
const PULL_THRESHOLD = 80;
let pullStartY = 0;
let pulling = false;
let pullDistance = 0;
let refreshing = false;

const mainScroll = document.getElementById('main');
const refreshIndicator = document.getElementById('refresh-indicator');
const refreshLabel = document.getElementById('refresh-label');

function refreshableView() {
  const active = document.querySelector('.view.active');
  const id = active ? active.id.replace('view-', '') : '';
  return id === 'home' || id === 'search' || id === 'history';
}

function setPullIndicator(distance) {
  const progress = Math.min(distance / PULL_THRESHOLD, 1);
  refreshIndicator.style.transform = 'translate(-50%, ' + Math.round(distance - 110) + 'px)';
  refreshIndicator.style.opacity = String(Math.min(progress * 1.5, 1));
  refreshLabel.textContent = progress >= 1 ? 'Release to refresh' : 'Pull to refresh';
}

function resetPullIndicator() {
  refreshIndicator.style.transform = '';
  refreshIndicator.style.opacity = '';
  refreshLabel.textContent = 'Pull to refresh';
  refreshIndicator.classList.remove('loading');
}

mainScroll.addEventListener('touchstart', e => {
  if (refreshing || !refreshableView() || mainScroll.scrollTop > 0) return;
  pulling = true;
  pullDistance = 0;
  pullStartY = e.touches[0].clientY;
}, { passive: true });

mainScroll.addEventListener('touchmove', e => {
  if (!pulling) return;
  const delta = e.touches[0].clientY - pullStartY;
  if (delta <= 0) { pulling = false; resetPullIndicator(); return; }
  pullDistance = Math.min(delta * 0.45, 130);
  setPullIndicator(pullDistance);
  e.preventDefault();
}, { passive: false });

mainScroll.addEventListener('touchend', () => {
  if (!pulling) return;
  const shouldRefresh = pullDistance >= PULL_THRESHOLD;
  pulling = false;
  resetPullIndicator();
  if (shouldRefresh) refreshCurrentView();
});

mainScroll.addEventListener('touchcancel', () => {
  if (!pulling) return;
  pulling = false;
  resetPullIndicator();
});

async function refreshCurrentView() {
  if (refreshing) return;
  refreshing = true;
  refreshIndicator.style.transform = 'translate(-50%, 8px)';
  refreshIndicator.style.opacity = '1';
  refreshLabel.textContent = 'Refreshing...';
  refreshIndicator.classList.add('loading');
  const active = document.querySelector('.view.active');
  const id = active ? active.id.replace('view-', '') : '';
  // Drop only the cache entries this view actually uses — the whole cache is
  // never wiped, so other views stay warm and API rate limits aren't hammered.
  let cachePrefixes = '';
  if (id === 'home') cachePrefixes = 'trending';
  else if (id === 'search') {
    cachePrefixes = searchInput.value.trim() ? 'anidb-search|,anidb-resolve|' : 'trending,tags,tag|';
  }
  try { if (window.AndroidApi && window.AndroidApi.refreshCache) window.AndroidApi.refreshCache(cachePrefixes); } catch (_) {}
  try {
    if (id === 'home') await loadHome();
    else    if (id === 'search') {
      const chip = activeGenreChip();
      if (searchInput.value.trim()) await doSearch(searchInput.value.trim());
      else if (chip) await doSearch('', chip.dataset.genre);
      else { await loadTrending(); loadTags(true); }
    }
    else if (id === 'history') loadHistory();
  } catch (_) {}
  resetPullIndicator();
  refreshing = false;
  showToast('Refreshed', 'success');
}

//  Global listeners 
systemThemeQuery.addEventListener('change', () => {
  if ((S.settings.theme || 'dark') === 'auto') applyTheme('auto');
});

window.addEventListener('resize', () => {
  refreshPillSliders();
  updateSegPills(S.translation || 'sub');
}, { passive: true });

window.addEventListener('beforeunload', () => maybePersistPlaybackProgress(true));
document.addEventListener('visibilitychange', () => {
  if (document.hidden) maybePersistPlaybackProgress(true);
});

//  Boot 
// One-time cleanup of the legacy accent_h key from pre-refactor versions
if (window.localStorage) { try { localStorage.removeItem('accent_h'); } catch (_) {} }

// MPV availability: hide the Open-in-MPV button and the MPV player option when
// nothing is installed (no CLI mpv binary, no mpv-android app).
const mpvReady = getMpvStatus().then(status => {
  if (status) S.mpv = { available: !!status.available, cli: !!status.cli, app: !!status.app };
  const btn = document.getElementById('btn-mpv');
  if (btn) btn.style.display = S.mpv.available ? '' : 'none';
  const mpvOpt = document.querySelector('#pill-player .pill-option[data-value="mpv"]');
  if (mpvOpt) mpvOpt.style.display = S.mpv.available ? '' : 'none';
});

// Wait for both status and settings so a stale 'mpv' default falls back to web
Promise.all([mpvReady, loadSettings()]).then(() => {
  if (!S.mpv.available && S.settings.player === 'mpv') {
    S.settings.player = 'web';
    setPillValue('pill-player', 'web');
    api.post('/api/settings', { player: 'web' }).catch(() => {});
  }
});
showView('home');
loadTags();
