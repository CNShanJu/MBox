'use strict';
const $ = id => document.getElementById(id);
const routeUrl = {home:'/',video:'/video.html',cast:'/cast.html',files:'/files.html'};
const byName = new Intl.Collator('zh-CN',{numeric:true,sensitivity:'base'});
let page = location.pathname === '/video.html' ? 'video' : location.pathname === '/cast.html' ? 'cast' : location.pathname === '/files.html' ? 'files' : 'home';
let folder = '';
let videoGroups = [];
let selectedVideoFolder = -1;
let folderVideos = [];
let playingQueue = [];
let currentVideoIndex = -1;
let playbackRevision = 0;
let castHls = null;
let castHlsManifestReady = false;
let castPlaybackStarted = false;
let castRebufferReported = false;
let castPlayGeneration = 0;
let castPlayFailureName = null;
let castHlsFatal = false;
let castStartup = null;
let castRetryCount = 0;
let castRetryTimer = null;
const MAX_CAST_RETRIES = 2;
let playbackPollEpoch = 0;
let playbackPollTimer = null;
let playbackPollPending = null;
let lastPlaybackCommandId = 0;
let pendingCastSeek = null;
let castDesiredPaused = false;
let castSource = null;
let castAudioSignature = '';
let castAudioReports = 0;
let castStateReportingActive = false;
let castStateReportTimer = null;
let castStateSending = false;
let castStatePendingSnapshot = null;
let castEpisodeSignature = '';
let castAbandonedRevision = 0;
let lastPlaybackLeaveRevision = 0;
let noticeTimer;

function castStateSnapshot() {
  if (!playbackRevision) return null;
  const player = $('castVideo');
  const milliseconds = seconds => Number.isFinite(seconds) && seconds > 0
    ? String(Math.round(seconds * 1000)) : '0';
  return {revision:String(playbackRevision),positionMs:milliseconds(player.currentTime),
    durationMs:milliseconds(player.duration),paused:String(!!player.paused),
    lastCommandId:String(lastPlaybackCommandId)};
}
function sendCastStateSnapshot(snapshot) {
  if (!snapshot) return;
  if (castStateSending) { castStatePendingSnapshot = snapshot; return; }
  castStateSending = true;
  fetch('/api/playback/state', {method:'POST',credentials:'same-origin',cache:'no-store',
    headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
    body:new URLSearchParams(snapshot)
  }).catch(() => {}).finally(() => {
    castStateSending = false;
    const pending = castStatePendingSnapshot;
    castStatePendingSnapshot = null;
    if (pending) sendCastStateSnapshot(pending);
  });
}
function stopCastStateReporting() {
  castStateReportingActive = false;
  clearTimeout(castStateReportTimer);
  castStateReportTimer = null;
}
function reportCastState(immediate = false) {
  if (!castStateReportingActive || page !== 'cast' || $('dashboard').hidden || !playbackRevision) return;
  if (immediate) {
    clearTimeout(castStateReportTimer);
    castStateReportTimer = null;
    sendCastStateSnapshot(castStateSnapshot());
  } else if (!castStateReportTimer) {
    castStateReportTimer = setTimeout(() => {
      castStateReportTimer = null;
      if (castStateReportingActive) sendCastStateSnapshot(castStateSnapshot());
    }, 1000);
  }
}
function resetCastView() {
  $('castTitle').textContent = '等待手机推送';
  $('castStatus').textContent = '等待播放';
  $('castMessage').textContent = '在手机播放器设置中选择「推送到电脑播放」。';
  $('castEpisodeCount').textContent = '等待手机推送视频';
  $('castEpisodes').textContent = '';
}
function leaveActiveCast(preferBeacon = false) {
  const revision = playbackRevision;
  if (!revision || revision === lastPlaybackLeaveRevision) return Promise.resolve();
  lastPlaybackLeaveRevision = revision;
  castAbandonedRevision = revision;
  resetPlaybackPolling();
  stopCastStateReporting();
  clearCastPlayer();
  playbackRevision = 0;
  lastPlaybackCommandId = 0;
  castEpisodeSignature = '';
  clearCastRetry();
  resetCastView();
  const body = new URLSearchParams({revision:String(revision)});
  if (preferBeacon && typeof navigator.sendBeacon === 'function') {
    try {
      if (navigator.sendBeacon('/api/playback/leave', body)) return Promise.resolve();
    } catch (_) { /* Fall through to a keepalive request. */ }
  }
  return fetch('/api/playback/leave', {method:'POST',credentials:'same-origin',cache:'no-store',
    keepalive:true,headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
    body}).catch(() => {});
}

function cancelPlaybackPollTimer() {
  clearTimeout(playbackPollTimer);
  playbackPollTimer = null;
}
function resetPlaybackPolling() {
  playbackPollEpoch++;
  cancelPlaybackPollTimer();
  playbackPollPending = null;
}
function schedulePlaybackPoll() {
  if ($('dashboard').hidden || playbackPollPending || playbackPollTimer) return;
  const epoch = playbackPollEpoch;
  const delay = document.hidden ? 30000 : page === 'cast' && playbackRevision ? 2000 : 5000;
  playbackPollTimer = setTimeout(() => {
    playbackPollTimer = null;
    if (epoch === playbackPollEpoch && !$('dashboard').hidden) pollPlayback();
  }, delay);
}

