#!/usr/bin/env node
/**
 * Release 正文自检(离线,无网络、不碰真机):
 * 按 AGENTS.md「发布与版本纪律」第 2 条,拦截"写进用户可见正文的开发者内容"。
 *
 * 用法:
 *   node scripts/check-release-notes.js <发布正文文件.md>
 *   node scripts/check-release-notes.js --stdin < 正文.md
 *
 * 规则:
 *   - 只检查 <!-- dev --> … <!-- /dev --> 围栏之外的内容(围栏内是留给开发者看的,不检查);
 *   - 命中禁止字样 → 非零退出,并指出行号与命中项,提示挪进围栏或删除;
 *   - 同时检查 commit 短号形态(7~40 位十六进制,且整段像一个号)。
 */
'use strict';

const fs = require('fs');

// 禁止出现在用户可见正文里的字样(与 AGENTS.md 自检清单保持一致)
const FORBIDDEN = [
  'AGENTS', '改进.txt', 'doc/', 'README',
  'chore(', 'refactor(', 'docs(', 'ci(', 'test(', 'fix(', 'feat(', 'perf(',
  '依赖', '模块', '重构', '门禁', '单测', '单元测试', 'Gradle', 'AGP',
  'versionCode', 'versionName', 'apkSize', 'CI', 'PR #', 'merge ',
  'commit', '脚本', '工作流', '签名', '打包',
];

// 围栏:独占一行(允许行首空白);只写起始围栏视为"以下全是内部内容"。
// 注意:围栏词大小写不敏感 —— 客户端 ReleaseNotes.java 用的是 CASE_INSENSITIVE,
// 这里必须一致,否则 `<!-- DEV -->` 客户端会剔除、本脚本却当用户可见内容报错。
const FENCE_START = /^\s*<!--\s*(dev|dev-only|internal|内部|开发者)\s*-->\s*$/i;
const FENCE_END = /^\s*<!--\s*\/\s*(dev|dev-only|internal|内部|开发者)\s*-->\s*$/i;
// 同一行内成对写出(<!-- dev -->x<!-- /dev -->)也算整段内部内容
const INLINE_PAIR = /<!--\s*(dev|dev-only|internal|内部|开发者)\s*-->[\s\S]*?<!--\s*\/\s*\1\s*-->/gi;
const COMMITISH = /\b[0-9a-f]{7,40}\b/;

function visibleLines(text) {
  const out = [];
  let inFence = false;
  text.split(/\r?\n/).forEach((raw, idx) => {
    const line = raw.replace(INLINE_PAIR, '');
    if (FENCE_START.test(line)) { inFence = true; return; }
    if (FENCE_END.test(line)) { inFence = false; return; }
    if (inFence) return;                       // 围栏内:开发者内容,跳过
    out.push({ no: idx + 1, text: line });
  });
  return out;
}

function main() {
  const args = process.argv.slice(2);
  let text;
  if (args[0] === '--stdin') {
    text = fs.readFileSync(0, 'utf8');
  } else if (args[0]) {
    text = fs.readFileSync(args[0], 'utf8');
  } else {
    console.error('用法: node scripts/check-release-notes.js <正文.md> | --stdin');
    process.exit(2);
  }

  const lines = visibleLines(text);
  const hits = [];
  for (const { no, text: line } of lines) {
    if (!line.trim()) continue;
    for (const bad of FORBIDDEN) {
      if (line.includes(bad)) hits.push({ no, bad, line: line.trim() });
    }
    const m = COMMITISH.exec(line);
    if (m && !/^\d+$/.test(m[0])) hits.push({ no, bad: 'commit 短号', line: line.trim() });
  }

  if (hits.length === 0) {
    console.log('OK: 用户可见正文未命中开发者内容("' + lines.length + '" 行已检查)');
    return;
  }
  console.error('FAIL: 用户可见正文里出现开发者内容,请挪进 <!-- dev --> 围栏或删除:');
  for (const h of hits) {
    console.error('  第 ' + h.no + ' 行  命中「' + h.bad + '」  ' + h.line);
  }
  console.error('');
  console.error('用户该看到的是:新增功能 / 功能变更 / 移除停用 / 问题修复 / 体验与性能 / 兼容性注意。');
  process.exit(1);
}

main();
