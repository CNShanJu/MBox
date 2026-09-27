package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;

import androidx.exifinterface.media.ExifInterface;

import com.github.tvbox.osc.state.StorageSpace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 背景图导入(用户选图 → 应用私有目录里一张可用的 WebP)。<b>全应用唯一实现</b>:
 * "设置背景图"页的全局底图与"自定义主题"里的主题背景图都走这里,口径不许各写一套。
 *
 * <p>流程(每一步都是踩过坑才这么定的):
 * <ol>
 *   <li><b>存储预检</b>:先问中控层 {@link StorageSpace}(与视频下载共用同一 1GB 底线)——
 *       这里要写盘,写完还得留住余量,空间不够直接给可读原因而不是写到一半失败;</li>
 *   <li>复制到临时文件,顺便卡体积上限({@link BgImageImportRules#MAX_BYTES} 30MB);</li>
 *   <li>看文件头 + MIME 判格式:<b>拒绝 GIF</b>(背景要静态图),改过扩展名的假图也能拦;</li>
 *   <li>解头拿尺寸确认确实是图片 → 按上限采样解码(大图不整张进内存);</li>
 *   <li>按 EXIF 纠正方向(相机拍的图方向写在 EXIF 里,不纠正落盘后是横的);</li>
 *   <li>重新编码为 WebP(照片走高质量有损、带透明通道走无损,见 {@link BgImageImportRules});</li>
 *   <li>产出落在<b>应用缓存目录</b>的临时文件,由调用方决定归属:
 *       全局底图交给 PageBackgroundStore 落 {@code page_bg/},主题背景图交给
 *       ThemeBackgroundLibrary 按内容 hash 收进 {@code theme_bg/}(两边都会把这个临时文件收编掉)。</li>
 * </ol>
 *
 * <p>耗时操作(解码 + 编码),务必在后台线程调用。
 */
public final class BgImageImporter {

    /** 结果:webp 非空=成功(调用方负责收编或删除这个临时文件),否则 error 是可直接提示的原因 */
    public static final class Result {
        public final File webp;
        public final String error;

        private Result(File webp, String error) {
            this.webp = webp;
            this.error = error;
        }

        public boolean ok() {
            return webp != null;
        }
    }

    private BgImageImporter() {
    }

    /**
     * 导入一张图,产出私有缓存里的 WebP 临时文件。
     *
     * @param uri 系统选图(SAF)给的 Uri
     */
    public static Result toWebp(Context context, Uri uri) {
        if (context == null || uri == null) return new Result(null, "没有拿到图片");
        File dir = context.getCacheDir();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return new Result(null, "存储不可用");

        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }
        if ("image/gif".equalsIgnoreCase(mime)) {
            return new Result(null, "不支持 GIF,请用静态图片");
        }

        File temp = new File(dir, "bg_import_src.tmp");
        long bytes = 0L;
        // 1) 先复制到临时文件:顺便卡体积上限,后面解码/读 EXIF 都基于这个文件
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return new Result(null, "图片打不开,换一张试试");
            // 存储预检放在"知道要写多少"之后:按源图大小预估(转码后的 WebP 通常更小,是保守估计)
            long declared = declaredSize(context, uri);
            if (!StorageSpace.canWrite(Math.max(declared, 0L))) {
                return new Result(null, StorageSpace.insufficientMessage(Math.max(declared, 0L)));
            }
            byte[] buf = new byte[64 * 1024];
            try (OutputStream os = new FileOutputStream(temp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes += n;
                    if (bytes > BgImageImportRules.MAX_BYTES) {
                        delete(temp);
                        return new Result(null, "图片超过 30MB,请换一张小一点的");
                    }
                    os.write(buf, 0, n);
                }
            }
        } catch (Throwable th) {
            delete(temp);
            return new Result(null, "图片读取失败,换一张试试");
        }

        byte[] header = readHeader(temp);
        String reject = BgImageImportRules.rejectReason(bytes, mime, header);
        if (reject != null) {
            delete(temp);
            return new Result(null, reject);
        }
        // 复制完之后空间真的不够(或上面拿不到大小):再按实际字节数确认一次
        if (!StorageSpace.canWrite(0L)) {
            delete(temp);
            return new Result(null, StorageSpace.insufficientMessage(0L));
        }

        // 2) 解头拿尺寸:确认确实是图片(改扩展名的假图在这里被拦下)
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            delete(temp);
            return new Result(null, "这不是有效的图片文件");
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
                return new Result(null, "图片解码失败,换一张试试");
            }
            bitmap = applyExifOrientation(temp, bitmap);
            out = new File(dir, "bg_import_" + System.currentTimeMillis() + ".webp");
            boolean encoded;
            try (OutputStream os = new FileOutputStream(out)) {
                encoded = compressToWebp(bitmap, os);
            }
            if (!encoded || !out.exists() || out.length() <= 0) {
                delete(out);
                return new Result(null, "图片转换失败,换一张试试");
            }
            return new Result(out, null);
        } catch (Throwable th) {
            delete(out);
            return new Result(null, "图片处理失败,换一张试试");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            delete(temp);
        }
    }

    /** 内容提供者声明的大小(拿不到按 0 = 只做"不低于 1GB"的底线判定) */
    private static long declaredSize(Context context, Uri uri) {
        try (android.database.Cursor c = context.getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return Math.max(0L, c.getLong(idx));
            }
        } catch (Throwable ignored) {
        }
        return 0L;
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

    private static void delete(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
