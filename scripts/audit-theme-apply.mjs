#!/usr/bin/env node
/**
 * 离线审计:给定一份主题 JSON(自定义导出或内置预设),把布局里用到的 drawable **按换肤层的真实规则**解析一遍,
 * 报出"换了这套主题之后仍然会显示内置色"的组件(即"卡片颜色不对"的候选)。
 *
 * 用法:node scripts/audit-theme-apply.mjs <主题.json> [--verbose]
 *
 * 判定口径(与运行期一致):
 *   1) 布局里 android:background="@drawable/X" / app:hl_layoutBackground="@color/Y" 等引用;
 *   2) X 若登记在 theme_shapes.json → 由 ThemeDrawableFactory 渲染,fill 概念名 → 用主题 JSON 派生;
 *   3) X 未登记 → 走 ThemeDrawables 的旧资源兼容重建(读 drawable XML 里的 @color,替换成派生值);
 *   4) 关键:**引用的概念名必须在派生调色板里存在**,否则运行期会抛异常并静默退回编译期底 —— 这正是
 *      "某块底不跟主题走"最常见的原因。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const RES = path.join(ROOT, 'app', 'src', 'main', 'res');
const SHAPES = path.join(ROOT, 'app', 'src', 'main', 'assets', 'theme', 'radius', 'theme_shapes.json');
const COLORS_XML = path.join(ROOT, 'app', 'build', 'generated', 'theme_colors', 'values', 'theme_colors.xml');

const themeFile = process.argv[2];
const verbose = process.argv.includes('--verbose');
if (!themeFile || !fs.existsSync(themeFile)) {
  console.error('用法: node scripts/audit-theme-apply.mjs <主题.json> [--verbose]');
  process.exit(2);
}
const theme = JSON.parse(fs.readFileSync(themeFile, 'utf8'));

// ---- 1) 复刻 build.gradle#derivePalette(与运行期 ThemePaletteFactory 同口径) ----
function parseColor(v, fallback) {
  if (typeof v !== 'string') return fallback;
  const hex = v.trim().replace('#', '');
  try {
    if (hex.length === 6) return (0xff000000 | parseInt(hex, 16)) >>> 0;
    if (hex.length === 8) return parseInt(hex, 16) >>> 0;
  } catch { /* ignore */ }
  return fallback;
}
const withAlpha = (color, ratio) => {
  const a = Math.round(Math.max(0, Math.min(1, ratio)) * 255);
  return (((a << 24) | (color & 0xffffff)) >>> 0);
};
const hex = (c) => '#' + c.toString(16).toUpperCase().padStart(8, '0');

const c = theme.colors || theme;
const brand = withAlpha(parseColor(c.brand, 0xff1f2937), 1);
const sourceSchema = Number.isFinite(Number(theme.schema))
  ? Number(theme.schema) : (theme.colors ? 3 : 1);
const type = theme.type === 'dark' ? 'dark' : 'bright';
const builtinDir = path.join(ROOT, 'app', 'src', 'main', 'assets', 'theme', 'themes', type);
const builtinTheme = fs.readdirSync(builtinDir)
  .filter((name) => name.endsWith('.json'))
  .map((name) => JSON.parse(fs.readFileSync(path.join(builtinDir, name), 'utf8')))
  .find((candidate) => candidate.default === true);
const builtinConfirmBg = withAlpha(parseColor(builtinTheme?.btn_confirm_bg, brand), 1);
const confirmFallback = sourceSchema <= 3 ? brand : builtinConfirmBg;
const configuredConfirmBg = parseColor(c.btn_confirm_bg, confirmFallback);
const confirmBg = (configuredConfirmBg >>> 24) === 0xff ? configuredConfirmBg : confirmFallback;
const surfaceColor = parseColor(c.bg_surface, 0xffececf4);
const cardAlpha = (typeof c.bg_card_alpha === 'number' ? c.bg_card_alpha : 100) / 100;
const floatAlpha = (typeof c.bg_float_alpha === 'number' ? c.bg_float_alpha : 100) / 100;
const cardBg = withAlpha(surfaceColor, cardAlpha);
const floatBg = withAlpha(surfaceColor, floatAlpha);
const confirmText = parseColor(c.btn_confirm_text, 0xffffffff);
const highlightText = parseColor(c.text_highlight, 0xff1890ff);
const success = parseColor(c.success, 0xff08ca2c);

