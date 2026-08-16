//  Player: episode playback (Plyr + hls.js), MPV, prev/next, Up next sidebar,
//  AniSkip skip button, stream teardown. Playback is driven by the Plyr
//  control bar — there are no custom tap gestures or overlay buttons.
//  On Android, fullscreen uses Plyr's CSS fallback mode (see initPlayer):
//  the player fills the WebView viewport and the control bar stays in the
//  DOM, so it overlays the video. Java is told via AndroidApi.setFullscreen
//  to hide the system bars and rotate to landscape for the duration.
let player;
let hlsPlayer = null; // active hls.js instance (m3u8 streams only)
let localPlayback = false; // playing a downloaded file, not a live stream
//  Metadata for the download being played (history keying + MPV title) and the
//  loopback URL the file is served from (MPV launch). Cleared when playback
//  stops; history saving uses these instead of S.anime, which may be unset or
//  belong to a different series when playing from Library › Downloads.
let localPlaybackMeta = null; // { animeId, animeTitle, thumbnail, episode }
let localPlaybackUrl = null;  // http://127.0.0.1:PORT/token served by the app
let localPlaybackMime = 'video/mp4'; // container type of the active download

//  Resume: saved-progress pickup when an episode is reopened. Don't offer it
//  for the opening seconds, and treat anything within RESUME_TAIL_SECONDS of
//  the end (or past the 0.9 ratio the episode grid labels "Watched") as done.
const RESUME_MIN_SECONDS = 15;
const RESUME_TAIL_SECONDS = 10;
const RESUME_MAX_RATIO = 0.9;

//  Read the saved position for an episode from watch history. Returns 0 when
//  there is nothing worth resuming (never watched, watched from the start, or
//  effectively finished). Called before startWebPlayer resets the row, so the
//  position survives the overwrite.
async function savedResumePosition(ep) {
  try {
    const historyMap = await getHistoryMap();
    const h = historyMap && historyMap[ep];
    if (!h || !Number.isFinite(h.progress) || !(h.duration > 0)) return 0;
    if (h.progress < RESUME_MIN_SECONDS || h.progress >= h.duration - RESUME_TAIL_SECONDS
        || h.progress / h.duration >= RESUME_MAX_RATIO) return 0;
    return h.progress;
  } catch (e) { return 0; }
}

//  Same lookup as savedResumePosition, but for a downloaded file: watch
//  history is keyed by the download's own anime id, and S.anime may be unset
//  or belong to a different series when playing from Library › Downloads.
async function savedResumePositionFor(animeId, ep) {
  try {
    // Without an anime id the query would match rows from any series (the
    // backend treats an empty anime_id filter as "all") — never guess.
    if (animeId == null || String(animeId).trim() === '') return 0;
    const rows = await api.get('/api/history?anime_id=' + encodeURIComponent(String(animeId))).catch(() => null);
    const h = Array.isArray(rows) ? rows.find(r => String(r.episode) === String(ep)) : null;
    if (!h || !Number.isFinite(h.progress) || !(h.duration > 0)) return 0;
    if (h.progress < RESUME_MIN_SECONDS || h.progress >= h.duration - RESUME_TAIL_SECONDS
        || h.progress / h.duration >= RESUME_MAX_RATIO) return 0;
    return h.progress;
  } catch (e) { return 0; }
}

//  mm:ss for the resume toast (Plyr formats its own clock internally).
function formatTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m + ':' + String(s).padStart(2, '0');
}

// The Plyr quality picker is rebuilt from the actual renditions in the m3u8
// (hls.js levels) instead of a fixed ladder, and starts on the Default Quality
// from Settings — snapping to the closest available rendition when the exact
// height isn't in the manifest. 'auto' lets hls.js pick by bandwidth.

function nearestHeight(heights, target) {
  return heights.reduce((a, b) => Math.abs(b - target) < Math.abs(a - target) ? b : a);
}

