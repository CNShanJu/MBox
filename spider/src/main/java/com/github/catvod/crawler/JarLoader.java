package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    /** 注入的 application context（独立模块 :spider，由 ApiConfig.setAppContext 同步设置） */
    private static volatile Context context;

    public static void setContext(Context c) {
        context = c == null ? null : c.getApplicationContext();
    }

    private ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    /** 站点 key → 该站点所属 jar 的 key：代理请求要按"这个站点用哪个 jar"，而不是"最近取过哪个 jar" */
    private ConcurrentHashMap<String, String> spiderJarKeys = new ConcurrentHashMap<>();
    /** jar 下载失败冷却：失败后一段时间内不再重下（原实现每次调用都重下，弱网下反复白等） */
    private ConcurrentHashMap<String, Long> downloadFailedAt = new ConcurrentHashMap<>();
    private static final long DOWNLOAD_FAIL_COOLDOWN_MS = 5 * 60 * 1000L;
    private volatile String recentJarKey = "";

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        spiders.clear();
        recentJarKey = "main";
        proxyMethods.clear();
        classLoaders.clear();
        spiderJarKeys.clear();
        downloadFailedAt.clear();
        return loadClassLoader(cache, "main");
    }

    private boolean loadClassLoader(String jar, String key) {
        boolean success = false;
        try {
            ensureSpiderDb();
            File cacheDir = new File(context.getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            // Android 8+ 禁止加载可写的 dex/jar 文件(FileOutputStream 创建的文件为 0666,组/其他用户可写),
            // 加载前置为只读,否则抛 SecurityException: Writable dex file is not allowed
            File jarFile = new File(jar);
            if (jarFile.exists()) {
                jarFile.setReadOnly();
            }
            DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, context.getClassLoader());
            // make force wait here, some device async dex load
            int count = 0;
            do {
                try {
                    Class classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    if (classInit != null) {
                        Method method = classInit.getMethod("init", Context.class);
                        method.invoke(null, context);
                        LOG.i("Csp", "自定义爬虫代码加载成功!");
                        success = true;
                        try {
                            Class proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                            Method mth = proxy.getMethod("proxy", Map.class);
                            proxyMethods.put(key, mth);
                        } catch (Throwable th) {
                            // jar 里没有 com.github.catvod.spider.Proxy 属正常(该源不做代理)，不打日志噪音
                        }
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 5);

            if (success) {
                classLoaders.put(key, classLoader);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    /**
     * 部分第三方爬虫 jar 的 Init(类加载期)会打开本应用私有库 databases/tv
     * (CatVod 系主应用才有该库;本 App Room 库名为 tvbox.v3.db,不存在 "tv")。
     * 缺失文件 → 刷 SQLite CANTOPEN;只有空库 → 刷 no such table Config。
     * 预置一个带 Config(url/type/time) 空表的 SQLite 文件,使其查询成功返回空,消除两类噪音。
     */
    private void ensureSpiderDb() {
        try {
            if (context == null) return;
            File dbFile = context.getDatabasePath("tv");
            File dir = dbFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            android.database.sqlite.SQLiteDatabase db = null;
            try {
                db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null);
                db.execSQL("CREATE TABLE IF NOT EXISTS Config(url TEXT, type INTEGER, time INTEGER)");
            } catch (Throwable ignored) {
            } finally {
                if (db != null) {
                    try {
                        db.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** jar 缓存无 md5 时的最长信任期:既不必每次冷启动都重下重载,也不会永远吃一份老 jar */
    static final long STALE_JAR_MS = 3L * 24 * 60 * 60 * 1000;

    /**
     * 缓存/下载下来的文件是否像个可加载的包:zip(jar/apk,头 "PK")或裸 dex(头 "dex")。
     * <p>
     * 订阅里的 jar 多数没有 md5,下载端可能拿到错误页/登录页(HTML)并被当成 jar 存下来 →
     * DexClassLoader 加载失败,而用户只看到"这个源打不开"。这里把它挡在加载之前并留下日志。
     */
    static boolean isLoadableArchive(File file) {
        if (file == null || !file.exists() || file.length() < 4) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[4];
            if (in.read(head) < 4) return false;
            boolean zip = head[0] == 'P' && head[1] == 'K';
            boolean dex = head[0] == 'd' && head[1] == 'e' && head[2] == 'x';
            return zip || dex;
        } catch (Throwable th) {
            return false;
        }
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        // containsKey：ConcurrentHashMap.contains 判的是"值"，而这里存的是 DexClassLoader、键才是
        // String → 原写法恒 false，缓存永不命中，每次首次创建都重下 jar + 新建 DexClassLoader
        if (classLoaders.containsKey(key))
            return classLoaders.get(key);
        Long failedAt = downloadFailedAt.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < DOWNLOAD_FAIL_COOLDOWN_MS) {
            LOG.e("Csp", "jar 下载失败冷却中，暂不重试：" + jar);
            return null;
        }
        File cache = new File(context.getFilesDir().getAbsolutePath() + "/" + key + ".jar");
        boolean cacheUsable = isLoadableArchive(cache);
        if (cacheUsable && !md5.isEmpty()) {
            cacheUsable = MD5.getFileMd5(cache).equalsIgnoreCase(md5);
        } else if (cacheUsable && System.currentTimeMillis() - cache.lastModified() > STALE_JAR_MS) {
            // 没有 md5 可比对:文件本身看着正常就先用,但过期就重下,避免永远用一份老 jar
            cacheUsable = false;
        }
        if (cacheUsable && loadClassLoader(cache.getAbsolutePath(), key)) {
            return classLoaders.get(key);
        }
        try {
            HttpClient.downloadSync(jar, cache);
            if (!isLoadableArchive(cache)) {
                LOG.e("Csp", "下载到的内容不是 jar/dex 包(可能是错误页)：" + jar);
                cache.delete();
                downloadFailedAt.put(key, System.currentTimeMillis());
                return null;
            }
            // 下载校验：md5 是订阅里给的唯一凭据，原来下完直接 DexClassLoader 加载，内容不对也照跑
            if (!md5.isEmpty() && !MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                LOG.e("Csp", "jar 校验失败(md5 不符)：" + jar);
                cache.delete();
                downloadFailedAt.put(key, System.currentTimeMillis());
                return null;
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classLoaders.get(key);
        } catch (Throwable e) {
            LOG.e("Csp", "jar 下载/加载失败：" + jar + " " + e);
            downloadFailedAt.put(key, System.currentTimeMillis());
        }
        return null;
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey = "";
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        // 与旧实现一致:缓存命中也要更新 recentJarKey(proxyInvoke 的兜底依赖"最近取过的 jar")
        recentJarKey = jarKey;
        // 记录"这个站点用的是哪个 jar":代理请求按站点反查，不能靠"最近创建过谁"
        spiderJarKeys.put(key, jarKey);
        // 快路径:已创建的源直接返回(无锁),不同源调用可并行
        Spider cached = spiders.get(key);
        if (cached != null) {
            return cached;
        }
        // 慢路径:首次创建串行化(下载 jar / DexClassLoader / newInstance / init),避免并行创建相互踩踏
        synchronized (this) {
            cached = spiders.get(key);
            if (cached != null) {
                return cached;
            }
            DexClassLoader classLoader = null;
            if (jarKey.equals("main"))
                classLoader = classLoaders.get("main");
            else {
                classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
            }
            if (classLoader == null)
                return new SpiderNull();
            try {
                Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
                sp.init(context, ext);
                spiders.put(key, sp);
                return sp;
            } catch (Throwable th) {
                LOG.e("Csp", "源初始化失败：" + clsKey + " " + th);
            }
            return new SpiderNull();
        }
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map params) {
        try {
            // 代理请求来自本机 HTTP 服务线程，"最近创建过哪个 jar"跟它毫无关系：
            // 多 jar 订阅下会把代理打到错误的 jar（结果错或直接失败）。
            // 优先按请求里的 siteKey 反查该站点所属 jar，取不到才退回旧行为。
            Method proxyFun = null;
            Object siteKey = params == null ? null : params.get("siteKey");
            String jarKey = siteKey == null ? null : spiderJarKeys.get(String.valueOf(siteKey));
            if (jarKey != null) proxyFun = proxyMethods.get(jarKey);
            if (proxyFun == null) proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
            LOG.e("proxyInvoke", "未找到代理方法：siteKey=" + siteKey + " recentJarKey=" + recentJarKey);
        } catch (Throwable th) {
            // 原来是空 catch：代理出错连一行日志都没有，只能看到播放器 500
            LOG.e("proxyInvoke", th);
        }
        return null;
    }
}
