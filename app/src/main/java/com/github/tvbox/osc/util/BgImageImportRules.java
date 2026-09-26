package com.github.tvbox.osc.util;

/**
 * 背景图导入的纯规则(不依赖 Android,可 JVM 单测):
 * 格式识别与拒绝、体积上限、解码采样、WebP 编码策略。
 * <p>
 * 实际落盘(解码/EXIF 纠正/WebP 重编码)在 {@link PageBackgroundStore}。
 */
public final class BgImageImportRules {

    /** 单张图上限:30MB(超过直接拒绝,避免手滑选中超大文件) */
    public static final long MAX_BYTES = 30L * 1024 * 1024;

    /** 落盘图长边上限:2560(手机上显示不会掉细节,同时把解码内存压住) */
    public static final int MAX_LONG_SIDE = 2560;

    /** 不透明图(照片)的 WebP 编码质量:90(视觉上基本无损,体积通常远小于原 JPEG) */
    public static final int WEBP_QUALITY = 90;

    private BgImageImportRules() {
    }

    /**
     * 文件头识别图片格式。
     *
     * @return "jpeg"/"png"/"webp"/"gif"/"bmp"/"heif";识别不出返回 null
     */
    public static String formatOf(byte[] header) {
        if (header == null || header.length < 12) return null;
        // JPEG: FF D8 FF
        if ((header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) return "jpeg";
        // PNG: 89 50 4E 47
        if ((header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') return "png";
        // GIF: "GIF8"
        if (isGif(header)) return "gif";
        // WebP: "RIFF"...."WEBP"
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return "webp";
        // BMP: "BM"
        if (header[0] == 'B' && header[1] == 'M') return "bmp";
        // HEIF/HEIC/AVIF: 偏移 4 起 "ftyp"
        if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') return "heif";
        return null;
    }

    /** GIF 动图不支持(背景要静态图):按文件头判定,改过扩展名也认得出来 */
    public static boolean isGif(byte[] header) {
        return header != null && header.length >= 4
                && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8';
    }

    /**
     * 文件头 + MIME 的准入判定。
     *
     * @return 拒绝原因(可直接提示用户);通过返回 null
     */
    public static String rejectReason(long bytes, String mime, byte[] header) {
        if (bytes > MAX_BYTES) return "图片超过 30MB,请换一张小一点的";
        if ("image/gif".equalsIgnoreCase(mime) || isGif(header)) return "不支持 GIF,请用静态图片";
        return null;
    }

    /**
     * 解码采样率:让长边落到 {@code (maxLongSide/2, maxLongSide]},只缩不放。
     * 用 inSampleSize(2 的幂)而不是二次缩放,避免多占一份大图内存。
     */
    public static int sampleSizeFor(int width, int height, int maxLongSide) {
        if (width <= 0 || height <= 0 || maxLongSide <= 0) return 1;
        int longSide = Math.max(width, height);
        int sample = 1;
        while (longSide / sample > maxLongSide) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * 落盘 WebP 的编码策略:带透明通道的走无损(图形/PNG 截图,无损 WebP 通常还更小);
     * 不透明的照片走高质量有损 —— 照片的无损 WebP 反而会把 JPEG 那点近似像素原样存下来,
     * 体积通常是原图的数倍,得不偿失。
     */
    public static boolean useLosslessWebp(boolean hasAlpha) {
        return hasAlpha;
    }
}
