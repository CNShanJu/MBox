/**
 * maccms.js —— 苹果CMS(MacCMS)系影视站 HTML 抓取源(通用模板,不依赖 cheerio/pdfh)
 *
 * 用途:站点没有可用的采集接口(接口关闭/未开放)时,直接抓站点页面出片:
 *   分类列表页(标题/海报/详情链接) → 详情页(剧集 sid/nid 播放链接) → 播放页(player_aaaa 里的 m3u8/mp4)
 *
 * 站点差异全部走 ext 配置(导入时由 App 嗅探生成,见 app/.../util/HtmlSiteImporter.java):
 *   {
 *     "siteName": "姐姐视频",
 *     "host": "https://wap.jiejiesp19.xyz",     // 站点根(含协议)
 *     "prefix": "/jiejie",                        // 站点挂载子目录(无则 ""),相对链接按它拼接
 *     "listUrl": "/index.php/vod/type/id/{id}.html",
 *     "listPageUrl": "/index.php/vod/type/id/{id}/page/{pg}.html",
 *     "searchUrl": "/index.php/vod/search.html?wd={key}",
 *     "searchPageUrl": "/index.php/vod/search/page/{pg}/wd/{key}.html",
 *     "homeUrl": "/",                             // 首页(首页推荐用,缺省 "/")
 *     "classes": [{"type_id":"87","type_name":"黄瓜资源"}],
 *     "headers": {"User-Agent":"..."},
 *     "timeout": 15000
 *   }
 *
 * 设计取舍:
 *   - 只用正则/字符串解析(不用 cheerio):模板能在 App(QuickJS)与 Node 校验脚本里跑同一份代码;
 *   - 剧集只认"与当前详情页同一个 vod id"的播放链接:详情页底部"猜你喜欢"里的其它 id 不能被当成本片剧集;
 *   - 播放页优先取 player_aaaa/MacPlayer 里的真实地址(如 m3u8),取不到再正则兜底,仍取不到则交给 App 嗅探。
 */

let cfg = {};
let HOST = '';
let PREFIX = '';
let HEADERS = {};

const DEFAULT_UA = 'Mozilla/5.0 (Linux; Android 12.0; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

