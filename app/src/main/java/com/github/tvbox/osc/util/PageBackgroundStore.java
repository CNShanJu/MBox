package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;

import androidx.exifinterface.media.ExifInterface;

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
 * 这样做的好处:不依赖系统相册的临时授权;过大素材先压下来,不会把私有目录和内存顶爆;
 * 每次换图都是新文件名(带时间戳),路径一变页面才会重新加载,旧副本换图成功后删除(目录里最多一份)。
 */
public final class PageBackgroundStore {

    /** 应用内背景图目录(私有目录,不走外部存储权限) */
    private static final String DIR = "page_bg";
    /** 副本固定前缀:换图后删同前缀旧文件,避免越攒越多 */
    private static final String PREFIX = "custom_bg";
    /** 复制阶段的临时文件名(转码完成后删除) */
    private static final String TEMP = "import_tmp";

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

        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }
        if ("image/gif".equalsIgnoreCase(mime)) {
            return new ImportResult(null, "不支持 GIF,请用静态图片");
        }

        File temp = new File(dir, TEMP);
        long bytes = 0L;
        // 1) 先复制到临时文件:顺便卡体积上限,后面解码/读 EXIF 都基于这个文件
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return new ImportResult(null, "图片打不开,换一张试试");
            byte[] buf = new byte[64 * 1024];
            try (OutputStream os = new FileOutputStream(temp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes += n;
                    if (bytes > BgImageImportRules.MAX_BYTES) {
                        delete(temp);
                        return new ImportResult(null, "图片超过 30MB,请换一张小一点的");
                    }
                    os.write(buf, 0, n);
                }
            }
        } catch (Throwable th) {
            delete(temp);
            return new ImportResult(null, "图片读取失败,换一张试试");
        }

        byte[] header = readHeader(temp);
        String reject = BgImageImportRules.rejectReason(bytes, mime, header);
        if (reject != null) {
            delete(temp);
            return new ImportResult(null, reject);
        }

        // 2) 解头拿尺寸:确认确实是图片(改扩展名的假图在这里被拦下)
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            delete(temp);
            return new ImportResult(null, "这不是有效的图片文件");
        }

        // 3) 按上限采样解码(大图不整张进内存)
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = BgImageImportRules.sampleSizeFor(bounds.outWidth, bounds.outHeight,
                BgImageImportRules.MAX_LONG_SIDE);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = null;
        File out = null;
        try {
            bitmap = BitmapFactory.decodeFile(temp.getAbsolutePath(), opts);
            if (bitmap == null) {
                delete(temp);
                return new ImportResult(null, "图片解码失败,换一张试试");
            }
            bitmap = applyExifOrientation(temp, bitmap);
            out = new File(dir, PREFIX + "_" + System.currentTimeMillis() + ".webp");
            boolean encoded;
            try (OutputStream os = new FileOutputStream(out)) {
                encoded = compressToWebp(bitmap, os);
            }
            if (!encoded || !out.exists() || out.length() <= 0) {
                delete(out);
                return new ImportResult(null, "图片转换失败,换一张试试");
            }
            deleteCopies(dir, out);
            return new ImportResult(out.getAbsolutePath(), null);
        } catch (Throwable th) {
            delete(out);
            return new ImportResult(null, "图片处理失败,换一张试试");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            delete(temp);
        }
    }

    /** WebP 编码:不透明图(照片)走高质量有损,带透明通道的走无损 */
    private static boolean compressToWebp(Bitmap bitmap, OutputStream os) {
        boolean lossless = BgImageImportRules.useLosslessWebp(bitmap.hasAlpha());
        if (lossless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, os);
        }
        // API 30 以下没有 WEBP_LOSSLESS:WEBP + quality 100 即无损;照片走 90 有损
        return bitmap.compress(Bitmap.CompressFormat.WEBP, lossless ? 100 : BgImageImportRules.WEBP_QUALITY, os);
    }

    /** 相机拍的照片方向常写在 EXIF 里,重新编码前必须纠正,否则落盘后是横的 */
    private static Bitmap applyExifOrientation(File file, Bitmap bitmap) {
        int orientation;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable th) {
            return bitmap;
        }
        int degrees;
        boolean mirror;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                degrees = 90;
                mirror = false;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                degrees = 180;
                mirror = false;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                degrees = 270;
                mirror = false;
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                degrees = 0;
                mirror = true;
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                degrees = 180;
                mirror = true;
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                degrees = 90;
                mirror = true;
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                degrees = 270;
                mirror = true;
                break;
            default:
                return bitmap;
        }
        try {
            Matrix matrix = new Matrix();
            if (mirror) matrix.postScale(-1f, 1f);
            if (degrees != 0) matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap && !bitmap.isRecycled()) bitmap.recycle();
            return rotated;
        } catch (Throwable th) {
            return bitmap;
        }
    }

    /** 读文件头 16 字节(格式识别/GIF 判定用) */
    private static byte[] readHeader(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[16];
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n <= 0) break;
                read += n;
            }
            return head;
        } catch (Throwable th) {
            return null;
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
