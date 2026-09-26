/**
 * maccms.js(抓页面源模板)运行时校验脚本 —— 开发期用,不参与 App 构建/门禁。
 *
 * 作用:把 App 资源里的模板 app/src/main/assets/js/lib/maccms.js 原样载入 Node,
 * 用 curl 同步取真实页面,跑一遍 首页分类 → 分类页 → 详情页 → 播放页 → 搜索 全链路,
 * 确认模板对目标站点可用(改模板后先用它回归,再装包试)。
 *
 * 用法:
 *   node scripts/check-maccms-source.mjs <站点地址> [子目录]
 *   node scripts/check-maccms-source.mjs https://example.com /sub
 *
 * 可选环境变量:
 *   MACCMS_EXT=<ext.json 路径>  用 Java 侧 HtmlSiteRules.buildExtJson 生成的 ext 驱动模板
 *                               (即验证"Java 生成配置 → JS 运行时消费"整条链路,而不是脚本里手写的配置)
 *   MACCMS_OFFLINE=1            不联网,改读脚本目录下的本地页面夹具(文件名由 URL 推导)
 *
 * 例:先跑 Java 侧生成 ext,再喂给模板:
 *   javac -d out spider/src/main/java/com/github/tvbox/osc/spiderapi/{HtmlSiteRules,CmsApiRules}.java ...
 *   MACCMS_EXT=ext.json node scripts/check-maccms-source.mjs https://example.com /sub
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import os from 'node:os';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\//, '')), '..');
const SRC = path.join(ROOT, 'app/src/main/assets/js/lib/maccms.js');
const UA = 'Mozilla/5.0 (Linux; Android 12.0; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

const HOST = process.argv[2];
const PREFIX = process.argv[3] !== undefined ? process.argv[3] : '';
if (!HOST) {
  console.error('用法: node scripts/check-maccms-source.mjs <站点地址> [子目录]');
  process.exit(2);
}
const OFFLINE = process.env.MACCMS_OFFLINE === '1';

// App 环境里 req 由 net.js + Java 绑定提供;Node 侧用 curl 同步实现同一语义
globalThis.req = (url, options = {}) => {
  if (OFFLINE) {
    const key = url.replace(/^https?:\/\//, '').replace(/[^\w.-]+/g, '_');
    try {
      return { content: readFileSync(path.join(process.cwd(), key + '.html'), 'utf8') };
    } catch (e) {
      return { content: '' };
    }
  }
  const args = ['-s', '-m', '20', '-L', '-A', (options.headers && options.headers['User-Agent']) || UA, url];
  try {
    return { content: execFileSync('curl', args, { maxBuffer: 32 * 1024 * 1024 }).toString('utf8') };
  } catch (e) {
    return { content: '' };
  }
};

// 模板是 ES module(export default),Node 下换个后缀载入
const tmp = path.join(os.tmpdir(), 'maccms_mod.mjs');
writeFileSync(tmp, readFileSync(SRC, 'utf8'), 'utf8');
const spider = (await import(pathToFileURL(tmp).href)).default;

const homeHtml = globalThis.req(HOST + PREFIX + '/').content;

// 与 HtmlSiteRules.classes 同一套规则(仅用于没有 MACCMS_EXT 时兜底构造配置)
function siteClasses(html) {
  const out = [];
  const seen = {};
  const re = /<a\b[^>]*href\s*=\s*["']([^"']*?(?:\/vod\/type\/id\/|\/vodtype\/)(\d+)(?:\.html)?[^"']*)["'][^>]*>([\s\S]{0,40}?)<\/a>/gi;
  let m;
  while ((m = re.exec(html))) {
    const id = m[2];
    const name = m[3].replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
    if (!name || seen[id]) continue;
    seen[id] = 1;
    out.push({ type_id: id, type_name: name });
  }
  return out;
}

const EXT_FILE = process.env.MACCMS_EXT;
const cfg = EXT_FILE ? JSON.parse(readFileSync(EXT_FILE, 'utf8')) : {
  siteName: (homeHtml.match(/<title[^>]*>([^<]*)<\/title>/i) || [, ''])[1].trim(),
  host: HOST,
  prefix: PREFIX,
  homeUrl: '/',
  listUrl: '/index.php/vod/type/id/{id}.html',
  listPageUrl: '/index.php/vod/type/id/{id}/page/{pg}.html',
  searchUrl: '/index.php/vod/search.html?wd={key}',
  searchPageUrl: '/index.php/vod/search/page/{pg}/wd/{key}.html',
  classes: siteClasses(homeHtml)
};

console.log('首页长度=' + homeHtml.length + ' 配置来源=' + (EXT_FILE ? 'Java ext(' + EXT_FILE + ')' : '脚本内置'));
spider.init(cfg);

const home = JSON.parse(spider.home(true));
console.log('home: class=' + home.class.length);

const tid = (cfg.classes[0] || {}).type_id;
if (!tid) {
  console.error('首页没解析到分类:模板或站点结构需要再看一眼');
  process.exit(1);
}
const cat = JSON.parse(spider.category(tid, 1, false, {}));
console.log('category(' + tid + ',1): pagecount=' + cat.pagecount + ' items=' + cat.list.length);
console.log('  首条: ' + JSON.stringify(cat.list[0]));
const cat2 = JSON.parse(spider.category(tid, 2, false, {}));
console.log('翻页第2页条数=' + cat2.list.length + ' 与第1页不同=' + ((cat.list[0] || {}).vod_id !== (cat2.list[0] || {}).vod_id));

const detail = JSON.parse(spider.detail(cat.list[0].vod_id)).list[0] || {};
console.log('detail: name=' + detail.vod_name + ' 海报=' + (detail.vod_pic ? '有' : '无')
  + ' 线路=' + detail.vod_play_from + ' 剧集数=' + String(detail.vod_play_url).split('#').length);

const epUrl = String(detail.vod_play_url).split('#')[0].split('$')[1];
const play = JSON.parse(spider.play(String(detail.vod_play_from).split('$$$')[0], epUrl, []));
console.log('play: parse=' + play.parse + ' url=' + play.url);
console.log('  直链(m3u8/mp4)=' + /\.(m3u8|mp4)/i.test(play.url || ''));

if (cfg.searchUrl) {
  const kw = (cfg.siteName || '').replace(/[^\u4e00-\u9fa5A-Za-z0-9]/g, '').slice(0, 2) || '电影';
  const search = JSON.parse(spider.search(kw, false, 1));
  console.log('search(' + kw + '): items=' + search.list.length + ' pagecount=' + search.pagecount);
}
console.log('homeVod: items=' + JSON.parse(spider.homeVod()).list.length);
