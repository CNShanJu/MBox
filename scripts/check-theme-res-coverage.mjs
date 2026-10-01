#!/usr/bin/env node
/**
 * 离线校验:自定义主题(运行时换肤)的"覆盖面"自检。
 *
 * 背景:主题颜色在编译期被烤进资源表,运行时只能覆盖"换肤层认得的东西":
 *   1) 颜色资源 —— 必须登记在 `ThemeColorAliases`(颜色 id → 调色板概念名);
 *   2) 布局属性 —— 必须是 `ThemeInflaterFactory` 处理过的属性名
 *      (android:textColor / app:tint / android:background ...);
 *   3) 已迁移 drawable —— 必须在 theme_shapes.json 与 ThemeDrawableFactory 显式登记;
 *      其余过渡 drawable / 色值选择器仍由 ThemeDrawables 兼容重建;
 *   4) 单色矢量图标 —— 由 tint 覆盖,不需要登记。
 *
 * 漏了 1 或 2 的症状是"自定义主题下,某一处颜色没变"(不会有任何报错),所以在 CI/提交前扫一遍:
 *   - 颜色资源漏登记:**报错**(退出码 1),必须补表;
 *   - 属性名不认识:**报错**;第三方属性必须核对真实公开 setter,禁止反射猜测;
 *   - 配方未映射、配方令牌不存在、业务代码直接 setBackgroundResource(R.drawable.*):**报错**。
 *
 * 用法:node scripts/check-theme-res-coverage.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(process.cwd());
const RES = path.join(ROOT, 'app', 'src', 'main', 'res');
const ALIAS_JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'github', 'tvbox', 'osc', 'theme', 'ThemeColorAliases.java');
const FACTORY_JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'github', 'tvbox', 'osc', 'theme', 'ThemeInflaterFactory.java');
const DRAWABLE_FACTORY_JAVA = path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'github', 'tvbox', 'osc', 'theme', 'ThemeDrawableFactory.java');
const SHAPES_JSON = path.join(ROOT, 'app', 'src', 'main', 'assets', 'theme', 'theme_shapes.json');
const GEN_COLORS = path.join(ROOT, 'app', 'build', 'generated', 'theme_colors', 'values', 'theme_colors.xml');

/** 派生出来的主题资源名(= 调色板里的概念名)。没有生成文件时退到硬编码清单(与 build.gradle 的派生表一致) */
function paletteNames() {
  if (fs.existsSync(GEN_COLORS)) {
    const xml = fs.readFileSync(GEN_COLORS, 'utf8');
    return [...xml.matchAll(/<color name="([^"]+)"/g)].map((m) => m[1]);
  }
  return [
    'bg_body', 'bg_surface', 'bg_card', 'bg_float',
    'text_main', 'text_sub', 'text_hint', 'text_main_half', 'text_disable', 'text_accent', 'text_highlight',
    'color_highlight', 'select_fill', 'press_overlay',
    'btn_confirm_bg', 'btn_confirm_text', 'btn_confirm_stroke', 'btn_cancel_bg',
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

// 2) 属性覆盖:布局、菜单、色值选择器与共享 style 里用到主题色的属性名,看换肤层认不认识。
const attrUse = new Map();
const layoutDirs = [path.join(RES, 'layout'), path.join(RES, 'menu'), path.join(RES, 'color')];
for (const dir of layoutDirs) {
  for (const file of walk(dir)) {
    const xml = fs.readFileSync(file, 'utf8');
    // 逐个匹配“这个属性自己的值”,不能按整行先找第一个属性、再找任意 @color。
    // 同一行写 tools:text="…" android:textColor="@color/…" 时,旧算法会把它误报成
    // text 属性在吃主题色,也就无法把未知属性真正升级为错误。
    for (const match of xml.matchAll(/([A-Za-z_][\w:.-]*)\s*=\s*["']@color\/([A-Za-z0-9_]+)["']/g)) {
      let attr = match[1];
      const color = match[2];
      if (!themedNames.has(color)) continue;
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
const stylesFile = path.join(RES, 'values', 'styles.xml');
if (fs.existsSync(stylesFile)) {
  const xml = fs.readFileSync(stylesFile, 'utf8');
  const blocks = new Map();
  for (const match of xml.matchAll(/<style\s+name="([^"]+)"([^>]*)>([\s\S]*?)<\/style>/g)) {
    const parent = /\bparent="(?:@style\/)?([^"]+)"/.exec(match[2])?.[1] ?? '';
    blocks.set(match[1], { parent, body: match[3] });
  }
  const used = new Set();
  for (const file of walk(path.join(RES, 'layout'))) {
    const layout = fs.readFileSync(file, 'utf8');
    for (const match of layout.matchAll(/\bstyle="@style\/([^"]+)"/g)) used.add(match[1]);
  }
  // 本地父 style 同样参与最终属性；AppTheme 等只在 Manifest 使用，不是视图 setter，不混进本检查。
  const pending = [...used];
  while (pending.length) {
    const name = pending.pop();
    const block = blocks.get(name);
    if (block?.parent && blocks.has(block.parent) && !used.has(block.parent)) {
      used.add(block.parent);
      pending.push(block.parent);
    }
  }
  for (const name of used) {
    const block = blocks.get(name);
    if (!block) continue;
    for (const match of block.body.matchAll(/<item\s+name="(?:android:|app:)?([A-Za-z0-9_]+)"\s*>\s*@color\/([A-Za-z0-9_]+)\s*<\/item>/g)) {
      const [, attr, color] = match;
      if (!themedNames.has(color)) continue;
      if (!attrUse.has(attr)) attrUse.set(attr, new Set());
      attrUse.get(attr).add(path.relative(ROOT, stylesFile) + '#' + name);
    }
  }
}

const unknownAttrs = [];
for (const [attr, files] of attrUse) {
  if (handled.has(attr)) continue;
  unknownAttrs.push(`${attr}  (${files.size} 个文件,例如 ${[...files][0]})`);
}

// 3) 配方必须只引用已知令牌,并且每个配方 id 都由运行时工厂显式映射。
const recipeErrors = [];
const recipes = JSON.parse(fs.readFileSync(SHAPES_JSON, 'utf8'));
const drawableFactory = fs.readFileSync(DRAWABLE_FACTORY_JAVA, 'utf8');
const radiiJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'app', 'src', 'main', 'assets', 'theme', 'theme_radii.json'), 'utf8'));
const shapeTokens = new Set(Object.keys(radiiJson).filter((k) => k !== 'desc' && k !== 'type'));
function checkRecipeValue(id, key, value) {
  if (typeof value !== 'string') return;
  if (['fill', 'stroke', 'ripple'].includes(key)) {
    if (value !== 'transparent' && !value.startsWith('#') && !palette.has(value)) {
      recipeErrors.push(`${id}:未知颜色令牌 ${value}`);
    }
  } else if (key === 'radius' || key === 'strokeWidth' || /^(top|bottom)(Left|Right|Start|End)$/.test(key)) {
    if (!shapeTokens.has(value)) recipeErrors.push(`${id}:未知形状令牌 ${value}`);
  }
}
function walkRecipe(id, value, key = '') {
  if (Array.isArray(value)) value.forEach((item) => walkRecipe(id, item));
  else if (value && typeof value === 'object') {
    for (const [childKey, child] of Object.entries(value)) walkRecipe(id, child, childKey);
  } else checkRecipeValue(id, key, value);
}
for (const [id, recipe] of Object.entries(recipes)) {
  walkRecipe(id, recipe);
  if (!drawableFactory.includes(`return "${id}"`)) recipeErrors.push(`${id}:ThemeDrawableFactory 未映射`);
}

// 4) 业务代码不得把已编译 drawable 直接盖回去;统一走 ThemeDrawables.applyBackground。
const directBackgrounds = [];
const javaRoot = path.join(ROOT, 'app', 'src', 'main', 'java');
function walkCode(dir) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walkCode(file);
    else if (/\.(java|kt)$/.test(entry.name)) {
      const text = fs.readFileSync(file, 'utf8');
      if (/\.setBackgroundResource\(\s*R\.drawable\./.test(text)) {
        directBackgrounds.push(path.relative(ROOT, file));
      }
    }
  }
}
walkCode(javaRoot);

