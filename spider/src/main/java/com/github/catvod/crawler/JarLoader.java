package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    /**
     * 源创建失败冷却（与 JsLoader 同语义）：插件缺类 / 初始化抛异常这类确定性失败，
     * 冷却期内直接返回 SpiderNull，不再每次搜索/详情都重试一遍 loadClass 并把日志刷屏。
     * 原因留在 {@link SpiderFaults} 里，冷却期内也会重新登记，保证页面拿得到说法。
     */
    private ConcurrentHashMap<String, Long> createFailedAt = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String> createFailedReason = new ConcurrentHashMap<>();
    private static final long CREATE_FAIL_COOLDOWN_MS = 5 * 60 * 1000L;
    private volatile String recentJarKey = "";

    // ------------------------------------------------------------------
    // 插件崩溃隔离(2026-10-01)
    //
    // 现象(真机实测,魅族 21 / Android 16):社区 csp 包的 guard 类(DouDouGuard / BaseSpiderGuard)
    // 在构造器里就调自己的 native 库(`DexNative.getSpider`),而那段 native 拿到的类加载器是 null,
    // 于是在 null 上直接调 `ClassLoader.loadClass` → CheckJNI 抛
    // "JNI DETECTED ERROR IN APPLICATION: can't call ... loadClass ... on null object" → abort(SIGABRT)。
    // 那个 null 由 native 自己产生(`Init.loader()` 的值来自 native `DexNative.getLoader(ctx)`),
    // 宿主侧给不了它任何加载器(实测:把当前线程的 contextClassLoader 指向 jar 的 DexClassLoader 也无效)。
    // 关键:这是**进程级 native abort** —— Java 侧 catch 不到,崩溃页(CustomActivityOnCrash)也不会起来
    // (它只在 Java 未捕获异常时被拉起),于是 StartupGuard 的崩溃计数永远是 0、安全模式永不触发,
    // 用户看到的就是"每次进 App 直接退出",连进设置换源的机会都没有。
    //
    // 做法:进 native 之前先落一个"正在加载这份插件"的标记(files/spider_plugin_loading),
    // 加载与实例化都成功返回后再清掉。下次启动若发现标记还在,说明上个进程就死在这份插件上 ——
    // 把它的身份(路径+大小+mtime)记进 files/spider_plugin_blocked,之后不再加载它:
    // 该源的插件按 SpiderFaults 报"插件会导致 App 闪退(已停用)",App 照常起来,用户可去换源/更新订阅。
    // jar 一变(源更新、换订阅后重下 → 大小或时间必变)身份就不同,自动获得一次重试机会,无需人工清黑名单。
    // ------------------------------------------------------------------
    /** 进 native 前落的"正在加载这份插件"标记(崩溃后残留 = 上个进程死在这里) */
    private static final String PLUGIN_MARK_FILE = "spider_plugin_loading";
    /** 已确认"会让 App 闪退"的插件身份(每行一个,见 {@link #pluginId}) */
    private static final String PLUGIN_BLOCKED_FILE = "spider_plugin_blocked";
    private final Set<String> blockedPlugins = ConcurrentHashMap.newKeySet();
    private volatile boolean crashMarkChecked = false;
    /** 主 jar 因"会让 App 闪退"被停用(load() 判定):源取用它时据此给出说法,而不是只显示"暂无数据" */
    private volatile boolean mainJarBlocked = false;

    /**
     * 插件身份:路径 + 大小 + mtime。
     * <p>取"路径+大小+修改时间"而非单纯路径:源更新 / 换订阅后 jar 会被重下(大小或时间必变),
     * 身份随之改变、黑名单自然失效,相当于自动给新 jar 一次机会,不需要任何"解除停用"的人工操作。
     */
    private static String pluginId(File jar) {
        return jar.getAbsolutePath() + "|" + jar.length() + "|" + jar.lastModified();
    }

    private File pluginStateFile(String name) {
        return context == null ? null : new File(context.getFilesDir(), name);
    }

    /** 读一次上次进程留下的标记:还在 → 上个进程死在它上面 → 记进黑名单并停用 */
    private void checkPluginCrashMark() {
        if (crashMarkChecked) return;
        crashMarkChecked = true;
        try {
            File mark = pluginStateFile(PLUGIN_MARK_FILE);
            if (mark == null || !mark.exists()) return;
            String id = readText(mark).trim();
            //noinspection ResultOfMethodCallIgnored
            mark.delete();
            if (!id.isEmpty()) {
                appendLine(pluginStateFile(PLUGIN_BLOCKED_FILE), id);
                LOG.e("Csp", "上次启动死在这份插件上(native abort),已停用不再加载: " + id);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            loadBlockedPlugins();
        }
    }

    private void loadBlockedPlugins() {
        File blocked = pluginStateFile(PLUGIN_BLOCKED_FILE);
        if (blocked == null) return;
        for (String line : readText(blocked).split("\n")) {
            String id = line.trim();
            if (!id.isEmpty()) blockedPlugins.add(id);
        }
    }

    /** 这份插件是否已被判定"会让 App 闪退" */
    private boolean isPluginBlocked(File jar) {
        checkPluginCrashMark();
        return jar != null && blockedPlugins.contains(pluginId(jar));
    }

    /** 进 native 前落标记:必须同步落盘 —— abort 随时会来,异步写就丢了 */
    private void markPluginLoading(File jar) {
        File mark = pluginStateFile(PLUGIN_MARK_FILE);
        if (mark == null || jar == null) return;
        try (FileOutputStream out = new FileOutputStream(mark)) {
            out.write(pluginId(jar).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 插件加载/实例化成功返回:撤掉标记(标记还在 = 这次没走到这里,下次启动据此停用) */
    private void clearPluginLoading() {
        File mark = pluginStateFile(PLUGIN_MARK_FILE);
        if (mark == null) return;
        //noinspection ResultOfMethodCallIgnored
        mark.delete();
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.max(1, Math.min(f.length(), 8192))];
            int len = in.read(buf);
            return len <= 0 ? "" : new String(buf, 0, len, StandardCharsets.UTF_8);
        } catch (Throwable th) {
            return "";
        }
    }

    private static void appendLine(File f, String line) {
        if (f == null) return;
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 站点自带 jar 的缓存文件(files/&lt;jarKey&gt;.jar);main 就是订阅主 jar files/csp.jar */
    private File pluginJarFile(String jarKey) {
        if (context == null) return null;
        return new File(context.getFilesDir(), "main".equals(jarKey) ? "csp.jar" : jarKey + ".jar");
    }

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        clearSources();
        recentJarKey = "main";
        File jar = new File(cache);
        // 主 jar 已被判定会让 App 闪退:直接不加载(native abort 接不住,只能不进它的 native)
        mainJarBlocked = isPluginBlocked(jar);
        if (mainJarBlocked) {
            LOG.e("Csp", "主 jar 已停用(上次启动死在它上面),本次不加载: " + jar.getAbsolutePath());
            return false;
        }
        markPluginLoading(jar);
        boolean ok = loadClassLoader(cache, "main");
        clearPluginLoading();
        return ok;
    }

    /** 放掉全部源实例与缓存(jar 重载 / 换订阅都走这里);与 load 的差别:不重新加载任何 jar */
    public void reset() {
        clearSources();
        recentJarKey = "";
        // 换订阅 / jar 地址变了:插件层"会让 App 闪退"的结论一并作废(换的是另一份 jar);
        // 这也给用户留了恢复路径 —— 换个订阅再换回来,即重新给这份插件一次机会
        clearPluginCrashGuard();
    }

    /** 清掉插件崩溃隔离的全部状态(残留标记 + 黑名单);换订阅时调用,见 {@link #reset()} */
    private void clearPluginCrashGuard() {
        blockedPlugins.clear();
        mainJarBlocked = false;
        crashMarkChecked = false;
        File mark = pluginStateFile(PLUGIN_MARK_FILE);
        if (mark != null) {
            //noinspection ResultOfMethodCallIgnored
            mark.delete();
        }
        File blocked = pluginStateFile(PLUGIN_BLOCKED_FILE);
        if (blocked != null) {
            //noinspection ResultOfMethodCallIgnored
            blocked.delete();
        }
    }

    private void clearSources() {
        spiders.clear();
        proxyMethods.clear();
        classLoaders.clear();
        spiderJarKeys.clear();
        downloadFailedAt.clear();
        createFailedAt.clear();
        createFailedReason.clear();
        // 注意:这里**不能**清 loaderCache(见下方注释)—— 同一个 jar 反复新建 DexClassLoader
        // 会让插件里的原生库被 System.load 多次,第二次起 ART 拒绝在另一个 classloader 里打开同一个 .so。
        // jar/订阅整体换了:旧的"插件不可用"结论一并作废(新 jar 里可能已经有那个类了)
        SpiderFaults.get().clear();
    }

    // ------------------------------------------------------------------
    // 同一个 jar 只建一次 DexClassLoader(跨订阅解析 / 换源 / 重载复用)
    //
    // 为什么:每次解析订阅、每次换源都会走 load() → 新建 DexClassLoader,而插件里的原生库会因此被
    // System.load 再加载一次,ART 拒绝在另一个 classloader 里打开同一个 .so ——
    // 真机日志(2026-10-01,魅族 21 / Android 16):
    //   Shared library ".../files/soproxy-android-arm64.so" already opened by ClassLoader 0x11c7
    //   (DexClassLoader[… /files/csp.jar]); can't open in ClassLoader 0x78f26d289c(… /files/csp.jar)
    // csp 包的 soproxy 代理因此起不来,源请求永远等不到结果(首页只能靠 45s 看门狗收尾成空态)。
    //
    // 口径与插件崩溃隔离一致:身份 = 路径 + 大小 + mtime(jar 一变——源更新/换订阅重下——自动重建)。
    // 缓存是静态的:clearSources()/换源都会走,但同一份 jar 的 classloader 必须活下来。
    // ------------------------------------------------------------------
    private static final Map<String, DexClassLoader> loaderCache = new ConcurrentHashMap<>();
    /** loaderCache 上限:单进程内用到的 jar 不会多,超了丢一份最早的,避免长期驻留 */
    private static final int LOADER_CACHE_MAX = 6;

    /** 取这份 jar 已建好的 classloader(文件没变才复用) */
    private static DexClassLoader cachedClassLoader(File jar) {
        return jar == null ? null : loaderCache.get(pluginId(jar));
    }

    /** 记住这份 jar 的 classloader,并清掉同路径的旧身份、限制总量 */
    private static void rememberClassLoader(File jar, DexClassLoader loader) {
        if (jar == null || loader == null) return;
        String id = pluginId(jar);
        String prefix = jar.getAbsolutePath() + "|";
        for (String key : loaderCache.keySet()) {
            if (key.startsWith(prefix) && !key.equals(id)) loaderCache.remove(key);
        }
        loaderCache.put(id, loader);
        while (loaderCache.size() > LOADER_CACHE_MAX) {
            java.util.Iterator<String> it = loaderCache.keySet().iterator();
            if (!it.hasNext()) break;
            loaderCache.remove(it.next());
        }
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
            // 复用同一份 jar 的 classloader(见上方 loaderCache 注释):新建会让插件的原生库被二次 System.load。
            // 复用时 Init.init 与 Proxy 注册照旧再走一遍 —— 它们是幂等的,而且 proxyMethods 已随 clearSources 清空。
            DexClassLoader classLoader = cachedClassLoader(jarFile);
            if (classLoader == null) {
                classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, context.getClassLoader());
                rememberClassLoader(jarFile, classLoader);
            }
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
    public static boolean isLoadableArchive(File file) {
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
        // 已判定"会让 App 闪退"的插件(见上方 #pluginCrashGuard 注释)不再进它的 native,
        // 否则每次启动都会在同一处 abort,用户连换源的机会都没有。
        // 放在快路径之后:这个判定要读 jar 的大小/时间(两次 stat),不压到已建好的源的热路径上。
        if (isPluginBlocked(pluginJarFile(jarKey))) {
            SpiderFaults.get().markUnavailable(key, SpiderFaults.pluginCrashReason(clsKey));
            return new SpiderNull();
        }
        Long failedAt = createFailedAt.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < CREATE_FAIL_COOLDOWN_MS) {
            // 冷却期内不再重试（每次重试都是 loadClass + 一行 E 日志）；原因重新登记一次,UI 仍能给出说法
            SpiderFaults.get().markUnavailable(key, createFailedReason.get(key));
            return new SpiderNull();
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
            if (classLoader == null) {
                // 主 jar 被停用(会让 App 闪退)时给出明确说法:页面据此说明"为什么这个源不可用",
                // 而不是只显示"暂无数据"(与 SpiderFaultApi 的口径一致)
                if ("main".equals(jarKey) && mainJarBlocked)
                    SpiderFaults.get().markUnavailable(key, SpiderFaults.pluginCrashReason(clsKey));
                return new SpiderNull();
            }
            // guard 类的构造器里就调自己的 native 库 —— 先落标记:进程若在这里被 abort,
            // 下次启动就能知道"是这份插件干的",把它停用(见上方 #pluginCrashGuard 注释)
            markPluginLoading(pluginJarFile(jarKey));
            try {
                Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
                sp.init(context, ext);
                spiders.put(key, sp);
                // 创建成功:清掉该源的失败冷却与故障登记
                createFailedAt.remove(key);
                createFailedReason.remove(key);
                SpiderFaults.get().markAvailable(key);
                return sp;
            } catch (Throwable th) {
                // 日志必须带上站点 key 与 jar:原来只打类名,排查时根本不知道是哪个源、用的哪份 jar
                // (线上实例:某订阅的 cc 站点声明 csp_XPathGuard,而它自己的 jar 里没有这个类)
                LOG.e("Csp", "源初始化失败: key=" + key + " cls=csp_" + clsKey
                        + " jar=" + jarDesc(jarKey, jarUrl) + " " + th);
                String reason = SpiderFaults.isMissingClass(th)
                        ? SpiderFaults.missingClassReason(clsKey)
                        : SpiderFaults.initFailedReason(clsKey);
                createFailedAt.put(key, System.currentTimeMillis());
                createFailedReason.put(key, reason);
                SpiderFaults.get().markUnavailable(key, reason);
            } finally {
                // 走到这里说明这次没被 abort:撤掉标记,别把这份插件误判成"会让 App 闪退"
                clearPluginLoading();
            }
            return new SpiderNull();
        }
    }

    /** 日志用的 jar 标识:main 就是订阅主 jar(files/csp.jar),其余是站点自带 jar 的缓存文件 */
    private static String jarDesc(String jarKey, String jarUrl) {
        return "main".equals(jarKey) ? "main(files/csp.jar)" : jarUrl + " -> files/" + jarKey + ".jar";
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