const palette = {
  bg_body: withAlpha(parseColor(c.bg_body, 0xfffaf8ff), 1),
  bg_surface: cardBg,
  bg_card: cardBg,
  bg_float: floatBg,
  text_main: brand,
  text_sub: withAlpha(brand, 0.6),
  text_hint: withAlpha(brand, 0.4),
  text_main_half: withAlpha(brand, 0.5),
  text_disable: withAlpha(brand, 0.6),
  text_accent: brand,
  text_highlight: highlightText,
  color_highlight: brand,
  select_fill: brand,
  press_overlay: withAlpha(brand, 0.24),
  btn_confirm_bg: confirmBg,
  btn_confirm_text: confirmText,
  btn_cancel_bg: parseColor(c.btn_cancel_bg, 0x661f2937),
  btn_plain_text: brand,
  btn_select_bg: confirmBg,
  btn_select_text: confirmText,
  btn_select_stroke: confirmBg,
  btn_stroke: parseColor(c.btn_cancel_bg, 0x661f2937),
  switch_track_on: success,
  switch_track_off: parseColor(c.switch_track_off, brand),
  switch_thumb: parseColor(c.switch_thumb, 0xffffffff),
  download_active: parseColor(c.download_active, 0xff037aff),
  download_done: success,
};

// ---- 2) 读生成的概念名清单(哪些 @color/X 是"随主题走"的) ----
const conceptColors = new Set();
for (const m of fs.readFileSync(COLORS_XML, 'utf8').matchAll(/<color name="([^"]+)"/g)) conceptColors.add(m[1]);

/** 读 res/values(-night)/colors.xml:别名链(name → "@color/other" 或字面量) */
const aliasMap = new Map();
for (const rel of [['values', 'colors.xml'], ['values-night', 'colors.xml']]) {
  const f = path.join(RES, ...rel);
  if (!fs.existsSync(f)) continue;
  for (const m of fs.readFileSync(f, 'utf8').matchAll(/<color\s+name="([^"]+)"\s*>([^<]*)<\/color>/g)) {
    aliasMap.set(m[1], m[2].trim());
  }
}

/** 一个 @color/名 最终是不是主题概念(顺着别名链走);返回概念名或 null */
function conceptOf(name, depth = 0) {
  if (depth > 8) return null;
  if (conceptColors.has(name)) return name;
  const v = aliasMap.get(name);
  if (!v || !v.startsWith('@color/')) return null;
  return conceptOf(v.slice('@color/'.length).trim(), depth + 1);
}

// ---- 3) 读 theme_shapes.json ----
const shapes = JSON.parse(fs.readFileSync(SHAPES, 'utf8'));

// ---- 4) 收集布局里对 drawable / 颜色 的引用 ----
const layoutDir = path.join(RES, 'layout');
const drawableDir = path.join(RES, 'drawable');
const generatedDir = path.join(ROOT, 'app', 'build', 'generated', 'theme_shapes', 'drawable');

/** drawable 名 → 它(递归)引用的概念名集合 */
const refCache = new Map();
function drawableRefs(name, depth = 0) {
  if (refCache.has(name)) return refCache.get(name);
  if (depth > 6) return { file: '?', names: [], unresolved: [] };
  const candidates = [path.join(drawableDir, name + '.xml'), path.join(generatedDir, name + '.xml')];
  const file = candidates.find((f) => fs.existsSync(f));
  if (!file) return null;
  const text = fs.readFileSync(file, 'utf8');
  const names = new Set();
  const unresolved = new Set();
  for (const m of text.matchAll(/@color\/([A-Za-z0-9_]+)/g)) {
    const concept = conceptOf(m[1]);
    if (concept) names.add(concept);
    else if (/^(bg_|text_|card_|btn_|color_highlight|select_fill|switch_|download_|press_overlay)/.test(m[1])) unresolved.add(m[1]);
  }
  // 递归进被引用的 drawable(如 placeholder_poster 里的 shape 内联项)
  for (const m of text.matchAll(/@drawable\/([A-Za-z0-9_]+)/g)) {
    const sub = drawableRefs(m[1], depth + 1);
    if (sub) sub.names.forEach((n) => names.add(n));
  }
  const out = { file: path.relative(ROOT, file), names: [...names], unresolved: [...unresolved] };
  refCache.set(name, out);
  return out;
}

