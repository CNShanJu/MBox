/**
 * 阅读视频书源的 JSON 接口适配器。ext 由 LegadoVideoRules 生成，只使用声明的
 * URL 模板和简单 JSON 路径；不执行书源里携带的 JS/HTML。
 */
let cfg = {};

function str(value) {
    return value === undefined || value === null ? '' : String(value);
}

function readPath(root, path) {
    if (!path || path.slice(0, 2) !== '$.') return null;
    let value = root;
    const fields = path.slice(2).replace(/\[\*\]$/, '').split('.');
    for (const field of fields) {
        if (!field || value === null || typeof value !== 'object') return null;
        value = value[field];
    }
    return value === undefined ? null : value;
}

function absolute(value) {
    const url = str(value).trim();
    if (!url) return '';
    if (/^https?:\/\//i.test(url)) return url;
    if (url.slice(0, 2) === '//') return cfg.host.split(':')[0] + ':' + url;
    return cfg.host + (url.charAt(0) === '/' ? '' : '/') + url;
}

function request(url) {
    try {
        const response = req(url, { headers: cfg.headers || {}, timeout: cfg.timeout || 15000 });
        return JSON.parse(response && response.content ? response.content : '{}');
    } catch (error) {
        console.log('[legado_video] 请求或 JSON 解析失败: ' + url + ' ' + error);
        return {};
    }
}

function fill(template, page, key, row) {
    let url = str(template).replace(/\{page\}/g, str(page)).replace(/\{key\}/g, encodeURIComponent(str(key)));
    url = url.replace(/\{random:(\d+)\}/g, function (_, bound) {
        return str(1 + Math.floor(Math.random() * Math.max(1, Number(bound) || 1)));
    });
    url = url.replace(/\{\{\s*(\$\.[\w.]+)\s*\}\}/g, function (_, path) {
        return encodeURIComponent(str(readPath(row, path)));
    });
    return absolute(url);
}

function rows(response) {
    const value = readPath(response, cfg.listPath);
    if (!Array.isArray(value)) return [];
    const out = [];
    for (const row of value) {
        if (!row || typeof row !== 'object') continue;
        const detail = fill(cfg.detailTemplate, 1, '', row);
        if (!detail || detail.indexOf('{{') >= 0) continue;
        const title = str(readPath(row, cfg.titlePath)).trim() || '影片';
        const picture = absolute(readPath(row, cfg.imagePath));
        out.push({ vod_id: detail, vod_name: title, vod_pic: picture,
            vod_remarks: str(readPath(row, cfg.remarksPath)) });
    }
    return out;
}

function result(response, page) {
    const list = rows(response);
    const lastPage = Number(readPath(response, cfg.lastPagePath)) || 0;
    const next = readPath(response, cfg.nextPagePath);
    const pagecount = lastPage > 0 ? lastPage : (next ? page + 1 : page);
    return JSON.stringify({ page: page, pagecount: pagecount, limit: list.length, total: 0, list: list });
}

function init(ext) {
    cfg = typeof ext === 'string' ? JSON.parse(ext) : (ext || {});
}

function home() {
    return JSON.stringify({ class: cfg.classes || [], filters: {} });
}

function homeVod() {
    const route = cfg.routes && cfg.routes['0'];
    return JSON.stringify({ list: route ? rows(request(fill(route, 1, '', null))).slice(0, 24) : [] });
}

function category(tid, pg) {
    const page = Math.max(1, parseInt(pg, 10) || 1);
    const route = cfg.routes && cfg.routes[str(tid)];
    return result(route ? request(fill(route, page, '', null)) : {}, page);
}

function detail(id) {
    const response = request(absolute(id));
    const media = absolute(readPath(response, cfg.mediaPath));
    const name = str(readPath(response, cfg.detailTitlePath));
    const picture = absolute(readPath(response, cfg.detailImagePath));
    return JSON.stringify({ list: [{ vod_id: str(id), vod_name: name, vod_pic: picture,
        vod_play_from: '直连', vod_play_url: media ? '播放$' + media : '' }] });
}

function search(key, quick, pg) {
    const page = Math.max(1, parseInt(pg, 10) || 1);
    if (!cfg.searchRoute || !str(key).trim()) return result({}, page);
    return result(request(fill(cfg.searchRoute, page, key, null)), page);
}

function play(flag, id) {
    return JSON.stringify({ parse: 0, playUrl: '', url: absolute(id), header: cfg.headers || {} });
}

function sniffer() { return false; }
function isVideo(url) { return /\.(m3u8|mp4|flv|webm)(?:\?|$)/i.test(str(url)); }

export default { init, home, homeVod, category, detail, search, play, sniffer, isVideo };
