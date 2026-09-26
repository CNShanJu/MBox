package com.github.tvbox.osc.di;

import android.content.Context;

/**
 * App 组合根(改进.txt 第四节):集中完成跨模块服务的依赖注入。
 * 现阶段接入:
 * - 播放地址解析契约(PlayUrlResolverApi)→ :spider 实现(下载/播放解析用)
 * - 手动视频判定(SpiderManualCheckApi)→ :spider 实现(嗅探 WebView 用)
 * - 爬虫内容服务(SpiderContentApi)→ :spider 实现(首页/分类/详情/搜索/播放用)
 * - PlayerFactory adapter 注册(type=1 IJK / type=2 Exo,roadmap 2.1 原型)
 * 后续扩展位:NetworkProvider / StorageFacade 等。
 */
public final class AppCompositionRoot {

    private static volatile com.github.tvbox.osc.net.NetworkProvider networkProvider;
    private static volatile boolean playerAdaptersRegistered = false;

    private AppCompositionRoot() {
    }

    /** App.onCreate 网络/下载组件初始化后调用 */
    public static void init() {
        android.util.Log.i("AppCompositionRoot", "init: 注入 UrlResolver/ManualCheck/Content/NetworkProvider");
        // 下载侧播放地址解析(:spider 提供实现,download 不依赖 :spider 实现)
        com.github.tvbox.osc.download.DownloadFacade.setUrlResolverApi(
                com.github.catvod.crawler.SpiderUrlResolverImpl.get());
        // app 侧批量下载解析:同一实现经契约持有者暴露(页面/工具不直连 :spider 实现)
        com.github.tvbox.osc.spiderapi.PlayUrlResolverProviders.set(
                com.github.catvod.crawler.SpiderUrlResolverImpl.get());
        // 嗅探型源“手动视频判定”
        com.github.tvbox.osc.spiderapi.SpiderManualCheckProviders.set(
                com.github.catvod.crawler.SpiderManualCheckImpl.get());
        // 首页/分类/详情/搜索/播放解析内容(SourceViewModel 不取具体 Spider)
        com.github.tvbox.osc.spiderapi.SpiderContentProviders.set(
                com.github.catvod.crawler.SpiderContentImpl.get());
        // 强类型详情(type3,解析下沉 :spider;失败自动回退字符串通道)
        com.github.tvbox.osc.spiderapi.SpiderDetailProviders.set(
                com.github.catvod.crawler.SpiderDetailImpl.get());
        // 强类型搜索(type3:quick/聚合;失败自动回退字符串通道)
        com.github.tvbox.osc.spiderapi.SpiderSearchProviders.set(
                com.github.catvod.crawler.SpiderSearchImpl.get());
        // 强类型分类/首页视频(type3;失败自动回退字符串通道)
        com.github.tvbox.osc.spiderapi.SpiderHomeProviders.set(
                com.github.catvod.crawler.SpiderHomeImpl.get());
        // 直播频道配置契约:主/兜底分组读取、直播源重建,桥接 ApiConfig
        com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.set(new com.github.tvbox.osc.spiderapi.LiveChannelConfigApi() {
            @Override
            public java.util.List<com.github.tvbox.osc.bean.LiveChannelGroup> getChannelGroupList() {
                return com.github.tvbox.osc.api.ApiConfig.get().getChannelGroupList();
            }

            @Override
            public java.util.List<com.github.tvbox.osc.bean.LiveChannelGroup> getFallbackChannelGroupList() {
                return com.github.tvbox.osc.api.ApiConfig.get().getFallbackChannelGroupList();
            }

            @Override
            public void loadLives(com.google.gson.JsonArray livesArray) {
                com.github.tvbox.osc.api.ApiConfig.get().loadLives(livesArray);
            }
        });
        // 源/订阅加载器契约:订阅配置/jar 加载触发,桥接 ApiConfig(回调语义一致)
        com.github.tvbox.osc.spiderapi.SourceLoaderProviders.set(new com.github.tvbox.osc.spiderapi.SourceLoaderApi() {
            @Override
            public void loadConfig(final boolean useCache,
                                   final com.github.tvbox.osc.spiderapi.SourceLoaderApi.Callback callback,
                                   final android.app.Activity activity) {
                com.github.tvbox.osc.api.ApiConfig.get().loadConfig(useCache, callback == null ? null
                        : new com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        callback.success();
                    }

                    @Override
                    public void retry() {
                        callback.retry();
                    }

                    @Override
                    public void error(String msg) {
                        callback.error(msg);
                    }
                }, activity);
            }

            @Override
            public void loadJar(final boolean useCache, final String spider,
                                final com.github.tvbox.osc.spiderapi.SourceLoaderApi.Callback callback) {
                com.github.tvbox.osc.api.ApiConfig.get().loadJar(useCache, spider, callback == null ? null
                        : new com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        callback.success();
                    }

                    @Override
                    public void retry() {
                        callback.retry();
                    }

                    @Override
                    public void error(String msg) {
                        callback.error(msg);
                    }
                });
            }

            @Override
            public String getSpider() {
                return com.github.tvbox.osc.api.ApiConfig.get().getSpider();
            }

            @Override
            public void stopAllSourceTasks() {
                // JS 源运行时动作留在 :spider 内(JsRuntimeBridge),页面只经契约调用
                com.github.catvod.crawler.JsRuntimeBridge.stopAllSourceTasks();
            }

            @Override
            public void resetSources() {
                com.github.catvod.crawler.JsRuntimeBridge.resetSources();
            }
        });
        // 源配置元信息(ApiConfig 即契约实现:源注册表/首页源/vip 解析旗标)
        com.github.tvbox.osc.spiderapi.SourceConfigProviders.set(
                com.github.tvbox.osc.api.ApiConfig.get());
        // 解析配置/解析执行契约:默认解析/解析列表/通用与混流解析,桥接 ApiConfig(jar loader)
        com.github.tvbox.osc.spiderapi.ParseConfigProviders.set(new com.github.tvbox.osc.spiderapi.ParseConfigApi() {
            @Override
            public com.github.tvbox.osc.bean.ParseBean getDefaultParse() {
                return com.github.tvbox.osc.api.ApiConfig.get().getDefaultParse();
            }

            @Override
            public void setDefaultParse(com.github.tvbox.osc.bean.ParseBean parseBean) {
                com.github.tvbox.osc.api.ApiConfig.get().setDefaultParse(parseBean);
            }

            @Override
            public java.util.List<com.github.tvbox.osc.bean.ParseBean> getParseBeanList() {
                return com.github.tvbox.osc.api.ApiConfig.get().getParseBeanList();
            }

            @Override
            public org.json.JSONObject jsonExt(String key, java.util.LinkedHashMap<String, String> jxs, String url) {
                return com.github.tvbox.osc.api.ApiConfig.get().jsonExt(key, jxs, url);
            }

            @Override
            public org.json.JSONObject jsonExtMix(String flag, String key, String name,
                                                  java.util.LinkedHashMap<String, java.util.HashMap<String, String>> jxs, String url) {
                return com.github.tvbox.osc.api.ApiConfig.get().jsonExtMix(flag, key, name, jxs, url);
            }
        });
        // IJK 解码配置契约:播放内核/设置页经接口读取,适配器桥接 ApiConfig 现有实现
        com.github.tvbox.osc.player.api.IjkCodecConfigProviders.set(new com.github.tvbox.osc.player.api.IjkCodecConfigApi() {
            @Override
            public java.util.List<com.github.tvbox.osc.bean.IJKCode> getIjkCodes() {
                return com.github.tvbox.osc.api.ApiConfig.get().getIjkCodes();
            }

            @Override
            public com.github.tvbox.osc.bean.IJKCode getCurrentIJKCode() {
                return com.github.tvbox.osc.api.ApiConfig.get().getCurrentIJKCode();
            }

            @Override
            public com.github.tvbox.osc.bean.IJKCode getIJKCodec(String name) {
                return com.github.tvbox.osc.api.ApiConfig.get().getIJKCodec(name);
            }
        });
        // 网络客户端提供者:general/noRedirect 来自 OkGoHelper;playback 复用 Exo 已建实例
        networkProvider = new com.github.tvbox.osc.net.NetworkProvider() {
            @Override
            public okhttp3.OkHttpClient general() {
                return com.github.tvbox.osc.util.OkGoHelper.getDefaultClient();
            }

            @Override
            public okhttp3.OkHttpClient noRedirect() {
                return com.github.tvbox.osc.util.OkGoHelper.getNoRedirectClient();
            }

            @Override
            public okhttp3.OkHttpClient playback() {
                okhttp3.OkHttpClient c = com.github.tvbox.osc.base.App.playbackHttpClient();
                return c != null ? c : com.github.tvbox.osc.net.NetworkProvider.DEFAULT.playback();
            }
        };
        registerPlayerAdapters();
    }

    /**
     * PlayerFactory 内核适配器注册(roadmap 2.1 会话原型):
     * type=1(IJK) / type=2(Exo) 注册引擎无关的 PlayerApi 适配器,
     * 供 PlaybackSessions 会话层/后续 PlayFragment 薄层化按 playType 取用。
     * 适配器仅作协议收敛:真机回归前 UI 仍由 mVideoView 直接驱动,此处不改变现有播放行为。
     * 内核/渲染工厂语义与 PlayerHelper.updateCfg 一致(IJK=type1 / Exo=type2)。
     */
    private static synchronized void registerPlayerAdapters() {
        if (playerAdaptersRegistered) return;
        try {
            // type=1 IJK:复用 PlayerHelper 的 IJK 内核工厂语义(new IjkMediaPlayer(context, codec))
            com.github.tvbox.osc.player.api.PlayerFactory.register(1, (context, options, listener) -> {
                android.util.Log.i("AppCompositionRoot", "PlayerFactory: 创建 IJK(type=1) 适配器");
                xyz.doikki.videoplayer.player.VideoView vv = newVideoView(context, 1);
                com.github.tvbox.osc.player.api.PlayerApi api =
                        new com.github.tvbox.osc.player.VideoViewPlayerApi(vv, true);
                api.subscribe(listener);
                return api;
            });
            // type=2 Exo:复用 EXOmPlayer 内核工厂语义
            com.github.tvbox.osc.player.api.PlayerFactory.register(2, (context, options, listener) -> {
                android.util.Log.i("AppCompositionRoot", "PlayerFactory: 创建 Exo(type=2) 适配器");
                xyz.doikki.videoplayer.player.VideoView vv = newVideoView(context, 2);
                com.github.tvbox.osc.player.api.PlayerApi api =
                        new com.github.tvbox.osc.player.VideoViewPlayerApi(vv, true);
                api.subscribe(listener);
                return api;
            });
            playerAdaptersRegistered = true;
            android.util.Log.i("AppCompositionRoot", "PlayerFactory: IJK(1)/Exo(2) 适配器已注册");
        } catch (Throwable th) {
            android.util.Log.w("AppCompositionRoot", "PlayerFactory 适配器注册失败(不影响主链路)", th);
        }
    }

    /** 按内核类型创建独立 VideoView(内核/渲染统一经 PlayerKernels;渲染默认 texture) */
    private static xyz.doikki.videoplayer.player.VideoView newVideoView(Context context, int playType) {
        xyz.doikki.videoplayer.player.VideoView vv = new xyz.doikki.videoplayer.player.VideoView(context);
        // 内核单点(与 PlayerHelper.updateCfg 同源;Media3 升级只改 PlayerKernels)
        vv.setPlayerFactory(com.github.tvbox.osc.player.PlayerKernels.doikkiFactory(playType, null));
        try {
            int renderType = com.github.tvbox.osc.player.api.PlayConfig.getRenderType();
            vv.setRenderViewFactory(com.github.tvbox.osc.player.PlayerKernels.renderFactory(renderType));
        } catch (Throwable th) {
            android.util.Log.w("AppCompositionRoot", "VideoView 渲染工厂设置失败(原型)", th);
        }
        return vv;
    }

    /** 网络客户端提供者(供后续新代码经接口取客户端;缺省为按需构建) */
    public static com.github.tvbox.osc.net.NetworkProvider network() {
        com.github.tvbox.osc.net.NetworkProvider p = networkProvider;
        return p != null ? p : com.github.tvbox.osc.net.NetworkProvider.DEFAULT;
    }
}
