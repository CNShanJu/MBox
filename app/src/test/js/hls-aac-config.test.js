// Run with: node app/src/test/js/hls-aac-config.test.js
// Check the shipped artifact: UA/manifest guesses must not turn AAC-LC into HE-AAC.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const bundle = fs.readFileSync(path.resolve(__dirname, '../../main/res/raw/hls_js.txt'), 'utf8');
const runtime = vm.createContext({});
vm.runInContext(bundle, runtime);
assert.equal(runtime.Hls.version, '1.7.3');
for (const event of ['MANIFEST_PARSED','FRAG_PARSING_INIT_SEGMENT','FRAG_LOADING',
  'FRAG_PARSED','BUFFER_APPENDED','ERROR']) assert.equal(typeof runtime.Hls.Events[event], 'string');
for (const api of ['on','loadSource','attachMedia','destroy'])
  assert.equal(typeof runtime.Hls.prototype[api], 'function');
assert.equal(typeof runtime.Hls.isSupported, 'function');

// The AAC reader is private. Extract its bounded function without altering the vendored file.
const errorAt = bundle.indexOf('invalid ADTS sampling index:');
assert.ok(errorAt > 0);
const start = bundle.lastIndexOf('function(', errorAt);
const body = bundle.indexOf('{', start);
assert.ok(errorAt - start < 4096, 'AAC reader must stay small and identifiable');
let depth = 0;
let quote = '';
let escaped = false;
let end = -1;
for (let index = body; index < bundle.length; index++) {
  const char = bundle[index];
  if (quote) {
    if (escaped) escaped = false;
    else if (char === '\\') escaped = true;
    else if (char === quote) quote = '';
  } else if (char === '"' || char === "'") quote = char;
  else if (char === '{') depth++;
  else if (char === '}' && --depth === 0) { end = index + 1; break; }
}
assert.ok(end > errorAt && end - start < 4096);
const reader = bundle.slice(start, end);
const logName = reader.match(/([A-Za-z_$][\w$]*)\.log\("manifest codec:/)[1];
const context = vm.createContext({[logName]:{log() {}},navigator:{userAgent:''}});
vm.runInContext('readAudio = (' + reader + ')', context);

function config(objectType, sampleIndex, channels, manifestCodec) {
  const header = new Uint8Array(7);
  header[0] = 0xff;
  header[1] = 0xf1;
  header[2] = ((objectType - 1) << 6) | (sampleIndex << 2) | (channels >> 2);
  header[3] = (channels & 3) << 6;
  const track = context.readAudio({emit() { throw new Error('unexpected AAC parse failure'); }},
    header, 0, manifestCodec);
  return {codec:track.codec,rate:track.samplerate,channels:track.channelCount,config:[...track.config]};
}

for (const ua of ['Chrome/154.0.0.0','Android Chrome/154.0.0.0','Firefox/115.0']) {
  context.navigator.userAgent = ua;
  for (const manifest of [undefined,'mp4a.40.2','mp4a.40.5']) {
    assert.deepEqual(config(2,3,2,manifest),
      {codec:'mp4a.40.2',rate:48000,channels:2,config:[0x11,0x90]});
    assert.deepEqual(config(2,4,2,manifest),
      {codec:'mp4a.40.2',rate:44100,channels:2,config:[0x12,0x10]});
    assert.deepEqual(config(2,7,2,manifest),
      {codec:'mp4a.40.2',rate:22050,channels:2,config:[0x13,0x90]});
    assert.deepEqual(config(1,7,2,manifest),
      {codec:'mp4a.40.5',rate:22050,channels:2,config:[0x2b,0x92,0x08,0]});
  }
}
console.log('hls-aac-config.test.js: passed');
