package com.github.tvbox.osc.api;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FCallBack;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.SubUrlResolver;
import com.github.tvbox.osc.util.SubUrlResolvers;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.github.tvbox.osc.spiderapi.JarCachePolicy;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 * <p>
 * 实现 {@link com.github.tvbox.osc.spiderapi.SourceConfigApi}：源配置元信息契约
 * （getSource/getHomeSourceBean/getVipParseFlags），由 AppCompositionRoot 注入，
 * app 侧 ViewModel 经接口读取，不直依赖本类。
 */
public class ApiConfig implements com.github.tvbox.osc.spiderapi.SourceConfigApi {
    /** 注入的 application context（独立模块 :spider，不依赖 app 类） */
    private static volatile Context appContext;
    /** 局域网地址前缀（由 app 侧 ControlManager 初始化后注入，替代直接依赖） */
    private static volatile String lanBase = "";

    /** App 启动时注入 context（配置缓存目录等用；同步给爬虫 loader / FileUtils / UA / JS 本地桥 / catvod Init） */
    public static void setAppContext(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        com.github.catvod.crawler.JarLoader.setContext(context);
        com.github.catvod.crawler.JsLoader.setContext(context);
        com.github.tvbox.osc.util.FileUtils.setContext(context);
        com.github.tvbox.osc.util.UA.setContext(context);
        com.github.tvbox.osc.util.js.local.setContext(context);
        // catvod.Init：合并 jar/JS 侧通用工具（Path.cache/files/asset、Util.androidId 等）都读它，
        // 全仓原先零注入点 → Init.context() 恒 NPE；DoH 改动把它拉进 OkHttp 的静态初始化后，
        // 直接炸成 ExceptionInInitializerError 并永久毒化该进程内的所有 JS/JAR 源网络请求
        com.github.catvod.Init.set(context);
    }

    /** 局域网地址前缀注入（app 侧 ControlManager.get().getAddress(true) 设置） */
    public static void setLanBase(String base) {
        lanBase = base == null ? "" : base;
    }

    /** 局域网地址前缀（util.js 等用） */
    public static String getLanBase() {
        return lanBase;
    }

    private static Context getAppContext() {
        return appContext;
    }

    private static ApiConfig instance;
    private LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private List<LiveChannelGroup> liveChannelGroupList;
    /** 订阅源自带的直播(主直播源 = 设置里配置的直播源不可用时的兜底;没有则为空) */
    private List<LiveChannelGroup> subscribeLiveGroupList;
    private List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private List<IJKCode> ijkCodes;
    private String spider = null;
    public String wallpaper = "";

    private SourceBean emptyHome = new SourceBean();

    private JarLoader jarLoader = new JarLoader();
    private JsLoader jsLoader = new JsLoader();

    private String userAgent = "okhttp/3.15";

    private String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private ApiConfig() {
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        subscribeLiveGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            // 去掉 BOM 与开头的 // 注释行(部分源配置带注释,Gson 不支持注释)
            content = stripJsonNoise(content);
            if (AES.isJson(content)) return content;
            Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    /** 剥离配置开头的 BOM 与 // 注释行 */
    private static String stripJsonNoise(String content) {
        if (content == null || content.isEmpty()) return content;
        if (content.charAt(0) == '\ufeff') {
            content = content.substring(1);
        }
        String trimmed = content.trim();
        while (trimmed.startsWith("//")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) {
                trimmed = "";
                break;
            }
            trimmed = trimmed.substring(nl + 1).trim();
        }
        return trimmed.isEmpty() ? content : trimmed;
    }