// Apply a requested height to hls.js (nearest match when missing) and reflect
// it in the quality menu.
function applyHlsQuality(target) {
  if (!hlsPlayer || !hlsPlayer.levels || !hlsPlayer.levels.length) return;
  const heights = hlsPlayer.levels.map(l => l.height).filter(h => h > 0);
  if (!heights.length) return;
  const h = nearestHeight(heights, target);
  const idx = hlsPlayer.levels.findIndex(l => l.height === h);
  if (idx >= 0) hlsPlayer.currentLevel = idx;
  setQualityMenuState(String(h), h + 'p');
}

// Mark the active entry in the Quality submenu and the settings entry label.
function setQualityMenuState(value, label) {
  if (!player || !player.elements || !player.elements.settings) return;
  const panel = player.elements.settings.panels.quality;
  if (panel) {
    panel.querySelectorAll('[role="menuitemradio"]').forEach(btn => {
      btn.setAttribute('aria-checked', btn.value === String(value) ? 'true' : 'false');
    });
  }
  const valueEl = player.elements.settings.buttons.quality &&
    player.elements.settings.buttons.quality.querySelector('.plyr__menu__value');
  if (valueEl) valueEl.textContent = label;
}

// After a menu pick, step back to the settings home panel like Plyr's own menu
// items do (its createMenuItem handlers navigate back on selection).
function showSettingsHomePanel() {
  if (!player || !player.elements || !player.elements.settings) return;
  const { panels } = player.elements.settings;
  if (!panels || !panels.quality || !panels.home) return;
  panels.quality.hidden = true;
  panels.home.hidden = false;
}

// Rebuild Plyr's Quality submenu from the manifest's renditions (plus Auto),
// then start on the saved Default Quality.
function buildQualityMenu() {
  if (!player || !player.elements || !player.elements.settings) return;
  const panel = player.elements.settings.panels.quality;
  const list = panel && panel.querySelector('[role="menu"]');
  if (!list || !hlsPlayer || !hlsPlayer.levels) return;
  const heights = [...new Set(hlsPlayer.levels.map(l => l.height).filter(h => h > 0))]
    .sort((a, b) => b - a);
  if (!heights.length) {
    // No video renditions (audio-only or unparsed) — drop the Quality entry
    // rather than leaving dead options in the menu.
    if (player.elements.settings.buttons.quality) {
      player.elements.settings.buttons.quality.hidden = true;
    }
    return;
  }

  list.innerHTML = '';
  const addItem = (value, label) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.role = 'menuitemradio';
    btn.className = 'plyr__control';
    btn.value = value;
    btn.dataset.plyr = 'quality';
    btn.setAttribute('aria-checked', 'false');
    const span = document.createElement('span');
    span.textContent = label;
    btn.appendChild(span);
    btn.addEventListener('click', () => {
      if (value === 'auto') {
        if (hlsPlayer) hlsPlayer.currentLevel = -1;
        setQualityMenuState('auto', 'Auto');
      } else {
        applyHlsQuality(parseInt(value, 10));
      }
      showSettingsHomePanel();
    });
    list.appendChild(btn);
  };

  addItem('auto', 'Auto');
  heights.forEach(h => addItem(String(h), h + 'p'));

  // Start on the saved quality (closest rendition if unavailable). Honors
  // the separate stream quality when "Separate stream & download quality"
  // is on.
  const setting = String(streamQuality());
  if (setting === 'auto') {
    if (hlsPlayer) hlsPlayer.currentLevel = -1;
    setQualityMenuState('auto', 'Auto');
  } else {
    applyHlsQuality(parseInt(setting, 10) || 480);
  }
}

