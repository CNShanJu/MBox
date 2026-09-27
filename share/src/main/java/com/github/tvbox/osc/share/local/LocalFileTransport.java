package com.github.tvbox.osc.share.local;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareAvailability;
import com.github.tvbox.osc.share.ShareCallback;
import com.github.tvbox.osc.share.ShareCapability;
import com.github.tvbox.osc.share.ShareErrorCode;
import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.ShareImportListener;
import com.github.tvbox.osc.share.ShareLimits;
import com.github.tvbox.osc.share.ShareLink;
import com.github.tvbox.osc.share.SharePackage;
import com.github.tvbox.osc.share.SharePlatform;
import com.github.tvbox.osc.share.ShareProgress;
import com.github.tvbox.osc.share.ShareRequest;
import com.github.tvbox.osc.share.internal.BaseTransport;
import com.github.tvbox.osc.share.internal.ShareCallbackHandle;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 本地文件传输:归档包已经在本机落盘,本"传输"只负责把它变成一条统一的 {@link ShareLink}
 * 交给界面走系统分享({@code ACTION_SEND})或让用户自己拷走。
 *
 * <p>它存在的意义不是"传输"(确实没传任何东西),而是<b>兜底与统一</b>:
 * <ul>
 *   <li><b>永远可用</b>:没网、在线平台挂了、局域网开关没开 —— 本地导出仍然能走。
 *       注册表按"优先级 + 可用性"挑平台,把它排在最后就得到"优先发链接,不行就存文件"的行为,
 *       界面不需要写 if-else;</li>
 *   <li><b>调用点统一</b>:所有平台的导出都返回 {@link ShareLink},界面只有一条渲染路径;
 *       没有它,本地导出就得在界面里单开一个分支("这种情况返回 File,那种情况返回链接");</li>
 *   <li>它天然演示了"能力不对称":只有 {@link ShareCapability#EXPORT}。
 *       <b>导入不走这里</b> —— 用户从本机选一个归档包导入属于系统文件选择器(SAF)的活,
 *       由 :app 用 {@code ACTION_OPEN_DOCUMENT} 处理,与本模块无关。</li>
 * </ul>
 *
 * <p>本类<b>不复制、不移动</b>归档文件:{@code pkg.file()} 是所有者的临时文件,
 * 分享出去后由所有者负责清理(见 {@link SharePackage} 的生命周期约定)。
 * 因此 {@link ShareLink#directUrl()} 里放的是<b>绝对路径</b>而不是 {@code http(s)} 链接 ——
 * 界面据此知道要交给系统分享而不是"复制链接"。
 */
public final class LocalFileTransport extends BaseTransport {

    private static final Set<ShareCapability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(ShareCapability.EXPORT));

    private final Context appContext;

    public LocalFileTransport(@Nullable Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    @NonNull
    @Override
    public SharePlatform platform() {
        return SharePlatform.LOCAL_FILE;
    }

    @NonNull
    @Override
    public Set<ShareCapability> capabilities() {
        return CAPABILITIES;
    }

    @NonNull
    @Override
    public ShareAvailability availability() {
        // 本地导出唯一的前置条件是"有地方放文件"。归档由调用方(:app)落到自己的缓存目录,
        // 这里拿不到那个目录,所以只能确认 Context 在(CacheDir 取不到就会是 null)。
        if (appContext == null) {
            return ShareAvailability.unavailable("应用上下文未就绪");
        }
        try {
            File cache = appContext.getExternalCacheDir();
            if (cache == null) cache = appContext.getCacheDir();
            if (cache == null) {
                return ShareAvailability.unavailable("本地缓存目录不可用");
            }
        } catch (Throwable t) {
            return ShareAvailability.unavailable("本地缓存目录不可用");
        }
        return ShareAvailability.available();
    }

    @NonNull
    @Override
    public ShareLimits limits() {
        // 本地导出不经过网络,没有平台上限;受磁盘空间约束
        return new ShareLimits(-1L, -1, 0, 0, 0, "保存在本机,不经过网络,受可用存储空间限制");
    }

    @Override
    public void export(@NonNull ShareRequest request, @NonNull ShareCallback<ShareLink> callback) {
        final ShareCallbackHandle<ShareLink> handle = ShareCallbackHandle.of(callback);

        ShareAvailability availability = availability();
        if (!availability.isAvailable()) {
            handle.error(ShareException.unavailable(availability.reason()));
            return;
        }
        SharePackage pkg = request.pkg();
        if (pkg == null || !pkg.isUsable()) {
            handle.error(ShareException.invalid("要导出的归档文件不可用"));
            return;
        }
        // 本地导出是纯内存操作(只 stat 一下文件),没有可取消的中间态,直接同步给结果。
        // 仍走 beginOperation:让"连点两次导出"的前一次以 CANCELLED 收尾,语义与其它平台一致。
        final long op = beginOperation(handle);
        try {
            handle.progress(ShareProgress.of(ShareProgress.Phase.PREPARING, "正在准备本地文件"));
            File f = pkg.file();
            String path = f.getAbsolutePath();
            ShareLink link = ShareLink.builder(SharePlatform.LOCAL_FILE)
                    .directUrl(path)
                    .fileName(pkg.fileName())
                    .sizeBytes(pkg.sizeBytes())
                    .note("已保存到本机,可通过系统分享发送给其它设备")
                    .build();
            handle.success(link);
        } catch (Throwable t) {
            handle.error(new ShareException(ShareErrorCode.IO,
                    "本地导出失败:" + t.getMessage(), t));
        } finally {
            finishOperation(op);
        }
    }

    /** 本地没有"按链接拉取"这回事:导入走系统文件选择器,不经本模块 */
    @Override
    public void pull(@NonNull String shareRef, @NonNull ShareCallback<SharePackage> callback) {
        callback.onError(ShareException.unsupported(
                "本地文件导入请使用系统文件选择器;本平台只支持导出"));
    }

    /** 本地没有"等对端推送"这回事 */
    @Override
    public void listen(@Nullable ShareImportListener listener) {
        if (listener != null) {
            listener.onError(ShareException.unsupported("本地文件平台不支持接收对端推送"));
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "LocalFileTransport{" + platform().id() + "}";
    }
}
