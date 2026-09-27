package com.github.tvbox.osc.base;

import android.text.TextUtils;

import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.ui.activity.MainActivity;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubscriptionConfig;
import com.github.tvbox.osc.util.Utils;
import com.kingja.loadsir.core.LoadSir;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;

import okhttp3.OkHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;

    public boolean isNormalStart;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo: 全局 OkHttpClient 初始化(:core-network, context 注入); Exo/Picasso 初始化拆回 app 侧
        OkGoHelper.init(this);
        initPicasso();
        // 安全 DNS 变更订阅:本模块的播放客户端与 Picasso 都要跟着换(必须在 initPicasso 之后注册)
        registerDohChangeListener();
        // EPG JSON 解析从启动主线程移除:首次直播取 EPG 信息时懒加载(EpgUtil.getEpgInfo 内自触发)
        // 初始化Web服务器
        ControlManager.init(this);
        // ApiConfig(:spider 模块) context + 局域网地址注入(替代直接依赖)
        com.github.tvbox.osc.api.ApiConfig.setAppContext(this);
        try {
            // server 未启动时 getAddress 会 NPE,此处兜底跳过(lanBase 由 startServer 就绪后注入)
            com.github.tvbox.osc.api.ApiConfig.setLanBase(ControlManager.get().getAddress(true));
        } catch (Throwable ignored) {
        }
        //初始化数据库(context 注入式:不再依赖 App 单例,见 AppDataManager)
        AppDataManager.init(this);
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance()
                .setExcludeFontScale(true)
                .setCustomFragment(true)
                .getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        // 播放器缓存清理移出启动主线程:延迟到首屏后再由后台低优先级线程执行,且超过阈值才清(见 schedulePlayerCacheCleanup)
        schedulePlayerCacheCleanup();
        initCrashConfig();
        Utils.initTheme();
        // 业务日志(系统类目):应用启动(旧 AppLog 文件通道已退役,统一走 LogStore 结构化日志)
        LogStore.log(Category.SYSTEM, "应用启动(Android " + android.os.Build.VERSION.RELEASE + ")");
        // 业务日志(系统类目):记录本机屏幕尺寸(宽×高,px),便于按机型定位布局/适配问题
        logDeviceScreenToBiz();
        // 崩溃捕获:未捕获异常落库(log 模块)
        LogStore.get().installCrashHandler();
        // 全局系统状态监控(网络/前后台/横竖屏/电量/磁盘, 基座层)必须先于下载模块初始化:
        // 下载模块在构造时会订阅网络事件(仅WiFi暂停/恢复),若监控未就绪订阅被跳过 → 切流量不停、恢复无法继续
        SystemStateMonitor.init(this);
        // 下载模块(:download) context 注入(保存目录/海报/网络监听/通知)
        com.github.tvbox.osc.download.DownloadFacade.init(this);
        // 组合根:收敛爬虫契约(:spider)服务注入(解析/手动判定/内容服务),见 AppCompositionRoot
        com.github.tvbox.osc.di.AppCompositionRoot.init();
        // 方案A:注册无头 WebView 嗅探器(嗅探型源任务启动前用它拿真实播放地址,串行复用保会话)
        com.github.tvbox.osc.download.DownloadFacade.setUrlSniffer(com.github.tvbox.osc.util.WebSniffResolver.get());
        // 下载完成通知渠道(可选增强)

        // 更新缓存清理:更新完成并安装后,首次启动删除"版本与当前一致"的本地 APK;并清半成品
        try {
            com.github.tvbox.osc.update.UpdateManager.cleanupOnAppStart(this);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 「跟随系统」下手机自己翻明暗:进程不会重启,但<b>生效主题</b>是按系统明暗解析出来的
     * (该类型的默认主题,见 {@code ThemeStore.resolveActive()}),不重解析就会出现
     * "资源已经是夜间、换肤层还按白天那套画(或压根没介入)"——弹窗、开关一类全错位。
     * <p>Application 的这个回调早于 Activity 重建,所以在这里做最早一次刷新;
     * {@code BaseActivity.attachBaseContext} 再兜一道(防某些 ROM 不派发应用级回调)。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            com.github.tvbox.osc.theme.ThemeRuntime.refresh();
            // 换主题顺带把"主题自带的默认背景"同步过去(明暗两套默认主题可以配不同的图)
            com.github.tvbox.osc.storage.theme.ThemeStore.applyActiveBackground();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记录本机屏幕尺寸到业务日志(系统类目,INFO):每次启动调用一次,便于按机型/分辨率定位
     * 布局与适配问题。用真实显示区域(含状态栏/导航栏,getRealMetrics);LogStore 未启用时静默丢弃。
     */
    private void logDeviceScreenToBiz() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.Display display = ((android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
            display.getRealMetrics(dm);
            int wDp = Math.round(dm.widthPixels / dm.density);
            int hDp = Math.round(dm.heightPixels / dm.density);
            LogStore.log(Category.SYSTEM, "设备屏幕: " + dm.widthPixels + "x" + dm.heightPixels
                    + " px(" + wDp + "x" + hDp + " dp,density=" + dm.density + ")");
        } catch (Throwable ignored) {
        }
    }

    /** 播放器缓存清理:延迟到首屏后再执行;仅当缓存超阈值才递归删除,低优先级后台线程 */
    private void schedulePlayerCacheCleanup() {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(() -> {
            Thread t = new Thread(() -> {
                try {
                    FileUtils.cleanPlayerCacheIfOverflow(100L * 1024 * 1024);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }, "player-cache-clean");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }, 5000L);
    }

    private void initParams() {
        // 现代化偏好存储(Preferences DataStore;标量域逐步迁移,见 DownloadPolicy 试点)
        com.github.tvbox.osc.config.PrefsDataStore.init(this);
        com.github.tvbox.osc.config.PrefsDataStore.put(HawkConfig.DEBUG_OPEN, false);

        // 主题系统:注入存储上下文 + 解析"这次启动用哪套配色"(自定义主题才有运行时换肤,
        // 内置亮/暗直接走编译期资源,不受影响)。必须在任何 Activity 创建前完成。
        com.github.tvbox.osc.storage.theme.ThemeStore.init(this);
        com.github.tvbox.osc.theme.ThemeRuntime.install();
        // 当前主题的默认背景同步给全局背景系统(用户显式设过底图时仍以他的为准,见 SystemConfig 的解析链)
        try {
            com.github.tvbox.osc.storage.theme.ThemeStore.applyActiveBackground();
        } catch (Throwable ignored) {
        }

        putDefault(HawkConfig.HOME_REC, 0);                  //推荐: 0=豆瓣热播, 1=站点推荐
        putDefault(HawkConfig.PLAY_TYPE, 2);                 //播放器: 0=系统, 1=IJK, 2=Exo
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE,2);           //后台播放: 0 关闭,1 开启,2 画中画
        putDefault(HawkConfig.DOH_URL, 0);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.HISTORY_NUM, 2);                //历史记录数量: 0=30, 1=50, 2=70
        putDefault(HawkConfig.APP_LOG, false);                //运行日志:默认关闭,排查问题时开启
        putDefault(HawkConfig.SUBTITLE_OPEN, false);          //字幕:默认关闭,播放器设置里可开关
        putDefault(HawkConfig.IGNORE_SSL_ERROR, false);       //忽略证书错误:默认关闭(开启会降低 TLS 安全性)
        putDefault(HawkConfig.LAN_SERVER_ENABLE, false);      //局域网服务:默认关闭(HTTP 服务仅监听 127.0.0.1)
        putDefault(HawkConfig.LOADING_ANIM, "");               //加载动画:空=默认,或 assets/loading/ 下的文件名
        putDefault(HawkConfig.LIVE_URL, "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.txt"); //直播源:默认地址
        putDefaultApi();
        // 日志模块初始化:按 LogConfig 同步开关(默认关),开启时自动启动 logcat 捕获(package:mine)
        // 注入版本号:日志行首展示(定位问题时确认是哪个版本产生)
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            int c = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            LogStore.setAppVersion(v + "(" + c + ")");
        } catch (Throwable ignored) {
        }
        // 日志配置持久化走 core-storage 配置封装(依赖倒置;先于 LogStore.init,避免同一键两侧分叉)
        com.github.tvbox.osc.log.LogConfig.setStore(com.github.tvbox.osc.config.DefaultLogConfigStore.get());
        LogStore.init(this);
    }

    private void putDefaultApi() {
        // 本机调试用的默认订阅清单是"默认订阅"的唯一来源(只放 debug 源集,release 包与克隆仓库都没有):
        // 文件在 -> 每次启动与列表同步(只增删**我们注入过**的条目,用户自建/改名的订阅一律不动);
        // 文件不在 -> 既不注入也不移除,完全尊重用户现有列表。
        List<Subscription> defaults = readDefaultSubscriptions();
        if (defaults == null) {
            // 关键:缺文件绝不等于"清单被清空"。若按"清单里没有就删掉注入项"处理,
            // 从带清单的包升级到不带清单的包(release/CI 包)时会一次性删光用户此前被注入的订阅,
            // 甚至把订阅列表清空、接口地址置空 —— 这正是历史上"升级后订阅消失"的成因。
            return;
        }
        List<Subscription> injected = SubscriptionConfig.getDefaultSubs();
        List<Subscription> subs = SubscriptionConfig.getSubscriptions();
        if (subs == null) subs = new ArrayList<>();

        List<String> injectedTags = SubscriptionConfig.getInjectedTags();

        // 迁移兼容(只做一次):早期版本用 DEFAULT_SUBS 当删除依据,升级后首次启动会把与该快照
        // 同名的用户自建订阅当成注入项删掉(实证:整表清空 + api_url 置空,订阅"更新后消失")。
        // 无注入记录时只建立基线——曾由旧版注入的项照旧补进记录,其余绝不删除。
        if (injectedTags == null) {
            injectedTags = new ArrayList<>();
            for (Subscription s : subs) {
                if (!SubscriptionConfig.isTagInjected(injectedTags, s)
                        && (containsSub(injected, s) || containsSub(defaults, s))) {
                    injectedTags.add(SubscriptionConfig.injectedTag(s));
                }
            }
        }

        // 删除依据 = "上次注入记录里确实注入过、但现在文件已移除"的条目(用户自建订阅永不触碰)
        List<String> removalTags = new ArrayList<>(injectedTags);
        for (Subscription def : defaults) {
            removalTags.remove(SubscriptionConfig.injectedTag(def));
        }

        boolean changed = false;
        boolean removedChecked = false;

        // 1) 移除:仅限确实注入过、且文件已不再提供的条目
        if (!removalTags.isEmpty()) {
            Iterator<Subscription> it = subs.iterator();
            while (it.hasNext()) {
                Subscription s = it.next();
                String tag = SubscriptionConfig.injectedTag(s);
                if (removalTags.contains(tag)) {
                    if (s.isChecked()) removedChecked = true;
                    LogStore.log(Category.SUBSCRIPTION, "订阅: 默认订阅已从清单移除 " + s.getName());
                    it.remove();
                    removalTags.remove(tag);
                    changed = true;
                }
            }
        }
        // 2) 补齐:仅文件里新增的默认订阅(不在注入记录中 = 本次文件新增,用户主动删除的不补回)
        for (Subscription def : defaults) {
            if (!SubscriptionConfig.isTagInjected(injectedTags, def) && !containsSub(subs, def)) {
                subs.add(new Subscription(def.getName(), def.getUrl()));
                injectedTags.add(SubscriptionConfig.injectedTag(def));
                changed = true;
            }
        }

        // 3) 勾选与接口地址维护
        if (subs.isEmpty()) {
            if (changed) {
                LogStore.log(Category.SUBSCRIPTION, "订阅: 列表已空(默认订阅被清单移除)");
                SubscriptionConfig.setSubscriptions(subs);
                SubscriptionConfig.setApiUrl("");
            }
        } else {
            boolean hasChecked = false;
            for (Subscription s : subs) {
                if (s.isChecked()) {
                    hasChecked = true;
                    break;
                }
            }
            if (!hasChecked || removedChecked || TextUtils.isEmpty(SubscriptionConfig.getApiUrl())) {
                subs.get(0).setChecked(true);
                SubscriptionConfig.setApiUrl(subs.get(0).getUrl());
                changed = true;
            }
            if (changed) SubscriptionConfig.setSubscriptions(subs);
        }

        // 4) 记录本次文件内容(快照)与注入记录,供下次同步
        SubscriptionConfig.setDefaultSubs(defaults);
        SubscriptionConfig.setInjectedTags(injectedTags);
    }

    private static boolean containsSub(List<Subscription> list, Subscription sub) {
        for (Subscription s : list) {
            if (TextUtils.equals(s.getName(), sub.getName()) && TextUtils.equals(s.getUrl(), sub.getUrl())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取本机调试用的默认订阅文件(只放在 debug 源集:app/src/debug/assets/config/default_subscriptions.json)
     * 格式: [{"name":"订阅名","url":"订阅地址"}, ...]
     * <p>
     * 文件存在(本机 debug 打包)则返回默认订阅列表;文件不存在(release 包、克隆仓库后的构建)返回 null。
     */
    private List<Subscription> readDefaultSubscriptions() {
        try {
            InputStream is = getAssets().open("config/default_subscriptions.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            JSONArray array = new JSONArray(sb.toString());
            List<Subscription> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String name = obj.optString("name", "");
                String url = obj.optString("url", "");
                if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(url)) {
                    list.add(new Subscription(name, url));
                }
            }
            return list;
        } catch (Throwable th) {
            LOG.e("readDefaultSubscriptions: " + th.getMessage());
            return null;
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // 销毁并清空 JS 源实例(经契约,不直连 :spider 的 JsLoader)
        com.github.tvbox.osc.spiderapi.SourceLoaderProviders.get().resetSources();
    }

    /** 写默认值:仅当现代化偏好存储尚无该键(各配置门面 getter 亦有默认兜底;不回写旧 Hawk) */
    private void putDefault(String key, Object value) {
        if (!com.github.tvbox.osc.config.PrefsDataStore.contains(key)) {
            com.github.tvbox.osc.config.PrefsDataStore.put(key, value);
        }
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(instance.getExternalCacheDir().getAbsolutePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    /**
     * Exo 播放内核客户端:懒建 + DoH 变更后整体替换(替代原 static final 字段)。
     * <p>
     * 为什么可换:安全 DNS 只在 build 时写进 client,定死实例就只能重启才换 DNS。
     * 而 Exo 侧不换的另一个原因是 {@link xyz.doikki.videoplayer.exo.ExoMediaSourceHelper}
     * 会把 client 包成 DataSource 工厂并缓存 —— 换 client 必须同时让那个工厂失效。
     */
    private static final class PlaybackHttp {
        static volatile OkHttpClient client;
        /** 安全 DNS 变更后置位:下次取用(起播/预加载)时才重建,避免打断正在播的流 */
        static volatile boolean dirty;
    }

    /**
     * 播放内核客户端(供 NetworkProvider.playback 复用同一实例)。
     * <p>
     * 懒建:首次起播时才构建(启动路径不再多做一次客户端构建);
     * 旧实例不 shutdown:正在播的流仍持有它的连接;新起的播放/预加载在下次取用时拿到新实例。
     */
    public static OkHttpClient playbackHttpClient() {
        OkHttpClient c = PlaybackHttp.client;
        if (c != null && !PlaybackHttp.dirty) return c;
        synchronized (PlaybackHttp.class) {
            if (PlaybackHttp.client == null || PlaybackHttp.dirty) {
                PlaybackHttp.client = buildPlaybackClient(PlaybackHttp.client);
                PlaybackHttp.dirty = false;
            }
            return PlaybackHttp.client;
        }
    }

    /**
     * @param previous 已有实例:重建时当作"根"派生,复用其连接池/线程池 ——
     *                 同一份配置只换 DNS,没必要连连接池一起丢掉
     */
    private static OkHttpClient buildPlaybackClient(OkHttpClient previous) {
        OkHttpClient.Builder builder = previous != null
                ? previous.newBuilder()
                : OkGoHelper.newBaseBuilder().retryOnConnectionFailure(true);
        builder.followRedirects(true);
        builder.followSslRedirects(true);
        builder.dns(OkGoHelper.currentDns());
        OkHttpClient c = builder.build();
        try {
            xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.getInstance(instance).setOkClient(c);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return c;
    }

    /**
     * 安全 DNS 变更:播放客户端与 Picasso 都要跟着换,否则"设置里改了、播放/海报还是老 DNS"。
     * <p>
     * 播放侧只<b>置脏</b>不立即重建:设置页点击时可能正在播,当场换 client 会让在跑的流
     * 落到被丢弃的连接池上(卡顿风险);置脏后由下一次取用统一重建。
     * 一次注册(不注销):本类生命周期 = 进程,监听列表用写时复制不会残留无效引用。
     */
    private void registerDohChangeListener() {
        // 写入方(设置页直接写 / 备份恢复 importConfig / 以后新增入口)一律经 SystemConfig 门面,
        // 这里订阅门面变更并复核 DoH:值真变了才重建(OkGoHelper 内部按 url 早退),
        // 这样"换安全 DNS"不依赖每个调用方都记得多调一次 refreshDnsOverHttps
        com.github.tvbox.osc.config.SystemConfig.subscribe(OkGoHelper::refreshDnsOverHttps);
        OkGoHelper.addDohChangeListener(url -> {
            PlaybackHttp.dirty = true;
            // 同步作废 Exo 已缓存的 DataSource 工厂(它裹着旧 client),下次起播按新 client 重建
            try {
                xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.getInstance(this).dropOkClient();
            } catch (Throwable th) {
                th.printStackTrace();
            }
            // Picasso 单例持有的是图片客户端实例:换 DNS 后换掉它的下载器
            // (Picasso 不允许重建单例,只能更换 downloader)
            reinitPicassoDownloader();
        });
    }

    /** 用当前图片客户端替换 Picasso 的下载器(DoH 变更时调用;Picasso 单例本身不可重建) */
    private void reinitPicassoDownloader() {
        try {
            OkHttpClient client = currentImageClient();
            if (client == null) return;
            com.github.tvbox.osc.picasso.MyOkhttpDownLoader downloader =
                    new com.github.tvbox.osc.picasso.MyOkhttpDownLoader(client);
            java.lang.reflect.Field f = com.squareup.picasso.Picasso.class.getDeclaredField("downloader");
            f.setAccessible(true);
            f.set(com.squareup.picasso.Picasso.get(), downloader);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 当前图片客户端(带分发配置)。
     * <p>
     * 为什么每次都要 setMaxRequestsPerHost:换 DoH 后图片客户端是新实例,而
     * {@code dispatcher()} 是它的成员,新实例只有默认值(5),漏配会让海报并发骤降。
     */
    private static OkHttpClient currentImageClient() {
        OkHttpClient client = OkGoHelper.getImageClient();
        if (client == null) client = OkGoHelper.getDefaultClient();
        if (client != null) client.dispatcher().setMaxRequestsPerHost(32);
        return client;
    }

    /** Picasso 全局单例（原 OkGoHelper.initPicasso 拆回 app 侧） */
    private void initPicasso() {
        try {
            // 图片专用客户端:共享默认连接池 + 100MB 磁盘缓存 + 缓存头兜底(OkGoHelper.getImageClient);
            // 修:搜索结果等长列表滑走再滑回时,海报不再因无磁盘缓存而回源重下
            OkHttpClient client = currentImageClient();
            if (client == null) return;
            com.github.tvbox.osc.picasso.MyOkhttpDownLoader downloader = new com.github.tvbox.osc.picasso.MyOkhttpDownLoader(client);
            com.squareup.picasso.Picasso picasso = new com.squareup.picasso.Picasso.Builder(this)
                    .downloader(downloader)
                    // 图片解码专用池:与搜索/爬虫共享大池隔离,大量海报解码不拖慢各来源搜索请求
                    .executor(com.github.tvbox.osc.util.HeavyTaskUtil.getImageExecutorService())
                    .defaultBitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .build();
            com.squareup.picasso.Picasso.setSingletonInstance(picasso);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void initCrashConfig(){
        //配置全局异常崩溃操作
        CaocConfig.Builder.create()
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) //背景模式,开启沉浸式
                .enabled(true) //是否启动全局异常捕获
                .showErrorDetails(true) //是否显示错误详细信息
                .showRestartButton(true) //是否显示重启按钮
                .trackActivities(true) //是否跟踪Activity
                .minTimeBetweenCrashesMs(2000) //崩溃的间隔时间(毫秒)
                .errorDrawable(R.drawable.ic_crash) //错误图标(记录空空的,矢量)
                .restartActivity(MainActivity.class) //重新启动后的activity
                .apply();
        // 魅族系统(如 ContentCapture 线程的 com.meizu.internal.picker)存在已知 NPE bug,
        // 属于系统问题而非 App 代码,直接吞掉避免整个 App 被杀,其余异常仍走 CAOC
        final Thread.UncaughtExceptionHandler caoc = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                if (isBenignSystemThrowable(thread, throwable)) {
                    return;
                }
                if (caoc != null) {
                    caoc.uncaughtException(thread, throwable);
                }
            }
        });
    }

    /** 判断是否为魅族系统内部的无害异常(系统线程 NPE 等),不应导致 App 崩溃 */
    private boolean isBenignSystemThrowable(Thread thread, Throwable throwable) {
        try {
            if (thread != null && "ContentCapture".equals(thread.getName())) {
                return true;
            }
            Throwable t = throwable;
            while (t != null) {
                for (StackTraceElement e : t.getStackTrace()) {
                    String cls = e.getClassName();
                    if (cls.startsWith("com.meizu.internal.") || cls.startsWith("com.meizu.picker.")) {
                        return true;
                    }
                }
                t = t.getCause();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

}