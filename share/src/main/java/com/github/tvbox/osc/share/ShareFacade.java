package com.github.tvbox.osc.share;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.internal.ShareTransportRegistry;
import com.github.tvbox.osc.share.local.LocalFileTransport;
import com.github.tvbox.osc.share.online.StorageToTransport;
import com.github.tvbox.osc.share.transport.ShareTransport;
import com.github.tvbox.osc.share.transport.lan.LanShareHost;
import com.github.tvbox.osc.share.transport.lan.LanShareTransport;

import java.util.List;
import java.util.Map;

/**
 * 分享/导入导出对外唯一入口(门面)。
 *
 * <p>调用方(界面/业务)只认本类与 {@code com.github.tvbox.osc.share} 下的模型,
 * <b>不要直接 new 具体传输实现</b>,也不要 import {@code .internal.*} 或 {@code .online.*}
 * (已由 app 层源码门禁拦住,见根 build.gradle 的 checkModuleDependencies)。
 *
 * <p>换平台的姿势(这是本模块的设计目的):
 * <pre>
 * // 1) 新平台实现 ShareTransport(自己的 platform()/capabilities()/availability()/export()…)
 * // 2) 在更靠前的位置注册,即优先级更高
 * ShareFacade.register(new MyNewOnlineTransport(ctx));
 * // 3) 结果:exportDefault(...) 与界面上的"默认平台"自动切过去,现有调用点一行不改
 * </pre>
 * 也可以不换默认、只让旧平台"自己报不可用"({@link ShareTransport#availability()} 返回 false),
 * 效果一样 —— 注册表按"注册顺序 + 可用性"挑第一个能用的
 * ({@link ShareTransportRegistry#firstAvailable(ShareCapability)})。
 *
 * <p>初始化时机:必须在任何分享动作之前完成。:app 在组合根
 * ({@code di.AppCompositionRoot}) 里调 {@link #init(Context)} 与
 * {@link #installLanHost(LanShareHost)};未初始化就调用分享 API 会得到
 * {@link ShareErrorCode#NOT_INITIALIZED},而不是崩溃。
 */
public final class ShareFacade {

    /** 注册表:平台 → 实现,插入顺序即优先级 */
    private static final ShareTransportRegistry REGISTRY = new ShareTransportRegistry();

    private static volatile Context appContext;
    private static volatile boolean initialized;

    private ShareFacade() {
    }

    // ------------------------------------------------------------------
    // 装配(仅 :app 组合根调用)
    // ------------------------------------------------------------------

    /**
     * 初始化并注册内置传输:
     * <ul>
     *   <li>{@link SharePlatform#LOCAL_FILE} —— 始终可用,无网络也可导出(兜底);</li>
     *   <li>{@link SharePlatform#STORAGE_TO} —— 在线平台(当前实现在线导入导出走它)。</li>
     * </ul>
     * 幂等:重复调用只更新 Context,不会重复注册(避免把用户手动插到前面的优先级顶掉)。
     *
     * <p>局域网平台不在这里注册 —— 它依赖 :app 的 HTTP 服务,必须经
     * {@link #installLanHost(LanShareHost)} 显式接入。
     */
    public static void init(@Nullable Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        appContext = app;
        if (initialized) return;
        initialized = true;
        // 注册顺序 = 优先级:在线在前(用户预期"导出=发链接"),本地文件在后(兜底)。
        // 本地文件必须在"在线不可用"时仍能被选中,所以它排最后但永远 available。
        if (app != null) {
            REGISTRY.register(new StorageToTransport(app));
            REGISTRY.register(new LocalFileTransport(app));
        }
    }

    /**
     * 接入局域网分享(由 :app 实现 {@link LanShareHost}:把 :app 的 HTTP 服务端桥接过来)。
     *
     * <p>为什么要桥接而不是本模块自己起服务:局域网分享必须复用<b>同一个</b> HTTP 服务实例
     * (端口、进程令牌、绑定方式都是一份),自己再起一个会有端口冲突与双重暴露面;
     * 而那个服务在 :app(server/RemoteServer),本模块不得反向依赖 :app(见门禁)。
     * 桥接接口是这条依赖的出口。
     */
    public static void installLanHost(@NonNull LanShareHost host) {
        REGISTRY.register(new LanShareTransport(host));
    }

    /** 注册自定义平台实现(新增在线平台时用;注册越早优先级越高) */
    public static void register(@NonNull ShareTransport transport) {
        REGISTRY.register(transport);
    }

