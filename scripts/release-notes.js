#!/usr/bin/env node
/**
 * 更新说明解析(唯一入口):doc/release/release-notes.md 按 `## vX.Y.Z` 分节,按 tag 取对应那一节。
 *
 * 用法:
 *   node scripts/release-notes.js get <tag> [--out <文件>]   # 打印(或写出)该版正文
 *   node scripts/release-notes.js list                       # 列出可取的版本(新→旧)
 *
 * 退出码:0 成功;1 取不到该版本;2 用法错误。
 *
 * 取值顺序(为什么这个顺序):
 *   1. doc/release/release-notes.md —— **唯一维护来源**,发版只在这里加一节;
 *   2. doc/release/release-notes-v<tag>.md —— 老的逐版本文件,只在汇总文件缺该版本时兜底
 *      (仓库里仅保留 release-notes-v3.6.11.md 一份,不再新增)。
 *
 * 分节规则:`## v3.6.12`(允许不带 v)独占一行才算版本节;代码围栏(``` / ~~~)内的
 * 同名标题是示例文本,不算分节 —— 否则文件头的写作模板会把正文切断。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const RELEASE_DIR = path.join(ROOT, 'doc', 'release');
const AGG_PATH = path.join(RELEASE_DIR, 'release-notes.md');

/** 版本节标题:必须是 h2 + 纯版本号,避免把正文里的 `## 新增功能` 当成分节 */
const VERSION_HEADING = /^##\s+(?:v)?(\d+(?:\.\d+)+)\s*$/;
const FENCE = /^\s*(?:```|~~~)/;

// 开发者围栏(与 check-release-notes.js / 客户端 ReleaseNotes 同规则:大小写不敏感)
const DEV_FENCE_START = /^\s*<!--\s*(dev|dev-only|internal|内部|开发者)\s*-->\s*$/i;
const DEV_FENCE_END = /^\s*<!--\s*\/\s*(dev|dev-only|internal|内部|开发者)\s*-->\s*$/i;
const DEV_INLINE_PAIR = /<!--\s*(dev|dev-only|internal|内部|开发者)\s*-->[\s\S]*?<!--\s*\/\s*\1\s*-->/gi;

/**
 * 该节除了版本标题,还有没有用户可见内容?
 * 全是 `<!-- dev -->` 内容时不输出孤立版本标题(AGENTS §九:纯开发者内容的版本不占说明条目)。
 */
function hasVisibleBody(lines) {
  let inFence = false;
  for (const raw of lines.slice(1)) {
    const line = raw.replace(DEV_INLINE_PAIR, '');
    if (DEV_FENCE_START.test(line)) { inFence = true; continue; }
    if (DEV_FENCE_END.test(line)) { inFence = false; continue; }
    if (inFence) continue;
    if (line.trim()) return true;
  }
  return false;
}


function normTag(tag) {
  return String(tag || '').trim().replace(/^v/i, '');
}

/** 按版本切分:返回 [{ version, lines }],lines 含标题行本身(不含尾部分隔线) */
function parseSections(text) {
  const out = [];
  let cur = null;
  let fence = false;

  for (const line of text.split(/\r?\n/)) {
    const isFence = FENCE.test(line);
    if (!fence && !isFence) {
      const m = VERSION_HEADING.exec(line);
      if (m) {
        if (cur) out.push(cur);
        cur = { version: m[1], lines: [line] };
        continue;
      }
    }
    if (cur) cur.lines.push(line);
    if (isFence) fence = !fence;
  }
  if (cur) out.push(cur);

  for (const s of out) s.lines = trimSection(s.lines);
  return out;
}

/** 去掉尾部空行与收尾的 `---`(有人习惯在节之间加分隔线,不属于正文) */
function trimSection(lines) {
  const out = lines.slice();
  while (out.length && !out[out.length - 1].trim()) out.pop();
  while (out.length && /^-{3,}$/.test(out[out.length - 1].trim())) {
    out.pop();
    while (out.length && !out[out.length - 1].trim()) out.pop();
  }
  return out;
}

function readAggregate() {
  if (!fs.existsSync(AGG_PATH)) return '';
  return fs.readFileSync(AGG_PATH, 'utf8');
}

/** 取某版本的正文;取不到返回 null */
function resolveBody(tag) {
  const v = normTag(tag);
  if (!v) return null;

  const sec = parseSections(readAggregate()).find(s => s.version === v);
  if (sec) {
    // 纯开发者内容的版本:去掉版本标题,避免正文里留一个用户可见的孤立标题
    const lines = hasVisibleBody(sec.lines) ? sec.lines : sec.lines.slice(1);
    while (lines.length && !lines[0].trim()) lines.shift();
    return { version: v, body: lines.join('\n'), source: 'doc/release/release-notes.md' };
  }

  // 兼容兜底:老的逐版本文件(仓库里只留最新一个,且内容不再维护)
  const legacy = path.join(RELEASE_DIR, `release-notes-v${v}.md`);
  if (fs.existsSync(legacy)) {
    return {
      version: v,
      body: fs.readFileSync(legacy, 'utf8').replace(/\r\n/g, '\n').trim(),
      source: `doc/release/release-notes-v${v}.md`,
    };
  }
  return null;
}

function listVersions() {
  const fromAgg = parseSections(readAggregate()).map(s => s.version);
  const legacy = fs.readdirSync(RELEASE_DIR)
    .map(f => /^release-notes-v(\d+(?:\.\d+)+)\.md$/.exec(f))
    .filter(Boolean)
    .map(m => m[1]);
  return [...new Set([...fromAgg, ...legacy])];
}

function main() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  const positional = [];
  let outFile = null;
  for (let i = 1; i < argv.length; i++) {
    if (argv[i] === '--out') { outFile = argv[++i]; continue; }
    positional.push(argv[i]);
  }

  if (cmd === 'get') {
    const tag = positional[0];
    if (!tag) {
      console.error('用法: node scripts/release-notes.js get <tag> [--out <文件>]');
      process.exit(2);
    }
    const hit = resolveBody(tag);
    if (!hit) {
      console.error(`未找到 ${normTag(tag)} 的更新说明:请在 doc/release/release-notes.md 里新增一节 \`## v${normTag(tag)}\``);
      process.exit(1);
    }
    if (outFile) {
      fs.writeFileSync(outFile, hit.body + '\n', 'utf8');
      console.error(`已写出 ${outFile}(来源 ${hit.source})`);
    } else {
      process.stdout.write(hit.body + '\n');
    }
    return;
  }

  if (cmd === 'list') {
    const all = listVersions();
    if (!all.length) {
      console.error('doc/release/release-notes.md 里没有版本节');
      process.exit(1);
    }
    console.log(all.join('\n'));
    return;
  }

  console.error('用法:');
  console.error('  node scripts/release-notes.js get <tag> [--out <文件>]');
  console.error('  node scripts/release-notes.js list');
  process.exit(2);
}

module.exports = { AGG_PATH, normTag, parseSections, resolveBody, listVersions };

if (require.main === module) main();
