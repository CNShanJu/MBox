package com.github.catvod.crawler;


import android.content.Context;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.js.JsSpider;

import java.io.File;
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
    /** spider 创建串行锁:spiders/classs 为静态,首次创建(下载 jar/编译 JS 模块/写模块缓存)必须串行,
     *  不同源并行取源时才不会相互踩踏;已创建的源走无锁快路径直接返回,调用仍并行。 */
    private static final Object CREATE_LOCK = new Object();

    public static void load() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
            spider.destroy();
        }
        spiders.clear();
        classs.clear();
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
                        System.out.println("自定义jsapi加载成功!");
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
            th.printStackTrace();
        }
        return success;
    }

    private Class<?> loadJarInternal(String jar, String md5, String key) {
        if (classs.contains(key))
            return classs.get(key);
        File cache = new File(context.getFilesDir().getAbsolutePath() + "/" + key + ".jar");
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                loadClassLoader(cache.getAbsolutePath(), key);
                return classs.get(key);
            }
        }
        try {
            HttpClient.downloadSync(jar, cache);
            loadClassLoader(cache.getAbsolutePath(), key);
            return classs.get(key);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }
    private volatile String recentJarKey = "";


    public Spider getSpider(String key, String api, String ext, String jar) {
        // 快路径:已创建的源直接返回(无锁),使不同源的调用可真正并行
        Spider cached = spiders.get(key);
        if (cached != null) {
            recentJarKey = key;
            return cached;
        }
        // 慢路径:首次创建串行化(下载 jar / new JsSpider 编译 JS 模块 / 写模块缓存 / init)
        synchronized (CREATE_LOCK) {
            cached = spiders.get(key);
            if (cached != null) {
                recentJarKey = key;
                return cached;
            }
            Class<?> classLoader = null;
            if (!jar.isEmpty()) {
                String[] urls = jar.split(";md5;");
                String jarUrl = urls[0];
                String jarKey = MD5.string2MD5(jarUrl);
                String jarMd5 = urls.length > 1 ? urls[1].trim() : "";
                classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
            }
            recentJarKey = key;
            try {
                Spider sp = new JsSpider(key, api, classLoader);
                sp.init(context, ext);
                spiders.put(key, sp);
                return sp;
            } catch (Throwable th) {
                th.printStackTrace();
                LOG.e("QuJS", th);
            }
            return new SpiderNull();
        }
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            Spider proxyFun = spiders.get(recentJarKey);
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params);
            }
        } catch (Throwable th) {
            LOG.e("proxyInvoke", th);
        }
        return null;
    }
}