async function playEpisode(ep, index) {
  localPlayback = false;
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
  refreshPlayerDownloadButton();

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

//  Play a downloaded file (Library › Downloads › Play). .mp4 plays directly
//  through the native <video> element; .ts goes through hls.js via a tiny
//  VOD playlist served by the app's loopback file server (hls.js demuxes TS).
//  dl (the download entry, when available) carries the series metadata so the
//  playback counts as watch history, resumes from the saved position, and can
//  be handed to MPV.
async function playLocalFile(res, dl) {
  localPlayback = true;
  const episode = dl ? String(dl.episode) : null;
  S.currentEp = { ep: episode, index: 0 };
  localPlaybackMeta = dl ? {
    animeId:    String(dl.animeId != null ? dl.animeId : ''),
    animeTitle: String(dl.animeTitle || res.fileName || 'Downloaded episode'),
    thumbnail:  dl.thumb || '',
    episode,
  } : null;
  localPlaybackUrl = res.url || null;
  localPlaybackMime = res.ext === 'ts' ? 'video/mp2t' : 'video/mp4';
  const name = res.fileName || 'Downloaded file';
  document.getElementById('player-title').innerHTML = '<span id="player-ep-label">' + name + '</span>';
  document.getElementById('player-error').style.display = 'none';
  const avatar = document.getElementById('player-avatar');
  if (avatar) avatar.src = '';
  const ch = document.getElementById('watch-channel-name');
  if (ch) ch.textContent = 'Downloaded episode';
  const sub = document.getElementById('watch-channel-sub');
  if (sub) sub.textContent = res.ext === 'mp4' ? 'Local MP4 file' : 'Local MPEG-TS file';
  document.getElementById('btn-prev').disabled = true;
  document.getElementById('btn-next').disabled = true;
  const upNext = document.getElementById('upnext-count');
  if (upNext) upNext.textContent = '';
  const eps = document.getElementById('eps-list');
  if (eps) eps.innerHTML = '';
  pushView('player');
  // Pick up where the user left off (history is keyed by this download's anime
  // id + episode, which may differ from whatever anime is in S.anime).
  const resumeAt = dl ? await savedResumePositionFor(dl.animeId, dl.episode) : 0;
  loadStream(res.url + (res.ext === 'ts' ? '.m3u8' : ''), res.ext === 'ts' ? 'hls' : 'mp4', null, null, resumeAt);
}

async function startWebPlayer(stream, ep) {
  // proxy URL → web player; raw CDN URL is kept for MPV. The master playlist
  // (when present) goes to hls.js so the quality menu lists every rendition.
  // The embed referer is passed through: some CDNs gate the manifest/segment
  // fetches on it, and the WebView sends no referer of its own.
  // Read the saved position BEFORE the force=true reset below overwrites the
  // history row, so reopening an episode picks up where the user left off.
  const resumeAt = await savedResumePosition(ep);
  loadStream(stream.url, stream.type || null, stream.master || null, stream.referer || null, resumeAt);
  loadSkipTimes(ep); // AniSkip: fire-and-forget (no data → silent)
  // force=true: create the history row immediately instead of waiting for a flush
  saveProgress(ep, 0, 0, true);
}

function loadStream(url, type, master, referer, resumeAt) {
  // Forget the previous episode's save cadence so a resume-seek isn't mistaken
  // for an unchanged position (and skipped) on the first timeupdate.
  progressSaveState = { at: 0, progress: 0 };
  // Tear down the previous instance. destroy() removes Plyr's UI and puts the
  // original <video> element back into #video-wrap ready for the next init.
  if (player) { try { player.destroy(); } catch (e) {} player = null; }
  // Stop the previous hls.js instance so it stops downloading segments.
  if (hlsPlayer) { try { hlsPlayer.destroy(); } catch (e) {} hlsPlayer = null; }
  let v = document.getElementById('video');
  // Defensive: if destroy ever left the DOM in a weird state, rebuild a
  // pristine <video> so every load starts from the same markup. Also clear
  // any leftover .plyr wrapper so a stale wrapper can't stack a second
  // sized box inside #video-wrap.
  if (!v) {
    const wrap = document.getElementById('video-wrap');
    wrap.querySelectorAll('.plyr').forEach(w => w.remove());
    v = document.createElement('video');
    v.id = 'video';
    v.setAttribute('playsinline', '');
    wrap.prepend(v);
  }
  // Drop any leftover src/poster from a previous episode before the new load.
  try { v.removeAttribute('src'); v.removeAttribute('poster'); v.load(); } catch (e) {}

  // Cover art as the poster: the box shows it while the stream loads
  // instead of a blank black rectangle (same image as the channel avatar).
  const poster =
    S.anime?.anilist?.coverImage?.extraLarge ||
    S.anime?.anilist?.coverImage?.large ||
    S.anime?.thumbnail || '';

  const initPlayer = () => {
    player = new Plyr(v, {
      controls: [
        'play-large', 'play', 'progress', 'current-time', 'duration',
        'mute', 'settings', 'fullscreen',
      ],
      settings: ['quality', 'speed'],
      // The Quality menu is rebuilt from the real hls.js renditions once the
      // manifest parses (buildQualityMenu below); this static ladder only
      // scaffolds the settings entry so the menu exists from the start.
      quality: {
        default: 720,
        options: [1080, 720, 480, 360],
        forced: true,
        onChange: quality => applyHlsQuality(quality),
      },
      speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2] },
      poster,
      // Fullscreen toggle enabled; the volume slider is removed (the mute
      // button stays) to keep the control bar compact on phones.
      // Fullscreen runs in Plyr's CSS fallback mode: the JS Fullscreen API
      // targets the .plyr container, which Android WebView ignores (it only
      // honors fullscreen on the <video> element, through the native custom
      // view — which would hide the WebView and its controls). Forcing the
      // fallback keeps the player inside the WebView with the control bar
      // overlaid, and Java hides the bars + rotates via setFullscreen below.
      fullscreen: { fallback: 'force' },
    });
    // Fullscreen enter/exit on Android: Plyr fires these events when the
    // fallback class is toggled. Tell Java to hide the system bars and rotate
    // to landscape for the duration, and restore on exit. The Plyr button
    // state/icon is handled by Plyr itself (class-based, no WebView quirks).
    if (window.AndroidApi && player.elements.container) {
      const setNativeFullscreen = on => {
        try { window.AndroidApi.setFullscreen(!!on); } catch (e) {}
      };
      player.elements.container.addEventListener('enterfullscreen', () => setNativeFullscreen(true));
      player.elements.container.addEventListener('exitfullscreen', () => setNativeFullscreen(false));
    }
    // HLS: Plyr 3.6+ dropped its built-in hls.js support, so m3u8 playback is
    // wired manually (same approach as the official Plyr demo). The master
    // playlist lets hls.js pick the best level automatically; the settings >
    // quality menu is driven by quality.onChange below.
    const isHls = type === 'hls' || /\.m3u8/i.test(url);
    // Non-HLS streams have no renditions to pick from — drop the Quality entry
    // so the menu can't offer options that do nothing.
    if (!isHls && player.elements.settings && player.elements.settings.buttons.quality) {
      player.elements.settings.buttons.quality.hidden = true;
    }
    if (isHls && window.Hls && Hls.isSupported()) {
      // enableWorker:false keeps hls.js deterministic inside the WebView
      // (workers from a file:// page are flaky on some devices); segment
      // fetch is still fully async on the main thread. xhrSetup stamps the
      // embed referer onto every CDN request, mirroring what the Java
      // scraper sends so the CDN doesn't reject the WebView's fetches.
      hlsPlayer = new Hls({
        enableWorker: false,
        xhrSetup: (xhr, hlsUrl) => {
          if (referer) try { xhr.setRequestHeader('Referer', referer); } catch (e) {}
        },
      });
      hlsPlayer.loadSource(master || url);
      hlsPlayer.attachMedia(v);
      // Once the manifest is parsed the renditions are known: rebuild the
      // Quality menu from them and start on the saved Default Quality.
      hlsPlayer.on(Hls.Events.MANIFEST_PARSED, () => buildQualityMenu());
      // Keep the checked entry honest while Auto/ABR picks levels.
      hlsPlayer.on(Hls.Events.LEVEL_SWITCHED, (event, data) => {
        if (hlsPlayer && hlsPlayer.levels && hlsPlayer.levels[data.level]) {
          const h = hlsPlayer.levels[data.level].height;
          if (h > 0) setQualityMenuState(String(h), h + 'p');
        }
      });
      // Surface fatal load failures with a message that explains what happened
      // (Plyr's own 'error' event won't fire for hls.js network errors — hls.js
      // swallows them during retries). CDN blocks are almost always referer/UA
      // gating, so those get an explicit "try MPV" hint.
      hlsPlayer.on(Hls.Events.ERROR, (event, data) => {
        if (data && data.fatal) showPlayerError(hlsFatalMessage(data));
      });
    } else if (isHls) {
      // No hls.js (native HLS browsers like Safari): let the browser play it.
      player.source = { type: 'video', sources: [{ src: url, type: 'application/x-mpegURL' }] };
    } else {
      player.source = { type: 'video', sources: [{ src: url }] }; // plain src: Plyr infers the type
    }
    // Resize the player box to the video's real aspect ratio once known, so
    // there are no letterbox bars and nothing looks stretched or squished.
    // The 16:9 CSS placeholder stays until then, keeping the box sized to the
    // screen width instead of the video's intrinsic pixel size.
    // Pick up where the user left off. Native MP4 exposes its real duration
    // and seekable range at loadedmetadata, so the seek applies there. hls.js
    // (.ts downloads) repositions the media when the source opens — that
    // overrides a JS seek applied before data exists — and its VOD playlist
    // duration is a placeholder that can exceed the real file, so the seek
    // waits until the seekable range actually covers the target (fires on
    // seekablechange/playing) and never seeks past buffered data. Seeking only
    // into reachable territory is what keeps the player alive: a seek into
    // un-buffered space just spins forever with no UI.
    let resumeToastShown = false;
    let resumeHeld = false;
    const seekableEnd = () => (v.seekable && v.seekable.length)
      ? v.seekable.end(v.seekable.length - 1)
      : 0;
    const seekToResume = () => {
      if (resumeHeld || !(resumeAt > 0)) return;
      if (v.currentTime >= resumeAt - 1) { resumeHeld = true; return; }
      if (seekableEnd() < resumeAt - 1) return; // target not buffered yet — wait
      resumeHeld = true;
      try { v.currentTime = resumeAt; } catch (e) {}
    };
    const applyResume = () => {
      if (resumeToastShown || !(resumeAt > 0)) return;
      if (!Number.isFinite(v.duration) || v.duration <= 0) return;
      resumeToastShown = true;
      showToast('Resuming from ' + formatTime(resumeAt), 'info');
      seekToResume(); // only acts when the target is already buffered
    };
    player.on('loadedmetadata', () => {
      const vw = v.videoWidth, vh = v.videoHeight;
      if (vw && vh) document.getElementById('video-wrap').style.aspectRatio = vw + ' / ' + vh;
      applyResume();
    });
    v.addEventListener('durationchange', applyResume);
    v.addEventListener('seekablechange', seekToResume);
    v.addEventListener('playing', seekToResume);
    player.on('error', () => {
      // Plyr only emits 'error' for real failures (hls.js retries recoverable
      // errors internally and they never reach the media element), so every
      // event here is worth surfacing.
      showPlayerError('Stream error. Try opening in MPV.');
    });
    v.ontimeupdate = () => {
      maybePersistPlaybackProgress();
      updateSkipButton(v.currentTime);
    };
    v.onpause      = () => maybePersistPlaybackProgress(true);
    v.onended      = () => maybePersistPlaybackProgress(true);
  };

  if (typeof Plyr !== 'function') {
    showPlayerError('Player library failed to load.');
    return;
  }
  initPlayer();
}