    private static byte[] getImgJar(String body){
        Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if(matcher.find()){
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        String apiUrl = PrefsDataStore.getString(HawkConfig.API_URL, "");
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        File cache = new File(getAppContext().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        if (useCache && cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        String TempKey = null, configUrl = "", pk = ";pk;";
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            TempKey = a[1];
            if (apiUrl.startsWith("clan")){
                configUrl = clanToAddress(a[0]);
            }else if (apiUrl.startsWith("http")){
                configUrl = a[0];
            }else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + configUrl;
        } else {
            configUrl = apiUrl;
        }
        String configKey = TempKey;
        // 统一特殊处理接口:当前未注册任何特殊源,所有订阅一律按正常规则直接拉取。
        // 将来确有需要特殊动作(如地址变换)的源时,在 SubUrlResolvers 注册实现即可,命中才生效。
        SubUrlResolver resolver = SubUrlResolvers.find(configUrl);
        if (resolver != null) {
            configUrl = resolver.transform(configUrl);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", requestAccept);
        HttpClient.get(configUrl, headers, null, new HCallBack() {
            @Override
            public void onSuccess(String content) {
                try {
                    String result = FindResult(content, configKey);
                    if (apiUrl.startsWith("clan")) {
                        result = clanContentFix(clanToAddress(apiUrl), result);
                    }
                    //假相對路徑
                    result = fixContentPath(apiUrl, result);
                    parseJson(apiUrl, result);
                    try {
                        File cacheDir = cache.getParentFile();
                        if (!cacheDir.exists())
                            cacheDir.mkdirs();
                        if (cache.exists())
                            cache.delete();
                        FileOutputStream fos = new FileOutputStream(cache);
                        fos.write(result.getBytes("UTF-8"));
                        fos.flush();
                        fos.close();
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    LogStore.success(Category.SUBSCRIPTION, "订阅: 加载配置成功 " + apiUrl);
                    callback.success();
                } catch (Throwable th) {
                    th.printStackTrace();
                    LogStore.fail(Category.SUBSCRIPTION, "订阅: 配置解析失败 " + apiUrl + " " + th.getMessage());
                    callback.error(parseErrorTip(th));
                }
            }

            @Override
            public void onError(Throwable e) {
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 配置拉取失败 " + apiUrl + " " + (e != null ? e.getMessage() : ""));
                if (cache.exists()) {
                    try {
                        parseJson(apiUrl, cache);
                        LogStore.log(Category.SUBSCRIPTION, "订阅: 拉取失败改用本地缓存配置 " + apiUrl);
                        callback.success();
                        return;
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
                callback.error("拉取配置失败\n" + (e != null ? e.getMessage() : ""));
            }
        });
    }

    /**
     * 解析失败给用户的提示:缺 sites 的多半是导入的内容根本不是订阅配置(如「阅读」App 的书源),
     * 此时只说"解析配置失败"用户无从下手(线上实例:反复重启、重选订阅都恢复不了),
     * 故给出可照做的说法(提示弹窗右上角即"切换订阅"入口)。
     */
    private static String parseErrorTip(Throwable th) {
        String detail = th == null ? null : th.getMessage();
        if (detail != null && detail.contains("不是订阅配置")) {
            return "该订阅不是 TVBox 配置";
        }
        return "解析配置失败";
    }

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(getAppContext().getFilesDir().getAbsolutePath() + "/csp.jar");
        LogStore.log(Category.SUBSCRIPTION, "订阅: 开始更新爬虫 jar " + jarUrl);

        String realJarUrl = jarUrl.replace("img+", "");
        boolean isJarInImg = jarUrl.startsWith("img+");
        // 订阅换了吗(爬虫 jar 地址变了 = 本地这份 jar 属于别的订阅/别的仓库):
        // 换了就先把本地 jar 全清掉 —— 旧 jar 里的类与当前站点列表无关,留着只会得到一批
        // "源初始化失败"(线上实例:换订阅后仍加载上一份 jar,站点声明的类不在里面)
        String lastJarUrl = legacyPrefs(HawkConfig.SPIDER_JAR_URL, "");
        boolean subscriptionSwitched = JarCachePolicy.jarUrlChanged(lastJarUrl, realJarUrl);
        if (subscriptionSwitched) {
            clearSourceJarCache("订阅已更换(爬虫 jar 地址变了)");
        }
        if (!realJarUrl.equals(lastJarUrl)) {
            PrefsDataStore.put(HawkConfig.SPIDER_JAR_URL, realJarUrl);
        }

        boolean cacheExists = cache.exists();
        // md5 是订阅声明"这份 jar 应该是什么"的唯一凭据:配置里带了 md5 就必须校验通过,
        // useCache(带缓存配置重启)只回答"允许用本地缓存",不能把 md5 不符的旧 jar 当可用(见 JarCachePolicy)
        String cachedMd5 = cacheExists && !md5.isEmpty() ? MD5.getFileMd5(cache) : "";
        if (JarCachePolicy.cacheUsable(cacheExists, md5, cachedMd5, useCache)) {
            if (jarLoader.load(cache.getAbsolutePath())) {
                LogStore.success(Category.SUBSCRIPTION, "订阅: 使用缓存爬虫 jar " + jarUrl);
                callback.success();
            } else {
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 缓存爬虫 jar 加载失败 " + jarUrl);
                callback.error("");
            }
            return;
        }
        if (cacheExists && !md5.isEmpty()) {
            LogStore.log(Category.SUBSCRIPTION, "订阅: 本地爬虫 jar 与订阅声明不符(md5),重新下载 " + jarUrl);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", requestAccept);
        if (isJarInImg) {
            HttpClient.get(realJarUrl, headers, null, new HCallBack() {
                @Override
                public void onSuccess(String respData) {
                    try {
                        File cacheDir = cache.getParentFile();
                        if (!cacheDir.exists())
                            cacheDir.mkdirs();
                        byte[] imgJar = getImgJar(respData);
                        // 解出来不是包就别动本地那份:原来无条件 delete+覆盖,解码失败会把上一份
                        // 可用 jar 一起毁掉(兜底也就没得兜了)
                        if (imgJar == null || imgJar.length < 4 || imgJar[0] != 'P' || imgJar[1] != 'K') {
                            LogStore.fail(Category.SUBSCRIPTION, "订阅: 爬虫 jar 图片套路解析失败(内容不是包) " + realJarUrl);
                            fallbackToLocalJar(cache, cacheExists, realJarUrl, callback);
                            return;
                        }
                        if (cache.exists())
                            cache.delete();
                        FileOutputStream fos = new FileOutputStream(cache);
                        fos.write(imgJar);
                        fos.flush();
                        fos.close();
                        onJarDownloaded(cache, md5, callback, realJarUrl);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 爬虫 jar 图片套路解析失败 " + realJarUrl);
                        fallbackToLocalJar(cache, cacheExists, realJarUrl, callback);
                    }
                }

                @Override
                public void onError(Throwable e) {
                    LogStore.fail(Category.SUBSCRIPTION, "订阅: 爬虫 jar 下载失败 " + realJarUrl + " " + (e != null ? e.getMessage() : ""));
                    fallbackToLocalJar(cache, cacheExists, realJarUrl, callback);
                }
            });
        } else {
            HttpClient.download(realJarUrl, cache, headers, null, new FCallBack() {
                @Override
                public void onSuccess(File file) {
                    onJarDownloaded(cache, md5, callback, realJarUrl);
                }

                @Override
                public void onError(Throwable e) {
                    LogStore.fail(Category.SUBSCRIPTION, "订阅: 爬虫 jar 下载失败 " + realJarUrl + " " + (e != null ? e.getMessage() : ""));
                    fallbackToLocalJar(cache, cacheExists, realJarUrl, callback);
                }
            });
        }
    }

    /**
     * jar 下载/解码失败时的兜底:本地还有一份"像包"的 jar 就先顶上(否则本次一个 jar 源都用不了)。
     * <p>
     * 语义边界:只在"订阅没换、只是这次没拉下来"时兜底(地址换了的那份属于别的订阅,
     * 顶上来只会得到一堆看不懂的失败,已在 {@link #loadJar} 开头清掉);
     * 且无论成败都给用户失败提示 —— 用的可能不是订阅当前那份,不能装作一切正常。
     */
    private void fallbackToLocalJar(File cache, boolean cacheExists, String jarUrl, LoadConfigCallback callback) {
        if (cacheExists && JarLoader.isLoadableArchive(cache) && jarLoader.load(cache.getAbsolutePath())) {
            LogStore.log(Category.SUBSCRIPTION, "订阅: 爬虫 jar 更新失败,回退使用本地缓存(可能与订阅不一致,部分源会不可用) " + jarUrl);
        }
        callback.error("");
    }

    /**
     * 清掉订阅作用域的本地爬虫 jar(files/csp.jar 与各站点自带 jar 的缓存 files/&lt;md5&gt;.jar)。
     * 只在"订阅换了"时调用:这些文件与旧订阅的站点列表一一对应,留着既占地方,
     * 又会在下次加载时被当成可用缓存(旧 jar 里没有新站点声明的类 → 整源空白)。
     */
    private void clearSourceJarCache(String why) {
        try {
            File dir = getAppContext().getFilesDir();
            File[] files = dir == null ? null : dir.listFiles();
            int removed = 0;
            if (files != null) {
                for (File f : files) {
                    if (f == null || !f.isFile() || !f.getName().endsWith(".jar")) continue;
                    if (f.delete()) removed++;
                }
            }
            // 内存里的 DexClassLoader / 源实例同样作废(文件已删,别再用它们应答)
            jarLoader.reset();
            LogStore.log(Category.SUBSCRIPTION, "订阅: " + why + ",清理本地爬虫 jar " + removed + " 个");
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void onJarDownloaded(File cache, String md5, LoadConfigCallback callback, String jarUrl) {
        // 兼容图片套路:部分源把 jar 伪装成 .jpg,内容是 图片+**+base64(jar)(与配置同套路),需先解码
        try {
            if (!isZipFile(cache)) {
                byte[] raw = readFileBytes(cache);
                if (raw != null && raw.length > 0) {
                    String body = new String(raw, "UTF-8");
                    byte[] imgJar = getImgJar(body);
                    if (imgJar != null && imgJar.length > 0 && imgJar[0] == 'P' && imgJar[1] == 'K') {
                        if (cache.exists() && !cache.canWrite()) {
                            cache.setWritable(true);
                        }
                        FileOutputStream fos = new FileOutputStream(cache);
                        fos.write(imgJar);
                        fos.flush();
                        fos.close();
                        LogStore.log(Category.SUBSCRIPTION, "订阅: 爬虫 jar 图片套路解码成功 " + jarUrl);
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        // 订阅给了 md5 就必须相符:否则多半是错误页/半截包(原实现下完直接加载,内容不对也照跑)
        if (!md5.isEmpty() && !md5.equalsIgnoreCase(MD5.getFileMd5(cache))) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 爬虫 jar 校验失败(md5 不符) " + jarUrl);
            cache.delete();
            callback.error("");
            return;
        }
        if (jarLoader.load(cache.getAbsolutePath())) {
            LogStore.success(Category.SUBSCRIPTION, "订阅: 爬虫 jar 加载成功 " + jarUrl);
            callback.success();
        } else {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 爬虫 jar 加载失败(文件可能损坏或与蜘蛛不匹配) " + jarUrl);
            callback.error("");
        }
    }

    private static boolean isZipFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] head = new byte[4];
            int n = fis.read(head);
            fis.close();
            return n == 4 && head[0] == 'P' && head[1] == 'K';
        } catch (Throwable th) {
            return false;
        }
    }

    private static byte[] readFileBytes(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            return data;
        } catch (Throwable th) {
            return null;
        }
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        System.out.println("从本地缓存加载" + f.getAbsolutePath());
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private void parseJson(String apiUrl, String jsonStr) {
        // 裸站点条目/数组(旧版导入或用户手改过的本地订阅文件)先补 {"sites":[…]} 外壳再解析:
        // 这类内容缺 sites,直接解析只会报"解析配置失败"(sites 已存在时不改动,避免静默丢其它字段)
        if (jsonStr != null && !jsonStr.contains("\"sites\"")) {
            String wrapped = CmsApiRules.wrapSiteJson(jsonStr);
            if (wrapped != null && !wrapped.isEmpty()) {
                LogStore.log(Category.SUBSCRIPTION, "订阅: 裸站点内容补 sites 外壳 " + apiUrl);
                jsonStr = wrapped;
            }
        }
        JsonObject infoJson = new Gson().fromJson(jsonStr, JsonObject.class);
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 远端站点源
        SourceBean firstSite = null;
        if (sourceBeanList!= null)
            sourceBeanList.clear();
        // 远端站点源:缺 sites 说明这份内容不是订阅配置(如误把单站点条目/采集数据当订阅存了),
        // 给出可读原因,交由 loadConfig 的 onError/缓存兜底处理;不再直接抛 NPE
        JsonElement sitesEl = infoJson == null ? null : infoJson.get("sites");
        if (sitesEl == null || !sitesEl.isJsonArray()) {
            throw new IllegalStateException("不是订阅配置(缺少 sites):" + apiUrl);
        }
        for (JsonElement opt : sitesEl.getAsJsonArray()) {
            JsonObject obj = (JsonObject) opt;
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.get("name").getAsString().trim());
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            if(obj.has("ext") && (obj.get("ext").isJsonObject() || obj.get("ext").isJsonArray())){
                sb.setExt(obj.get("ext").toString());
            }else {
                sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            }
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            if (firstSite == null)
                firstSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = legacyPrefs(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null)
                setSourceBean(firstSite);
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if(infoJson.has("parses")){
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = legacyPrefs(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }
        // ── 直播源:优先"用户在设置里配置的直播源"(默认内置 iptv 源),它为空/加载失败才退到订阅源自带的直播 ──
        // 订阅源自带的直播(内嵌分组,或 proxy:// / fengmi 形式的直播地址)只作兜底:
        // 以前是订阅源的内嵌频道直接顶掉用户配的直播源(列表里多分组时直播页只会用订阅源那份,用户配的直播源形同虚设);
        // 现在两者分开:主列表 = 用户直播源(包成一个待拉取的代理分组),兜底列表 = 订阅源自带直播。
        // 两个都没有 → 主列表为空(主页不显示直播入口,直播页提示"频道列表为空")。
        liveChannelGroupList.clear();           //修复从后台切换重复加载频道列表
        subscribeLiveGroupList.clear();
        String liveURL = SystemConfig.getLiveUrl();
        String subscribeLiveUrl = null;
        try {
            if (infoJson.has("lives") && infoJson.get("lives").getAsJsonArray() != null) {
                JsonObject livesOBJ = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                String lives = livesOBJ.toString();
                int index = lives.indexOf("proxy://");
                if (index != -1) {
                    int endIndex = lives.lastIndexOf("\"");
                    String url = lives.substring(index, endIndex);
                    url = DefaultConfig.checkReplaceProxy(url);

                    //clan
                    String extUrl = Uri.parse(url).getQueryParameter("ext");
                    if (extUrl != null && !extUrl.isEmpty()) {
                        String extUrlFix;
                        if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                            extUrlFix = extUrl;
                        } else {
                            extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        }
                        if (extUrlFix.startsWith("clan://")) {
                            extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix);
                        }

                        System.out.println("Live URL :" + extUrlFix);
                        putLiveHistory(extUrlFix);
                        // 订阅源的直播地址:留着当兜底(用户直播源为空/失败时才用)
                        subscribeLiveUrl = extUrlFix;
                    }

                    // takagen99 : Getting EPG URL from File Config & put into Settings
                    if (livesOBJ.has("epg")) {
                        String epg = livesOBJ.get("epg").getAsString();
                        System.out.println("EPG URL :" + epg);
                        PrefsDataStore.put(HawkConfig.EPG_URL, epg);
                    }

                } else {

                    // if FongMi Live URL Formatting exists
                    if (!lives.contains("type")) {
                        // 订阅源内嵌频道列表:只作兜底,不直接当主列表(否则会顶掉用户配的直播源)
                        loadLivesInto(infoJson.get("lives").getAsJsonArray(), subscribeLiveGroupList);
                    } else {
                        JsonObject fengMiLives = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                        String type = fengMiLives.get("type").getAsString();
                        if (type.equals("0")) {
                            String url = fengMiLives.get("url").getAsString();

                            // takagen99 : Getting EPG URL from File Config & put into Settings
                            if (fengMiLives.has("epg")) {
                                String epg = fengMiLives.get("epg").getAsString();
                                System.out.println("EPG URL :" + epg);
                                PrefsDataStore.put(HawkConfig.EPG_URL, epg);
                            }

                            if (url.startsWith("http")) {
                                System.out.println("Live URL :" + url);
                                putLiveHistory(url);
                                // 订阅源的直播地址:留着当兜底(用户直播源为空/失败时才用)
                                subscribeLiveUrl = url;
                            }
                        }
                    }
                }
            }


        } catch (Throwable th) {
            th.printStackTrace();
        }

        // 订阅源兜底:内嵌分组直接用;只给了地址就包成一个"待拉取"的代理分组(与用户直播源同形)
        if (subscribeLiveGroupList.isEmpty() && !StringUtils.isBlank(subscribeLiveUrl)) {
            LiveChannelGroup subscribeGroup = proxyLiveGroup(subscribeLiveUrl);
            if (subscribeGroup != null) subscribeLiveGroupList.add(subscribeGroup);
        }
        // 优先用户配置的直播源;没配才用订阅源的
        if (!StringUtils.isBlank(liveURL)) {
            LiveChannelGroup liveGroup = proxyLiveGroup(liveURL);
            if (liveGroup != null) liveChannelGroupList.add(liveGroup);
        } else {
            liveChannelGroupList.addAll(subscribeLiveGroupList);
            // 已经在用订阅源的直播了:没有"另一份"可兜底,免得失败后拿同一份重试一遍
            subscribeLiveGroupList.clear();
        }


        //video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        rule.add(one.getAsString());
                    }

                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                    }
                }
            }
        }

        String defaultIJKADS="{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson=new Gson().fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址:默认名单进程内只装一次(幂等),当前源名单每次解析配置都整体替换。
        // 旧实现用 AdBlocker.isEmpty() 当"只初始化一次"的开关,导致切源后新源的 ads 永远加不进来、
        // 旧源的 ads 一直生效(且 clear() 全仓无人调用,名单无法刷新)。
        if (defaultJson != null && defaultJson.get("ads") != null && defaultJson.get("ads").isJsonArray()) {
            List<String> defaultAds = new ArrayList<>();
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                if (host != null && !host.isJsonNull()) defaultAds.add(host.getAsString());
            }
            AdBlocker.ensureDefaultHosts(defaultAds);
        }
        List<String> sourceAds = new ArrayList<>();
        if (infoJson != null && infoJson.has("ads") && infoJson.get("ads").isJsonArray()) {
            for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                if (host != null && !host.isJsonNull()) sourceAds.add(host.getAsString());
            }
        }
        AdBlocker.setSourceHosts(sourceAds);
        // IJK解码配置
        if(ijkCodes==null){
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = PrefsDataStore.getString(HawkConfig.IJK_CODEC, "");
            JsonArray ijkJsonArray = infoJson.has("ijk")?infoJson.get("ijk").getAsJsonArray():defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
    }

    /** 直播源历史列表 gson 类型(LiveConfig 同格式,DataStore json 共享) */
    private static final Type LIVE_HISTORY_TYPE = new TypeToken<ArrayList<String>>() {
    }.getType();

    private void putLiveHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> liveHistory = PrefsDataStore.getJson(HawkConfig.LIVE_HISTORY, LIVE_HISTORY_TYPE, new ArrayList<String>());
            if (!liveHistory.contains(url))
                liveHistory.add(0, url);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            PrefsDataStore.putJson(HawkConfig.LIVE_HISTORY, liveHistory);
        }
    }

    /** 用直播源 json(lives 数组)重建<b>主</b>频道分组(直播页拉完直播源后调用) */
    public void loadLives(JsonArray livesArray) {
        loadLivesInto(livesArray, liveChannelGroupList);
    }

    /**
     * 把 lives 数组解析成分组写进目标列表。
     * <p>
     * 两种用途:① 直播页拉取直播源成功后重建<b>主</b>列表;② 源配置解析时把订阅源内嵌的频道
     * 写进<b>兜底</b>列表(见 {@link #getFallbackChannelGroupList()})。
     */
    private void loadLivesInto(JsonArray livesArray, List<LiveChannelGroup> target) {
        target.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            // "分组名_密码" 是直播源里给分组设密码的约定写法,但分组名里带下划线也很常见
            // (CCTV_高清 / 央视_4K / 港澳台_HD)：一律把后半段当密码会让这些分组"打不开"
            // (要输一个用户不可能知道的后缀,LiveChannelAuth.needInputPassword 只看密码是否非空)。
            // 故只把"纯 4~8 位数字"的后缀当密码(源里写 _8888/_1234 这类),其余保留下划线保留原名。
            String[] splitGroupName = groupName.split("_", 2);
            String password = splitGroupName.length > 1 && looksLikeGroupPassword(splitGroupName[1])
                    ? splitGroupName[1].trim() : "";
            liveChannelGroup.setGroupName(password.isEmpty() ? groupName : splitGroupName[0]);
            liveChannelGroup.setGroupPassword(password);
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + Integer.toString(sourceIndex));
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            target.add(liveChannelGroup);
        }
    }

    /**
     * 把直播源地址包成一个"待拉取"的代理分组:直播页看到<b>单个</b>、以 {@code http://127.0.0.1}
     * 开头的分组,就去把该地址拉下来解析成频道(见 LiveActivity.loadProxyLives)。
     * <p>
     * 用户在设置里配的直播源、订阅源里的直播地址都用这个形态,加载逻辑只有一套。
     * <p>
     * 注意:这个回环 URL 是**信封/标记**,没有谁会去请求它 —— 直播页只按前缀识别
     * ({@code isProxyOnly}),真正的直播源地址在 {@code ext} 参数里,拉取时解出来直连,
     * 不会打到本机 HTTP 服务。端口仍跟随注入的本机基址,避免"信封写 9978、真实端口 9979"的不一致。
     */
    private LiveChannelGroup proxyLiveGroup(String liveUrl) {
        if (StringUtils.isBlank(liveUrl)) return null;
        String encoded = Base64.encodeToString(liveUrl.getBytes(StandardCharsets.UTF_8),
                Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
        LiveChannelGroup group = new LiveChannelGroup();
        group.setGroupName(loopbackBase() + "proxy?do=live&type=txt&ext=" + encoded);
        return group;
    }

    /** 本机服务默认端口(与 RemoteServer 默认值一致;仅在本机基址尚未注入时兜底) */
    private static final int FALLBACK_LOCAL_PORT = 9978;

    /**
     * 本机回环基址(含动态端口):本机 HTTP 服务就绪后由 ControlManager 经 {@link #setLanBase} 注入。
     * 端口可能因 9978 被占用而 +1 回退,所以这里读注入值而不是把端口写死;服务未启动(注入为空,
     * 或注入的不是回环地址)时回落 {@link #FALLBACK_LOCAL_PORT}。
     */
    private static String loopbackBase() {
        String base = getLanBase();
        if (base != null && base.startsWith("http://127.0.0.1")) return base;
        return "http://127.0.0.1:" + FALLBACK_LOCAL_PORT + "/";
    }

    /**
     * 判断 "_" 后缀是不是分组密码:仅 4~8 位纯数字算密码。
     * <p>
     * 判定从严的理由:误判成"加密分组"的代价是该分组直接打不开(要输对后缀才显示频道),
     * 而漏判的代价只是不做密码保护 —— 前者对用户伤害大得多。故 {@code CCTV_5}、{@code CCTV_高清}、
     * {@code 央视_4K} 这类都保留原名、不设密码。
     */
    private static boolean looksLikeGroupPassword(String suffix) {
        if (suffix == null) return false;
        String s = suffix.trim();
        if (s.length() < 4 || s.length() > 8) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        boolean js = sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?");
        if (js) return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
    }

    @SuppressWarnings("unchecked")
    public Object[] proxyLocal(Map param) {
        // do=js 的代理请求必须交给 JS 源：Global.js2Proxy 生成的正是 proxy?do=js&from=catvod，
        // JsSpider.proxyLocal 也只认 from=catvod。原来无论什么都只转 jarLoader.proxyInvoke，
        // 而 JsLoader.proxyInvoke 全仓零调用点 → JS 源的代理(含 Exo 走 127.0.0.1:9978/proxy?do=js)
        // 恒为 null/500。
        Object doParam = param == null ? null : param.get("do");
        if ("js".equals(String.valueOf(doParam).trim())) {
            return jsLoader.proxyInvoke((Map<String, String>) param);
        }
        return jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void retry();

        void error(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key))
            return null;
        return sourceBeanList.get(key);
    }


    /** 读现代化偏好键(DataStore 权威;旧 Hawk 迁移已退役) */
    private static String legacyPrefs(String key, String def) {
        return PrefsDataStore.getString(key, def);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        PrefsDataStore.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        PrefsDataStore.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    /** 主直播分组:用户在设置里配置的直播源(单个待拉取的代理分组);没配时才回落到订阅源自带的直播 */
    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    /**
     * 兜底直播分组 = <b>订阅源自带</b>的直播(内嵌频道分组,或订阅源里的直播地址包成的代理分组)。
     * 只在主直播源(= 设置里配置的直播源)没内容/加载失败时才用;没有则返回空列表。
     */
    public List<LiveChannelGroup> getFallbackChannelGroupList() {
        return subscribeLiveGroupList;
    }

    /**
     * 避免离线(订阅未配置成功时),parseJson未调用,ijkCodes未初始化报空指针
     * @return
     */
    private List<IJKCode> offlineGetIjkCodes() {

        String defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson = new Gson().fromJson(defaultIJKADS, JsonObject.class);

        List<IJKCode> ijkCodes = new ArrayList<>();
        boolean foundOldSelect = false;
        String ijkCodec = PrefsDataStore.getString(HawkConfig.IJK_CODEC, "");
        JsonArray ijkJsonArray = defaultJson.get("ijk").getAsJsonArray();
        for (JsonElement opt : ijkJsonArray) {
            JsonObject obj = (JsonObject) opt;
            String name = obj.get("group").getAsString();
            LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
            for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                JsonObject cObj = (JsonObject) cfg;
                String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                String val = cObj.get("value").getAsString();
                baseOpt.put(key, val);
            }
            IJKCode codec = new IJKCode();
            codec.setName(name);
            codec.setOption(baseOpt);
            if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                codec.selected(true);
                ijkCodec = name;
                foundOldSelect = true;
            } else {
                codec.selected(false);
            }
            ijkCodes.add(codec);
        }
        if (!foundOldSelect && ijkCodes.size() > 0) {
            ijkCodes.get(0).selected(true);
        }
        return ijkCodes;
    }

    /**
     * 订阅成功还是用拉取的ijk解码配置,未拉取成功时用默认的
     * @return
     */
    public List<IJKCode> getIjkCodes() {
        return ijkCodes==null?offlineGetIjkCodes():ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = PrefsDataStore.getString(HawkConfig.IJK_CODEC, "");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        List<IJKCode> all = getIjkCodes();
        if (name != null) {
            for (IJKCode code : all) {
                if (name.equals(code.getName())) {
                    return code;
                }
            }
        }
        // 原实现直接 ijkCodes.get(0):离线时成员字段 ijkCodes 为 null 或列表为空都会崩溃,统一从非空列表取
        return all.isEmpty() ? null : all.get(0);
    }

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", lanBase + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            if(!url.startsWith("http") && !url.startsWith("clan://")){
                url = "http://" + url;
            }
            if(url.startsWith("clan://"))url=clanToAddress(url);
            content = content.replace("./", url.substring(0,url.lastIndexOf("/") + 1));
        }
        return content;
    }
}