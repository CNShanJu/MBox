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
let castPlaybackStarted = false;
let castRebufferReported = false;
let noticeTimer;

function notice(message) {
  $('notice').textContent = message;
  $('notice').hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => $('notice').hidden = true, 4200);
}
function disconnected() {
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
}
async function request(path, options) {
  const response = await fetch(path, {credentials:'same-origin',cache:'no-store',...options});
  if (response.status === 403) { disconnected(); throw new Error('请先输入手机上的配对码'); }
  if (!response.ok) throw new Error('请求失败：' + response.status);
  return response;
}
async function postForm(path, data) {
  return request(path, {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:new URLSearchParams(data)});
}
function sendPlaybackEvent(event, detail = '') {
  if (!playbackRevision) return;
  const safeDetail = String(detail).replace(/[^A-Za-z0-9_:-]/g,'').slice(0,48);
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
  $('pairPanel').hidden = true;
  $('dashboard').hidden = false;
  $('mainNav').hidden = false;
  $('logoutButton').hidden = false;
  $('connection').textContent = '已连接服务器';
  await loadTheme().catch(() => {});
  await showPage(page);
}
async function showPage(route) {
  if (!routeUrl[route]) route = 'home';
  if (page === 'video' && route !== 'video') $('video').pause();
  if (page === 'cast' && route !== 'cast') $('castVideo').pause();
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
  } else if (route === 'cast') {
    await pollPlayback();
  }
  if (route !== 'cast') await checkIncoming();
}
function navigate(route) {
  if (!routeUrl[route]) return;
  if (location.pathname !== routeUrl[route]) history.pushState({route},'',routeUrl[route]);
  showPage(route);
}
async function loadTheme() {
  const theme = await (await request('/api/theme')).json();
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
async function pollPlayback() {
  try {
    const state = await (await request('/api/playback')).json();
    if (!state.revision) {
      if (playbackRevision) {
        playbackRevision = 0;
        clearCastPlayer();
        $('castTitle').textContent = '等待手机推送';
        $('castStatus').textContent = '等待播放';
        $('castMessage').textContent = '在手机播放器设置中选择「推送到电脑播放」。';
        $('castEpisodeCount').textContent = '等待手机推送视频';
        $('castEpisodes').textContent = '';
      }
      return;
    }
    if (state.revision === playbackRevision) return;
    playbackRevision = state.revision;
    $('castTitle').textContent = state.title || '手机推送的视频';
    $('castStatus').textContent = '手机推送';
    renderCastEpisodes(state.episodes || [], state.selectedIndex);
    await playCast(state.url || '', !!state.hls, !!state.nativeVideo);
  } catch (error) {
    if (!$('dashboard').hidden) $('castMessage').textContent = '读取推送状态失败：' + error.message;
  }
}
async function checkIncoming() {
  try {
    const state = await (await request('/api/playback')).json();
    if (state.revision && state.revision !== playbackRevision) navigate('cast');
  } catch (_) { /* request 已显示配对状态 */ }
}
function clearCastPlayer() {
  const player = $('castVideo');
  player.pause();
  if (castHls) { castHls.destroy(); castHls = null; }
  player.removeAttribute('src');
  player.load();
  castPlaybackStarted = false;
  castRebufferReported = false;
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
function isHlsUrl(url) {
  return /m3u8/i.test(url) || /^\/(?:proxy|api\/cast\/media)(?:\?|$)/i.test(url);
}
async function playCast(url, hlsHint, nativeHint) {
  clearCastPlayer();
  const player = $('castVideo');
  if (!url) { $('castMessage').textContent = '手机没有发送可播放地址。'; return; }
  $('castMessage').textContent = '正在载入手机推送的视频…';
  if (!nativeHint && (hlsHint || isHlsUrl(url)) && !player.canPlayType('application/vnd.apple.mpegurl')) {
    if (!window.Hls || !Hls.isSupported()) {
      $('castMessage').textContent = '此浏览器不支持 M3U8 播放。';
      sendPlaybackEvent('unsupported','hls');
      return;
    }
    const hls = new Hls(); castHls = hls;
    hls.on(Hls.Events.MANIFEST_PARSED,() => {
      if (castHls !== hls) return;
      player.play().then(() => $('castMessage').textContent = '正在播放手机推送的视频。')
        .catch(error => {
          $('castMessage').textContent = '视频已载入，点击播放器的播放键。';
          if (error.name === 'NotAllowedError') sendPlaybackEvent('autoplay_blocked');
        });
    });
    hls.on(Hls.Events.ERROR,(_,data) => {
      if (castHls !== hls || !data.fatal) return;
      if (/^\/(?:proxy|api\/cast\/media)(?:\?|$)/i.test(url) && !hlsHint) {
        castHls = null; hls.destroy();
        player.src = url; player.load();
        player.play().catch(error => {
          $('castMessage').textContent = '视频已载入，点击播放器的播放键。';
          if (error.name === 'NotAllowedError') sendPlaybackEvent('autoplay_blocked');
        });
      } else {
        $('castMessage').textContent = 'M3U8 加载失败：' + (data.details || '网络或视频格式错误');
        sendPlaybackEvent('hls_error',data.details || 'unknown');
      }
    });
    hls.loadSource(url);
    hls.attachMedia(player);
    return;
  }
  player.src = url; player.load();
  try { await player.play(); $('castMessage').textContent = '正在播放手机推送的视频。'; }
  catch (error) {
    $('castMessage').textContent = '视频已载入，点击播放器的播放键。';
    if (error.name === 'NotAllowedError') sendPlaybackEvent('autoplay_blocked');
  }
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
  try { await request('/api/logout',{method:'POST'}); }
  catch (error) { if (!$('dashboard').hidden) notice(error.message); return; }
  disconnected();
};
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
$('castVideo').addEventListener('playing',() => {
  castPlaybackStarted = true;
  sendPlaybackEvent('playing');
});
$('castVideo').addEventListener('waiting',() => {
  if (!castPlaybackStarted || castRebufferReported) return;
  castRebufferReported = true;
  sendPlaybackEvent('rebuffer');
});
$('castVideo').addEventListener('error',() => {
  if ($('castVideo').hasAttribute('src') && !castHls) {
    $('castMessage').textContent = '浏览器无法播放此视频格式，或播放地址已失效。';
    const mediaError = $('castVideo').error;
    sendPlaybackEvent('media_error',String(mediaError ? mediaError.code : 0));
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
setInterval(() => { if (!$('dashboard').hidden) loadTheme().catch(() => {}); },10000);
setInterval(() => {
  if ($('dashboard').hidden) return;
  if (page === 'cast') pollPlayback(); else checkIncoming();
},2000);