//  Stop playback: tears down the stream so it stops downloading data 
function stopPlayback() {
  // Save the final position BEFORE tearing down: the video element is the only
  // reliable source, and the local-playback metadata must still be set so the
  // row lands under the download's own anime id (not a stale S.anime).
  const v = document.getElementById('video');
  if (v && v.currentTime > 0 && S.currentEp && S.currentEp.ep) {
    try { maybePersistPlaybackProgress(true); } catch (e) {}
  }
  // destroy() pauses and tears down the stream so it stops downloading data.
  if (player) { try { player.destroy(); } catch (e) {} player = null; }
  // Tear down hls.js too, otherwise its segment fetches keep running.
  if (hlsPlayer) { try { hlsPlayer.destroy(); } catch (e) {} hlsPlayer = null; }
  try {
    v.pause();
    v.removeAttribute('src');
    v.load(); // aborts any in-flight buffering
  } catch (e) {}
  const err = document.getElementById('player-error');
  if (err) err.style.display = 'none';
  // Restore the 16:9 CSS placeholder so the next visit shows a proper box
  // while its stream is being fetched.
  const wrap = document.getElementById('video-wrap');
  if (wrap) wrap.style.aspectRatio = '';
  // AniSkip state reset for the next episode; bump the token so any in-flight
  // fetch for the previous episode can't write stale intervals afterwards.
  skipLoadToken++;
  currentSkip = [];
  skipShownIndex = -1;
  const skipBtn = document.getElementById('skip-btn');
  if (skipBtn) skipBtn.hidden = true;
  // Clear the local-playback state last — after the final progress save above.
  localPlayback = false;
  localPlaybackMeta = null;
  localPlaybackUrl = null;
  localPlaybackMime = 'video/mp4';
}

