package com.github.catvod.crawler;


import android.content.Context;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.js.JsSpider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

public class JsLoader {
    /** 注入的 application context（独立模块 :spider，由 ApiConfig.setAppContext 同步设置） */
    private static volatile Context context;

    public static void setContext(Context c) {
        context = c == null ? null : c.getApplicationContext();
    }

    private static ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, Class<?>> classs = new ConcurrentHashMap<>();
    /**
     * 按源分把创建锁（原为一把全局 CreateLock）。
     * <p>
     * 首次创建要下载 jar / 编译 JS 模块 / 写模块缓存，同一源的创建必须串行；但不同源之间没有共享
     * 写入的资源（模块缓存文件的并发写已在 FileUtils.setCacheByte 里原子化），用全局锁会让
     * 一个卡死的源把<b>所有源</b>的首次创建一起堵死。实例化后的源走无锁快路径，调用仍并行。
     */
    private static final ConcurrentHashMap<String, Object> CREATE_LOCKS = new ConcurrentHashMap<>();

    /** 创建失败的源（含初始化超时）：冷却期内直接返回 SpiderNull，不再每次调用都白等一个超时 */
    private static final ConcurrentHashMap<String, Long> CREATE_FAILED_AT = new ConcurrentHashMap<>();
    /** 与冷却配套的失败原因：冷却期内不再真的重试，但仍要把原因登记给 UI（见 SpiderFaults） */
    private static final ConcurrentHashMap<String, String> CREATE_FAILED_REASON = new ConcurrentHashMap<>();
    private static final long CREATE_FAIL_COOLDOWN_MS = 5 * 60 * 1000L;

    /**
     * 常驻 JS 源实例上限。
     * <p>
     * 每个 {@link JsSpider} 都是"1 个 QuickJSContext(native 运行时 + 一整块 JS 堆)+ 1 条单线程
     * executor + 1 个 Timer 线程"。原实现只增不减(只有重载订阅才会 {@link #load} 清空),
     * 正常浏览几十个源就攒下几十套运行时与近百条线程,机器上表现为内存一路走高、
     * 低配盒子上最终 Too many open files / OOM。
     * <p>
     * 取 16 而不是更小:聚合搜索/首页推荐会同时用到多个源,回收正在用的源会立刻触发重建
     * (重建要重编译 JS 模块,是秒级卡顿);16 能覆盖一次会话里的活跃集,真正冷下来的才回收。
     */
    private static final int MAX_LIVE_SPIDERS = 16;

    /** 源的最近使用时间(毫秒),LRU 依据;随源创建/命中更新，回收时同步清理 */
    private static final ConcurrentHashMap<String, Long> LAST_USED = new ConcurrentHashMap<>();

