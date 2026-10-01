import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// 离线校验 Java ext 中按分类保存的路径能被实际 JS 抓取源使用。
const source = readFileSync(new URL('../app/src/main/assets/js/lib/maccms.js', import.meta.url), 'utf8');
const spider = (await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))).default;
const requested = [];
globalThis.req = url => {
    requested.push(url);
    return { content: '<a href="/catalog/index.php/vod/detail/id/1.html">测试影片</a>' };
};

spider.init({
    host: 'https://video.example',
    prefix: '/catalog',
    listUrl: '/catalog/index.php/vod/type/id/{id}.html',
    listPageUrl: '/catalog/index.php/vod/type/id/{id}/page/{pg}.html',
    classRoutes: {
        87: {
            listUrl: '/catalog/index.php/vod/type/id/{id}.html',
            listPageUrl: '/catalog/index.php/vod/type/id/{id}/page/{pg}.html'
        },
        251: {
            listUrl: '/catalog/index.php/vod/show/id/{id}.html',
            listPageUrl: '/catalog/index.php/vod/show/id/{id}/page/{pg}.html'
        }
    }
});
spider.category('87', 1, false, {});
spider.category('251', 1, false, {});
spider.category('251', 2, false, {});

assert.deepEqual(requested, [
    'https://video.example/catalog/index.php/vod/type/id/87.html',
    'https://video.example/catalog/index.php/vod/show/id/251.html',
    'https://video.example/catalog/index.php/vod/show/id/251/page/2.html'
]);
console.log('maccms 分类独立路由校验通过');