function showPlayerError(msg) {
  const el = document.getElementById('player-error');
  el.textContent = '' + msg;
  el.style.display = 'block';
  showToast(msg, 'error');
}

//  Map an hls.js fatal error to a user-facing message. hls.js retries
//  recoverable errors internally, so everything reaching here is fatal.
//  A 401/403 from the CDN means the referer/UA the WebView sent was rejected
//  (or the stream expired) — MPV is the reliable fallback for those.
function hlsFatalMessage(data) {
  const code = data && data.response ? data.response.code : 0;
  if (data && data.details === Hls.ErrorDetails.MANIFEST_PARSING_ERROR) {
    return 'The stream playlist is invalid — try again or open in MPV.';
  }
  if (data && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
    if (code === 403 || code === 401) {
      return 'The CDN blocked this stream (HTTP ' + code + ') — try opening in MPV.';
    }
    if (code === 404) {
      return 'Stream not found (HTTP 404) — it may have expired. Try again or open in MPV.';
    }
    if (code >= 400) return 'Stream request failed (HTTP ' + code + ') — try opening in MPV.';
    return 'Network error while loading the stream — check your connection or try MPV.';
  }
  if (data && data.type === Hls.ErrorTypes.MEDIA_ERROR) {
    return 'Could not decode this video — try opening in MPV.';
  }
  return 'Stream error. Try opening in MPV.';
}