/** 详情页链接:MacCMS 常见几种路由都收 */
const DETAIL_HREF = /href\s*=\s*["']([^"']*?(?:vod\/detail\/id\/|voddetail\/|\/detail\/id\/)(\d+)\.html[^"']*)["']/gi;
/** 播放页链接(vod/play/id/N/sid/S/nid/T.html 或 vodplay/N-S-T.html) */
const PLAY_HREF = /href\s*=\s*["']([^"']*(?:vod\/play\/id\/|vodplay\/)\d+[^"']*\.html[^"']*)["']/gi;
/** 分页链接里的页码(用于推算总页数) */
const PAGE_NUM = /(?:page\/|vodtype\/|\/vodshow\/\d+-)(\d+)/g;

function log(msg) {
    try {
        console.log('[maccms] ' + msg);
    } catch (e) {
    }
}

function safeJson(s) {
    try {
        return JSON.parse(s);
    } catch (e) {
        return {};
    }
}

function str(v) {
    return v === undefined || v === null ? '' : String(v);
}

function trimSlash(s) {
    return str(s).replace(/\/+$/, '');
}

function normalizePrefix(p) {
    let v = str(p).trim();
    if (!v || v === '/') return '';
    if (v.charAt(0) !== '/') v = '/' + v;
    return v.replace(/\/+$/, '');
}

/** 相对链接绝对化:兼容 //host/... 、/path 、相对路径 */
function abs(u) {
    let v = str(u).trim();
    if (!v) return '';
    if (/^https?:\/\//i.test(v)) return v;
    if (v.indexOf('//') === 0) return (HOST.indexOf('https') === 0 ? 'https:' : 'http:') + v;
    if (v.charAt(0) !== '/') v = PREFIX + '/' + v;
    return HOST + v;
}

/**
 * ext 里的路径模板(相对站点根)绝对化:站点挂在子目录(如 /jiejie)时自动补上前缀,
 * 免得模板写成 /index.php/... 时漏掉子目录而抓到 404 页。
 */
function sitePath(p) {
    let v = str(p).trim();
    if (!v) return HOST + PREFIX + '/';
    if (/^https?:\/\//i.test(v)) return v;
    if (PREFIX && v !== PREFIX && v.indexOf(PREFIX + '/') !== 0) {
        v = PREFIX + (v.charAt(0) === '/' ? v : '/' + v);
    } else if (!PREFIX && v.charAt(0) !== '/') {
        v = '/' + v;
    }
    return HOST + v;
}

/** 同步取页面(App 内 req 由 net.js 提供;Node 校验脚本里给一个同名实现) */
function getHtml(url) {
    if (!url) return '';
    try {
        if (typeof req === 'function') {
            const res = req(url, { headers: HEADERS, timeout: cfg.timeout || 15000 });
            const content = res && res.content ? res.content : '';
            if (!content) log('取页面为空: ' + url);
            return content;
        }
        if (typeof http === 'function') {
            const res = http(url, { headers: HEADERS, timeout: cfg.timeout || 15000 });
            return (res && res.content) || '';
        }
    } catch (e) {
        log('取页面失败: ' + url + ' ' + e);
    }
    return '';
}

function unescapeHtml(s) {
    return str(s)
        .replace(/&nbsp;/gi, ' ')
        .replace(/&amp;/gi, '&')
        .replace(/&quot;/gi, '"')
        .replace(/&#39;|&apos;/gi, "'")
        .replace(/&lt;/gi, '<')
        .replace(/&gt;/gi, '>')
        .replace(/&#(\d+);/g, function (m, d) {
            return String.fromCharCode(parseInt(d, 10));
        })
        .replace(/\s+/g, ' ')
        .trim();
}

function stripTags(s) {
    return unescapeHtml(str(s).replace(/<[^>]*>/g, ''));
}

/** 取标签属性(按候选名依次找;兼容单/双引号/无引号) */
function pickAttr(tag, names) {
    const t = str(tag);
    for (let i = 0; i < names.length; i++) {
        const re = new RegExp(names[i] + '\\s*=\\s*("([^"]*)"|\'([^\']*)\'|([^\\s>]+))', 'i');
        const m = re.exec(t);
        if (m) {
            const v = m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : m[4]);
            if (v) return unescapeHtml(v);
        }
    }
    return '';
}

function firstMatch(html, re) {
    const m = re.exec(html);
    return m ? m : null;
}

/**
 * 从一段 HTML 里挑海报地址。
 * 站点排版差异:有的把 data-original 挂在 <a> 上、<img> 里只有占位图(苹果CMS 默认模板即如此),
 * 故扫所有带图片属性的标签,优先懒加载属性,并跳过占位图/透明图/data: 内联图。
 */
function pickPic(block) {
    const re = /<(?:img|a|div|source|li)\b[^>]*>/gi;
    let m;
    let best = '';
    while ((m = re.exec(block))) {
        const tag = m[0];
        const v = pickAttr(tag, ['data-original', 'data-src', 'data-echo', 'data-url', 'src']);
        if (!v || /^data:/i.test(v)) continue;
        if (!/\.(jpe?g|png|webp|gif|avif|bmp)(\?|$)/i.test(v) && v.indexOf('/upload/') < 0) continue;
        if (/(?:pi|loading|blank|spacer|placeholder|grey|none)\.(?:png|gif|jpe?g)/i.test(v)) continue;
        if (/\/statics\/img\//i.test(v)) continue;
        if (/data-original|data-src|data-echo/i.test(tag)) return v;   // 懒加载属性最可信
        if (!best) best = v;
    }
    return best;
}

function pageTitle(html) {
    const m = firstMatch(html, /<title[^>]*>([\s\S]{0,200}?)<\/title>/i);
    return m ? stripTags(m[1]) : '';
}

/**
 * 列表条目解析:每条取"详情链接 + 标题 + 海报 + 备注"。
 * 站点列表排版差异大(有的把海报挂 play 链接、标题挂 detail 链接),故以详情链接为锚,
 * 在前后窗口内找标题(h4/class=title/title 属性/图片 alt)与海报(data-original 等懒加载属性)。
 */
function parseList(html, limit) {
    const out = [];
    const seen = {};
    let m;
    DETAIL_HREF.lastIndex = 0;
    while ((m = DETAIL_HREF.exec(html))) {
        const href = m[1];
        const id = m[2];
        if (seen[id]) continue;
        seen[id] = 1;
        const from = Math.max(0, m.index - 1500);
        const block = html.substring(from, m.index + 1500);

        let name = '';
        const anchorRe = new RegExp('href\\s*=\\s*["\'][^"\']*' + href.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '["\'][^>]*>', 'i');
        const anchor = firstMatch(block, anchorRe);
        if (anchor) name = pickAttr(anchor[0], ['title']);
        if (!name) {
            const h = firstMatch(block, /<h[1-6][^>]*>([\s\S]{0,200}?)<\/h[1-6]>/i);
            if (h) name = stripTags(h[1]);
        }
        if (!name) {
            const t = firstMatch(block, /class\s*=\s*["'][^"']*(?:title|name)[^"']*["'][^>]*>([\s\S]{0,200}?)</i);
            if (t) name = stripTags(t[1]);
        }
        let pic = pickPic(block);
        if (!name) {
            const img = firstMatch(block, /<img\b[^>]*>/i);
            if (img) name = pickAttr(img[0], ['alt']);
        }
        let remarks = '';
        const rk = firstMatch(block, /class\s*=\s*["'][^"']*(?:pic-text|module-item-note|note|text-right)[^"']*["'][^>]*>([\s\S]{0,120}?)</i);
        if (rk) remarks = stripTags(rk[1]);
        if (!name) name = remarks || ('影片 ' + id);

        out.push({
            vod_id: abs(href),
            vod_name: name,
            vod_pic: pic ? abs(pic) : '',
            vod_remarks: remarks
        });
        if (limit > 0 && out.length >= limit) break;
    }
    return out;
}

/** 总页数:取分页里出现过的最大页码;取不到按当前页(即只有一页) */
function parsePageCount(html, pg) {
    let max = 0;
    let m;
    PAGE_NUM.lastIndex = 0;
    while ((m = PAGE_NUM.exec(html))) {
        const n = parseInt(m[1], 10);
        if (n > max) max = n;
    }
    const slash = firstMatch(html, /class\s*=\s*["'][^"']*(?:active|num)[^"']*["'][^>]*>[\s\S]{0,40}?\d+\s*\/\s*(\d+)/i);
    if (slash) {
        const n = parseInt(slash[1], 10);
        if (n > max) max = n;
    }
    const tail = firstMatch(html, /href\s*=\s*["'][^"']*(?:page\/|\/)(\d+)(?:\.html)?[^"']*["'][^>]*>\s*(?:尾页|末页)/i);
    if (tail) {
        const n = parseInt(tail[1], 10);
        if (n > max) max = n;
    }
    return max > 0 ? max : (pg > 0 ? pg : 1);
}

function fill(tpl, map) {
    let u = str(tpl);
    for (const k in map) {
        if (Object.prototype.hasOwnProperty.call(map, k)) u = u.split('{' + k + '}').join(str(map[k]));
    }
    return u;
}

function listUrl(tid, pg) {
    const page = parseInt(pg, 10) > 0 ? parseInt(pg, 10) : 1;
    if (cfg.listUrl) {
        if (page <= 1) return sitePath(fill(cfg.listUrl, { id: tid }));
        if (cfg.listPageUrl) return sitePath(fill(cfg.listPageUrl, { id: tid, pg: page }));
        const base = fill(cfg.listUrl, { id: tid });
        return sitePath(base.replace(/\.html?$/i, '/page/' + page + '.html'));
    }
    return sitePath('/index.php/vod/type/id/' + tid + (page > 1 ? '/page/' + page : '') + '.html');
}

function searchUrl(key, pg) {
    const page = parseInt(pg, 10) > 0 ? parseInt(pg, 10) : 1;
    if (!cfg.searchUrl) return '';
    const k = encodeURIComponent(str(key));
    if (page <= 1) return sitePath(fill(cfg.searchUrl, { key: k }));
    if (cfg.searchPageUrl) return sitePath(fill(cfg.searchPageUrl, { key: k, pg: page }));
    const base = fill(cfg.searchUrl, { key: k });
    return sitePath(base + (base.indexOf('?') >= 0 ? '&' : '?') + 'page=' + page);
}

function listResult(list, pg, pageCount) {
    return JSON.stringify({
        page: pg > 0 ? pg : 1,
        pagecount: pageCount > 0 ? pageCount : 1,
        limit: list.length,
        total: list.length * (pageCount > 0 ? pageCount : 1),
        list: list
    });
}

/** 从播放链接里取 vod id / 线路 sid / 集号 nid */
function playIds(href) {
    let m = /vod\/play\/id\/(\d+)\/sid\/(\d+)\/nid\/(\d+)/i.exec(href);
    if (m) return { id: m[1], sid: m[2], nid: m[3] };
    m = /vodplay\/(\d+)-(\d+)-(\d+)/i.exec(href);
    if (m) return { id: m[1], sid: m[2], nid: m[3] };
    m = /vod\/play\/id\/(\d+)/i.exec(href);
    if (m) return { id: m[1], sid: '1', nid: '1' };
    return null;
}

/**
 * 详情页剧集解析:只收"与当前片同一个 vod id"的播放链接
 * (详情页底部"猜你喜欢"同样是播放链接,不过滤会把别的片当成剧集)。
 */
function parseEpisodes(html, vodId, fallbackDetailUrl) {
    const groups = {};
    const order = [];
    let m;
    PLAY_HREF.lastIndex = 0;
    while ((m = PLAY_HREF.exec(html))) {
        const href = m[1];
        const ids = playIds(href);
        if (!ids) continue;
        if (vodId && ids.id !== vodId) continue;
        const tail = html.substring(m.index, Math.min(html.length, m.index + 400));
        const anchor = firstMatch(tail, /^[\s\S]{0,200}?<\/a>/i);
        let name = '';
        if (anchor) name = stripTags(anchor[0].replace(/^[^>]*>/, ''));
        name = name.replace(/^(立即播放|播放|详情)$/, '');
        if (!name) name = '第' + ids.nid + '集';
        const key = ids.sid + '_' + ids.nid;
        if (groups[key]) continue;
        if (!groups[ids.sid]) {
            groups[ids.sid] = [];
            order.push(ids.sid);
        }
        groups[ids.sid].push({ name: name, url: abs(href) });
    }
    // 单片站点(只有"立即播放")也至少给一条,取详情页地址兜底
    if (order.length === 0 && fallbackDetailUrl) {
        order.push('1');
        groups['1'] = [{ name: '立即播放', url: fallbackDetailUrl }];
    }
    const froms = [];
    const urls = [];
    for (let i = 0; i < order.length; i++) {
        const sid = order[i];
        froms.push('线路' + sid);
        urls.push(groups[sid].map(function (e) {
            return e.name + '$' + e.url;
        }).join('#'));
    }
    return { froms: froms.join('$$$'), urls: urls.join('$$$') };
}

/** 详情页信息:标题/海报/简介/类型/年份 */
function parseDetail(html, id) {
    let name = '';
    const h1 = firstMatch(html, /<h1[^>]*>([\s\S]{0,200}?)<\/h1>/i);
    if (h1) name = stripTags(h1[1]);
    if (!name) name = pageTitle(html).replace(/[-_|].*$/, '');
    const poster = firstMatch(html, /class\s*=\s*["'][^"']*(?:pic|thumb|poster)[^"']*["'][^>]*>[\s\S]{0,400}?(?=<div|<\/div|<\/a>)/i);
    const pic = poster ? pickPic(poster[0]) : pickPic(html.substring(0, Math.min(html.length, 40000)));
    const desc = firstMatch(html, /class\s*=\s*["'][^"']*(?:detail-sketch|detail-content|desc|sketch)[^"']*["'][^>]*>([\s\S]{0,2000}?)<\/(?:span|div|p)>/i);
    const data = firstMatch(html, /class\s*=\s*["']data["'][^>]*>([\s\S]{0,300}?)<\/(?:p|div)>/i);
    return {
        name: name ? name : ('影片 ' + id),
        pic: pic ? abs(pic) : '',
        content: desc ? stripTags(desc[1]) : stripTags(data ? data[1] : '')
    };
}

/** 播放页取真实播放地址:player_aaaa/MacPlayer 配置优先,其次 m3u8/mp4 正则,最后 iframe */
function parsePlayerUrl(html) {
    const player = firstMatch(html, /player_[a-z]+\s*=\s*(\{[\s\S]{0,4000}?\})\s*<\/script>/i);
    if (player) {
        const json = safeJson(player[1].replace(/\\\//g, '/'));
        const url = str(json.url || (json.data && json.data.url) || '');
        if (/^https?:\/\//i.test(url)) return { url: url, sniff: 0 };
    }
    const mac = firstMatch(html, /MacPlayer\s*=\s*\{[\s\S]{0,2000}?\}/i);
    if (mac) {
        const m = /["']?(?:url|Url)["']?\s*:\s*["']([^"']+)["']/i.exec(mac[0]);
        if (m) {
            const url = m[1].replace(/\\\//g, '/');
            if (/^https?:\/\//i.test(url)) return { url: url, sniff: 0 };
        }
    }
    const direct = firstMatch(html, /["'](https?:[^"'<>\\\s]+?\.(?:m3u8|mp4|flv|mkv|avi|webm)(?:\?[^"'<>\\\s]*)?)["']/i);
    if (direct) return { url: direct[1].replace(/\\\//g, '/'), sniff: 0 };
    const bare = firstMatch(html, /(https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mp4|flv|mkv|avi|webm)(?:\?[^\s"'<>\\]*)?)/i);
    if (bare) return { url: bare[1].replace(/\\/g, ''), sniff: 0 };
    const frame = firstMatch(html, /<iframe\b[^>]*src\s*=\s*["']([^"']+)["']/i);
    if (frame && frame[1] && frame[1].indexOf('javascript:') !== 0) {
        // 播放器地址是外链页面:交给 App 嗅探(parse=1)
        return { url: abs(frame[1]), sniff: 1 };
    }
    return null;
}

function init(ext) {
    cfg = (typeof ext === 'string') ? safeJson(ext) : (ext || {});
    HOST = trimSlash(cfg.host || '');
    PREFIX = normalizePrefix(cfg.prefix || '');
    HEADERS = { 'User-Agent': cfg.headers && cfg.headers['User-Agent'] ? cfg.headers['User-Agent'] : DEFAULT_UA };
    if (cfg.headers) {
        for (const k in cfg.headers) {
            if (Object.prototype.hasOwnProperty.call(cfg.headers, k)) HEADERS[k] = cfg.headers[k];
        }
    }
    log('初始化: ' + (cfg.siteName || '') + ' ' + HOST + PREFIX + ' 分类=' + ((cfg.classes || []).length));
}

function home() {
    const classes = Array.isArray(cfg.classes) ? cfg.classes : [];
    return JSON.stringify({ class: classes, filters: {} });
}

function homeVod() {
    const url = sitePath(cfg.homeUrl || '/');
    const html = getHtml(url);
    if (!html) return JSON.stringify({ list: [] });
    return JSON.stringify({ list: parseList(html, 24) });
}

function category(tid, pg, filter, extend) {
    const page = parseInt(pg, 10) > 0 ? parseInt(pg, 10) : 1;
    const url = listUrl(tid, page);
    const html = getHtml(url);
    if (!html) return listResult([], page, 1);
    const list = parseList(html, 0);
    if (list.length === 0) log('分类页没解析到条目: ' + url);
    return listResult(list, page, parsePageCount(html, page));
}

function detail(id) {
    const url = /^https?:\/\//i.test(str(id)) ? str(id) : abs(id);
    const html = getHtml(url);
    if (!html) return JSON.stringify({ list: [] });
    let vodId = '';
    const vid = firstMatch(url, /vod\/detail\/id\/(\d+)/i) || firstMatch(url, /voddetail\/(\d+)/i) || firstMatch(url, /(\d+)/);
    if (vid) vodId = vid[1];
    const info = parseDetail(html, vodId);
    const eps = parseEpisodes(html, vodId, url);
    return JSON.stringify({
        list: [{
            vod_id: str(id),
            vod_name: info.name,
            vod_pic: info.pic,
            vod_content: info.content,
            vod_play_from: eps.froms,
            vod_play_url: eps.urls
        }]
    });
}

function search(key, quick, pg) {
    const page = parseInt(pg, 10) > 0 ? parseInt(pg, 10) : 1;
    if (!str(key)) return listResult([], page, 1);
    const url = searchUrl(key, page);
    if (!url) return listResult([], page, 1);
    const html = getHtml(url);
    if (!html) return listResult([], page, 1);
    return listResult(parseList(html, 0), page, parsePageCount(html, page));
}

function play(flag, id, vipFlags) {
    const url = /^https?:\/\//i.test(str(id)) ? str(id) : abs(id);
    const html = getHtml(url);
    if (!html) {
        log('播放页取不到: ' + url);
        return JSON.stringify({ parse: 0, playUrl: '', url: url, header: HEADERS });
    }
    const found = parsePlayerUrl(html);
    if (!found) {
        log('播放页未找到播放地址,交嗅探: ' + url);
        return JSON.stringify({ parse: 1, url: url, header: HEADERS });
    }
    if (found.sniff === 1) {
        return JSON.stringify({ parse: 1, url: found.url, header: HEADERS });
    }
    return JSON.stringify({ parse: 0, playUrl: '', url: found.url, header: HEADERS });
}

function sniffer() {
    // 抓页面源不做嗅探(App 侧 manualVideoCheck 会对返回值拆箱,null 会抛,故明确返回 false)
    return false;
}

function isVideo(url) {
    return /\.(m3u8|mp4|flv|mkv|webm|avi)/i.test(str(url));
}

export default {
    init: init,
    home: home,
    homeVod: homeVod,
    category: category,
    detail: detail,
    search: search,
    play: play,
    sniffer: sniffer,
    isVideo: isVideo
};