function cancelCastRetryTimer() {
  clearTimeout(castRetryTimer);
  castRetryTimer = null;
}
function clearCastRetry() {
  cancelCastRetryTimer();
  castRetryCount = 0;
}
function scheduleCastRetry() {
  if (!playbackRevision || castRetryTimer || castRetryCount >= MAX_CAST_RETRIES ||
      castPlaybackStarted || page !== 'cast' || $('dashboard').hidden) return;
  const revision = playbackRevision;
  const attempt = ++castRetryCount;
  $('castMessage').textContent += ' 正在重试（' + attempt + '/' + MAX_CAST_RETRIES + '）…';
  castRetryTimer = setTimeout(() => {
    castRetryTimer = null;
    if (!castPlaybackStarted && page === 'cast' && !$('dashboard').hidden && playbackRevision === revision)
      pollPlayback(true, revision);
  }, attempt * 2000);
}

function notice(message) {
  $('notice').textContent = message;
  $('notice').hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => $('notice').hidden = true, 4200);
}
function disconnected() {
  resetPlaybackPolling();
  stopCastStateReporting();
  lastPlaybackCommandId = 0;
  pendingCastSeek = null;
  castEpisodeSignature = '';
  castAbandonedRevision = 0;
  lastPlaybackLeaveRevision = 0;
  $('dashboard').hidden = true;
  $('pairPanel').hidden = false;
  $('pairError').textContent = '';
  $('mainNav').hidden = true;
  $('logoutButton').hidden = true;
  $('connection').textContent = '未连接';
  $('video').pause();
  $('video').removeAttribute('src');
  $('video').load();
  clearCastPlayer();
  playbackRevision = 0;
  clearCastRetry();
}
async function request(path, options, expectedPollEpoch) {
  const response = await fetch(path, {credentials:'same-origin',cache:'no-store',...options});
  if (response.status === 403) {
    if (expectedPollEpoch === undefined || expectedPollEpoch === playbackPollEpoch) disconnected();
    throw new Error('请先输入手机上的配对码');
  }
  if (!response.ok) throw new Error('请求失败：' + response.status);
  return response;
}
async function postForm(path, data) {
  return request(path, {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:new URLSearchParams(data)});
}
function sendPlaybackEvent(event, detail = '') {
  if (!playbackRevision) return;
  const safeDetail = String(detail).replace(/[^A-Za-z0-9_.:-]/g,'').slice(0,96);
  fetch('/api/playback/event', {method:'POST',credentials:'same-origin',cache:'no-store',
    headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
    body:new URLSearchParams({revision:String(playbackRevision),event,detail:safeDetail})
  }).catch(() => {});
}
async function pair(code) {
  const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '电脑';
  const response = await fetch('/api/pair', {method:'POST',credentials:'same-origin',cache:'no-store',
    headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
    body:new URLSearchParams({code,name:(platform + ' 浏览器').slice(0,32)})});
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || '配对失败');
  playbackRevision = 0;
  $('pairCode').value = '';
  await showDashboard();
}
async function showDashboard() {
  resetPlaybackPolling();
  const epoch = playbackPollEpoch;
  $('pairPanel').hidden = true;
  $('dashboard').hidden = false;
  $('mainNav').hidden = false;
  $('logoutButton').hidden = false;
  $('connection').textContent = '已连接服务器';
  await loadTheme(epoch).catch(() => {});
  if (epoch === playbackPollEpoch && !$('dashboard').hidden) await showPage(page);
}
async function showPage(route, incomingState) {
  if (!routeUrl[route]) route = 'home';
  if (page === 'video' && route !== 'video') $('video').pause();
  if (page === 'cast' && route !== 'cast') leaveActiveCast();
  page = route;
  for (const name of Object.keys(routeUrl)) $(name + 'Page').hidden = name !== route;
  for (const link of document.querySelectorAll('a[data-route]')) {
    if (link.closest('.main-nav')) {
      if (link.dataset.route === route) link.setAttribute('aria-current','page');
      else link.removeAttribute('aria-current');
    }
  }
  document.title = 'MBox · ' + ({home:'局域网',video:'视频库',cast:'推送播放',files:'本地文件'}[route]);
  if ($('dashboard').hidden) return;
  if (route === 'home') {
    await loadCatalog().catch(error => notice(error.message));
  } else if (route === 'files') {
    await listFiles(folder).catch(error => notice(error.message));
  } else if (route === 'video') {
    await loadVideoLibrary().catch(error => notice(error.message));
  }
  if (incomingState && route === 'cast') await applyPlaybackState(incomingState, false, playbackPollEpoch);
  else await pollPlayback();
}
function navigate(route, incomingState) {
  if (!routeUrl[route]) return;
  if (location.pathname !== routeUrl[route]) history.pushState({route},'',routeUrl[route]);
  return showPage(route, incomingState);
}
async function loadTheme(epoch = playbackPollEpoch) {
  const theme = await (await request('/api/theme', undefined, epoch)).json();
  if (epoch !== playbackPollEpoch || $('dashboard').hidden) return;
  const colors = theme.colors || {};
  const names = {bg_body:'--bg',bg_surface:'--surface',text_main:'--text',text_sub:'--sub',
    text_highlight:'--accent',btn_select_bg:'--button',btn_select_text:'--button-text',btn_cancel_bg:'--stroke'};
  const saved = {};
  for (const [key, variable] of Object.entries(names)) {
    const color = colors[key] || '';
    let cssColor = '';
    if (/^#[0-9a-fA-F]{6}$/.test(color)) cssColor = color;
    else if (/^#[0-9a-fA-F]{8}$/.test(color)) cssColor = '#' + color.slice(3) + color.slice(1,3);
    if (cssColor) {
      document.documentElement.style.setProperty(variable,cssColor);
      saved[variable] = cssColor;
    }
  }
  document.documentElement.style.colorScheme = theme.dark ? 'dark' : 'light';
  sessionStorage.setItem('mboxTheme',JSON.stringify({colors:saved,dark:!!theme.dark}));
}
function encodedPath(path) { return path.split('/').filter(Boolean).map(encodeURIComponent).join('/'); }
function fileUrl(path) { return '/file/' + encodedPath(path); }
function mediaUrl(id) { return '/api/videos/media?id=' + encodeURIComponent(id); }
function parentPath(path) { return path.split('/').filter(Boolean).slice(0,-1).join('/'); }
function downloadBlob(blob, name) {
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement('a'); anchor.href = href; anchor.download = name; anchor.click();
  setTimeout(() => URL.revokeObjectURL(href), 30000);
}
async function loadCatalog() {
  const catalog = await (await request('/api/lan/catalog')).json();
  const container = $('dataTypes'); container.textContent = '';
  const labels = {subscriptions:'订阅源',live:'直播源',themes:'主题',settings:'我的设置',history:'历史记录'};
  for (const category of catalog.categories || []) {
    if (!labels[category.id]) continue;
    const row = document.createElement('div'); row.className = 'data-item';
    const info = document.createElement('div');
    const title = document.createElement('strong'); title.textContent = labels[category.id];
    const count = document.createElement('small'); count.textContent = category.count + ' 项';
    info.append(title,count); row.append(info);
    const button = document.createElement('button'); button.className = 'ghost'; button.textContent = '下载';
    button.disabled = category.count === 0;
    button.onclick = async () => {
      try { downloadBlob(await (await request('/api/lan/data?category=' + encodeURIComponent(category.id))).blob(),
        'MBox-' + labels[category.id] + '.json'); }
      catch (error) { notice(error.message); }
    };
    row.append(button); container.append(row);
    if (category.id === 'themes') {
      button.hidden = true;
      for (const theme of catalog.themes || []) {
        const item = document.createElement('div'); item.className = 'data-item';
        const name = document.createElement('span'); name.textContent = '↳ ' + theme.name;
        const download = document.createElement('button'); download.className = 'ghost'; download.textContent = '下载主题';
        download.onclick = async () => {
          try { downloadBlob(await (await request('/api/lan/theme?id=' + encodeURIComponent(theme.id))).blob(),
            theme.name + (theme.withImage ? '.zip' : '.json')); }
          catch (error) { notice(error.message); }
        };
        item.append(name,download); container.append(item);
      }
    }
  }
}

function sizeText(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '大小未知';
  const unit = bytes >= 1073741824 ? 'GB' : 'MB';
  return (bytes / (unit === 'GB' ? 1073741824 : 1048576)).toFixed(1) + ' ' + unit;
}
function durationText(milliseconds) {
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) return '时长未知';
  const seconds = Math.floor(milliseconds / 1000);
  const minutes = Math.floor(seconds / 60);
  return minutes >= 60 ? Math.floor(minutes / 60) + ':' + String(minutes % 60).padStart(2,'0') + ':' + String(seconds % 60).padStart(2,'0')
    : minutes + ':' + String(seconds % 60).padStart(2,'0');
}
async function loadVideoLibrary() {
  const data = await (await request('/api/videos')).json();
  const previousGroup = videoGroups[selectedVideoFolder];
  const previous = previousGroup && previousGroup.name;
  videoGroups = (data.folders || []).sort((a,b) => byName.compare(a.name,b.name));
  $('videoCount').textContent = (data.count || 0) + ' 个视频 · ' + videoGroups.length + ' 个文件夹';
  if (!videoGroups.length) {
    selectedVideoFolder = -1; folderVideos = [];
    $('videoFolders').textContent = ''; $('videoList').textContent = '';
    $('videoPath').textContent = '暂无本地视频'; $('videoFolderCount').textContent = '';
    const empty = document.createElement('p'); empty.className = 'muted';
    empty.textContent = 'App「我的 → 本地视频」目前没有视频。';
    $('videoFolders').append(empty);
    return;
  }
  const restored = videoGroups.findIndex(group => group.name === previous);
  selectVideoFolder(restored >= 0 ? restored : 0);
}
function selectVideoFolder(index) {
  const group = videoGroups[index]; if (!group) return;
  selectedVideoFolder = index;
  folderVideos = (group.videos || []).slice().sort((a,b) => byName.compare(a.name,b.name));
  $('videoPath').textContent = group.name;
  $('videoFolderCount').textContent = folderVideos.length + ' 个视频';
  const folders = $('videoFolders'); folders.textContent = '';
  videoGroups.forEach((item,position) => {
    const button = document.createElement('button');
    button.className = 'folder-row' + (position === index ? ' active' : '');
    const name = document.createElement('span'); name.textContent = item.name;
    const count = document.createElement('small'); count.textContent = item.count + ' 个';
    button.append(name,count);
    button.onclick = () => selectVideoFolder(position);
    folders.append(button);
  });
  const list = $('videoList'); list.textContent = '';
  folderVideos.forEach((item,position) => {
    const row = document.createElement('div'); row.className = 'video-item'; row.dataset.id = String(item.id);
    const play = document.createElement('button'); play.className = 'name';
    const title = document.createElement('strong'); title.textContent = '▶ ' + item.name;
    const meta = document.createElement('small'); meta.textContent = sizeText(item.size) + ' · ' + durationText(item.duration);
    play.append(title,meta); play.onclick = () => playLocal(position);
    row.append(play); list.append(row);
  });
  highlightCurrentVideo();
}
function highlightCurrentVideo() {
  const playing = playingQueue[currentVideoIndex];
  for (const row of $('videoList').querySelectorAll('.video-item'))
    row.classList.toggle('active',!!playing && row.dataset.id === String(playing.id));
}
function playLocal(index, queue = folderVideos) {
  const item = queue[index]; if (!item) return;
  playingQueue = queue.slice();
  currentVideoIndex = index;
  $('videoTitle').textContent = item.name;
  $('playbackStatus').textContent = '本地视频';
  $('videoMessage').textContent = '正在从手机读取视频。播放结束后可自动播放当前文件夹下一集。';
  const player = $('video'); player.pause(); player.src = mediaUrl(item.id); player.load();
  player.play().catch(() => { $('videoMessage').textContent = '视频已载入，点击播放器的播放键。'; });
  highlightCurrentVideo();
}
async function pollPlayback(force = false, expectedRevision = null) {
  if ($('dashboard').hidden) return;
  if (force && expectedRevision !== null && playbackRevision !== expectedRevision) return;
  const epoch = playbackPollEpoch;
  if (playbackPollPending) {
    await playbackPollPending;
    if (force && epoch === playbackPollEpoch && page === 'cast' && !castPlaybackStarted
        && (expectedRevision === null || playbackRevision === expectedRevision))
      return pollPlayback(true, expectedRevision);
    return;
  }
  cancelPlaybackPollTimer();
  const pending = (async () => {
    try {
      const state = await (await request('/api/playback', undefined, epoch)).json();
      if (epoch !== playbackPollEpoch || $('dashboard').hidden) return;
      if (page !== 'cast' && state.revision && state.revision !== playbackRevision
          && state.revision !== castAbandonedRevision)
        await navigate('cast', state);
      else await applyPlaybackState(state, force && page === 'cast', epoch);
    } catch (error) {
      if (epoch !== playbackPollEpoch || $('dashboard').hidden) return;
      if (page === 'cast') $('castMessage').textContent = '读取推送状态失败：' + error.message;
      if (force) scheduleCastRetry();
    }
  })();
  playbackPollPending = pending;
  try { await pending; }
  finally {
    if (playbackPollPending === pending) playbackPollPending = null;
    if (epoch === playbackPollEpoch) schedulePlaybackPoll();
  }
}
async function applyPlaybackState(state, force, epoch) {
  if (state.revision && state.revision === castAbandonedRevision) return;
  if (!state.revision) {
    stopCastStateReporting();
    lastPlaybackCommandId = 0;
    pendingCastSeek = null;
    castEpisodeSignature = '';
    castAbandonedRevision = 0;
    lastPlaybackLeaveRevision = 0;
    if (playbackRevision) {
      playbackRevision = 0;
      clearCastRetry();
      clearCastPlayer();
      resetCastView();
    }
    return;
  }
  if (page !== 'cast') return;
  const newRevision = state.revision !== playbackRevision;
  if (newRevision || force) {
    if (newRevision) {
      clearCastRetry();
      lastPlaybackCommandId = 0;
      castEpisodeSignature = '';
      castAbandonedRevision = 0;
      lastPlaybackLeaveRevision = 0;
    }
    playbackRevision = state.revision;
    $('castTitle').textContent = state.title || '手机推送的视频';
    $('castStatus').textContent = '手机推送';
    await playCast(state.url || '', !!state.hls, !!state.nativeVideo, epoch,
      Number(state.positionMs) || 0, !!state.paused);
  }
  syncCastEpisodes(state.episodes, state.selectedIndex);
  applyPlaybackCommands(state.commands, state.command, state.revision, epoch);
}
function clearCastPlayer() {
  stopCastStateReporting();
  clearCastStartupWatch();
  castPlayGeneration++;
  const player = $('castVideo');
  player.pause();
  if (castHls) { castHls.destroy(); castHls = null; }
  castHlsManifestReady = false;
  player.removeAttribute('src');
  player.load();
  castPlaybackStarted = false;
  castRebufferReported = false;
  castPlayFailureName = null;
  castHlsFatal = false;
  pendingCastSeek = null;
  castDesiredPaused = false;
  castSource = null;
  castAudioSignature = '';
  castAudioReports = 0;
  $('castReload').disabled = true;
}
// Audio init segments contain one audio track. Its mdhd timescale is the sample rate used by MSE.
function castAudioSampleRate(bytes) {
  if (!bytes || !bytes.buffer || !Number.isInteger(bytes.byteLength)) return 0;
  try {
    const data = new DataView(bytes.buffer, bytes.byteOffset || 0, bytes.byteLength);
    const find = (start, end, depth) => {
      if (depth > 4) return 0;
      for (let offset = start; offset + 8 <= end;) {
        const rawSize = data.getUint32(offset);
        const size = rawSize === 0 ? end - offset : rawSize;
        if (size < 8 || size > end - offset) return 0;
        const type = String.fromCharCode(data.getUint8(offset + 4), data.getUint8(offset + 5),
          data.getUint8(offset + 6), data.getUint8(offset + 7));
        if (type === 'mdhd') {
          const version = data.getUint8(offset + 8);
          const rateOffset = version === 0 ? 20 : version === 1 ? 28 : -1;
          if (rateOffset < 0 || rateOffset + 4 > size) return 0;
          const rate = data.getUint32(offset + rateOffset);
          return rate >= 8000 && rate <= 384000 ? rate : 0;
        }
        if (type === 'moov' || type === 'trak' || type === 'mdia') {
          const rate = find(offset + 8, offset + size, depth + 1);
          if (rate) return rate;
        }
        offset += size;
      }
      return 0;
    };
    return find(0, data.byteLength, 0);
  } catch (_) { return 0; }
}
function reportCastAudio(track, manifestCodec) {
  if (!track || castAudioReports >= 4) return;
  const codec = String(track.codec || 'unknown').replace(/[^A-Za-z0-9.]/g,'').slice(0,20);
  const declared = String(track.levelCodec || manifestCodec || 'unknown')
    .replace(/[^A-Za-z0-9.]/g,'').slice(0,20);
  const channels = Number(track.metadata && track.metadata.channelCount);
  const signature = 'codec:' + codec + '_declared:' + declared + '_hz:'
    + castAudioSampleRate(track.initSegment) + '_ch:'
    + (Number.isInteger(channels) && channels >= 1 && channels <= 32 ? channels : 0);
  if (signature === castAudioSignature) return;
  castAudioSignature = signature;
  castAudioReports++;
  sendPlaybackEvent('audio_config', signature);
}
async function reloadCastPlayback() {
  const source = castSource;
  if (!source || source.revision !== playbackRevision || page !== 'cast' || $('dashboard').hidden) return;
  const player = $('castVideo');
  const waitingSeek = pendingCastSeek && pendingCastSeek.revision === playbackRevision
    && pendingCastSeek.generation === castPlayGeneration ? pendingCastSeek : null;
  const positionMs = waitingSeek ? waitingSeek.positionMs
    : Number.isFinite(player.currentTime) && player.currentTime > 0
      ? Math.round(player.currentTime * 1000) : 0;
  const paused = player.readyState < 1 && !castPlaybackStarted ? castDesiredPaused : player.paused;
  const rate = Number.isFinite(player.playbackRate) && player.playbackRate > 0
    ? player.playbackRate : 1;
  sendPlaybackEvent('user_reload', 'at:' + positionMs + '_rate:' + Math.round(rate * 1000)
    + '_paused:' + Number(paused));
  clearCastRetry();
  await playCast(source.url, source.hlsHint, source.nativeHint, playbackPollEpoch,
    positionMs, paused, rate);
}
function tryApplyCastSeek() {
  const seek = pendingCastSeek;
  const player = $('castVideo');
  if (!seek || seek.revision !== playbackRevision || seek.generation !== castPlayGeneration ||
      player.readyState < 1 || page !== 'cast' || $('dashboard').hidden) return;
  pendingCastSeek = null;
  try {
    const seconds = seek.positionMs / 1000;
    player.currentTime = Number.isFinite(player.duration) && player.duration > 0
      ? Math.min(seconds, player.duration) : seconds;
    reportCastState(true);
  } catch (_) {
    $('castMessage').textContent = '当前视频尚不能跳转到指定进度。';
  }
}
function applyPlaybackCommand(command, revision, epoch) {
  if (!command || revision !== playbackRevision || page !== 'cast' || $('dashboard').hidden) return;
  const id = Number(command.id);
  if (!Number.isSafeInteger(id) || id <= lastPlaybackCommandId || id <= 0) return;
  if (command.action !== 'play' && command.action !== 'pause' && command.action !== 'seek') return;
  const positionMs = Number(command.positionMs);
  if (command.action === 'seek' && (!Number.isSafeInteger(positionMs) || positionMs < 0)) return;
  lastPlaybackCommandId = id;
  const player = $('castVideo');
  if (command.action === 'seek') {
    pendingCastSeek = {revision,generation:castPlayGeneration,positionMs};
    tryApplyCastSeek();
  } else if (command.action === 'pause') {
    castDesiredPaused = true;
    clearCastStartupWatch();
    player.pause();
    reportCastState(true);
  } else {
    castDesiredPaused = false;
    if (!castHls || castHlsManifestReady || player.readyState >= 1)
      startCastPlayback(player, epoch, castPlayGeneration);
  }
}
function applyPlaybackCommands(commands, legacyCommand, revision, epoch) {
  const ordered = Array.isArray(commands) ? commands.slice() : legacyCommand ? [legacyCommand] : [];
  ordered.sort((left, right) => Number(left && left.id) - Number(right && right.id));
  const acknowledged = lastPlaybackCommandId;
  for (const command of ordered) applyPlaybackCommand(command, revision, epoch);
  if (lastPlaybackCommandId !== acknowledged) reportCastState(true);
}
function renderCastEpisodes(episodes, selectedIndex) {
  const list = $('castEpisodes'); list.textContent = '';
  $('castEpisodeCount').textContent = episodes.length ? episodes.length + ' 集 · 点击切换' : '当前视频没有可选集';
  episodes.forEach((name,index) => {
    const row = document.createElement('div');
    row.className = 'video-item' + (index === selectedIndex ? ' active' : '');
    const button = document.createElement('button'); button.className = 'name';
    const title = document.createElement('strong'); title.textContent = (index === selectedIndex ? '▶ ' : '') + name;
    button.append(title);
    button.onclick = async () => {
      try {
        const result = await (await postForm('/api/playback/select',
          {revision:String(playbackRevision),index:String(index)})).json();
        $('castMessage').textContent = result.accepted ? '正在等待手机解析所选集…' : '切换未成功，请稍后重试。';
      } catch (error) { $('castMessage').textContent = error.message; }
    };
    row.append(button); list.append(row);
  });
}
function syncCastEpisodes(episodes, selectedIndex) {
  const list = Array.isArray(episodes) ? episodes : [];
  const selected = Number.isInteger(selectedIndex) ? selectedIndex : -1;
  const signature = JSON.stringify([list, selected]);
  if (signature === castEpisodeSignature) return;
  castEpisodeSignature = signature;
  renderCastEpisodes(list, selected);
}
function isHlsUrl(url) {
  return /m3u8/i.test(url) || /^\/(?:proxy|api\/cast\/media)(?:\?|$)/i.test(url);
}
function currentCastPlay(epoch, generation) {
  return epoch === playbackPollEpoch && generation === castPlayGeneration
    && page === 'cast' && !$('dashboard').hidden;
}
function clearCastStartupWatch() {
  if (!castStartup) return;
  clearTimeout(castStartup.timer);
  castStartup = null;
}
function startCastStartupWatch(player, epoch, generation) {
  const startup = {epoch, generation, code:'initializing', label:'初始化', rank:0, firstFragment:false,
    timer:null};
  castStartup = startup;
  startup.timer = setTimeout(() => {
    startup.timer = null;
    if (castStartup !== startup || !currentCastPlay(epoch, generation)) return;
    if (castPlaybackStarted || player.readyState >= 2) {
      clearCastStartupWatch();
      return;
    }
    $('castMessage').textContent = '视频仍未准备好（阶段：' + startup.label + '）。';
    sendPlaybackEvent('startup_stalled', startup.code);
  }, 30000);
  return startup;
}
function updateCastStartupStage(startup, rank, code, label) {
  if (startup && castStartup === startup
      && currentCastPlay(startup.epoch, startup.generation) && rank >= startup.rank) {
    startup.rank = rank;
    startup.code = code;
    startup.label = label;
  }
}
function castStartupReady() {
  if (castStartup && currentCastPlay(castStartup.epoch, castStartup.generation)
      && $('castVideo').readyState >= 2) clearCastStartupWatch();
}
function castErrorName(error) {
  const name = error && typeof error.name === 'string' ? error.name : '';
  return /^[A-Za-z][A-Za-z0-9]{0,47}$/.test(name) ? name : 'UnknownError';
}
function reportCastPlayFailure(error, epoch, generation) {
  if (!currentCastPlay(epoch, generation)) return;
  if (castHls && castHlsFatal) return;
  const name = castErrorName(error);
  castPlayFailureName = name;
  clearCastStartupWatch();
  if (name === 'NotAllowedError') {
    $('castMessage').textContent = '浏览器阻止了自动播放，请点击播放器的播放键。';
    sendPlaybackEvent('autoplay_blocked');
  } else {
    $('castMessage').textContent = '视频播放失败（' + name + '）。请检查视频地址与浏览器格式支持。';
    sendPlaybackEvent('media_error', name);
  }
}
function startCastPlayback(player, epoch, generation) {
  if (!castStartup && !castPlaybackStarted && player.readyState < 2)
    startCastStartupWatch(player, epoch, generation);
  try {
    const started = player.play();
    if (started && typeof started.catch === 'function')
      started.catch(error => reportCastPlayFailure(error, epoch, generation));
  } catch (error) {
    reportCastPlayFailure(error, epoch, generation);
  }
}
async function playCast(url, hlsHint, nativeHint, epoch = playbackPollEpoch,
    initialPositionMs = 0, initialPaused = false, initialRate = 1) {
  clearCastPlayer();
  const generation = castPlayGeneration;
  const player = $('castVideo');
  if (!url) { $('castMessage').textContent = '手机没有发送可播放地址。'; return; }
  castSource = {revision:playbackRevision,url,hlsHint,nativeHint};
  $('castReload').disabled = false;
  player.defaultPlaybackRate = initialRate;
  player.playbackRate = initialRate;
  if ('preservesPitch' in player) player.preservesPitch = true;
  castStateReportingActive = true;
  castDesiredPaused = initialPaused;
  if (Number.isSafeInteger(initialPositionMs) && initialPositionMs > 0)
    pendingCastSeek = {revision:playbackRevision,generation,positionMs:initialPositionMs};
  $('castMessage').textContent = '正在载入手机推送的视频…';
  const hlsSource = !nativeHint && (hlsHint || isHlsUrl(url));
  if (hlsSource && window.Hls && Hls.isSupported()) {
    sendPlaybackEvent('playback_engine', 'hlsjs');
    // Give the relay room for short network stalls while limiting long-play memory use.
    const config = {
      backBufferLength: 30,
      maxBufferLength: 60,
      maxMaxBufferLength: 120,
      maxBufferSize: 96 * 1024 * 1024,
      lowLatencyMode: false
    };
    // Load the requested fragment directly instead of starting at zero then seeking after metadata.
    if (Number.isSafeInteger(initialPositionMs) && initialPositionMs > 0)
      config.startPosition = initialPositionMs / 1000;
    const hls = new Hls(config);
    castHls = hls;
    if (!castDesiredPaused) startCastStartupWatch(player, epoch, generation);
    const currentStartup = () => castHls === hls && currentCastPlay(epoch, generation)
      ? castStartup : null;
    let reportedNonfatal = false;
    hls.on(Hls.Events.FRAG_PARSING_INIT_SEGMENT,(_,data) => {
      if (castHls !== hls || !currentCastPlay(epoch, generation)) return;
      const level = hls.levels && data && data.frag && hls.levels[data.frag.level];
      reportCastAudio(data && data.tracks && data.tracks.audio, level && level.audioCodec);
    });
    hls.on(Hls.Events.MANIFEST_PARSED,() => {
      if (castHls !== hls || !currentCastPlay(epoch, generation)) return;
      castHlsManifestReady = true;
      updateCastStartupStage(currentStartup(), 1, 'manifest_parsed', '清单已解析');
      if (!castDesiredPaused) startCastPlayback(player, epoch, generation);
    });
    hls.on(Hls.Events.FRAG_LOADING,() => {
      const startup = currentStartup();
      if (!startup) return;
      if (startup.firstFragment) return;
      startup.firstFragment = true;
      updateCastStartupStage(startup, 2, 'first_fragment_loading', '首片加载');
    });
    hls.on(Hls.Events.FRAG_PARSED,() =>
      updateCastStartupStage(currentStartup(), 3, 'fragment_parsed', '首片已解析'));
    hls.on(Hls.Events.BUFFER_APPENDED,() =>
      updateCastStartupStage(currentStartup(), 4, 'buffer_appended', '视频数据已写入缓冲区'));
    hls.on(Hls.Events.ERROR,(_,data) => {
      if (castHls !== hls || !currentCastPlay(epoch, generation)) return;
      const rawDetail = String(data && data.details || 'unknown');
      const detail = /^[A-Za-z][A-Za-z0-9]{0,79}$/.test(rawDetail) ? rawDetail : 'unknown';
      if (!data || !data.fatal) {
        if (!reportedNonfatal) {
          reportedNonfatal = true;
          sendPlaybackEvent('hls_warning', detail);
        }
        if (!castPlaybackStarted && !castPlayFailureName)
          $('castMessage').textContent = '视频加载遇到问题（' + detail + '），播放器正在继续尝试。';
        return;
      }
      const httpCode = Number(data.response && data.response.code);
      const status = Number.isInteger(httpCode) && httpCode >= 400 && httpCode <= 599
        ? '（HTTP ' + httpCode + '）' : '';
      sendPlaybackEvent('hls_error', detail + status);
      if (/^\/(?:proxy|api\/cast\/media)(?:\?|$)/i.test(url)
          && !hlsHint && detail === 'manifestParsingError') {
        castHls = null; hls.destroy();
        castHlsManifestReady = false;
        updateCastStartupStage(castStartup, 2, 'native_fallback', '浏览器原生播放');
        player.src = url; player.load();
        if (!castDesiredPaused) startCastPlayback(player, epoch, generation);
      } else {
        castHlsFatal = true;
        clearCastStartupWatch();
        $('castMessage').textContent = 'M3U8 加载失败：' + detail + status;
        scheduleCastRetry();
      }
    });
    hls.loadSource(url);
    hls.attachMedia(player);
    return;
  }
  if (hlsSource) {
    if (!player.canPlayType('application/vnd.apple.mpegurl')) {
      $('castMessage').textContent = '此浏览器不支持 M3U8 播放。';
      sendPlaybackEvent('playback_engine', 'unsupported_hls');
      sendPlaybackEvent('unsupported', 'hls');
      return;
    }
    sendPlaybackEvent('playback_engine', 'native_hls');
  } else sendPlaybackEvent('playback_engine', 'native_video');
  player.src = url;
  if (!castDesiredPaused) startCastStartupWatch(player, epoch, generation);
  player.load();
  if (!castDesiredPaused) startCastPlayback(player, epoch, generation);
}
function onVideoEnded() {
  if (!$('autoNext').checked) return;
  if (currentVideoIndex + 1 < playingQueue.length) playLocal(currentVideoIndex + 1, playingQueue);
  else $('videoMessage').textContent = '当前文件夹已经播放完毕。';
}
async function onCastEnded() {
  if (!$('castAutoNext').checked) return;
  try {
    const result = await (await postForm('/api/playback/next',{revision:String(playbackRevision)})).json();
    $('castMessage').textContent = result.accepted ? '正在等待手机解析下一集…' : '当前播放列表没有下一集。';
  } catch (error) { $('castMessage').textContent = error.message; }
}

async function listFiles(path) {
  const data = await (await request(fileUrl(path))).json();
  folder = path; $('folderPath').textContent = '/' + path;
  const list = $('fileList'); list.textContent = '';
  if (!data.files || !data.files.length) {
    const empty = document.createElement('p'); empty.className = 'muted'; empty.textContent = '这里还没有文件'; list.append(empty);
  }
  for (const item of data.files || []) {
    const row = document.createElement('div'); row.className = 'file-item';
    const name = document.createElement('button'); name.className = 'name';
    name.textContent = (item.dir ? '📁 ' : '📄 ') + item.name;
    name.onclick = () => item.dir ? listFiles(item.path).catch(error => notice(error.message))
      : downloadFile(item.path,item.name);
    row.append(name);
    const actions = document.createElement('div'); actions.className = 'actions';
    if (!item.dir) {
      const download = document.createElement('button'); download.className = 'ghost'; download.textContent = '下载';
      download.onclick = () => downloadFile(item.path,item.name); actions.append(download);
    }
    const remove = document.createElement('button'); remove.className = 'ghost danger'; remove.textContent = '删除';
    remove.onclick = () => deleteFile(item.path,!!item.dir); actions.append(remove);
    row.append(actions); list.append(row);
  }
}
function downloadFile(path,name) { const anchor = document.createElement('a'); anchor.href = fileUrl(path); anchor.download = name; anchor.click(); }
async function deleteFile(path,directory) {
  if (!confirm('确定删除“' + path + '”' + (directory ? '及其中所有文件' : '') + '吗？')) return;
  try { await postForm(directory ? '/delFolder' : '/delFile',{path}); await listFiles(folder); notice('已删除'); }
  catch (error) { notice(error.message); }
}

document.addEventListener('click',event => {
  const link = event.target.closest('a[data-route]');
  if (!link || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault(); navigate(link.dataset.route);
});
window.addEventListener('popstate',() => {
  const route = location.pathname === '/video.html' ? 'video' : location.pathname === '/cast.html' ? 'cast' : location.pathname === '/files.html' ? 'files' : 'home';
  showPage(route);
});
$('pairForm').addEventListener('submit',async event => {
  event.preventDefault(); $('pairError').textContent = '';
  try { await pair($('pairCode').value.trim()); }
  catch (error) { $('pairError').textContent = error.message; }
});
$('logoutButton').onclick = async () => {
  if (!confirm('退出与这台 MBox 的连接？下次访问需要重新输入配对码。')) return;
  await leaveActiveCast();
  try { await request('/api/logout',{method:'POST'}); }
  catch (error) { if (!$('dashboard').hidden) notice(error.message); return; }
  disconnected();
};
window.addEventListener('pagehide',() => { leaveActiveCast(true); });
window.addEventListener('beforeunload',() => { leaveActiveCast(true); });
document.addEventListener('keydown',event => {
  if (event.code !== 'Space' || (page !== 'video' && page !== 'cast') || $('dashboard').hidden) return;
  const player = $(page === 'cast' ? 'castVideo' : 'video');
  const active = document.activeElement;
  if (active && active !== document.body && active !== player &&
      (active.matches('button,input,textarea,select,a') || active.isContentEditable)) return;
  event.preventDefault();
  event.stopPropagation();
  if (player.paused) player.play().catch(() => {}); else player.pause();
},true);
$('refreshVideos').onclick = () => loadVideoLibrary().catch(error => notice(error.message));
$('video').addEventListener('ended',onVideoEnded);
$('video').addEventListener('error',() => {
  if ($('video').src) $('videoMessage').textContent = '浏览器无法播放此格式。可换用 MP4 或 WebM 视频。';
});
$('castVideo').addEventListener('ended',onCastEnded);
$('castReload').onclick = reloadCastPlayback;
$('castVideo').addEventListener('ratechange',() => {
  if (castStateReportingActive)
    sendPlaybackEvent('media_rate', 'rate:' + Math.round($('castVideo').playbackRate * 1000)
      + '_pitch:' + Number($('castVideo').preservesPitch !== false));
});
$('castVideo').addEventListener('playing',() => {
  if ($('dashboard').hidden || page !== 'cast' || !playbackRevision) return;
  castPlaybackStarted = true;
  castPlayFailureName = null;
  clearCastStartupWatch();
  cancelCastRetryTimer();
  $('castMessage').textContent = '正在播放手机推送的视频。';
  sendPlaybackEvent('playing');
  reportCastState(true);
});
$('castVideo').addEventListener('play',() => {
  if (castStateReportingActive) castDesiredPaused = false;
  reportCastState(true);
});
$('castVideo').addEventListener('pause',() => {
  if (castStateReportingActive) {
    castDesiredPaused = true;
    clearCastStartupWatch();
  }
  reportCastState(true);
});
$('castVideo').addEventListener('seeking',() => reportCastState(true));
$('castVideo').addEventListener('seeked',() => reportCastState(true));
$('castVideo').addEventListener('timeupdate',() => reportCastState());
$('castVideo').addEventListener('durationchange',() => reportCastState(true));
$('castVideo').addEventListener('waiting',() => {
  if (!castPlaybackStarted || castRebufferReported) return;
  castRebufferReported = true;
  sendPlaybackEvent('rebuffer');
});
$('castVideo').addEventListener('loadedmetadata',() => {
  updateCastStartupStage(castStartup, 5, 'loadedmetadata', '媒体信息已读取');
  tryApplyCastSeek();
  reportCastState(true);
  castStartupReady();
});
$('castVideo').addEventListener('loadeddata',castStartupReady);
$('castVideo').addEventListener('canplay',castStartupReady);
$('castVideo').addEventListener('error',() => {
  if ($('castVideo').hasAttribute('src') && !castHls) {
    clearCastStartupWatch();
    const mediaError = $('castVideo').error;
    const code = String(mediaError ? mediaError.code : 0);
    if (!castPlayFailureName)
      $('castMessage').textContent = '视频解码或读取失败（MediaError ' + code + '）。';
    sendPlaybackEvent('media_error', castPlayFailureName || 'code:' + code);
    scheduleCastRetry();
  }
});
$('refreshFiles').onclick = () => listFiles(folder).catch(error => notice(error.message));
$('upFolder').onclick = () => listFiles(parentPath(folder)).catch(error => notice(error.message));
$('newFolder').onclick = async () => {
  const name = prompt('新文件夹名称'); if (!name) return;
  try { await postForm('/newFolder',{path:folder,name}); await listFiles(folder); notice('已创建文件夹'); }
  catch (error) { notice(error.message); }
};
$('uploadFiles').onchange = async event => {
  const files = Array.from(event.target.files || []); if (!files.length) return;
  if (!confirm('上传 ' + files.length + ' 个文件到 /' + folder + '？')) return;
  const body = new FormData(); body.append('path',folder);
  files.forEach((file,index) => body.append('files-' + index,file));
  try { await request('/upload',{method:'POST',body}); await listFiles(folder); notice('上传完成'); }
  catch (error) { notice(error.message); }
  event.target.value = '';
};
window.mboxPageScriptReady = true;
request('/api/session').then(showDashboard).catch(error => {
  disconnected();
  if (error.message !== '请先输入手机上的配对码') $('pairError').textContent = '连接失败：' + error.message;
});
document.addEventListener('visibilitychange',() => {
  if ($('dashboard').hidden) return;
  cancelPlaybackPollTimer();
  if (document.hidden) schedulePlaybackPoll();
  else {
    loadTheme(playbackPollEpoch).catch(() => {});
    pollPlayback();
  }
});
