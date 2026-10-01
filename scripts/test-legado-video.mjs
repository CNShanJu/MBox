import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../app/src/main/assets/js/lib/legado_video.js', import.meta.url), 'utf8');
const spider = (await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))).default;
const requests = [];
globalThis.req = (url, options) => {
    requests.push({ url, headers: options.headers });
    if (url.includes('/api/videoplay/12')) {
        return { content: JSON.stringify({ rescont: {
            title: '示例影片', videopath: 'https://cdn.example/play.m3u8',
            coverbase64: { url: 'https://cdn.example/cover.jpg' }
        } }) };
    }
    return { content: JSON.stringify({ rescont: {
        data: [{ id: 12, title: '示例影片', coverbase64: { url: 'https://cdn.example/cover.jpg' } }],
        last_page: 3
    } }) };
};

spider.init({
    host: 'https://video.example',
    headers: { 'User-Agent': 'Browser', referer: 'https://video.example' },
    listPath: '$.rescont.data', titlePath: '$.title', imagePath: '$.coverbase64.url',
    detailTemplate: '/api/videoplay/{{$.id}}?uuid=1',
    mediaPath: '$.rescont.videopath', detailTitlePath: '$.rescont.title',
    detailImagePath: '$.rescont.coverbase64.url', lastPagePath: '$.rescont.last_page',
    classes: [{ type_id: '0', type_name: '最新' }],
    routes: { 0: 'https://video.example/api/videosort/0?page={page}' },
    searchRoute: 'https://video.example/api/videosort/0?serach={key}&page={page}'
});

assert.equal(JSON.parse(spider.home()).class[0].type_name, '最新');
const listing = JSON.parse(spider.category('0', 2));
assert.equal(listing.pagecount, 3);
assert.equal(listing.list[0].vod_id, 'https://video.example/api/videoplay/12?uuid=1');
const detail = JSON.parse(spider.detail(listing.list[0].vod_id));
assert.equal(detail.list[0].vod_play_url, '播放$https://cdn.example/play.m3u8');
assert.equal(JSON.parse(spider.play('直连', 'https://cdn.example/play.m3u8')).url,
    'https://cdn.example/play.m3u8');
spider.search('测试', false, 1);
assert.equal(requests[0].url, 'https://video.example/api/videosort/0?page=2');
assert.equal(requests[1].url, 'https://video.example/api/videoplay/12?uuid=1');
assert.equal(requests[2].url, 'https://video.example/api/videosort/0?serach=%E6%B5%8B%E8%AF%95&page=1');
assert.equal(requests[0].headers['User-Agent'], 'Browser');
console.log('阅读视频 JSON 源离线校验通过');