// 5) 汇总
console.log(`主题概念名:${palette.size} 个;登记的颜色资源:${registered.size} 个;换肤层处理的属性:${handled.size} 个`);
console.log(`用到主题色的属性:${attrUse.size} 种`);

if (unknownAttrs.length) {
  console.error('\n[错误] 以下主题属性没有显式处理。第三方属性必须在 ThemeInflaterFactory 中调用真实公开 API,'
    + '禁止按属性名猜 setter:');
  for (const a of unknownAttrs.sort()) console.error('  - ' + a);
}

if (recipeErrors.length) {
  console.error('\n[错误] 主题形状配方不完整:');
  for (const item of recipeErrors.sort()) console.error('  - ' + item);
}

if (directBackgrounds.length) {
  console.error('\n[错误] 以下代码直接 setBackgroundResource(R.drawable.*),会覆盖运行时主题:');
  for (const file of directBackgrounds.sort()) console.error('  - ' + file);
}

if (missingColors.length) {
  console.error('\n[错误] 以下颜色最终指向主题概念,却没有登记进 ThemeColorAliases(自定义主题下它们不会变色):');
  for (const c of missingColors.sort()) console.error('  - ' + c);
  process.exit(1);
}

if (unknownAttrs.length || recipeErrors.length || directBackgrounds.length) process.exit(1);

console.log('\ncheck-theme-res-coverage: 颜色资源与主题属性覆盖完整');
