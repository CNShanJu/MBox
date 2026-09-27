package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.PicassoLoad;
import com.lxj.xpopup.core.ImageViewerPopupView;
import com.lxj.xpopup.interfaces.XPopupImageLoader;
import com.lxj.xpopup.photoview.PhotoView;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * XPopup 大图查看器({@code asImageViewer})的图片加载器:走本 App 唯一的图片库 Picasso。
 * <p>
 * 为什么必须自己实现:XPopup 自带的 {@code SmartGlideImageLoader} 内部调用 Glide,
 * 而本仓已全量移除 Glide 依赖 —— 点缩略图看大图时会直接 NoClassDefFoundError 崩溃。
 * 这里按 XPopup 2.10.0 的 {@link XPopupImageLoader} 契约(与自带实现同语义)用 Picasso 重写:
 * <ul>
 *   <li>{@link #loadImage}:加载大图,进度圈跟随成功/失败收起,失败切统一占位(见 {@link PicassoLoad});</li>
 *   <li>{@link #loadSnapshot}:转场首帧复用缩略图已解码的 drawable(不闪),随后由 loadImage 覆盖;</li>
 *   <li>{@link #getImageFile}:"保存图片"用,XPopup 在单线程池里调用(非主线程),这里用共享图片客户端
 *       下载到 cache 并返回,失败返回 null 时 XPopup 自己提示"图片不存在"。</li>
 * </ul>
 * 图片统一经 {@link DefaultConfig#checkReplaceProxy} 换代理,与全 App 一致。
 */
public class PicassoImageLoader implements XPopupImageLoader {

    /** 保存图片时的单次下载上限:8MB 足够一张海报,避免超大响应把内存/磁盘写爆(与网络层无界响应的口径一致) */
    private static final long MAX_SAVE_BYTES = 8L * 1024 * 1024;
    /** 保存图片的临时下载目录(cache 下,XPopup 随后会拷进相册目录) */
    private static final String SAVE_TMP_DIR = "image_viewer";

    @Override
    public View loadImage(int position, Object url, ImageViewerPopupView popupView,
                          PhotoView photoView, ProgressBar progressBar) {
        load(photoView, url, progressBar);
        return photoView;
    }

    @Override
    public void loadSnapshot(Object url, PhotoView photoView, ImageView imageView) {
        // 首帧:直接把缩略图已解码的图复制过去,转场(共享元素放大)起点就是用户看到的那张图,不闪
        try {
            if (imageView != null && imageView.getDrawable() != null
                    && imageView.getDrawable().getConstantState() != null) {
                photoView.setImageDrawable(imageView.getDrawable().getConstantState().newDrawable());
            }
        } catch (Throwable ignored) {
        }
        load(photoView, url, null);
    }

    /** 大图加载:fit 到控件尺寸解码(避免原图尺寸直接进内存),进度圈与失败占位统一收口 */
    private static void load(final PhotoView photoView, Object url, @Nullable final ProgressBar progressBar) {
        if (photoView == null) return;
        final String real = url == null ? "" : DefaultConfig.checkReplaceProxy(String.valueOf(url));
        if (TextUtils.isEmpty(real)) {
            hide(progressBar);
            PicassoLoad.showFailedPlaceholder(photoView);
            return;
        }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        Picasso.get()
                .load(real)
                .fit()
                .into(photoView, new Callback() {
                    @Override
                    public void onSuccess() {
                        hide(progressBar);
                    }

                    @Override
                    public void onError(Exception e) {
                        hide(progressBar);
                        // 失败态与全 App 一致(灰底 + 居中图标 + "图片加载失败")
                        PicassoLoad.showFailedPlaceholder(photoView);
                    }
                });
    }

    private static void hide(@Nullable ProgressBar progressBar) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    @Nullable
    @Override
    public File getImageFile(Context context, Object url) {
        if (context == null || url == null) return null;
        final String real = DefaultConfig.checkReplaceProxy(String.valueOf(url));
        if (TextUtils.isEmpty(real)) return null;
        File dir = new File(context.getCacheDir(), SAVE_TMP_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        File out = new File(dir, "img_" + Integer.toHexString(real.hashCode()) + "_" + real.length());
        if (out.exists() && out.length() > 0) return out;
        OkHttpClient client = com.github.tvbox.osc.util.OkGoHelper.getImageClient();
        if (client == null) client = com.github.tvbox.osc.util.OkGoHelper.getDefaultClient();
        if (client == null) return null;
        Response resp = null;
        try {
            resp = client.newCall(new Request.Builder().url(real).build()).execute();
            ResponseBody body = resp.body();
            if (!resp.isSuccessful() || body == null) return null;
            if (body.contentLength() > MAX_SAVE_BYTES) return null;
            long written = 0;
            try (InputStream in = body.byteStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    written += n;
                    if (written > MAX_SAVE_BYTES) { // 服务端未给 Content-Length 时按实写量兜底
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        return null;
                    }
                    fos.write(buf, 0, n);
                }
            }
            return out.length() > 0 ? out : null;
        } catch (Throwable th) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        } finally {
            if (resp != null) {
                try {
                    resp.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
