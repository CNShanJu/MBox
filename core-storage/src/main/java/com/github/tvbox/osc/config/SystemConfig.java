package com.github.tvbox.osc.config;



import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * 系统配置门面（配置门面模式 3.6：数据自持 + 模块内持久化 + 变更订阅）。
 * <p>
 * 数据维护在系统层（common 模块内，Hawk 键沿用旧应用 key，与历史设置兼容）；
 * 对外只暴露 查询 / 操作 / 订阅 / 备份：
 * <ul>
 *   <li>查询：getDohUrl / getTheme / getThemeCustomId / getThemeDefaultId / getLoadingAnim / getHomeRec / getHistoryNum / getLiveUrl / isPrivateBrowsing</li>
 *   <li>操作：对应 setXxx（内部校验 + 持久化 + 广播变更）</li>
 *   <li>订阅：{@link #subscribe(Listener)}——设置页等关注方刷新 UI</li>
 *   <li>备份：{@link #exportConfig()} / {@link #importConfig(Map)}（BackupDialog 聚合）</li>
 * </ul>
 * 联动（如 DNS 切换后重建 DnsOverHttps、主题切换后重载 UI）由关注方订阅/调用方执行，
 * 门面只保证"数据 + 持久化 + 广播"。
 */
public final class SystemConfig {

    // 键沿用旧应用 key（兼容历史设置）
    private static final String KEY_DOH_URL = "doh_url";
    private static final String KEY_THEME = "theme_tag";
    private static final String KEY_LOADING_ANIM = "loading_anim";
    private static final String KEY_HOME_REC = "home_rec";
    private static final String KEY_HISTORY_NUM = "history_num";
    private static final String KEY_SEARCH_RESULT_LAYOUT = "search_result_layout";
    private static final String KEY_LIVE_URL = "live_url";
    private static final String KEY_PRIVATE_BROWSING = "private_browsing";
    // UI/功能偏好（同样沿用旧应用 key，历史设置兼容）
    private static final String KEY_SHOW_PREVIEW = "show_preview";
    private static final String KEY_FAST_SEARCH_MODE = "fast_search_mode";
    private static final String KEY_DEBUG_OPEN = "debug_open";
    private static final String KEY_IGNORE_SSL_ERROR = "ignore_ssl_error";
    private static final String KEY_LAN_SERVER_ENABLE = "lan_server_enable";
    private static final String KEY_LAN_PAIRING_CODE = "lan_pairing_code";
    private static final String KEY_INTERNAL_RESTART_AT = "internal_restart_at";
    private static final String KEY_THEME_RESTART_CACHE_AT = "_private_theme_restart_cache_at";
    private static final String KEY_AUTO_UPDATE_PROMPT_VERSION = "auto_update_prompt_version";
    private static final String KEY_AUTO_UPDATE_PROMPT_AT = "auto_update_prompt_at";
    private static final String KEY_AUTO_CHECK_UPDATE = "auto_check_update";
    // 全局页面背景("body"底图):用户设置(键不存在=跟随主题) + 主题默认图 + 遮罩/缩放/位置
    private static final String KEY_PAGE_BG = "page_bg_image";
    private static final String KEY_THEME_BG = "theme_default_bg";
    /** 生效主题是否自定义主题:是则全局背景设置休眠,页面背景一律跟主题自带 */
    private static final String KEY_ACTIVE_THEME_CUSTOM = "active_theme_custom";
    /** 休眠快照:自定义主题生效期间把用户的全局摆放暂存这里,切回内置主题原样恢复 */
    private static final String KEY_BG_STASHED = "page_bg_stashed";
    private static final String KEY_BG_STASH_ZOOM = "page_bg_stash_zoom";
    private static final String KEY_BG_STASH_ANCHOR_X = "page_bg_stash_anchor_x";
    private static final String KEY_BG_STASH_ANCHOR_Y = "page_bg_stash_anchor_y";
    private static final String KEY_BG_STASH_ALPHA = "page_bg_stash_alpha";
    private static final String KEY_BG_STASH_SCRIM = "page_bg_stash_scrim";
    // 自定义主题(见 theme/ThemeStore):选中的自定义主题 id + 亮/暗各自的"默认主题" id
    private static final String KEY_THEME_ID = "theme_custom_id";
    private static final String KEY_THEME_DEFAULT_BRIGHT = "theme_default_bright";
    private static final String KEY_THEME_DEFAULT_DARK = "theme_default_dark";
    private static final String KEY_PAGE_BG_SCRIM = "page_bg_scrim";
    private static final String KEY_PAGE_BG_ALPHA = "page_bg_alpha";
    private static final String KEY_PAGE_BG_ZOOM = "page_bg_zoom";
    /** 位置锚点比例 0~1(0=起始边贴边、0.5=居中、1=结束边贴边) */
    private static final String KEY_PAGE_BG_ANCHOR_X = "page_bg_anchor_x";
    private static final String KEY_PAGE_BG_ANCHOR_Y = "page_bg_anchor_y";
    // 旧版"图片中心位移"(±0.5,按屏宽/屏高归一化):只在没有锚点的老配置里读一次做迁移,
    // 新写入一律用上面的锚点键,写完就把这两个键删掉(旧值换横竖屏会漂,见 util/BgImageTransform)
    private static final String KEY_PAGE_BG_OFF_X = "page_bg_off_x";
    private static final String KEY_PAGE_BG_OFF_Y = "page_bg_off_y";

    /** 位置锚点的默认值/合法范围(与 util/BgImageTransform 的锚点模型一致) */
    private static final float ANCHOR_CENTER = 0.5f;

    /** 遮罩不透明度(0-100):**固定值**,用户只能在设置页开关遮罩,不能调这个数 */
    public static final int PAGE_BG_SCRIM_DIM = 50;
    /** 背景图不透明度默认值(0-100;100=原图,0=完全透明看不见) */
    public static final int PAGE_BG_ALPHA_DEFAULT = 100;

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 防止 AppCompat 在旧进程重建主页时提前消费主题重启标记。 */
    private static long themeRestartCacheMarkedInProcessAt;


    private SystemConfig() {
    }

    public interface Listener {
        void onConfigChanged();
    }

    // ── 查询 ──

    /** 安全 DNS 选项索引（0 关闭），默认 0 */
    public static int getDohUrl() {
        return PrefsDataStore.getInt(KEY_DOH_URL, 0);
    }

    /**
     * 主题<b>模式</b>：0 跟随系统 1 浅色 2 深色，默认 0。
     * <p>
     * 它只表示"跟随系统 / 强制亮 / 强制暗"这个选择；<b>具体用哪套配色</b>由主题系统解析
     * (见 {@code theme/ThemeStore}):
     * <ul>
     *   <li>选中了自定义主题({@link #getThemeCustomId()} 非空)→ 直接用那个主题(它的 type 决定亮暗);</li>
     *   <li>否则 模式=浅色/深色 → <b>就是内置亮色/暗色</b>(不看默认主题);</li>
     *   <li>否则 模式=跟随系统 → 按系统明暗取<b>该类型的默认主题</b>({@link #getThemeDefaultId(boolean)}，出厂=内置亮/暗)。</li>
     * </ul>
     */
    public static int getTheme() {
        return PrefsDataStore.getInt(KEY_THEME, 0);
    }

    /** 选中的<b>自定义</b>主题 id；空串=没选(走模式:浅色/深色=内置，跟随系统=该类型默认主题) */
    public static String getThemeCustomId() {
        return PrefsDataStore.getString(KEY_THEME_ID, "");
    }

    /**
     * 某类型的"默认主题" id(空串=内置亮色/暗色主题本身)。
     * <p>
     * <b>只服务「跟随系统」这一个模式</b>:跟随系统要在系统亮/暗两种情况下各有一套配色，
     * 所以亮、暗<b>各有一份默认</b>;用户在主题列表里对自定义主题点"设为默认"就是改这里
     * (有且只能一份,设置即覆盖)。用户显式选了浅色/深色时这两个值<b>不参与解析</b> ——
     * 选浅色/深色就是要内置那套。
     *
     * @param dark true=暗色类型的默认主题
     */
    public static String getThemeDefaultId(boolean dark) {
        return PrefsDataStore.getString(dark ? KEY_THEME_DEFAULT_DARK : KEY_THEME_DEFAULT_BRIGHT, "");
    }

    /** 加载动画文件夹名（空串=默认），默认空 */
    public static String getLoadingAnim() {
        return PrefsDataStore.getString(KEY_LOADING_ANIM, "");
    }

    /** 加载动画原始存储值（兼容旧数字 0/1 等历史值，供 LoadingAnim 兼容解析；新代码用 {@link #getLoadingAnim()}） */
    public static Object getLoadingAnimRaw() {
        return PrefsDataStore.getString(KEY_LOADING_ANIM, null);
    }

    /** 主页内容显示：0 豆瓣热播 1 站点推荐 2 关闭，默认 0 */
    public static int getHomeRec() {
        return PrefsDataStore.getInt(KEY_HOME_REC, 0);
    }

    /** 保留历史记录数量选项，默认 0 */
    public static int getHistoryNum() {
        return PrefsDataStore.getInt(KEY_HISTORY_NUM, 0);
    }

    /**
     * 搜索结果页展示布局：0 单列列表 1 宫格/网格 2 通栏卡片，默认 0（单列列表）。
     * 仅记忆用户选择（切换布局由页面消费方应用）。
     */
    public static int getSearchResultLayout() {
        int v = PrefsDataStore.getInt(KEY_SEARCH_RESULT_LAYOUT, 0);
        return Math.max(0, Math.min(2, v));
    }

    /** 直播源地址，默认空 */
    public static String getLiveUrl() {
        return PrefsDataStore.getString(KEY_LIVE_URL, "");
    }

    /** 无痕浏览（不存搜索/观看历史），默认关 */
    public static boolean isPrivateBrowsing() {
        return PrefsDataStore.getBoolean(KEY_PRIVATE_BROWSING, false);
    }

    /** 详情页缩略预览，默认开 */
    public static boolean isShowPreview() {
        return PrefsDataStore.getBoolean(KEY_SHOW_PREVIEW, true);
    }

    /** 快速搜索模式（列表页点击结果直接起快速搜索），默认关 */
    public static boolean isFastSearchMode() {
        return PrefsDataStore.getBoolean(KEY_FAST_SEARCH_MODE, false);
    }

    /** 调试叠加层/调试日志（播放页 debug 视图、网络日志等），默认关 */
    public static boolean isDebugOpen() {
        return PrefsDataStore.getBoolean(KEY_DEBUG_OPEN, false);
    }

    /** 忽略 HTTPS 证书错误（默认关：开启会降低 TLS 安全性，仅个别自签名站点用） */
    public static boolean isIgnoreSslError() {
        return PrefsDataStore.getBoolean(KEY_IGNORE_SSL_ERROR, false);
    }

    /** 局域网服务开关（默认关：关闭时 HTTP 服务仅监听 127.0.0.1） */
    public static boolean isLanServerEnabled() {
        return PrefsDataStore.getBoolean(KEY_LAN_SERVER_ENABLE, false);
    }

    /** 配对码在一次开启周期内保持不变，服务或进程重建时复用。 */
    public static synchronized String getOrCreateLanPairingCode() {
        String code = PrefsDataStore.getString(KEY_LAN_PAIRING_CODE, "");
        if (code.matches("[0-9]{8}")) return code;
        return regenerateLanPairingCode();
    }

    /** 仅由用户主动刷新配对码，或关闭后重新开启局域网服务时调用。 */
    public static synchronized String regenerateLanPairingCode() {
        String previous = PrefsDataStore.getString(KEY_LAN_PAIRING_CODE, "");
        SecureRandom random = new SecureRandom();
        String code;
        do {
            code = String.format(Locale.ROOT, "%08d", random.nextInt(100000000));
        } while (code.equals(previous));
        LanSessionConfig.clear();
        PrefsDataStore.put(KEY_LAN_PAIRING_CODE, code);
        return code;
    }

    /** 标记由设置/订阅/还原触发的应用内部重启，不把它当成一次新的启动更新检查。 */
    public static void markInternalRestart() {
        PrefsDataStore.put(KEY_INTERNAL_RESTART_AT, System.currentTimeMillis());
    }

    /** 消费短时重启标记；过期标记不影响用户下次主动打开应用。 */
    public static synchronized boolean consumeInternalRestart() {
        long markedAt = PrefsDataStore.getLong(KEY_INTERNAL_RESTART_AT, 0L);
        PrefsDataStore.delete(KEY_INTERNAL_RESTART_AT);
        long elapsed = System.currentTimeMillis() - markedAt;
        return markedAt > 0 && elapsed >= 0 && elapsed <= 120_000L;
    }

    /** 主题只改变外观；真重启后的首页可优先读取现有订阅配置与爬虫包缓存。 */
    public static synchronized void markThemeRestartUseCache() {
        long markedAt = System.currentTimeMillis();
        PrefsDataStore.put(KEY_THEME_RESTART_CACHE_AT, markedAt);
        themeRestartCacheMarkedInProcessAt = markedAt;
    }

    /** 仅由新进程消费一次，超时则按普通冷启动处理。 */
    public static synchronized boolean consumeThemeRestartUseCache() {
        long markedAt = PrefsDataStore.getLong(KEY_THEME_RESTART_CACHE_AT, 0L);
        if (markedAt <= 0 || markedAt == themeRestartCacheMarkedInProcessAt) return false;
        PrefsDataStore.delete(KEY_THEME_RESTART_CACHE_AT);
        long elapsed = System.currentTimeMillis() - markedAt;
        return elapsed >= 0 && elapsed <= 120_000L;
    }

    /** 同一新版本的自动弹窗 24 小时内只出现一次；手动检查不受影响。 */
    public static synchronized boolean claimAutoUpdatePrompt(String version) {
        if (version == null || version.isEmpty()) return false;
        String previous = PrefsDataStore.getString(KEY_AUTO_UPDATE_PROMPT_VERSION, "");
        long lastAt = PrefsDataStore.getLong(KEY_AUTO_UPDATE_PROMPT_AT, 0L);
        long now = System.currentTimeMillis();
        if (version.equals(previous) && now >= lastAt && now - lastAt < 86_400_000L) return false;
        PrefsDataStore.put(KEY_AUTO_UPDATE_PROMPT_VERSION, version);
        PrefsDataStore.put(KEY_AUTO_UPDATE_PROMPT_AT, now);
        return true;
    }

    /**
     * 启动时自动检查更新（<b>默认开</b>）：首页"上次看到"气泡消失后检查一次，
     * 有新版本则弹更新说明弹窗（与"我的-关于-检查更新"同一套动作）。
     */
    public static boolean isAutoCheckUpdate() {
        return PrefsDataStore.getBoolean(KEY_AUTO_CHECK_UPDATE, true);
    }

    // ── 页面背景(解析链:用户显式设置 > 主题默认 > 纯色) ──

    /**
     * 用户是否显式设置过背景图(含显式设成"纯色"),{@code false} = 跟随主题默认。
     * <p>
     * 为什么要分三态:{@code page_bg_image} 键<b>不存在</b> = 跟随主题;
     * 键存在且为空串 = 用户显式要纯色(压过主题默认图);键存在且为路径 = 用户自己的图。
     */
    public static boolean isPageBackgroundUserSet() {
        return PrefsDataStore.contains(KEY_PAGE_BG);
    }

    /** 用户显式设置过的图源(""=显式纯色);没设过返回 {@code null} */
    public static String getPageBackgroundUserPath() {
        return isPageBackgroundUserSet() ? PrefsDataStore.getString(KEY_PAGE_BG, "") : null;
    }

    /**
     * <b>当前生效的背景图源</b>(页面宿主/背景层用它渲染),空串=纯色(主题窗底色):
     * <pre>
     *   自定义主题生效:主题自带背景(全局设置休眠)
     *   内置主题生效:用户显式设置(图或"显式纯色")  >  主题默认背景  >  纯色
     * </pre>
     * 纯色取主题窗底色 {@code bg_body}:亮色主题是近白({@code #faf8ff})、暗色主题是近黑({@code #141218}),
     * 因此自定义主题若没定义默认背景图,天然就是"亮色主题纯白 / 暗色主题纯黑"。
     * <p>
     * 图源字符串支持两种:应用内文件绝对路径;打包素材 {@code file:///android_asset/...}
     * (后续内置主题自带的默认背景图用这种)。
     */
    public static String getPageBackgroundPath() {
        if (!isActiveThemeCustom() && isPageBackgroundUserSet()) return PrefsDataStore.getString(KEY_PAGE_BG, "");
        return getThemeDefaultBackground();
    }

    /**
     * 当前主题的默认背景图源(空=纯色)。由<b>主题系统</b>维护,背景设置页只读:
     * 内置浅色/深色主题都是纯色(没有默认图);后续新增内置主题时切主题写各自的素材,
     * 自定义主题则由用户为该主题定义默认背景图(见 {@link #setThemeDefaultBackground(String)})。
     */
    public static String getThemeDefaultBackground() {
        return PrefsDataStore.getString(KEY_THEME_BG, "");
    }

    /**
     * 设置当前主题的默认背景图源:<b>内置主题切换 / 自定义主题保存时由主题系统调用</b>。
     *
     * @param source 图源(应用内文件路径 或 {@code file:///android_asset/...});空串=该主题默认纯色
     */
    public static void setThemeDefaultBackground(String source) {
        String v = source == null ? "" : source;
        if (v.equals(getThemeDefaultBackground())) return;
        PrefsDataStore.put(KEY_THEME_BG, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 主题默认背景=" + v);
        fireChanged();
    }

    /** 生效主题是否自定义主题:是则全局背景设置(图 + 摆放)休眠,页面背景一律跟主题自带 */
    public static boolean isActiveThemeCustom() {
        return PrefsDataStore.getBoolean(KEY_ACTIVE_THEME_CUSTOM, false);
    }

    /**
     * 生效主题变化时由主题系统调用({@code ThemeStore#applyActiveBackground}):
     * 自定义主题生效前把用户的全局摆放暂存起来(免得被主题摆放覆盖),
     * 切回内置主题时原样恢复 —— 用户那张老图和它的裁切不该因为切过一次主题就丢。
     */
    public static void setActiveThemeCustom(boolean custom) {
        if (custom == isActiveThemeCustom()) return;
        if (custom) {
            stashPageBackgroundTransform();
        } else {
            restorePageBackgroundTransform();
        }
        PrefsDataStore.put(KEY_ACTIVE_THEME_CUSTOM, custom);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                "系统设置: 全局背景休眠=" + custom);
        fireChanged();
    }

    /** 只暂存"用户显式设过背景且还没暂存"这一次;用户没设过时摆放本就跟随主题,没什么要护的 */
    private static void stashPageBackgroundTransform() {
        if (!isPageBackgroundUserSet() || PrefsDataStore.contains(KEY_BG_STASHED)) return;
        PrefsDataStore.put(KEY_BG_STASH_ZOOM, getPageBackgroundZoom());
        PrefsDataStore.put(KEY_BG_STASH_ANCHOR_X, getPageBackgroundAnchorX());
        PrefsDataStore.put(KEY_BG_STASH_ANCHOR_Y, getPageBackgroundAnchorY());
        PrefsDataStore.put(KEY_BG_STASH_ALPHA, getPageBackgroundAlpha());
        PrefsDataStore.put(KEY_BG_STASH_SCRIM, isPageBackgroundScrimEnabled());
        PrefsDataStore.put(KEY_BG_STASHED, true);
    }

    private static void restorePageBackgroundTransform() {
        if (!PrefsDataStore.contains(KEY_BG_STASHED)) return;
        PrefsDataStore.put(KEY_PAGE_BG_ZOOM, PrefsDataStore.getFloat(KEY_BG_STASH_ZOOM, 0f));
        PrefsDataStore.put(KEY_PAGE_BG_ANCHOR_X, PrefsDataStore.getFloat(KEY_BG_STASH_ANCHOR_X, ANCHOR_CENTER));
        PrefsDataStore.put(KEY_PAGE_BG_ANCHOR_Y, PrefsDataStore.getFloat(KEY_BG_STASH_ANCHOR_Y, ANCHOR_CENTER));
        PrefsDataStore.put(KEY_PAGE_BG_ALPHA, PrefsDataStore.getInt(KEY_BG_STASH_ALPHA, PAGE_BG_ALPHA_DEFAULT));
        PrefsDataStore.put(KEY_PAGE_BG_SCRIM, PrefsDataStore.getBoolean(KEY_BG_STASH_SCRIM, true));
        PrefsDataStore.delete(KEY_BG_STASHED);
        PrefsDataStore.delete(KEY_BG_STASH_ZOOM);
        PrefsDataStore.delete(KEY_BG_STASH_ANCHOR_X);
        PrefsDataStore.delete(KEY_BG_STASH_ANCHOR_Y);
        PrefsDataStore.delete(KEY_BG_STASH_ALPHA);
        PrefsDataStore.delete(KEY_BG_STASH_SCRIM);
    }

    /**
     * 清掉"用户显式设置",回到跟随主题默认背景(保留缩放/位置/透明度/遮罩等设置)。
     * 与 {@link #resetPageBackground()} 的区别:后者连缩放位置透明度遮罩一起回默认。
     */
    public static void clearPageBackgroundUserSet() {
        if (!isPageBackgroundUserSet()) return;
        PrefsDataStore.delete(KEY_PAGE_BG);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 背景图跟随主题");
        fireChanged();
    }

    /** 背景遮罩开关(默认开):关掉后背景原图直出,压在底图上的文字对比度会下降 */
    public static boolean isPageBackgroundScrimEnabled() {
        return PrefsDataStore.getBoolean(KEY_PAGE_BG_SCRIM, true);
    }

    /**
     * 遮罩不透明度(0-100):固定值 {@link #PAGE_BG_SCRIM_DIM},关掉遮罩时为 0。
     * 用户只能开关遮罩(见 {@link #setPageBackgroundScrimEnabled(boolean)}),不能调这个数。
     */
    public static int getPageBackgroundDim() {
        return isPageBackgroundScrimEnabled() ? PAGE_BG_SCRIM_DIM : 0;
    }

    /** 背景图自身不透明度(0-100,默认 100):设置页那根"背景图透明度"滑杆就是它 */
    public static int getPageBackgroundAlpha() {
        return Math.max(0, Math.min(100, PrefsDataStore.getInt(KEY_PAGE_BG_ALPHA, PAGE_BG_ALPHA_DEFAULT)));
    }

    /**
     * 背景图缩放倍率(相对"铺满屏幕"的倍率,1=铺满);{@code <=0} 表示自动:
     * 普通图片铺满屏幕,尺寸很小的图片按原始像素显示(不放大到糊),详见 util/BgImageTransform。
     */
    public static float getPageBackgroundZoom() {
        return PrefsDataStore.getFloat(KEY_PAGE_BG_ZOOM, 0f);
    }

    /**
     * 位置锚点是否已按新模型写过。{@code false} = 配置里还是旧版"中心位移",
     * 背景层({@code PageBackgroundView})会拿它按当前屏幕几何换算一次再回写(见 {@link #setPageBackgroundTransform})。
     */
    public static boolean isPageBackgroundAnchorSet() {
        return PrefsDataStore.contains(KEY_PAGE_BG_ANCHOR_X) || PrefsDataStore.contains(KEY_PAGE_BG_ANCHOR_Y);
    }

    /** 背景图横向位置锚点:0=贴左、0.5=居中、1=贴右(与屏幕尺寸无关,横竖屏同一观感) */
    public static float getPageBackgroundAnchorX() {
        return clampAnchor(PrefsDataStore.getFloat(KEY_PAGE_BG_ANCHOR_X, ANCHOR_CENTER));
    }

    /** 背景图纵向位置锚点:0=贴上、0.5=居中、1=贴下 */
    public static float getPageBackgroundAnchorY() {
        return clampAnchor(PrefsDataStore.getFloat(KEY_PAGE_BG_ANCHOR_Y, ANCHOR_CENTER));
    }

    /**
     * 旧版横向位移:图片中心相对屏幕中心的位移(按屏宽归一化,0=居中,+0.5=贴右边缘)。
     * <b>只作老配置迁移用</b>(见 {@link #isPageBackgroundAnchorSet()}),新代码一律用
     * {@link #getPageBackgroundAnchorX()}。
     */
    public static float getPageBackgroundOffsetX() {
        return PrefsDataStore.getFloat(KEY_PAGE_BG_OFF_X, 0f);
    }

    /** 旧版纵向位移(按屏高归一化,0=居中,+0.5=贴下边缘);只作老配置迁移用 */
    public static float getPageBackgroundOffsetY() {
        return PrefsDataStore.getFloat(KEY_PAGE_BG_OFF_Y, 0f);
    }

    // ── 操作（内部校验 + 持久化 + 广播变更）──

    public static void setDohUrl(int pos) {
        int v = Math.max(0, pos);
        if (getDohUrl() == v) return;
        PrefsDataStore.put(KEY_DOH_URL, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 安全DNS=" + v);
        fireChanged();
    }

    public static void setTheme(int tag) {
        int v = Math.max(0, Math.min(2, tag));
        if (getTheme() == v) return;
        PrefsDataStore.put(KEY_THEME, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 主题=" + v);
        fireChanged();
    }

    /** 设置选中的自定义主题 id(空串=取消自定义选择,回到"模式 + 该类型默认主题") */
    public static void setThemeCustomId(String id) {
        String v = id == null ? "" : id;
        if (v.equals(getThemeCustomId())) return;
        if (v.isEmpty()) {
            PrefsDataStore.delete(KEY_THEME_ID);
        } else {
            PrefsDataStore.put(KEY_THEME_ID, v);
        }
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 自定义主题=" + v);
        fireChanged();
    }

    /** 设置某类型的默认主题 id(空串=内置亮色/暗色主题本身)；亮/暗各一份，设置即覆盖 */
    public static void setThemeDefaultId(boolean dark, String id) {
        String key = dark ? KEY_THEME_DEFAULT_DARK : KEY_THEME_DEFAULT_BRIGHT;
        String v = id == null ? "" : id;
        if (v.equals(getThemeDefaultId(dark))) return;
        if (v.isEmpty()) {
            PrefsDataStore.delete(key);
        } else {
            PrefsDataStore.put(key, v);
        }
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                "系统设置: " + (dark ? "暗色" : "亮色") + "默认主题=" + v);
        fireChanged();
    }

    public static void setLoadingAnim(String name) {
        String v = name == null ? "" : name;
        if (v.equals(getLoadingAnim())) return;
        PrefsDataStore.put(KEY_LOADING_ANIM, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 加载动画=" + v);
        fireChanged();
    }

    public static void setHomeRec(int type) {
        int v = Math.max(0, Math.min(2, type));
        if (getHomeRec() == v) return;
        PrefsDataStore.put(KEY_HOME_REC, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 首页内容=" + v);
        fireChanged();
    }

    public static void setHistoryNum(int num) {
        int v = Math.max(0, num);
        if (getHistoryNum() == v) return;
        PrefsDataStore.put(KEY_HISTORY_NUM, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 历史记录数=" + v);
        fireChanged();
    }

    /** 搜索结果页展示布局（0 单列列表 1 宫格/网格 2 通栏卡片），越界值钳制到合法范围 */
    public static void setSearchResultLayout(int mode) {
        int v = Math.max(0, Math.min(2, mode));
        if (getSearchResultLayout() == v) return;
        PrefsDataStore.put(KEY_SEARCH_RESULT_LAYOUT, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "搜索设置: 结果布局=" + v);
        fireChanged();
    }

    public static void setLiveUrl(String url) {
        String v = url == null ? "" : url;
        if (v.equals(getLiveUrl())) return;
        PrefsDataStore.put(KEY_LIVE_URL, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 直播源=" + v);
        fireChanged();
    }

    public static void setPrivateBrowsing(boolean on) {
        if (isPrivateBrowsing() == on) return;
        PrefsDataStore.put(KEY_PRIVATE_BROWSING, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 无痕浏览=" + on);
        fireChanged();
    }

    public static void setShowPreview(boolean on) {
        if (isShowPreview() == on) return;
        PrefsDataStore.put(KEY_SHOW_PREVIEW, on);
        fireChanged();
    }

    public static void setFastSearchMode(boolean on) {
        if (isFastSearchMode() == on) return;
        PrefsDataStore.put(KEY_FAST_SEARCH_MODE, on);
        fireChanged();
    }

    public static void setDebugOpen(boolean on) {
        if (isDebugOpen() == on) return;
        PrefsDataStore.put(KEY_DEBUG_OPEN, on);
        fireChanged();
    }

    public static void setIgnoreSslError(boolean on) {
        if (isIgnoreSslError() == on) return;
        PrefsDataStore.put(KEY_IGNORE_SSL_ERROR, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 忽略证书错误=" + on);
        fireChanged();
    }

    public static void setLanServerEnabled(boolean on) {
        if (isLanServerEnabled() == on) return;
        if (on) regenerateLanPairingCode();
        else {
            LanSessionConfig.clear();
            PrefsDataStore.delete(KEY_LAN_PAIRING_CODE);
        }
        PrefsDataStore.put(KEY_LAN_SERVER_ENABLE, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 局域网服务=" + on);
        fireChanged();
    }

    /** 启动时自动检查更新（默认开） */
    public static void setAutoCheckUpdate(boolean on) {
        if (isAutoCheckUpdate() == on) return;
        PrefsDataStore.put(KEY_AUTO_CHECK_UPDATE, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 启动自动检查更新=" + on);
        fireChanged();
    }

    /** 页面背景图(本地图片绝对路径;空串=显式纯色。清掉"用户设置"用 {@link #resetPageBackground()}) */
    public static void setPageBackgroundPath(String path) {
        String v = path == null ? "" : path;
        if (isPageBackgroundUserSet() && v.equals(getPageBackgroundUserPath())) return;
        PrefsDataStore.put(KEY_PAGE_BG, v);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 页面背景图=" + v);
        fireChanged();
    }

    /** 背景遮罩开关(关掉后背景原图直出,压在底图上的文字对比度会下降) */
    public static void setPageBackgroundScrimEnabled(boolean on) {
        if (isPageBackgroundScrimEnabled() == on) return;
        PrefsDataStore.put(KEY_PAGE_BG_SCRIM, on);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 背景遮罩=" + (on ? "开" : "关"));
        fireChanged();
    }

    /** 背景图自身不透明度(0-100),越界值钳制到合法范围 */
    public static void setPageBackgroundAlpha(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        if (getPageBackgroundAlpha() == v) return;
        PrefsDataStore.put(KEY_PAGE_BG_ALPHA, v);
        fireChanged();
    }

    /**
     * 保存背景图的缩放与位置(设置页拖动/双指缩放结束后调用,一次写入只广播一次)。
     * <p>
     * 位置是<b>锚点比例</b>(与屏幕尺寸无关):0=起始边贴边(左/上)、0.5=居中、1=结束边贴边(右/下)。
     * 换横竖屏/换分辨率都保持同一观感;旧版"中心位移"在写入时被锚点取代(写完删掉旧键)。
     *
     * @param zoom    缩放倍率(相对铺满;<b>传 &lt;=0 表示自动</b>,换新图时用)
     * @param anchorX 横向锚点 0~1(0=贴左、0.5=居中、1=贴右)
     * @param anchorY 纵向锚点 0~1(0=贴上、0.5=居中、1=贴下)
     */
    public static void setPageBackgroundTransform(float zoom, float anchorX, float anchorY) {
        float z = Float.isNaN(zoom) ? 0f : zoom;
        float ax = clampAnchor(anchorX);
        float ay = clampAnchor(anchorY);
        if (getPageBackgroundZoom() == z
                && isPageBackgroundAnchorSet()
                && getPageBackgroundAnchorX() == ax
                && getPageBackgroundAnchorY() == ay) {
            return;
        }
        PrefsDataStore.put(KEY_PAGE_BG_ZOOM, z);
        PrefsDataStore.put(KEY_PAGE_BG_ANCHOR_X, ax);
        PrefsDataStore.put(KEY_PAGE_BG_ANCHOR_Y, ay);
        // 锚点已建立:旧版位移字段作废(留着没人读,删掉免得后人误用)
        PrefsDataStore.delete(KEY_PAGE_BG_OFF_X);
        PrefsDataStore.delete(KEY_PAGE_BG_OFF_Y);
        fireChanged();
    }

    /** 锚点钳制(0~1,NaN 视为居中):core-storage 不依赖 app 侧 util,这里自带一份 */
    private static float clampAnchor(float anchor) {
        if (Float.isNaN(anchor)) return ANCHOR_CENTER;
        return Math.max(0f, Math.min(1f, anchor));
    }

    /**
     * 恢复默认背景:<b>清掉用户设置,回到"跟随主题"</b>(内置浅/深主题 = 纯色;其他主题 = 该主题的默认背景),
     * 并把缩放/位置/图不透明度/遮罩回到默认值。
     * 用户选过的自定义图副本不从磁盘删除(下次选图会覆盖旧副本),避免误触重置后原图找不回来。
     */
    public static void resetPageBackground() {
        PrefsDataStore.delete(KEY_PAGE_BG);
        PrefsDataStore.put(KEY_PAGE_BG_ZOOM, 0f);
        PrefsDataStore.put(KEY_PAGE_BG_ANCHOR_X, ANCHOR_CENTER);
        PrefsDataStore.put(KEY_PAGE_BG_ANCHOR_Y, ANCHOR_CENTER);
        PrefsDataStore.delete(KEY_PAGE_BG_OFF_X);
        PrefsDataStore.delete(KEY_PAGE_BG_OFF_Y);
        PrefsDataStore.put(KEY_PAGE_BG_ALPHA, PAGE_BG_ALPHA_DEFAULT);
        PrefsDataStore.put(KEY_PAGE_BG_SCRIM, true);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "系统设置: 背景图恢复默认(跟随主题)");
        fireChanged();
    }

    // ── 订阅 ──

    public static void subscribe(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    private static void fireChanged() {
        for (Listener l : listeners) {
            try {
                l.onConfigChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 备份/恢复（BackupDialog 聚合各模块配置）──

    public static Map<String, Object> exportConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put(KEY_DOH_URL, getDohUrl());
        cfg.put(KEY_THEME, getTheme());
        // 自定义主题选择(主题定义本身在 filesDir/themes/,随"数据备份还原"的整包一起走,见 ThemeStore)
        cfg.put(KEY_THEME_ID, getThemeCustomId());
        cfg.put(KEY_THEME_DEFAULT_BRIGHT, getThemeDefaultId(false));
        cfg.put(KEY_THEME_DEFAULT_DARK, getThemeDefaultId(true));
        cfg.put(KEY_LOADING_ANIM, getLoadingAnim());
        cfg.put(KEY_HOME_REC, getHomeRec());
        cfg.put(KEY_HISTORY_NUM, getHistoryNum());
        cfg.put(KEY_LIVE_URL, getLiveUrl());
        cfg.put(KEY_PRIVATE_BROWSING, isPrivateBrowsing());
        cfg.put(KEY_AUTO_CHECK_UPDATE, isAutoCheckUpdate());
        return cfg;
    }

    public static void importConfig(Map<String, Object> cfg) {
        if (cfg == null) return;
        Object v;
        if ((v = cfg.get(KEY_DOH_URL)) instanceof Number) setDohUrl(((Number) v).intValue());
        if ((v = cfg.get(KEY_THEME)) instanceof Number) setTheme(((Number) v).intValue());
        if ((v = cfg.get(KEY_THEME_ID)) instanceof String) setThemeCustomId((String) v);
        if ((v = cfg.get(KEY_THEME_DEFAULT_BRIGHT)) instanceof String) setThemeDefaultId(false, (String) v);
        if ((v = cfg.get(KEY_THEME_DEFAULT_DARK)) instanceof String) setThemeDefaultId(true, (String) v);
        if ((v = cfg.get(KEY_LOADING_ANIM)) instanceof String) setLoadingAnim((String) v);
        if ((v = cfg.get(KEY_HOME_REC)) instanceof Number) setHomeRec(((Number) v).intValue());
        if ((v = cfg.get(KEY_HISTORY_NUM)) instanceof Number) setHistoryNum(((Number) v).intValue());
        if ((v = cfg.get(KEY_LIVE_URL)) instanceof String) setLiveUrl((String) v);
        if ((v = cfg.get(KEY_PRIVATE_BROWSING)) instanceof Boolean) setPrivateBrowsing((Boolean) v);
        if ((v = cfg.get(KEY_AUTO_CHECK_UPDATE)) instanceof Boolean) setAutoCheckUpdate((Boolean) v);
    }
}