function playAdjacentEp(dir) {
  if (localPlayback || !S.currentEp) return;
  const next = S.currentEp.index + dir;
  if (next < 0 || next >= S.episodes.length) return;
  playEpisode(S.episodes[next], next);
}

async function markWatched() {
  if (localPlayback || !S.anime || !S.currentEp || !S.currentEp.ep || !S.episodes.length) return;
  const al = S.anime.anilist || {};
  // Set a watched-through boundary: every episode up to (and including) the
  // current one becomes watched, and episodes past it that were previously
  // marked revert to unwatched (one native call, one transaction).
  await api.post('/api/watched', {
    anime_id:    S.anime.id,
    anime_title: al.title?.english || S.anime.title,
    thumbnail:   al.coverImage?.large || S.anime.thumbnail || '',
    episodes:    S.episodes,
    upto_index:  S.currentEp.index,
  }).catch(() => {});
  showToast('Marked through Ep ' + S.currentEp.ep + ' as watched', 'success');
  // Refresh progress dots in the detail grid and the Up next sidebar
  await renderEpisodes();
  renderUpNext();
}

async function playInMpv() {
  if (!S.mpv.available) {
    showToast('MPV is not installed on this device', 'error');
    return;
  }
  // Downloaded files play from the app's loopback file server — hand that URL
  // straight to mpv (app or CLI) instead of scraping a stream.
  if (localPlayback) {
    if (!localPlaybackUrl) { showToast('No local file to open in MPV', 'error'); return; }
    const title = (localPlaybackMeta && localPlaybackMeta.animeTitle ? localPlaybackMeta.animeTitle : 'Downloaded episode')
      + (localPlaybackMeta && localPlaybackMeta.episode ? ' - Episode ' + localPlaybackMeta.episode : '');
    const res = await playInMpvUrlNative(localPlaybackUrl, title, localPlaybackMime);
    if (res.error) showPlayerError(res.error);
    else showToast('Launched in MPV', 'success');
    return;
  }
  if (!S.anime || !S.currentEp || !S.currentEp.ep) return;
  const res = await playInMpvNative(S.anime.id, S.currentEp.ep, S.translation);
  if (res.error) {
    showPlayerError(res.error);
  } else {
    showToast('Launched in MPV', 'success');
  }
}

