package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.Uri;

import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.ui.kit.PageBackgroundView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 页面背景图的落盘与配置组装。
 * <p>
 * 用户选图后:校验(是图片格式、非 GIF、≤30MB)→ 复制到临时文件 → 按 EXIF 纠正方向 →
 * 长边限制到 {@link BgImageImportRules#MAX_LONG_SIDE} → 重新编码为 WebP
 * (照片走高质量有损、带透明通道的走无损,见 {@link BgImageImportRules#useLosslessWebp})→
 * 落到应用私有目录,配置里只存这个路径。
 * <p>
 * 上面那串"选图 → WebP"的活<b>已经抽到 {@link BgImageImporter}</b>(与"自定义主题"的背景图共用同一份实现,
 * 口径不许两套);本类只负责"落到自己的 {@code page_bg/} 目录 + 每次换图删旧副本"这件事。
 * <p>
 * 这样做的好处:不依赖系统相册的临时授权;过大素材先压下来,不会把私有目录和内存顶爆;
 * 每次换图都是新文件名(带时间戳),路径一变页面才会重新加载,旧副本换图成功后删除(目录里最多一份)。
 */
public final class PageBackgroundStore {

    /** 应用内背景图目录(私有目录,不走外部存储权限) */
    private static final String DIR = "page_bg";
    /** 副本固定前缀:换图后删同前缀旧文件,避免越攒越多 */
    private static final String PREFIX = "custom_bg";

    private PageBackgroundStore() {
    }

    /** 导入结果:path 非空表示成功,否则 error 是可直接提示用户的原因 */
    public static final class ImportResult {
        public final String path;
        public final String error;

        private ImportResult(String path, String error) {
            this.path = path;
            this.error = error;
        }

        public boolean ok() {
            return path != null;
        }
    }

    /**
     * 导入用户选中的图片(耗时操作,务必在后台线程调用)。
     *
     * @return 落盘后的绝对路径 / 失败原因
     */
    public static ImportResult importFromUri(Context context, Uri uri) {
        if (context == null || uri == null) return new ImportResult(null, "没有拿到图片");
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) return new ImportResult(null, "存储不可用");

        // 选图 → 校验/纠方向/限尺寸/转 WebP(与自定义主题背景图同一份实现;含存储预检)
        BgImageImporter.Result imported = BgImageImporter.toWebp(context, uri);
        if (!imported.ok()) return new ImportResult(null, imported.error);

        File out = new File(dir, PREFIX + "_" + System.currentTimeMillis() + ".webp");
        try {
            if (out.exists() && !out.delete()) return new ImportResult(null, "图片处理失败,换一张试试");
            if (!imported.webp.renameTo(out)) {
                // 跨卷 rename 失败(缓存目录与私有目录极少数情况下不在同一卷):退回复制
                if (!copy(imported.webp, out)) return new ImportResult(null, "图片处理失败,换一张试试");
            }
            if (!out.exists() || out.length() <= 0) {
                delete(out);
                return new ImportResult(null, "图片转换失败,换一张试试");
            }
            deleteCopies(dir, out);
            return new ImportResult(out.getAbsolutePath(), null);
        } catch (Throwable th) {
            delete(out);
            return new ImportResult(null, "图片处理失败,换一张试试");
        } finally {
            delete(imported.webp); // 收编完成,临时文件清掉
        }
    }

    private static boolean copy(File src, File dst) {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return dst.length() > 0;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 组装背景层配置:页面宿主(Activity)统一从这里取,
     * 免得各处重复读 SystemConfig 门面、漏字段。
     * <p>
     * 位置:已建立锚点的走锚点;老配置(只有旧版"中心位移")把旧值原样塞进去并标记
     * {@link PageBackgroundView.Config#legacyOffsets},由背景层拿到图片尺寸后换算一次
     * (当屏视觉不变)并回调 {@link #persistAnchors} 落盘 —— 之后转横竖屏就不会再漂。
     */
    public static PageBackgroundView.Config currentConfig() {
        boolean anchors = SystemConfig.isPageBackgroundAnchorSet();
        return new PageBackgroundView.Config(
                SystemConfig.getPageBackgroundPath(),
                SystemConfig.getPageBackgroundDim(),
                SystemConfig.getPageBackgroundAlpha(),
                SystemConfig.getPageBackgroundZoom(),
                anchors ? SystemConfig.getPageBackgroundAnchorX() : SystemConfig.getPageBackgroundOffsetX(),
                anchors ? SystemConfig.getPageBackgroundAnchorY() : SystemConfig.getPageBackgroundOffsetY(),
                !anchors);
    }

    /**
     * 把"旧版位移换算出来的锚点"落盘(老配置一次性迁移,由背景层在拿到图片尺寸后回调)。
     * 换算结果与当屏正在显示的摆放完全一致,所以这里是"改写表示法",不是"改用户设置"。
     */
    public static void persistAnchors(float zoom, float anchorX, float anchorY) {
        SystemConfig.setPageBackgroundTransform(zoom, anchorX, anchorY);
    }

    /** 清掉同前缀的旧副本(时间戳不同都算),保证目录里最多只留一份背景图 */
    private static void deleteCopies(File dir, File keep) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f == null || f.equals(keep)) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith(PREFIX)) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void delete(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }
}
