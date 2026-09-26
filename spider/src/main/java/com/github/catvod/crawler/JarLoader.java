package com.github.catvod.crawler;

import android.content.Context;

import android.content.Context;
import com.github.tvbox.osc.util.HttpClient;
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
                        System.out.println("自定义爬虫代码加载成功!");
                        success = true;
                        try {
                            Class proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                            Method mth = proxy.getMethod("proxy", Map.class);
                            proxyMethods.put(key, mth);
                        } catch (Throwable th) {

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

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        if (classLoaders.contains(key))
            return classLoaders.get(key);
        File cache = new File(context.getFilesDir().getAbsolutePath() + "/" + key + ".jar");
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                loadClassLoader(cache.getAbsolutePath(), key);
                return classLoaders.get(key);
            }
        }
        try {
            HttpClient.downloadSync(jar, cache);
            loadClassLoader(cache.getAbsolutePath(), key);
            return classLoaders.get(key);
        } catch (Throwable e) {
            e.printStackTrace();
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
        // 与旧实现一致:缓存命中也要更新 recentJarKey(proxyInvoke 依赖"最近取过的 jar")
        recentJarKey = jarKey;
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
                th.printStackTrace();
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
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {

        }
        return null;
    }
}
