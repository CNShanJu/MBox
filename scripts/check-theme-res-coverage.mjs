#!/usr/bin/env node
/**
 * 离线校验:自定义主题(运行时换肤)的"覆盖面"自检。
 *
 * 背景:主题颜色在编译期被烤进资源表,运行时只能覆盖"换肤层认得的东西":
 *   1) 颜色资源 —— 必须登记在 `ThemeColorAliases`(颜色 id → 调色板概念名);
 *   2) 布局属性 —— 必须是 `ThemeInflaterFactory` 处理过的属性名
 *      (android:textColor / app:tint / android:background ...);
 *   3) drawable / 色值选择器 —— 由 ThemeDrawables 按原 XML 重建,不需要登记;
 *   4) 单色矢量图标 —— 由 tint 覆盖,不需要登记。
 *
 * 漏了 1 或 2 的症状是"自定义主题下,某一处颜色没变"(不会有任何报错),所以在 CI/提交前扫一遍:
 *   - 颜色资源漏登记:**报错**(退出码 1),必须补表;
 *   - 属性名不认识:先列出来(第三方控件属性走反射 setter 兜底,能兜住就不算错),
 *     只有连反射都兜不住的才需要人工看一眼 —— 因此这里只报"未处理属性"的数量与名字,不直接失败。
 *
 * 用法:node scripts/check-theme-res-coverage.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(process.cwd());
const RES = path.join(ROOT, 'app', 'src', 'main', 'res');
const ALIAS_JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'github', 'tvbox', 'osc', 'theme', 'ThemeColorAliases.java');
const FACTORY_JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'github', 'tvbox', 'osc', 'theme', 'ThemeInflaterFactory.java');
const GEN_COLORS = path.join(ROOT, 'app', 'build', 'generated', 'theme_colors', 'values', 'theme_colors.xml');

/** 派生出来的主题资源名(= 调色板里的概念名)。没有生成文件时退到硬编码清单(与 build.gradle 的派生表一致) */
function paletteNames() {
  if (fs.existsSync(GEN_COLORS)) {
    const xml = fs.readFileSync(GEN_COLORS, 'utf8');
    return [...xml.matchAll(/<color name="([^"]+)"/g)].map((m) => m[1]);
  }
  return [
    'bg_body', 'bg_surface', 'bg_card', 'bg_float',
    'text_main', 'text_sub', 'text_hint', 'text_disable', 'text_accent', 'text_highlight',
    'color_highlight', 'select_fill', 'btn_confirm_bg', 'btn_confirm_text', 'btn_cancel_bg', 'btn_cancel_text',
    'btn_plain_text', 'btn_select_bg', 'btn_select_text', 'btn_stroke',
    'switch_track_on', 'switch_track_off', 'switch_thumb',
    'download_active', 'download_done',
    // text_danger / swipe_red / swipe_red_text 已固定成 res 里的字面量,不再是主题概念
  ];
}

/** 读 colors.xml:name → 原始值 */
function readColors(file) {
  if (!fs.existsSync(file)) return new Map();
  const xml = fs.readFileSync(file, 'utf8');
  const map = new Map();
  for (const m of xml.matchAll(/<color\s+name="([^"]+)"\s*>([^<]*)<\/color>/g)) {
    map.set(m[1], m[2].trim());
  }
  return map;
}

/** 顺着 @color/x 引用找到底;返回最终名字(字面量返回 null) */
function resolve(colors, name, depth = 0) {
  if (depth > 8) return null;
  const v = colors.get(name);
  if (!v) return null;
  if (!v.startsWith('@color/')) return null;
  const next = v.slice('@color/'.length).trim();
  return colors.has(next) ? resolve(colors, next, depth + 1) : next;
}

/** ThemeColorAliases 里登记过哪些颜色名(解析 put(map, R.color.X, "name") 行) */
function registeredAliases() {
  const java = fs.readFileSync(ALIAS_JAVA, 'utf8');
  const names = new Set();
  for (const m of java.matchAll(/put\(map,\s*R\.color\.([A-Za-z0-9_]+)\s*,\s*"([^"]+)"\)/g)) {
    names.add(m[1]);
  }
  return names;
}