    /** 摘掉某平台(实现会被 {@code release()}) */
    public static void unregister(@Nullable SharePlatform platform) {
        REGISTRY.unregister(platform);
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** 应用上下文;未初始化时为 null(实现方须容忍,不要直接拿它 new 东西) */
    @Nullable
    public static Context context() {
        return appContext;
    }

    // ------------------------------------------------------------------
    // 查询(界面用它渲染平台列表/入口可见性)
    // ------------------------------------------------------------------

    @Nullable
    public static ShareTransport transport(@Nullable SharePlatform platform) {
        return REGISTRY.get(platform);
    }

    /** 已注册平台(按优先级) */
    @NonNull
    public static List<SharePlatform> platforms() {
        return REGISTRY.platforms();
    }

    /** 具备某能力的平台(不筛可用性;界面可据此显示"为什么这个平台是灰的") */
    @NonNull
    public static List<SharePlatform> platformsSupporting(@NonNull ShareCapability capability) {
        return REGISTRY.platformsSupporting(capability);
    }

    /** 具备某能力且当前可用的平台(按优先级;首个即默认选中项) */
    @NonNull
    public static List<SharePlatform> availablePlatforms(@NonNull ShareCapability capability) {
        return REGISTRY.availablePlatforms(capability);
    }

    /** 各平台可用性快照(设置页"分享平台状态") */
    @NonNull
    public static Map<SharePlatform, ShareAvailability> availabilitySnapshot() {
        return REGISTRY.availabilitySnapshot();
    }

    @NonNull
    public static ShareAvailability availability(@Nullable SharePlatform platform) {
        ShareTransport t = REGISTRY.get(platform);
        if (t == null) return ShareAvailability.unavailable("该分享平台未接入");
        try {
            ShareAvailability a = t.availability();
            return a == null ? ShareAvailability.unavailable("平台未声明可用性") : a;
        } catch (Throwable th) {
            return ShareAvailability.unavailable("平台状态读取失败");
        }
    }

    @NonNull
    public static ShareLimits limits(@Nullable SharePlatform platform) {
        ShareTransport t = REGISTRY.get(platform);
        if (t == null) return ShareLimits.UNKNOWN;
        try {
            ShareLimits l = t.limits();
            return l == null ? ShareLimits.UNKNOWN : l;
        } catch (Throwable th) {
            return ShareLimits.UNKNOWN;
        }
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    /** 导出到指定平台 */
    public static void export(@Nullable SharePlatform platform, @NonNull ShareRequest request,
                              @NonNull ShareCallback<ShareLink> callback) {
        ShareTransport t = REGISTRY.get(platform);
        if (t == null) {
            callback.onError(new ShareException(ShareErrorCode.NOT_INITIALIZED,
                    platform == null ? "未指定分享平台" : platform.label() + " 未接入"));
            return;
        }
        if (!t.supports(ShareCapability.EXPORT)) {
            callback.onError(ShareException.unsupported(platform.label() + " 不支持导出"));
            return;
        }
        t.export(request, callback);
    }

    /**
     * 导出到"当前优先级最高且可用"的平台(界面上的默认按钮用它)。
     * 一个可用平台都没有时,以 {@code UNAVAILABLE} 失败(带原因,界面可展示)。
     */
    public static void exportDefault(@NonNull ShareRequest request,
                                     @NonNull ShareCallback<ShareLink> callback) {
        SharePlatform p = REGISTRY.firstAvailable(ShareCapability.EXPORT);
        if (p == null) {
            callback.onError(ShareException.unavailable("没有可用的分享平台"));
            return;
        }
        export(p, request, callback);
    }

    /** 从在线引用拉取归档包 */
    public static void pull(@Nullable SharePlatform platform, @Nullable String shareRef,
                            @NonNull ShareCallback<SharePackage> callback) {
        ShareTransport t = REGISTRY.get(platform);
        if (t == null) {
            callback.onError(new ShareException(ShareErrorCode.NOT_INITIALIZED,
                    platform == null ? "未指定分享平台" : platform.label() + " 未接入"));
            return;
        }
        if (!t.supports(ShareCapability.IMPORT_PULL)) {
            callback.onError(ShareException.unsupported(platform.label() + " 不支持从链接导入"));
            return;
        }
        if (shareRef == null || shareRef.trim().isEmpty()) {
            callback.onError(ShareException.invalid("请填写分享链接"));
            return;
        }
        t.pull(shareRef.trim(), callback);
    }

    /**
     * 注册推送式导入监听(局域网:等对端上传)。传 {@code null} 监听即注销。
     *
     * <p>与 {@link #pull} 的区别:这是<b>长期等待</b>而不是一次请求,所以用
     * {@link ShareImportListener}(有"等待中/收到/出错"三种状态),且不随操作结束自动注销 ——
     * 页面退出时必须显式注销,否则界面对象会一直被持有。
     */
    public static void listenImports(@Nullable SharePlatform platform,
                                     @Nullable ShareImportListener listener) {
        ShareTransport t = REGISTRY.get(platform);
        if (t == null) {
            if (listener != null) {
                listener.onError(new ShareException(ShareErrorCode.NOT_INITIALIZED,
                        platform == null ? "未指定分享平台" : platform.label() + " 未接入"));
            }
            return;
        }
        if (!t.supports(ShareCapability.IMPORT_PUSH)) {
            if (listener != null) {
                listener.onError(ShareException.unsupported(platform.label() + " 不支持接收导入"));
            }
            return;
        }
        t.listen(listener);
    }

    /** 取消某平台在跑的操作 */
    public static void cancel(@Nullable SharePlatform platform) {
        ShareTransport t = REGISTRY.get(platform);
        if (t != null) t.cancel();
    }

    /** 取消所有平台在跑的操作(页面销毁/用户离开时收口) */
    public static void cancelAll() {
        for (SharePlatform p : REGISTRY.platforms()) {
            cancel(p);
        }
    }
}