//  Download the current episode (button next to Mark Watched / Open in MPV).
//  Toggles with state: start when idle, cancel while active, informational
//  toast when already on disk (deleting happens in the Downloads tab or the
//  episode rows).
async function downloadCurrentEpisode() {
  if (localPlayback || !S.anime || !S.currentEp || !S.currentEp.ep) return;
  const title = animeDisplayTitle();
  const state = episodeDownloadState(S.anime.id, title, S.currentEp.ep);
  const dl = findDownload(S.anime.id, S.currentEp.ep);
  if (state === 'done' || (dl && (dl.state === 'done' || dl.state === 'exists'))) {
    showToast('Already downloaded — manage it in Library › Downloads', 'info');
    return;
  }
  if (dl && DOWNLOAD_ACTIVE.includes(dl.state)) {
    cancelDownloadByKey(S.anime.id, S.currentEp.ep);
    return;
  }
  await requestDownload(S.anime.id, title, S.currentEp.ep, S.translation);
}

//  AniSkip: Crunchyroll-style skip button (intro/recap/credits) via the public
//  AniSkip API (https://api.aniskip.com). Best-effort integration: when there
//  is no MAL id or no community data for the episode, playback is unaffected.
const SKIP_TYPES = ['op', 'ed', 'mixed-op', 'mixed-ed', 'recap'];
const SKIP_LABELS = {
  op: 'Skip Intro',
  'mixed-op': 'Skip Mixed Intro',
  recap: 'Skip Recap',
  ed: 'Skip Credits',
  'mixed-ed': 'Skip Mixed Credits',
};
const skipCache = new Map(); // "malId|episode" → intervals (session cache)
let currentSkip = [];        // intervals for the current episode
let skipShownIndex = -1;     // interval currently offered to the user
let skipLoadToken = 0;       // guards against out-of-order episode loads

function malIdForAnime() {
  return S.anime?.anilist?.idMal || S.anime?.jikan_id || 0;
}

// Best display title for the current anime, used as the AniSkip lookup key
// when the anime object lacks a MAL id (see loadSkipTimes).
function animeTitleForAniskip() {
  const al = S.anime?.anilist || {};
  return al.title?.english || al.title?.romaji || S.anime?.title || '';
}

// Normalize the AniSkip API's results array into { type, start, end } intervals.
function normalizeSkipIntervals(results) {
  return Array.isArray(results)
    ? results
        .filter(r => r && r.interval && Number.isFinite(r.interval.startTime) && Number.isFinite(r.interval.endTime))
        .map(r => ({ type: r.skipType || 'op', start: r.interval.startTime, end: r.interval.endTime }))
    : [];
}

