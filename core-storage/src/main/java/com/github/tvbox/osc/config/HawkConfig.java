package com.github.tvbox.osc.config;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    public static final String LIVE_URL = "live_url";
    public static final String EPG_URL = "epg_url";
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String SUBSCRIPTIONS = "api_history";
    /** 本地默认订阅文件注入的订阅集合(用于与文件内容同步增删) */
    public static final String DEFAULT_SUBS = "default_subs";
    /** 运行日志开关(默认关闭,排查问题时开启) */
    public static final String APP_LOG = "app_log";
    /** 字幕开关(默认关闭,播放器设置里可打开/关闭) */
    public static final String SUBTITLE_OPEN = "subtitle_open";
    public static final String LIVE_HISTORY = "live_history";
    public static final String EPG_HISTORY = "epg_history";
    public static final String HOME_API = "home_api";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String DEBUG_OPEN = "debug_open";
    /**
     * 局域网服务开关(默认关闭):关闭时内置 HTTP 服务仅绑定 127.0.0.1(订阅/本地播放/代理不受影响);
     * 开启后绑定所有网卡,局域网设备可访问 web 控制台与文件共享(需进程令牌才可管理/删除)。
     */
    public static final String LAN_SERVER_ENABLE = "lan_server_enable";
    /**
     * 忽略 HTTPS 证书错误(默认关闭)。关闭时 WebView/OkHttp 均做证书与主机名校验,
     * 防止中间人篡改;个别自签名站点打不开时可手动开启。
     */
    public static final String IGNORE_SSL_ERROR = "ignore_ssl_error";
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";//0 系统 1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    public static final String PLAY_TIME_STEP = "play_time_step"; //0 texture 2
    public static final String DOH_URL = "doh_url";
    /**
     * 0 豆瓣热播 1 数据源推荐 2 关闭主页
     */
    public static final String HOME_REC = "home_rec";
    public static final String HISTORY_NUM = "history_num";
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String NOW_DATE = "now_date"; //当前日期
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    /**
     * 无痕浏览
     */
    public static final String PRIVATE_BROWSING = "private_browsing";
    /**
     * 主题,跟随系统0,浅1,深2
     */
    public static final String THEME_TAG = "theme_tag";
    /**
     * 后台播放模式 0 关闭,1 开启,2 画中画
     */
    public static final String BACKGROUND_PLAY_TYPE = "background_play_type";
    /**
     * 长按的倍速播放设置
     */
    public static final String VIDEO_SPEED = "video_speed";
    /**
     * 加载动画:空串=默认(鱼 glowing_fish_loader),或 assets/loading/ 目录下的文件夹名(动态扫描注册)
     */
    public static final String LOADING_ANIM = "loading_anim";
    /**
     * 搜索记录
     */
    public static final String HISTORY_SEARCH = "history_search";
}