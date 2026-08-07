//  Player: episode playback (Plyr/HLS), MPV, prev/next, Up next sidebar,
//  overlay controls (skip ±10s, fullscreen), auto-rotate, stream teardown.
let player;
let hlsInstance;

async function playEpisode(ep, index) {
  S.currentEp = { ep, index };
  const al = S.anime?.anilist || {};
  const title = al.title?.english || S.anime?.title || '';

  document.getElementById('player-title').innerHTML =
    `${title} <span id="player-ep-label">Episode ${ep}</span>`;
  document.getElementById('player-error').style.display = 'none';

  // YouTube-style channel row: poster avatar + anime meta + sub/dub toggle
  document.getElementById('player-avatar').src =
    al.coverImage?.extraLarge || al.coverImage?.large || S.anime?.thumbnail || '';
  document.getElementById('watch-channel-name').textContent = title;
  const subBits = [];
  if (al.seasonYear) subBits.push(al.seasonYear);
  if (al.status) subBits.push(al.status.replace(/_/g, ' '));
  document.getElementById('watch-channel-sub').textContent =
    subBits.join(' · ') || `${S.episodes.length || 0} episodes`;

  document.getElementById('btn-prev').disabled = index === 0;
  document.getElementById('btn-next').disabled = index === S.episodes.length - 1;

  pushView('player');
  renderUpNext();

  // MPV default: one native call fetches the stream and launches mpv in Java.
  // If only MPV failed, Java still returns the stream so we can fall back.
  // When no mpv is installed the button/setting are hidden, but guard anyway
  // (keyboard shortcut, saved pref from before uninstall, status not resolved).
  if (S.settings.player === 'mpv' && !S.mpv.available) {
    showToast('MPV not installed — using web player', 'error');
  } else if (S.settings.player === 'mpv') {
    const res = await playInMpvNative(S.anime.id, ep, S.translation);
    if (!res.error) { showToast('Launched in MPV', 'success'); return; }
    if (res.url) {
      startWebPlayer(res, ep);
      showPlayerError('MPV failed to launch. Falling back to web player.');
      return;
    }
    showPlayerError(res.error || 'Could not find a stream for this episode.');
    return;
  }

  const stream = await api.get(`/api/stream?id=${encodeURIComponent(S.anime.id)}&episode=${encodeURIComponent(ep)}&type=${encodeURIComponent(S.translation)}`).catch(() => null);
  if (!stream || stream.error) {
    showPlayerError(stream?.error || 'Could not find a stream for this episode.');
    return;
  }
  startWebPlayer(stream, ep);
}

// "Up next" sidebar: episode list reordered so the current episode is first,
// like YouTube's suggestion panel next to the player.
let upNextRenderId = 0;
async function renderUpNext() {
  const id = ++upNextRenderId;
  const list = document.getElementById('eps-list');
  if (!list) return;
  const countEl = document.getElementById('upnext-count');
  if (countEl) countEl.textContent = S.episodes.length ? `${S.episodes.length} episodes` : '';
  if (!S.episodes.length) { list.innerHTML = ''; return; }

  const historyMap = await getHistoryMap();
  if (id !== upNextRenderId) return; // a newer render superseded this one

  const items = S.episodes.map((ep, i) => ({ ep, i }));
  const cur = S.currentEp ? S.currentEp.index : 0;
  const ordered = items.slice(cur).concat(items.slice(0, cur));
  list.innerHTML = ordered
    .map(({ ep, i }, pos) => episodeRowHTML(ep, i, historyMap, i === cur, pos === 1))
    .join('');
}

function startWebPlayer(stream, ep) {
  // proxy URL → web player; raw CDN URL is kept for MPV
  loadStream(stream.url, stream.type || null);
  // force=true: create the history row immediately instead of waiting for a flush
  saveProgress(ep, 0, 0, true);
}

function loadStream(url, type) {
  // Destroy old instances and wipe all stale event listeners via cloneNode
  if (hlsInstance) { try { hlsInstance.destroy(); } catch(e) {} hlsInstance = null; }
  if (player)      { try { player.destroy();      } catch(e) {} player      = null; }
  const oldVideo = document.getElementById('video');
  oldVideo.replaceWith(oldVideo.cloneNode(false));
  const v = document.getElementById('video');

  const initPlyr = () => {
    player = new Plyr(v, {
      controls: ['play-large','play','progress','current-time','mute','volume','captions','settings'],
      settings: ['speed','loop'],
      clickToPlay: false,          // tap behavior is handled by our own gestures
      fullscreen: { enabled: false }, // fullscreen is our overlay + native rotation
      keyboard: { focused: true, global: false },
    });
    // Floating skip/fullscreen buttons: visible on load, then auto-hide per
    // our own inactivity timer (see showOverlayControls/hideOverlayControls).
    showOverlayControls(true);
    player.on('controlsshown',  () => showOverlayControls());
    player.on('controlshidden', () => hideOverlayControls());
    player.on('play',           () => showOverlayControls());
    player.on('pause',          () => showOverlayControls(true));
    v.ontimeupdate = () => maybePersistPlaybackProgress();
    v.onpause      = () => maybePersistPlaybackProgress(true);
    v.onended      = () => maybePersistPlaybackProgress(true);
  };

  if (type === 'hls' && Hls.isSupported()) {
    hlsInstance = new Hls({ enableWorker: false });
    hlsInstance.loadSource(url);
    hlsInstance.attachMedia(v);
    // Init Plyr only after manifest is ready — fixes intermittent blank player
    hlsInstance.on(Hls.Events.MANIFEST_PARSED, initPlyr);
    hlsInstance.on(Hls.Events.ERROR, (_, d) => {
      if (d.fatal) showPlayerError('Stream error. Try opening in MPV.');
    });
  } else {
    v.src = url;
    initPlyr();
  }
}

