#!/usr/bin/env node
/**
 * APK 完整性离线校验(纯 Node,零依赖)。
 *
 * 为什么需要它:真机上出现过这种故障 ——
 * `app/build/intermediates/apk/debug/MBox_v3.5.9_debug.apk` 这个中间产物**结构上是合法 zip,
 * 但少了 res/、resources.arsc 和 AndroidManifest.xml**(只有 149 个条目,正常包 1300+),
 * Android Studio 去读它时抛 `java.io.IOException: Missing AndroidManifest.xml entry`,
 * 而人眼只看到"包在那儿、装不上/改了不生效",很容易误判成"改动没生效"。
 *
 * 用法:
 *   node scripts/check-apk.mjs                       # 校验 app/build/outputs/apk/** 下所有 APK
 *   node scripts/check-apk.mjs <a.apk> [b.apk ...]    # 校验指定包
 *
 * 退出码:0=全部通过;1=有包不完整。可直接接在构建之后当门禁用。
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';

/** 一个可用 APK 必须具备的条目(缺任何一条都装不上/装上去是坏的) */
const REQUIRED = [
  'AndroidManifest.xml',
  'resources.arsc',
];
/** classes*.dex 至少要有一个(多 dex 时是 classes.dex / classes2.dex / …) */
const DEX_PATTERN = /^classes\d*\.dex$/;
/** 条目总数下限:正常包 1300+,给足余量;明显偏小说明打包只跑了一半 */
const MIN_ENTRIES = 200;

/**
 * 读取 zip 的中央目录条目名(只读到名字就够,不解压)。
 * 自己解析是为了零依赖:APK 就是 zip,End Of Central Directory → 中央目录 → 每个条目的文件名。
 */
function listZipEntries(buf) {
  // EOCD 签名 0x06054b50,从尾部往前找(注释最长 65535)
  let eocd = -1;
  const minPos = Math.max(0, buf.length - 65557);
  for (let i = buf.length - 22; i >= minPos; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是 zip(找不到中央目录结尾记录)');
  const total = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const names = [];
  for (let n = 0; n < total; n++) {
    if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) {
      // 中央目录被截断:条目数按已读到的算,交给下面的断言去判不完整
      break;
    }
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    names.push(buf.toString('utf8', off + 46, off + 46 + nameLen));
    off += 46 + nameLen + extraLen + commentLen;
  }
  return { names, declared: total };
}

function checkApk(path) {
  const buf = readFileSync(path);
  let info;
  try {
    info = listZipEntries(buf);
  } catch (th) {
    return { path, ok: false, reason: `无法解析: ${th.message}`, size: buf.length, count: 0 };
  }
  const { names, declared } = info;
  const missing = REQUIRED.filter((r) => !names.includes(r));
  if (!names.some((n) => DEX_PATTERN.test(n))) missing.push('classes*.dex');
  const problems = [];
  if (missing.length) problems.push(`缺少条目: ${missing.join(', ')}`);
  if (names.length < MIN_ENTRIES) problems.push(`条目数过少(${names.length} < ${MIN_ENTRIES}),打包只跑了一半`);
  if (declared !== names.length) problems.push(`中央目录声明 ${declared} 个条目但只读到 ${names.length} 个(文件被截断)`);
  const resCount = names.filter((n) => n.startsWith('res/')).length;
  if (resCount === 0) problems.push('没有 res/ 条目(资源没打进来)');
  return {
    path,
    ok: problems.length === 0,
    reason: problems.join('; '),
    size: buf.length,
    count: names.length,
    resCount,
  };
}

function defaultTargets() {
  const out = [];
  const root = join('app', 'build', 'outputs', 'apk');
  if (!existsSync(root)) return out;
  for (const variant of readdirSync(root)) {
    const dir = join(root, variant);
    if (!statSync(dir).isDirectory()) continue;
    for (const f of readdirSync(dir)) {
      if (f.endsWith('.apk')) out.push(join(dir, f));
    }
  }
  return out;
}

const args = process.argv.slice(2).filter((a) => !a.startsWith('-'));
const targets = args.length ? args : defaultTargets();
if (!targets.length) {
  console.error('没找到要校验的 APK(先构建,或把 apk 路径作为参数传进来)');
  process.exit(1);
}
let failed = 0;
for (const t of targets) {
  const r = checkApk(t);
  if (r.ok) {
    console.log(`OK   ${t}  ${(r.size / 1024 / 1024).toFixed(1)}MB  条目 ${r.count}(res ${r.resCount})`);
  } else {
    failed++;
    console.log(`坏包 ${t}  ${(r.size / 1024 / 1024).toFixed(1)}MB  条目 ${r.count}`);
    console.log(`     ${r.reason}`);
  }
}
if (failed) {
  console.log(`\n${failed} 个包不完整 —— 别安装它;重新构建(必要时 gradlew --stop 后删掉 app/build/intermediates/apk 与 app/build/outputs/apk/<变体>)再打一次`);
  process.exit(1);
}