async function loadSkipTimes(ep) {
  const token = ++skipLoadToken;
  currentSkip = [];
  skipShownIndex = -1;
  const skipBtn = document.getElementById('skip-btn');
  if (skipBtn) skipBtn.hidden = true;

  if (S.settings.aniskip === 'off') return; // turned off in Settings

  let malId = malIdForAnime();
  let intervals = null;

  // Android: fetch skip times through the native backend. It runs on the app's
  // own Cronet transport (no WebView fetch/CORS variables) and resolves the MAL
  // id from the title when the search-time AniList enrichment missed it (rate
  // limits / cache misses), so skip data works even without anilist.idMal.
  if (window.AndroidApi) {
    try {
      const q = 'title=' + encodeURIComponent(animeTitleForAniskip()) +
        '&episode=' + encodeURIComponent(ep);
      const data = await api.get('/api/aniskip?' + q);
      // Only trust the native path when it actually resolved a MAL id;
      // otherwise fall through to the direct call with the frontend's id.
      if (data && !data.error && data.mal_id) {
        malId = data.mal_id;
        intervals = normalizeSkipIntervals(data.results);
      }
    } catch (e) { /* fall back to the direct API call below */ }
  }

  const key = (malId || '0') + '|' + ep;
  if (skipCache.has(key)) {
    if (token === skipLoadToken) currentSkip = skipCache.get(key);
    return;
  }

  // Dev-mode browsers (no AndroidApi), native failure, or native couldn't find
  // a MAL id: query the public AniSkip API straight from the WebView. Needs a
  // MAL id to do so.
  if (intervals == null) {
    if (!malId) return;
    const num = parseFloat(ep);
    const epNum = Number.isFinite(num) ? num : ep;
    try {
      const params = new URLSearchParams();
      SKIP_TYPES.forEach(t => params.append('types', t));
      params.append('episodeLength', '0');
      const res = await fetch(
        `https://api.aniskip.com/v2/skip-times/${malId}/${encodeURIComponent(epNum)}?${params}`,
        { headers: { Accept: 'application/json' } }
      );
      if (!res.ok) return;
      const data = await res.json();
      intervals = normalizeSkipIntervals(data.results);
    } catch (e) { return; /* offline or blocked — keep playback unaffected */ }
  }

  skipCache.set(key, intervals);
  if (token === skipLoadToken) currentSkip = intervals;
}

// Called on every timeupdate: show the skip pill while the current time is
// inside a known interval, hide it otherwise.
function updateSkipButton(t) {
  const btn = document.getElementById('skip-btn');
  if (!btn) return;
  // Toggled off in Settings while playing → hide the button immediately.
  if (S.settings.aniskip === 'off') {
    skipShownIndex = -1;
    btn.hidden = true;
    return;
  }
  const idx = currentSkip.findIndex(s => t >= s.start && t < s.end);
  if (idx === skipShownIndex) return;
  skipShownIndex = idx;
  if (idx >= 0) {
    document.getElementById('skip-btn-label').textContent =
      SKIP_LABELS[currentSkip[idx].type] || 'Skip';
    btn.hidden = false;
  } else {
    btn.hidden = true;
  }
}

function skipCurrentInterval() {
  const s = currentSkip[skipShownIndex];
  if (!s) return;
  const v = document.getElementById('video');
  // Land just past the interval end so the next timeupdate doesn't re-match it
  // and flash the button back on screen.
  if (v && Number.isFinite(v.duration)) {
    v.currentTime = Math.max(0, Math.min(v.duration, s.end + 0.5));
  }
  const btn = document.getElementById('skip-btn');
  if (btn) btn.hidden = true;
  skipShownIndex = -1;
}

(function initSkipButton() {
  const btn = document.getElementById('skip-btn');
  if (!btn) return;
  btn.addEventListener('click', e => {
    e.stopPropagation();
    skipCurrentInterval();
  });
})();