//  Transport helpers 
function seekBy(sec) {
  const v = document.getElementById('video');
  if (!v || !isFinite(v.duration) || !v.duration) return;
  v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + sec));
  showSeekIndicator(sec, v.currentTime);
}

function formatTime(t) {
  if (!isFinite(t) || t < 0) t = 0;
  const m = Math.floor(t / 60);
  const s = Math.floor(t % 60);
  return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
}

let seekIndTimer = null;
function showSeekIndicator(sec, time) {
  const el = document.getElementById('seek-indicator');
  if (!el) return;
  const icon = document.getElementById('seek-indicator-icon');
  const timeEl = document.getElementById('seek-indicator-time');
  if (icon) icon.classList.toggle('flip', sec < 0);
  if (timeEl) timeEl.textContent = formatTime(time);
  el.classList.add('show');
  clearTimeout(seekIndTimer);
  seekIndTimer = setTimeout(() => el.classList.remove('show'), 700);
}

//  Overlay control auto-hide (YouTube-style) 
// Buttons show while the user interacts with the video and fade out after
// a few seconds of inactivity. Plyr's controlsshown/controlshidden events
// alone are unreliable in the Android WebView, so a self-managed timer is
// the source of truth (Plyr events are kept as extra hints).
let ovHideTimer = null;
const OV_HIDE_MS = 3000;

function showOverlayControls(keep = false) {
  const wrap = document.getElementById('video-wrap');
  if (!wrap) return;
  wrap.classList.add('controls-visible');
  clearTimeout(ovHideTimer);
  if (!keep) ovHideTimer = setTimeout(hideOverlayControls, OV_HIDE_MS);
}

function hideOverlayControls() {
  const wrap = document.getElementById('video-wrap');
  if (!wrap) return;
  // Never hide while paused — the control bar is up and the user is mid-tap.
  const v = document.getElementById('video');
  if (v && v.paused) return;
  // Stay in sync with Plyr's own bar: only fade once it has actually hidden
  // (Plyr marks the container with .plyr--hide-controls), and re-check until
  // it does. This covers WebViews where Plyr's events never fire.
  const plyrEl = wrap.querySelector('.plyr');
  const barVisible = plyrEl && !plyrEl.classList.contains('plyr--hide-controls');
  if (barVisible) {
    clearTimeout(ovHideTimer);
    ovHideTimer = setTimeout(hideOverlayControls, 1000);
    return;
  }
  wrap.classList.remove('controls-visible');
}

(function initOverlayAutoHide() {
  const wrap = document.getElementById('video-wrap');
  if (!wrap) return;
  wrap.addEventListener('mousemove',  () => showOverlayControls());
  wrap.addEventListener('touchstart', () => showOverlayControls(), { passive: true });
  wrap.addEventListener('click',      () => showOverlayControls());
})();

//  Overlay control buttons (skip ±10s, fullscreen) 
document.addEventListener('click', e => {
  const t = e.target.closest ? e.target.closest('[data-action]') : null;
  if (!t) return;
  // Cancel any pending single-tap toggle so a control press right after a
  // video tap doesn't also flip play/pause unexpectedly.
  clearTimeout(singleTapTimer);
  showOverlayControls();
  const a = t.dataset.action;
  if (a === 'seek-back') seekBy(-10);
  else if (a === 'seek-fwd') seekBy(10);
  else if (a === 'fullscreen') toggleFullscreen();
});

// Tapping anything outside the video also cancels the pending toggle.
document.addEventListener('click', e => {
  if (!(e.target.closest && e.target.closest('#video-wrap'))) clearTimeout(singleTapTimer);
});

//  Tap gestures: single tap toggles play, double tap seeks ±10s 
let isTouchDevice = false;
let lastTapT = 0;
let lastTapX = 0;
let singleTapTimer = null;