const problems = [];
const ok = [];
const seen = new Set();
for (const f of fs.readdirSync(layoutDir)) {
  if (!f.endsWith('.xml')) continue;
  const text = fs.readFileSync(path.join(layoutDir, f), 'utf8');
  // background="@drawable/X" 与 hl_layoutBackground="@drawable|@color/X" 都算
  const refs = [];
  for (const m of text.matchAll(/android:background="@drawable\/([A-Za-z0-9_]+)"/g)) refs.push({ kind: 'drawable', name: m[1] });
  for (const m of text.matchAll(/hl_layoutBackground(?:_true)?="@(drawable|color)\/([A-Za-z0-9_]+)"/g)) refs.push({ kind: m[1], name: m[2] });
  for (const m of text.matchAll(/android:background="@color\/([A-Za-z0-9_]+)"/g)) refs.push({ kind: 'color', name: m[1] });
  for (const r of refs) {
    const key = r.kind + '/' + r.name;
    if (seen.has(key)) continue;
    seen.add(key);
    if (r.kind === 'color') {
      const concept = conceptOf(r.name);
      if (concept) {
        const derived = palette[concept];
        if (derived === undefined) problems.push({ where: f, ref: `@color/${r.name}`, why: `概念名 ${concept} 不在派生调色板里` });
        else ok.push({ where: f, ref: `@color/${r.name}`, color: concept + '=' + hex(derived) });
      } else if (/^(bg_|text_|card_)/.test(r.name)) {
        problems.push({ where: f, ref: `@color/${r.name}`, why: '看着像主题色,但没登记成主题概念名(不会跟随)' });
      }
      continue;
    }
    const info = drawableRefs(r.name);
    if (!info) { problems.push({ where: f, ref: `${r.kind}/${r.name}`, why: '找不到这个 drawable(布局引用了不存在的资源?)' }); continue; }
    const registered = Object.prototype.hasOwnProperty.call(shapes, r.name);
    const missing = info.names.filter((n) => palette[n] === undefined);
    const untracked = info.unresolved || [];
    if (missing.length) problems.push({ where: f, ref: `@drawable/${r.name}`, why: `引用的概念名不在派生调色板里:${missing.join(',')}` });
    else if (untracked.length) problems.push({ where: f, ref: `@drawable/${r.name}`, why: `引用了非主题概念名(${untracked.join(',')})—— 不会跟随`, file: info.file });
    else ok.push({
      where: f,
      ref: `@drawable/${r.name}`,
      color: info.names.filter((n) => palette[n] !== undefined).map((n) => n + '=' + hex(palette[n])).join(' ') || '(无主题色)',
      registered,
    });
  }
}

console.log(`主题:${theme.name} (${theme.type})  bg_surface=${hex(surfaceColor)} cardα=${cardAlpha * 100}% floatα=${floatAlpha * 100}% brand=${hex(brand)} btn_confirm_bg=${hex(confirmBg)}`);
console.log(`扫描布局引用: 正常 ${ok.length} 处,可疑 ${problems.length} 处`);
if (problems.length) {
  console.log('\n=== 可疑(会显示内置色 / 不跟随) ===');
  for (const p of problems) console.log(`  [${p.where}] ${p.ref} → ${p.why}${p.file ? '  (' + p.file + ')' : ''}`);
}
if (verbose) {
  console.log('\n=== 明细 ===');
  for (const o of ok) console.log(`  [${o.where}] ${o.ref} → ${o.color ?? ''}${o.registered === false ? ' (未登记,走旧重建路径)' : ''}`);
}
process.exit(problems.length ? 1 : 0);