/** ThemeInflaterFactory 处理过哪些属性名(解析 case "xxx": 以及属性 switch 的标签) */
function handledAttributes() {
  const java = fs.readFileSync(FACTORY_JAVA, 'utf8');
  const names = new Set();
  for (const m of java.matchAll(/case\s+"([A-Za-z0-9_]+)"\s*:/g)) names.add(m[1]);
  return names;
}

/** 逐个文件列 res 下所有 XML */
function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.xml')) out.push(p);
  }
  return out;
}

const palette = new Set(paletteNames());
const colors = new Map([...readColors(path.join(RES, 'values', 'colors.xml')),
  ...readColors(path.join(RES, 'values-night', 'colors.xml'))]);
const registered = registeredAliases();
const handled = handledAttributes();

// 1) 颜色资源覆盖:所有"最终指向主题概念"的颜色名都要登记
const missingColors = [];
const themedNames = new Set();
for (const name of colors.keys()) {
  const target = resolve(colors, name);
  if (!target || !palette.has(target)) continue;
  themedNames.add(name);
  if (!registered.has(name)) missingColors.push(`${name} → ${target}`);
}
// 概念名本身也应当登记
for (const name of palette) {
  themedNames.add(name);
  if (!registered.has(name)) missingColors.push(name);
}

// 2) 属性覆盖:布局里用到主题色的属性名,看换肤层认不认识
//    只扫 layout/menu:drawable 内部的色值由 ThemeDrawables 按原 XML 重建,不靠属性注入;
//    values/*.xml 里没有"视图属性"这回事。
const attrUse = new Map();
const layoutDirs = [path.join(RES, 'layout'), path.join(RES, 'menu'), path.join(RES, 'color')];
for (const dir of layoutDirs) {
  for (const file of walk(dir)) {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    for (const line of lines) {
      const color = line.match(/@color\/([A-Za-z0-9_]+)/);
      if (!color || !themedNames.has(color[1])) continue;
      const attrMatch = line.match(/^\s*([A-Za-z_][\w:.-]*)\s*=/);
      if (!attrMatch) continue; // 不是属性行(例如整行就是色值),跳过
      let attr = attrMatch[1];
      if (attr.startsWith('xmlns')) continue;
      // 换肤层按"属性局部名"匹配(android:tint 与 app:tint 同一处理),这里也去掉命名空间前缀
      const colon = attr.indexOf(':');
      const local = colon >= 0 ? attr.slice(colon + 1) : attr;
      // 色值选择器(android:color/state_*)由 ThemeDrawables 重建,不算属性注入
      if (local === 'color' || local.startsWith('state_')) continue;
      if (!attrUse.has(local)) attrUse.set(local, new Set());
      attrUse.get(local).add(path.relative(ROOT, file));
    }
  }
}

const unknownAttrs = [];
for (const [attr, files] of attrUse) {
  if (handled.has(attr)) continue;
  unknownAttrs.push(`${attr}  (${files.size} 个文件,例如 ${[...files][0]})`);
}

// 3) 汇总
console.log(`主题概念名:${palette.size} 个;登记的颜色资源:${registered.size} 个;换肤层处理的属性:${handled.size} 个`);
console.log(`用到主题色的属性:${attrUse.size} 种`);

if (unknownAttrs.length) {
  console.log('\n[提示] 这些属性不在 ThemeInflaterFactory 的显式处理列表里(第三方控件属性会走反射 setter 兜底;'
    + '若某个属性既没显式处理、也找不到同名 setter,那一处颜色不会跟着主题走):');
  for (const a of unknownAttrs.sort()) console.log('  - ' + a);
}

if (missingColors.length) {
  console.error('\n[错误] 以下颜色最终指向主题概念,却没有登记进 ThemeColorAliases(自定义主题下它们不会变色):');
  for (const c of missingColors.sort()) console.error('  - ' + c);
  process.exit(1);
}

console.log('\ncheck-theme-res-coverage: 颜色资源覆盖完整(属性覆盖情况见上方提示)');