function onPlayerTap(e) {
  // Ignore taps on Plyr's controls and our overlay buttons.
  if (e.target.closest && e.target.closest('.plyr__controls, .plyr__control, [data-action]')) return;
  const x = e.clientX != null ? e.clientX : (e.changedTouches ? e.changedTouches[0].clientX : 0);
  const now = Date.now();
  if (now - lastTapT < 300 && Math.abs(x - lastTapX) < 60) {
    // Double tap: seek toward the tapped half of the video
    clearTimeout(singleTapTimer);
    lastTapT = 0;
    const wrap = document.getElementById('video-wrap');
    const rect = wrap.getBoundingClientRect();
    seekBy(x < rect.left + rect.width / 2 ? -10 : 10);
    return;
  }
  lastTapT = now;
  lastTapX = x;
  clearTimeout(singleTapTimer);
  singleTapTimer = setTimeout(() => { if (player) player.togglePlay(); }, 300);
}

(function initPlayerGestures() {
  const wrap = document.getElementById('video-wrap');
  if (!wrap) return;
  wrap.addEventListener('touchend', e => { isTouchDevice = true; onPlayerTap(e); });
  wrap.addEventListener('click', e => { if (!isTouchDevice) onPlayerTap(e); });
})();

//  Fullscreen: CSS overlay + native orientation (auto-flip) 
let fsActive = false;
let fsSensorTimer = null;

function setOrientation(mode) {
  if (window.AndroidApi && typeof window.AndroidApi.setOrientation === 'function') {
    try { window.AndroidApi.setOrientation(mode); } catch (e) {}
  }
}

function enterFullscreen() {
  if (fsActive) return;
  fsActive = true;
  document.body.classList.add('video-fullscreen');
  // Force landscape, then loosen to sensor so rotating back to portrait exits.
  setOrientation('landscape');
  clearTimeout(fsSensorTimer);
  fsSensorTimer = setTimeout(() => { if (fsActive) setOrientation('sensor'); }, 600);
}

function exitFullscreen() {
  if (!fsActive) return;
  fsActive = false;
  document.body.classList.remove('video-fullscreen');
  setOrientation('auto');
  clearTimeout(fsSensorTimer);
}

function toggleFullscreen() {
  if (fsActive) exitFullscreen();
  else enterFullscreen();
}

// Auto-flip: on phones, rotating to landscape enters fullscreen and rotating
// back to portrait leaves it (tablets/desktops use the button only).
const orientationQuery = window.matchMedia('(orientation: landscape)');
const phoneQuery = window.matchMedia('(max-width: 899px)');
function handleOrientationChange() {
  const playerView = document.getElementById('view-player');
  if (!playerView || !playerView.classList.contains('active')) return;
  if (!phoneQuery.matches) return;
  if (orientationQuery.matches) enterFullscreen();
  else exitFullscreen();
}
if (orientationQuery.addEventListener) {
  orientationQuery.addEventListener('change', handleOrientationChange);
} else if (orientationQuery.addListener) {
  orientationQuery.addListener(handleOrientationChange);
}

//  Stop playback: tears down the stream so it stops downloading data 
function stopPlayback() {
  if (hlsInstance) { try { hlsInstance.destroy(); } catch (e) {} hlsInstance = null; }
  if (player)      { try { player.destroy();      } catch (e) {} player      = null; }
  const v = document.getElementById('video');
  try {
    v.pause();
    v.removeAttribute('src');
    v.load(); // aborts any in-flight buffering
  } catch (e) {}
  const err = document.getElementById('player-error');
  if (err) err.style.display = 'none';
  const ind = document.getElementById('seek-indicator');
  if (ind) ind.classList.remove('show');
  clearTimeout(singleTapTimer);
  clearTimeout(seekIndTimer);
  clearTimeout(fsSensorTimer);
  clearTimeout(ovHideTimer);
  const wrap = document.getElementById('video-wrap');
  if (wrap) wrap.classList.remove('controls-visible');
  exitFullscreen();
}

function showPlayerError(msg) {
  const el = document.getElementById('player-error');
  el.textContent = '' + msg;
  el.style.display = 'block';
  showToast(msg, 'error');
}

function playAdjacentEp(dir) {
  const next = S.currentEp.index + dir;
  if (next < 0 || next >= S.episodes.length) return;
  playEpisode(S.episodes[next], next);
}

async function markWatched() {
  if (!S.anime || !S.currentEp) return;
  const video = document.getElementById('video');
  const duration = video.duration || 1;
  await saveProgress(S.currentEp.ep, duration, duration, true);
  showToast('Marked as watched', 'success');
  // Refresh progress dots in the detail grid and the Up next sidebar
  if (document.getElementById('view-detail').classList.contains('active')) {
    await renderEpisodes();
  }
  renderUpNext();
}

async function playInMpv() {
  if (!S.anime || !S.currentEp) return;
  if (!S.mpv.available) {
    showToast('MPV is not installed on this device', 'error');
    return;
  }
  const res = await playInMpvNative(S.anime.id, S.currentEp.ep, S.translation);
  if (res.error) {
    showPlayerError(res.error);
  } else {
    showToast('Launched in MPV', 'success');
  }
}