    public static void load() {
        // 只"退役"不硬拆：源实例可能仍被首页/详情/播放器持有，立刻 shutdownNow+ctx.destroy
        // 会让那些调用撞 RejectedExecutionException 或踩已销毁的 QuickJS 上下文（见 JsSpider.destroy）
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
        }
        for (Spider spider : spiders.values()){
            spider.destroy();
        }
        spiders.clear();
        classs.clear();
        LAST_USED.clear();
        CREATE_FAILED_AT.clear();
        CREATE_FAILED_REASON.clear();
        // 源实例整体重建:旧的"源不可用"结论作废
        SpiderFaults.get().clear();
    }

    public static void stopAll() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
        }
    }

    private boolean loadClassLoader(String jar, String key) {
        boolean success = false;
        Class<?> classInit = null;
        try {
            File cacheDir = new File(context.getCacheDir().getAbsolutePath() + "/catvod_jsapi");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            // Android 8+ 禁止加载可写的 dex/jar 文件,加载前置为只读
            File jarFile = new File(jar);
            if (jarFile.exists()) {
                jarFile.setReadOnly();
            }
            DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, context.getClassLoader());
            // make force wait here, some device async dex load
            int count = 0;
            do {
                try {
                    classInit = classLoader.loadClass("com.github.catvod.js.Method");
                    if (classInit != null) {
                        LOG.i("QuJs", "自定义jsapi加载成功!");
                        success = true;
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 5);

            if (success) {
                classs.put(key, classInit);
            }
        } catch (Throwable th) {
            LOG.e("QuJs", th);
        }
        return success;
    }

    private Class<?> loadJarInternal(String jar, String md5, String key) {
        // containsKey：ConcurrentHashMap.contains 是"按值判断"，而这里存的是 Class/DexClassLoader，
        // 键才是 String → 原写法恒 false，于是每次首次创建都重下 jar + 新建 DexClassLoader
        // （还在创建锁里、内含最多 5×200ms 轮询；dex 不可卸载，换几次订阅就涨一块 native 内存）
        if (classs.containsKey(key))
            return classs.get(key);
        File cache = new File(context.getFilesDir().getAbsolutePath() + "/" + key + ".jar");
        // 缓存判定与校验语义与 JarLoader.loadJarInternal 一致(见那里的注释):
        // 像个包 + md5 相符(有 md5 时)/ 未过期(没 md5 时)
        boolean cacheUsable = JarLoader.isLoadableArchive(cache);
        if (cacheUsable && !md5.isEmpty()) {
            cacheUsable = MD5.getFileMd5(cache).equalsIgnoreCase(md5);
        } else if (cacheUsable && System.currentTimeMillis() - cache.lastModified() > JarLoader.STALE_JAR_MS) {
            cacheUsable = false;
        }
        if (cacheUsable) {
            loadClassLoader(cache.getAbsolutePath(), key);
            if (classs.containsKey(key)) return classs.get(key);
        }
        try {
            HttpClient.downloadSync(jar, cache);
            if (!JarLoader.isLoadableArchive(cache)) {
                LOG.e("QuJs", "下载到的内容不是 jar/dex 包(可能是错误页)： " + jar);
                cache.delete();
                return null;
            }
            // 下载校验：md5 是订阅里给的唯一完整性凭据，原来下完直接加载，给错了也照用
            if (!md5.isEmpty() && !MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                LOG.e("QuJs", "jar 校验失败(md5 不符)： " + jar);
                cache.delete();
                return null;
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classs.get(key);
        } catch (Throwable e) {
            LOG.e("QuJs", "jar 下载/加载失败：" + jar + " " + e);
            // 失败不删缓存文件：文件本身可能完好，只是下载/加载这一步失败，留着下次可用
        }
        return null;
    }
    private volatile String recentJarKey = "";


    public Spider getSpider(String key, String api, String ext, String jar) {
        // 快路径:已创建的源直接返回(无锁),使不同源的调用可真正并行
        Spider cached = spiders.get(key);
        if (cached != null) {
            recentJarKey = key;
            LAST_USED.put(key, System.currentTimeMillis());
            return cached;
        }
        Long failedAt = CREATE_FAILED_AT.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < CREATE_FAIL_COOLDOWN_MS) {
            // 冷却期内不再重试：坏源重复创建会每次都白等一个初始化超时，把创建/调用的道一起占住
            // （原因重新登记一次:详情页/列表页在整个冷却期都还能给出"这个源为什么不可用"）
            SpiderFaults.get().markUnavailable(key, CREATE_FAILED_REASON.get(key));
            return new SpiderNull();
        }
        // 慢路径:同一源首次创建串行化(下载 jar / new JsSpider 编译 JS 模块 / 写模块缓存 / init)
        synchronized (CREATE_LOCKS.computeIfAbsent(key, k -> new Object())) {
            cached = spiders.get(key);
            if (cached != null) {
                recentJarKey = key;
                LAST_USED.put(key, System.currentTimeMillis());
                return cached;
            }
            Class<?> classLoader = null;
            try {
                if (!jar.isEmpty()) {
                    String[] urls = jar.split(";md5;");
                    String jarUrl = urls[0];
                    String jarKey = MD5.string2MD5(jarUrl);
                    String jarMd5 = urls.length > 1 ? urls[1].trim() : "";
                    classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
                }
                recentJarKey = key;
                JsSpider sp = new JsSpider(key, api, classLoader);
                try {
                    sp.init(context, ext);
                } catch (Throwable th) {
                    // init 失败/超时的实例必须销毁：它已经建好了 QuickJS 上下文与单线程 executor，
                    // 而实例从未进 map，load() 永远遍历不到它（反复导入含坏源的订阅即累积泄漏）
                    LOG.e("QuJs", th);
                    sp.destroy();
                    markCreateFailed(key, SpiderFaults.jsLoadFailedReason(), th);
                    return new SpiderNull();
                }
                spiders.put(key, sp);
                LAST_USED.put(key, System.currentTimeMillis());
                CREATE_FAILED_AT.remove(key);
                CREATE_FAILED_REASON.remove(key);
                SpiderFaults.get().markAvailable(key);
                trimLiveSpiders();
                return sp;
            } catch (Throwable th) {
                LOG.e("QuJs", th);
                String reason = SpiderFaults.isMissingClass(th)
                        ? SpiderFaults.jsMissingClassReason(api)
                        : SpiderFaults.jsLoadFailedReason();
                markCreateFailed(key, reason, th);
            }
            return new SpiderNull();
        }
    }

    private static void markCreateFailed(String key, String reason, Throwable th) {        CREATE_FAILED_AT.put(key, System.currentTimeMillis());
        CREATE_FAILED_REASON.put(key, reason);
        SpiderFaults.get().markUnavailable(key, reason);
        LOG.e("QuJs", "源创建失败(" + key + ")，" + (CREATE_FAIL_COOLDOWN_MS / 60000) + " 分钟内不再重试：" + th);
    }

    /**
     * 超出 {@link #MAX_LIVE_SPIDERS} 时按 LRU 回收<b>空闲</b>源实例。
     * <p>
     * 三条口径:
     * <ul>
     *   <li><b>只回收空闲的</b>({@link JsSpider#isIdle()}):正在跑的源被回收,页面会拿到空结果
     *       (虽然不会崩,见 JsSpider.call 的 retired 判定),而全部都在跑时宁可不回收;</li>
     *   <li><b>先摘 map 再 destroy</b>:摘掉之后晚到的调用会重新走创建路径(重建一个可用实例),
     *       而 destroy 只是"退役",已发起的调用照旧跑完、空闲后才真正销毁(native 上下文只在 JS 线程上销毁);</li>
     *   <li><b>回收失败不影响本次返回</b>:调用方拿到的是刚创建好的源,回收纯粹是内存治理。</li>
     * </ul>
     */
    private static void trimLiveSpiders() {
        int over = spiders.size() - MAX_LIVE_SPIDERS;
        if (over <= 0) return;
        try {
            List<Map.Entry<String, Long>> idle = new ArrayList<>();
            for (Map.Entry<String, Long> e : LAST_USED.entrySet()) {
                Spider s = spiders.get(e.getKey());
                if (s == null) {
                    LAST_USED.remove(e.getKey()); // 已被移除的源(load/上次回收):顺手清掉记录,别让这张表也长起来
                    continue;
                }
                if (s instanceof JsSpider && ((JsSpider) s).isIdle()) {
                    idle.add(e);
                }
            }
            if (idle.isEmpty()) return;
            idle.sort((a, b) -> Long.compare(a.getValue(), b.getValue())); // 最久未用的排前面
            int removed = 0;
            for (Map.Entry<String, Long> e : idle) {
                if (removed >= over) break;
                String key = e.getKey();
                Spider s = spiders.remove(key);
                if (s == null) continue;
                LAST_USED.remove(key);
                removed++;
                try {
                    s.destroy();
                } catch (Throwable th) {
                    LOG.e("QuJs", "回收源实例失败(已从缓存移除)：" + key + " " + th);
                }
            }
            if (removed > 0) {
                LOG.i("QuJs", "源实例缓存回收 " + removed + " 个(上限 " + MAX_LIVE_SPIDERS + ",剩余 " + spiders.size() + ")");
            }
        } catch (Throwable th) {
            LOG.e("QuJs", "源实例缓存回收异常：" + th);
        }
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            // 代理请求来自本机 HTTP 服务线程，与"最近创建过哪个源"没有任何关系。
            // 优先按请求里的 siteKey 定位源（Global.js2Proxy 生成的地址带 siteKey），找不到再退回旧行为。
            Spider proxyFun = spiders.get(siteKeyOfProxyParam(params == null ? null : params.get("siteKey")));
            if (proxyFun == null) {
                proxyFun = spiders.get(recentJarKey);
            }
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params);
            }
            LOG.e("proxyInvoke", "未找到可用的 JS 源：siteKey=" + (params == null ? null : params.get("siteKey")));
        } catch (Throwable th) {
            LOG.e("proxyInvoke", th);
        }
        return null;
    }

    /**
     * 代理地址里的 siteKey 与内部 map 键对齐。
     * <p>
     * JS 源从 cfg 里拿到的是 {@code skey = "J"+MD5(siteKey)}（见 JsSpider.cfg），所以请求里
     * 既可能是订阅里的 site.key，也可能是这个 J 前缀键，两种都要认。
     */
    private static String siteKeyOfProxyParam(String paramKey) {
        if (paramKey == null || paramKey.isEmpty()) return null;
        if (spiders.containsKey(paramKey)) return paramKey;
        for (String siteKey : spiders.keySet()) {
            if (paramKey.equals("J" + MD5.encode(siteKey))) return siteKey;
        }
        return null;
    }
}
