// Run with: node app/src/test/js/cast-push-retry.test.js
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.resolve(__dirname, '../../main/res/raw/script.js'), 'utf8');

function browserHarness(initialPlayback = null, initialPath = '/cast.html') {
  const elements = new Map();
  const timers = new Map();
  const instances = [];
  const requests = [];
  const events = [];
  const documentListeners = new Map();
  let nextTimer = 1;
  let playbackGate = null;
  let hlsSupported = true;
  let playback = initialPlayback || {
    revision: 1,
    title: '推送测试',
    url: '/api/cast/media?id=1',
    hls: true,
    nativeVideo: false,
    episodes: []
  };

  function element() {
    const attributes = new Set();
    const listeners = new Map();
    return {
      hidden: false,
      textContent: '',
      value: '',
      src: '',
      readyState: 0,
      style: {setProperty() {}},
      classList: {toggle() {}},
      append() {},
      addEventListener(name, listener) { listeners.set(name, listener); },
      emit(name) { listeners.get(name)(); },
      querySelectorAll() { return []; },
      pause() { this.pauseCalls = (this.pauseCalls || 0) + 1; },
      load() {},
      play() { return this.playImpl ? this.playImpl() : Promise.resolve(); },
      canPlayType() { return this.canPlayTypeResult || ''; },
      removeAttribute(name) { attributes.delete(name); if (name === 'src') this.src = ''; },
      hasAttribute(name) { return attributes.has(name); },
      setAttribute(name) { attributes.add(name); }
    };
  }

  class FakeHls {
    static Events = {MANIFEST_PARSED: 'manifest', ERROR: 'error', FRAG_LOADING: 'frag_loading',
      FRAG_PARSED: 'frag_parsed', BUFFER_APPENDED: 'buffer_appended'};
    static isSupported() { return hlsSupported; }
    constructor(config) { this.config = config; this.handlers = new Map(); instances.push(this); }
    on(event, handler) { this.handlers.set(event, handler); }
    loadSource(url) { this.source = url; }
    attachMedia() {}
    destroy() { this.destroyed = true; }
    manifest() { this.handlers.get(FakeHls.Events.MANIFEST_PARSED)(); }
    fragmentLoading() { this.handlers.get(FakeHls.Events.FRAG_LOADING)(); }
    fragmentParsed() { this.handlers.get(FakeHls.Events.FRAG_PARSED)(); }
    bufferAppended() { this.handlers.get(FakeHls.Events.BUFFER_APPENDED)(); }
    warn(details) { this.handlers.get(FakeHls.Events.ERROR)(null, {fatal: false, details}); }
    fail() { this.handlers.get(FakeHls.Events.ERROR)(null, {fatal: true, details: 'manifestLoadError'}); }
  }

  const document = {
    title: '',
    hidden: false,
    documentElement: {style: {setProperty() {}}},
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, element());
      return elements.get(id);
    },
    addEventListener(name, listener) { documentListeners.set(name, listener); },
    emit(name) { documentListeners.get(name)(); },
    querySelectorAll() { return []; },
    createElement() { return element(); }
  };
  const window = {Hls: FakeHls, addEventListener() {}};
  const location = {pathname: initialPath};
  const context = vm.createContext({
    document,
    window,
    Hls: FakeHls,
    location,
    history: {pushState(_state, _title, url) { location.pathname = url; }},
    sessionStorage: {setItem() {}},
    navigator: {},
    URLSearchParams,
    setInterval() { throw new Error('page should not use fixed intervals'); },
    setTimeout(callback, delay) { const id = nextTimer++; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    fetch: async (url, options) => {
      requests.push(url);
      if (url === '/api/playback/event') events.push(Object.fromEntries(options.body));
      const snapshot = {...playback};
      if (url === '/api/playback' && playbackGate) await playbackGate;
      const data = url === '/api/playback' ? snapshot :
        url === '/api/theme' ? {colors: {}} : {paired: true};
      return {ok: true, status: 200, json: async () => data};
    }
  });
  vm.runInContext(script, context, {filename: 'script.js'});

  return {
    context,
    document,
    elements,
    timers,
    instances,
    requests,
    events,
    setPlayback(state) { playback = state; },
    setPlaybackGate(promise) { playbackGate = promise; },
    setHlsSupported(supported) { hlsSupported = supported; },
    requestCount(url) { return requests.filter(request => request === url).length; },
    timerFor(name) {
      const id = vm.runInContext(name, context);
      return id === null ? null : {id, ...timers.get(id)};
    },
    fireTimer(name) {
      const timer = this.timerFor(name);
      assert.ok(timer, name + ' is scheduled');
      timers.delete(timer.id);
      timer.callback();
    },
    async flush() { for (let i = 0; i < 6; i++) await new Promise(resolve => setImmediate(resolve)); },
    async runRetry() {
      this.fireTimer('castRetryTimer');
      await this.flush();
    },
    async runPoll() {
      this.fireTimer('playbackPollTimer');
      await this.flush();
    }
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return {promise, resolve, reject};
}

function namedError(name) {
  const error = new Error(name);
  error.name = name;
  return error;
}

async function testEngineSelection() {
  const both = browserHarness();
  both.elements.get('castVideo').canPlayTypeResult = 'maybe';
  await both.flush();
  assert.equal(both.instances.length, 1,
    'Hls.js wins when both MSE and native HLS claim support');
  assert.equal(both.events.find(event => event.event === 'playback_engine').detail, 'hlsjs');

  const nativeHls = browserHarness();
  nativeHls.setHlsSupported(false);
  nativeHls.elements.get('castVideo').canPlayTypeResult = 'maybe';
  await nativeHls.flush();
  assert.equal(nativeHls.instances.length, 0, 'native HLS is used when MSE is unavailable');
  assert.equal(nativeHls.elements.get('castVideo').src, '/api/cast/media?id=1');
  assert.equal(nativeHls.events.find(event => event.event === 'playback_engine').detail,
    'native_hls');

  const unsupported = browserHarness();
  unsupported.setHlsSupported(false);
  await unsupported.flush();
  assert.equal(unsupported.instances.length, 0);
  assert.match(unsupported.elements.get('castMessage').textContent, /不支持 M3U8/);
  assert.equal(unsupported.events.find(event => event.event === 'playback_engine').detail,
    'unsupported_hls');
  assert.equal(unsupported.timerFor('castStartup && castStartup.timer'), null);

  const nativeVideo = browserHarness({revision: 1, title: 'MP4', url: '/api/cast/media?id=2',
    hls: true, nativeVideo: true, episodes: []});
  nativeVideo.elements.get('castVideo').canPlayTypeResult = 'maybe';
  await nativeVideo.flush();
  assert.equal(nativeVideo.instances.length, 0, 'native video hint bypasses Hls.js');
  assert.equal(nativeVideo.events.find(event => event.event === 'playback_engine').detail,
    'native_video');
}

async function testPlayErrors() {
  const state = {revision: 1, title: '原生视频', url: '/api/cast/media?id=1',
    hls: false, nativeVideo: true, episodes: []};
  const success = browserHarness(state);
  await success.flush();
  assert.match(success.elements.get('castMessage').textContent, /正在载入/,
    'resolved play promise alone does not claim video is playing');
  success.elements.get('castVideo').emit('playing');
  assert.match(success.elements.get('castMessage').textContent, /正在播放/,
    'only the playing event confirms playback');

  const blocked = browserHarness(state);
  blocked.elements.get('castVideo').playImpl = () => Promise.reject(namedError('NotAllowedError'));
  await blocked.flush();
  assert.match(blocked.elements.get('castMessage').textContent, /浏览器阻止了自动播放/);
  assert.equal(blocked.events.find(event => event.event === 'autoplay_blocked').detail, '');
  assert.equal(blocked.events.some(event => event.event === 'media_error'), false);

  const unsupported = browserHarness(state);
  unsupported.elements.get('castVideo').playImpl = () => Promise.reject(namedError('NotSupportedError'));
  await unsupported.flush();
  assert.match(unsupported.elements.get('castMessage').textContent, /NotSupportedError/);
  assert.doesNotMatch(unsupported.elements.get('castMessage').textContent, /已载入/);
  assert.equal(unsupported.events.find(event => event.event === 'media_error').detail, 'NotSupportedError');

  const interrupted = browserHarness(state);
  interrupted.elements.get('castVideo').playImpl = () => Promise.reject(namedError('AbortError'));
  await interrupted.flush();
  assert.match(interrupted.elements.get('castMessage').textContent, /AbortError/);
  assert.equal(interrupted.events.find(event => event.event === 'media_error').detail, 'AbortError');

  const hlsRejected = browserHarness();
  hlsRejected.elements.get('castVideo').playImpl = () => Promise.reject(namedError('NotSupportedError'));
  await hlsRejected.flush();
  hlsRejected.instances[0].manifest();
  await hlsRejected.flush();
  assert.match(hlsRejected.elements.get('castMessage').textContent, /NotSupportedError/);
  assert.equal(hlsRejected.events.find(event => event.event === 'media_error').detail,
    'NotSupportedError');
  const rejectedMessage = hlsRejected.elements.get('castMessage').textContent;
  hlsRejected.instances[0].warn('fragLoadError');
  assert.equal(hlsRejected.elements.get('castMessage').textContent, rejectedMessage,
    'a later nonfatal HLS warning keeps the explicit play rejection visible');
  assert.equal(hlsRejected.events.find(event => event.event === 'hls_warning').detail,
    'fragLoadError');

  const fallback = browserHarness({...state, hls: false, nativeVideo: false});
  fallback.elements.get('castVideo').playImpl = () => Promise.reject(namedError('NotSupportedError'));
  await fallback.flush();
  fallback.instances[0].fail();
  await fallback.flush();
  assert.match(fallback.elements.get('castMessage').textContent, /NotSupportedError/,
    'extensionless HLS fallback reports native play rejection');

  const switched = browserHarness(state);
  const oldPlay = deferred();
  let playCalls = 0;
  switched.elements.get('castVideo').playImpl = () => ++playCalls === 1 ? oldPlay.promise : Promise.resolve();
  await switched.flush();
  await vm.runInContext('playCast("/api/cast/media?id=1", false, true)', switched.context);
  switched.elements.get('castVideo').emit('playing');
  oldPlay.reject(namedError('AbortError'));
  await switched.flush();
  assert.match(switched.elements.get('castMessage').textContent, /正在播放/,
    'old promise from the same push cannot overwrite a new player attempt');
  assert.equal(switched.events.some(event => event.event === 'media_error'), false);

  const disconnected = browserHarness(state);
  const unfinished = deferred();
  disconnected.elements.get('castVideo').playImpl = () => unfinished.promise;
  await disconnected.flush();
  vm.runInContext('disconnected()', disconnected.context);
  unfinished.reject(namedError('NotSupportedError'));
  await disconnected.flush();
  assert.equal(disconnected.events.some(event => event.event === 'media_error'), false,
    'old promise cannot report into a later connection');
}

async function testHlsNonfatalError() {
  const browser = browserHarness();
  await browser.flush();
  const hls = browser.instances[0];
  hls.warn('fragLoadError');
  assert.match(browser.elements.get('castMessage').textContent, /fragLoadError/);
  assert.equal(browser.events.find(event => event.event === 'hls_warning').detail,
    'fragLoadError');
  assert.equal(browser.timerFor('castRetryTimer'), null,
    'nonfatal HLS error does not restart the stream');
  hls.warn('bufferStalledError');
  assert.equal(browser.events.filter(event => event.event === 'hls_warning').length, 1,
    'repeated nonfatal errors do not flood business events');
  hls.manifest();
  await browser.flush();
  assert.doesNotMatch(browser.elements.get('castMessage').textContent, /正在播放/);
  browser.elements.get('castVideo').emit('playing');
  hls.warn('bufferStalledError');
  assert.match(browser.elements.get('castMessage').textContent, /正在播放/,
    'nonfatal rebuffer does not replace the playback status');
  assert.equal(browser.timerFor('castRetryTimer'), null);
}

async function testStartupStall() {
  const native = {revision: 1, title: '原生视频', url: '/api/cast/media?id=1',
    hls: false, nativeVideo: true, episodes: []};
  const browser = browserHarness(native);
  await browser.flush();
  assert.equal(browser.timerFor('castStartup && castStartup.timer').delay, 30000);
  assert.match(browser.elements.get('castMessage').textContent, /正在载入/,
    'resolved play promise still waits for media data');
  browser.fireTimer('castStartup && castStartup.timer');
  assert.match(browser.elements.get('castMessage').textContent,
    /视频仍未准备好（阶段：初始化）/);
  assert.equal(browser.events.find(event => event.event === 'startup_stalled').detail,
    'initializing');
  browser.elements.get('castVideo').readyState = 2;
  browser.elements.get('castVideo').emit('loadeddata');
  assert.equal(browser.timerFor('castStartup && castStartup.timer'), null);
  browser.elements.get('castVideo').emit('playing');
  assert.match(browser.elements.get('castMessage').textContent, /正在播放/,
    'playback may recover after the startup warning without reloading');
  assert.equal(browser.requestCount('/api/playback'), 1);

  const hlsBrowser = browserHarness();
  await hlsBrowser.flush();
  const hls = hlsBrowser.instances[0];
  hls.manifest();
  hls.fragmentLoading();
  hls.fragmentParsed();
  hls.bufferAppended();
  const hlsPlayer = hlsBrowser.elements.get('castVideo');
  hlsPlayer.readyState = 1;
  hlsPlayer.emit('loadedmetadata');
  assert.ok(hlsBrowser.timerFor('castStartup && castStartup.timer'),
    'metadata alone is not enough media data');
  hlsBrowser.fireTimer('castStartup && castStartup.timer');
  assert.match(hlsBrowser.elements.get('castMessage').textContent, /阶段：媒体信息已读取/);
  assert.equal(hlsBrowser.events.find(event => event.event === 'startup_stalled').detail,
    'loadedmetadata');
  assert.equal(hlsBrowser.instances.length, 1, 'stall does not restart HLS');
  hlsPlayer.readyState = 2;
  hlsPlayer.emit('loadeddata');
  hlsPlayer.emit('playing');
  assert.match(hlsBrowser.elements.get('castMessage').textContent, /正在播放/);

  const blocked = browserHarness(native);
  blocked.elements.get('castVideo').playImpl = () => Promise.reject(namedError('NotAllowedError'));
  await blocked.flush();
  blocked.elements.get('castVideo').readyState = 2;
  assert.equal(blocked.timerFor('castStartup && castStartup.timer'), null,
    'definite autoplay rejection cancels the startup watchdog');
  assert.match(blocked.elements.get('castMessage').textContent, /浏览器阻止了自动播放/);
  assert.equal(blocked.events.some(event => event.event === 'startup_stalled'), false,
    'autoplay blocked with media ready is not a startup stall');

  const ready = browserHarness(native);
  await ready.flush();
  ready.elements.get('castVideo').readyState = 2;
  ready.fireTimer('castStartup && castStartup.timer');
  assert.equal(ready.events.some(event => event.event === 'startup_stalled'), false,
    'actual media data prevents a stall even without a playing event');

  const terminal = browserHarness(native);
  const delayedPlay = deferred();
  terminal.elements.get('castVideo').playImpl = () => delayedPlay.promise;
  await terminal.flush();
  const oldAfterError = terminal.timerFor('castStartup && castStartup.timer').callback;
  delayedPlay.reject(namedError('NotSupportedError'));
  await terminal.flush();
  assert.equal(terminal.timerFor('castStartup && castStartup.timer'), null);
  oldAfterError();
  assert.match(terminal.elements.get('castMessage').textContent, /NotSupportedError/,
    'queued watchdog cannot cover a definite playback error');
  assert.equal(terminal.events.some(event => event.event === 'startup_stalled'), false);

  const switched = browserHarness(native);
  await switched.flush();
  const oldAttempt = switched.timerFor('castStartup && castStartup.timer').callback;
  await vm.runInContext('playCast("/api/cast/media?id=1", false, true)', switched.context);
  const currentAttempt = switched.timerFor('castStartup && castStartup.timer');
  oldAttempt();
  assert.equal(switched.events.some(event => event.event === 'startup_stalled'), false,
    'previous player attempt cannot report a stall');
  vm.runInContext('disconnected()', switched.context);
  currentAttempt.callback();
  assert.equal(switched.events.some(event => event.event === 'startup_stalled'), false,
    'watchdog queued before disconnect cannot report into a new epoch');
}

async function testPlaybackPolling() {
  const browser = browserHarness({revision: 0}, '/');
  await browser.flush();
  assert.equal(browser.requestCount('/api/theme'), 1, 'theme loads once on connect');
  assert.equal(browser.requestCount('/api/playback'), 1, 'idle browser checks for incoming pushes');
  assert.equal(browser.timerFor('playbackPollTimer').delay, 5000, 'visible idle poll is every 5 seconds');

  await browser.runPoll();
  assert.equal(browser.requestCount('/api/playback'), 2);
  assert.equal(browser.requestCount('/api/theme'), 1, 'polling does not reload theme');

  browser.document.hidden = true;
  browser.document.emit('visibilitychange');
  assert.equal(browser.timerFor('playbackPollTimer').delay, 30000, 'hidden poll is every 30 seconds');
  await browser.runPoll();
  assert.equal(browser.requestCount('/api/playback'), 3, 'hidden page keeps session alive');
  assert.equal(browser.requestCount('/api/theme'), 1, 'hidden page does not reload theme');

  browser.document.hidden = false;
  browser.document.emit('visibilitychange');
  await browser.flush();
  assert.equal(browser.requestCount('/api/playback'), 4, 'returning to page checks immediately');
  assert.equal(browser.requestCount('/api/theme'), 2, 'returning to page refreshes theme');
  assert.equal(browser.timerFor('playbackPollTimer').delay, 5000);

  browser.setPlayback({revision: 1, title: '新推送', url: '/api/cast/media?id=1',
    hls: true, nativeVideo: false, episodes: []});
  await browser.runPoll();
  assert.equal(browser.requestCount('/api/playback'), 5, 'navigation reuses the incoming state');
  assert.equal(browser.elements.get('castPage').hidden, false, 'new push opens cast page');
  assert.equal(browser.instances.length, 1, 'new push starts browser media');
  assert.equal(browser.timerFor('playbackPollTimer').delay, 2000, 'active cast polls every 2 seconds');

  const overlapping = deferred();
  browser.setPlaybackGate(overlapping.promise);
  browser.fireTimer('playbackPollTimer');
  const parallel = vm.runInContext('pollPlayback()', browser.context);
  browser.document.emit('visibilitychange');
  await browser.flush();
  assert.equal(browser.requestCount('/api/playback'), 6, 'in-flight checks share one request');
  assert.equal(browser.timerFor('playbackPollTimer'), null, 'next poll waits for completion');
  overlapping.resolve();
  await parallel;
  await browser.flush();
  assert.equal(browser.timerFor('playbackPollTimer').delay, 2000);

  const stale = deferred();
  browser.setPlayback({revision: 2, title: '旧响应', url: '/api/cast/media?id=2',
    hls: true, nativeVideo: false, episodes: []});
  browser.setPlaybackGate(stale.promise);
  browser.fireTimer('playbackPollTimer');
  await browser.flush();
  assert.equal(browser.requestCount('/api/playback'), 7);
  vm.runInContext('disconnected()', browser.context);
  assert.equal(browser.timerFor('playbackPollTimer'), null, 'disconnect leaves no poll');
  browser.setPlaybackGate(null);
  browser.setPlayback({revision: 3, title: '重连后推送', url: '/api/cast/media?id=3',
    hls: true, nativeVideo: false, episodes: []});
  await vm.runInContext('showDashboard()', browser.context);
  assert.equal(browser.requestCount('/api/playback'), 8, 'new connection starts its own check');
  assert.equal(browser.elements.get('castTitle').textContent, '重连后推送');
  stale.resolve();
  await browser.flush();
  assert.equal(browser.elements.get('castTitle').textContent, '重连后推送',
    'old response cannot overwrite the new connection');
  assert.equal(browser.instances.length, 2, 'old response does not reload media');

  const scheduled = browser.timerFor('playbackPollTimer').callback;
  vm.runInContext('disconnected()', browser.context);
  scheduled();
  await browser.flush();
  assert.equal(browser.requestCount('/api/playback'), 8, 'queued poll cannot run after disconnect');
  assert.equal(browser.timerFor('playbackPollTimer'), null);
}

async function testRetryDoesNotReloadNewPush() {
  const browser = browserHarness();
  await browser.flush();
  browser.instances[0].fail();
  browser.setPlayback({revision: 2, title: '新推送', url: '/api/cast/media?id=2',
    hls: true, nativeVideo: false, episodes: []});
  const pending = deferred();
  browser.setPlaybackGate(pending.promise);
  browser.fireTimer('playbackPollTimer');
  browser.fireTimer('castRetryTimer');
  await browser.flush();
  assert.equal(browser.requestCount('/api/playback'), 2, 'retry shares the in-flight check');
  pending.resolve();
  await browser.flush();
  assert.equal(browser.requestCount('/api/playback'), 2, 'old retry does not issue another check');
  assert.equal(browser.instances.length, 2, 'old retry does not reload the new push');
  assert.equal(browser.elements.get('castTitle').textContent, '新推送');

  const player = browser.elements.get('castVideo');
  const pauses = player.pauseCalls;
  browser.document.hidden = true;
  browser.document.emit('visibilitychange');
  assert.equal(player.pauseCalls, pauses, 'hiding tab does not pause the media element');
  assert.equal(browser.instances[1].destroyed, undefined, 'hiding tab keeps HLS stream attached');
  assert.equal(browser.timerFor('playbackPollTimer').delay, 30000);
}

async function main() {
  const browser = browserHarness();
  await browser.flush();
  assert.equal(browser.instances.length, 1, 'first push loads in Hls.js');
  assert.equal(browser.instances[0].source, '/api/cast/media?id=1');
  assert.deepEqual({...browser.instances[0].config}, {
    backBufferLength: 30,
    maxBufferLength: 60,
    maxMaxBufferLength: 120,
    maxBufferSize: 96 * 1024 * 1024,
    lowLatencyMode: false
  }, 'browser playback uses the bounded continuous-playback buffer');

  browser.instances[0].fail();
  assert.match(browser.elements.get('castMessage').textContent, /正在重试（1\/2）/);
  await vm.runInContext('pollPlayback()', browser.context);
  assert.equal(browser.instances.length, 1, 'ordinary poll skips unchanged revision');
  await browser.runRetry();
  assert.equal(browser.instances.length, 2, 'retry reloads unchanged revision');
  assert.equal(browser.instances[0].destroyed, true, 'retry releases failed HLS instance');

  browser.instances[1].fail();
  await browser.runRetry();
  assert.equal(browser.instances.length, 3);
  browser.instances[2].fail();
  assert.equal(browser.timerFor('castRetryTimer'), null, 'retry count is bounded');

  browser.setPlayback({revision: 2, title: '下一条推送', url: '/api/cast/media?id=2',
    hls: true, nativeVideo: false, episodes: []});
  await vm.runInContext('pollPlayback()', browser.context);
  assert.equal(browser.instances.length, 4, 'new push loads');
  browser.instances[3].fail();
  assert.ok(browser.timerFor('castRetryTimer'), 'new revision gets a fresh retry budget');
  assert.match(browser.elements.get('castMessage').textContent, /正在重试（1\/2）/);

  const queuedRetry = browser.timerFor('castRetryTimer').callback;
  browser.elements.get('castVideo').emit('playing');
  assert.equal(browser.timerFor('castRetryTimer'), null, 'successful playback cancels pending retry');
  assert.equal(vm.runInContext('castRetryCount', browser.context), 1, 'playing does not reset retry budget');
  queuedRetry();
  await browser.flush();
  assert.equal(browser.instances.length, 4, 'already queued retry does not interrupt playback');

  browser.setPlayback({revision: 3, title: '第三条推送', url: '/api/cast/media?id=3',
    hls: true, nativeVideo: false, episodes: []});
  await vm.runInContext('pollPlayback()', browser.context);
  browser.instances[4].fail();
  assert.ok(browser.timerFor('castRetryTimer'), 'later push can still retry');

  vm.runInContext('disconnected()', browser.context);
  assert.equal(browser.timerFor('castRetryTimer'), null, 'disconnect cancels pending retry');
  assert.equal(browser.timerFor('playbackPollTimer'), null, 'disconnect cancels polling');
  await testEngineSelection();
  await testPlayErrors();
  await testHlsNonfatalError();
  await testStartupStall();
  await testPlaybackPolling();
  await testRetryDoesNotReloadNewPush();
  console.log('cast-push-retry.test.js: passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
