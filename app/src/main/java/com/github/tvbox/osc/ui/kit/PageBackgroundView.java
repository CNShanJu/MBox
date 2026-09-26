package com.github.tvbox.osc.ui.kit;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.BgImageTransform;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;
import com.squareup.picasso.Target;

import java.io.File;

/**
 * 全局页面背景层:所有页面共用的"body"底图。
 * <p>
 * 各页面布局根节点都是透明的,页面内容实际浮在窗口底色({@code @color/windowBackground} →
 * {@code bg_body})之上,这一层即所有页面共用的 body。本组件由
 * {@link com.github.tvbox.osc.base.BaseActivity} 统一挂到内容容器({@code android.R.id.content})
 * 的最底层,因此<b>所有页面自动获得同一张背景图</b>,无需逐页改动布局。
 * <p>
 * 结构:背景图({@link ImageView} + {@link Matrix},支持用户拖动/等比缩放的自由摆放) + 主题色半透明遮罩
 * (取 {@code @color/bg_body})。遮罩让直接画在底色上的文字/图标(顶部标题、tab、宫格间隙等)保持可读,
 * 并随明暗主题自动换色:浅色主题偏白、深色主题偏暗。遮罩只提供<b>开关</b>,不透明度是固定值;设置页那根滑杆
 * 调的是<b>背景图自身的不透明度</b>({@code imageAlpha},作用于图片而非遮罩)。
 * <p>
 * 缩放/位置模型见 {@link BgImageTransform}:普通图片默认铺满屏幕;尺寸很小的图(如 100×100)
 * 默认按原始像素显示,用户可以拖到任意位置(例如右下角做点缀);位置存的是<b>锚点比例</b>
 * (0=起始边贴边、0.5=居中、1=结束边贴边),与屏幕尺寸无关,所以换分辨率/转横竖屏仍是同一观感。
 * 设置入口见 {@code ui/activity/BackgroundSettingActivity}。
 * <p>
 * 组件本身不读配置:图源/遮罩/缩放/位置由页面宿主按 {@code SystemConfig} 的配置门面取值后经
 * {@link Config} 传入(见 {@link #attach(Activity, Config)});设置页的实时预览用
 * {@link #setDim(int)} / {@link #setTransform(float, float, float)} 直接改本层。
 */
public class PageBackgroundView extends FrameLayout {

    /** 背景配置(由页面宿主从 SystemConfig 组装,组件自身不读全局配置) */
    public static final class Config {
        /**
         * 图源:<b>空串=纯色</b>(不挂背景图,页面即主题窗底色);
         * 应用内文件绝对路径 = 用户选的图;{@code file:///android_asset/...} = 打包素材
         * (后续内置主题自带的默认背景图用这种)。
         */
        public final String imagePath;
        /** 主题色遮罩不透明度 0-100;0=不加遮罩(固定值,用户只能开关遮罩) */
        public final int dimPercent;
        /** 背景图自身不透明度 0-100;100=原图,0=完全透明(设置页滑杆) */
        public final int imageAlpha;
        /** 缩放倍率(相对铺满;<=0 自动:大图铺满、小图原始像素) */
        public final float zoom;
        /** 横向位置:锚点比例 0~1(0=贴左、0.5=居中、1=贴右) */
        public final float anchorX;
        /** 纵向位置:锚点比例 0~1(0=贴上、0.5=居中、1=贴下) */
        public final float anchorY;
        /**
         * 上面两个位置字段装的是不是<b>旧版的"图片中心位移"</b>(-0.5..0.5,按屏宽/屏高归一化)。
         * <p>
         * 旧值只在"写入时那块屏幕的几何"下有意义(转横竖屏会漂),组件拿到图片尺寸后会按
         * {@link BgImageTransform#anchorFromLegacyOffset} 换算成锚点(当屏视觉不变),
         * 换算结果经 {@link #setOnLegacyMigratedListener} 交宿主落盘,之后不再走这条路。
         */
        public final boolean legacyOffsets;

        public Config(String imagePath, int dimPercent, int imageAlpha,
                      float zoom, float anchorX, float anchorY, boolean legacyOffsets) {
            this.imagePath = imagePath;
            this.dimPercent = dimPercent;
            this.imageAlpha = imageAlpha;
            this.zoom = zoom;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.legacyOffsets = legacyOffsets;
        }
    }

    /** 旧版"中心位移"配置被换算成锚点后的回调(宿主落盘用) */
    public interface LegacyMigratedListener {
        void onLegacyMigrated(float zoom, float anchorX, float anchorY);
    }

    /** 背景图(自绘矩阵,置于遮罩之下) */
    private final ImageView image;
    /** 主题色半透明遮罩(bg_body) */
    private final View scrim;
    private final Matrix imageMatrix = new Matrix();

    /** 当前已应用的图源(空串=内置默认图);null 表示尚未加载过 */
    private String source = null;
    private int dimPercent = -1;
    /** 背景图自身不透明度(0-100) */
    private int imageAlpha = 100;
    /** 配置的缩放(<=0 自动)与位置锚点;设置页实时预览会直接改这三个值 */
    private float zoom = 0f;
    private float anchorX = BgImageTransform.ANCHOR_CENTER;
    private float anchorY = BgImageTransform.ANCHOR_CENTER;
    /** 当前配置的位置字段是不是旧版"中心位移"(拿到图片尺寸后换算成锚点,只换算一次) */
    private boolean legacyOffsets = false;
    private boolean legacyResolved = false;
    /** 当前已加载图片的像素尺寸(0=未加载/失败) */
    private int imageW = 0;
    private int imageH = 0;
    /** Picasso 对 Target 只持弱引用,这里强引用住,避免加载中被回收 */
    private Target loadTarget;
    /** 图片加载完成(成功或失败)后回调:设置页用它刷新预设高亮(加载前拿不到图片尺寸) */
    private Runnable onImageReady;
    /** 旧版位移换算成锚点后的回调(页面宿主用它把锚点落盘) */
    private LegacyMigratedListener onLegacyMigrated;

    public PageBackgroundView(Context context) {
        this(context, null);
    }

    public PageBackgroundView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PageBackgroundView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setId(R.id.page_background_layer);
        // 纯装饰层:不参与无障碍朗读,也不消费触摸事件
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        image = new ImageView(context);
        // 缩放/位置完全由矩阵决定(不是 centerCrop),这样小图、偏移摆放都能实现
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scrim = new View(context);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /**
     * 给 Activity 挂上/刷新全局背景层(幂等:已挂则只按传入配置刷新,不重复添加)。
     * 由 {@link com.github.tvbox.osc.base.BaseActivity} 在 onCreate/onResume 调用。
     *
     * @return 当前活动的背景层(挂不上时返回 null),便于宿主接着注册回调/读取状态
     */
    @Nullable
    public static PageBackgroundView attach(Activity activity, Config config) {
        if (activity == null || config == null) return null;
        PageBackgroundView layer = find(activity);
        if (layer == null) {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return null;
            layer = new PageBackgroundView(activity);
            // 加在最底层:各页面布局(含 Fragment 容器)都在它上面,布局透明处即露出背景
            content.addView(layer, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        layer.applyConfig(config);
        return layer;
    }

    /** 取当前 Activity 已挂载的背景层(设置页实时预览用);未挂载返回 null */
    @Nullable
    public static PageBackgroundView find(Activity activity) {
        if (activity == null) return null;
        View v = activity.findViewById(R.id.page_background_layer);
        return v instanceof PageBackgroundView ? (PageBackgroundView) v : null;
    }

    // ── 对外状态(设置页读取当前值/实时预览用) ──

    /** 当前实际缩放倍率:配置为"自动"时给出解析值(可回落写入配置) */
    public float getEffectiveZoom() {
        if (zoom > 0) return BgImageTransform.clampZoom(zoom);
        return resolveZoom();
    }

    /** 当前位置:横向锚点比例 0~1(0=贴左、0.5=居中、1=贴右) */
    public float getAnchorX() {
        return anchorX;
    }

    /** 当前位置:纵向锚点比例 0~1(0=贴上、0.5=居中、1=贴下) */
    public float getAnchorY() {
        return anchorY;
    }

    /**
     * 位置是否已经是可用的锚点:配置本来就是锚点,或旧版位移已按当前几何换算完。
     * {@code false} 时 {@link #getAnchorX()}/{@link #getAnchorY()} 里还是旧版的位移值,
     * 宿主(设置页)不要拿它当锚点用(见 {@link Config#legacyOffsets})。
     */
    public boolean isPositionResolved() {
        return !legacyOffsets;
    }

    /** 当前背景图像素宽(0=未加载完,此时不可拖动) */
    public int getImageWidth() {
        return imageW;
    }

    /** 当前背景图像素高(0=未加载完) */
    public int getImageHeight() {
        return imageH;
    }

    /** 实时预览:只改遮罩不透明度(持久化由调用方负责) */
    public void setDim(int percent) {
        applyDim(percent);
    }

    /** 实时预览:只改背景图自身不透明度(100=原图,持久化由调用方负责) */
    public void setImageAlpha(int percent) {
        applyAlpha(percent);
    }

    /** 当前背景图自身不透明度(0-100) */
    public int getImageAlpha() {
        return imageAlpha;
    }

    /**
     * 设置"背景图加载完成"回调(设置页用于刷新预设高亮/拖动可用状态)。
     * 加载完成(含失败)时在<b>主线程</b>回调;传 null 清除。
     */
    public void setOnImageReadyListener(@Nullable Runnable listener) {
        onImageReady = listener;
    }

    /**
     * 设置"旧版位移已换算成锚点"回调(页面宿主据此落盘,只发生一次);传 null 清除。
     * 见 {@link Config#legacyOffsets} 与 {@link BgImageTransform#anchorFromLegacyOffset}。
     */
    public void setOnLegacyMigratedListener(@Nullable LegacyMigratedListener listener) {
        onLegacyMigrated = listener;
    }

    /** 实时预览:只改缩放/位置锚点(持久化由调用方负责) */
    public void setTransform(float zoom, float anchorX, float anchorY) {
        this.zoom = Float.isNaN(zoom) ? 0f : zoom;
        this.anchorX = BgImageTransform.clampAnchor(anchorX);
        this.anchorY = BgImageTransform.clampAnchor(anchorY);
        updateMatrix();
    }

    // ── 内部 ──

    private void applyConfig(Config cfg) {
        applyDim(cfg.dimPercent);
        applyAlpha(cfg.imageAlpha);
        String path = cfg.imagePath == null ? "" : cfg.imagePath;
        boolean sourceChanged = !path.equals(source);
        if (sourceChanged) {
            source = path;
            loadImage(path);
        }
        // 位置:旧版位移(偏移量语义:0=居中、±0.5=贴边)等到拿到图片尺寸再换算(见 resolveLegacyOffsets),
        // 此处必须**原样收下**;只有新模型的锚点(比例语义:0=贴左/上、0.5=居中、1=贴右/下)才钳制到 0~1。
        // 旧值若也按锚点钳制,负位移(贴左/贴上)会被夹成 0,再经换算 = 恒定居中 —— 老配置一进页面位置就被改成居中,
        // 而且迁移回调会立刻落盘并丢掉旧键,用户不可回退。
        legacyOffsets = cfg.legacyOffsets;
        legacyResolved = false;
        zoom = Float.isNaN(cfg.zoom) ? 0f : cfg.zoom;
        if (legacyOffsets) {
            anchorX = Float.isNaN(cfg.anchorX) ? 0f : cfg.anchorX;
            anchorY = Float.isNaN(cfg.anchorY) ? 0f : cfg.anchorY;
        } else {
            anchorX = BgImageTransform.clampAnchor(cfg.anchorX);
            anchorY = BgImageTransform.clampAnchor(cfg.anchorY);
        }
        resolveLegacyOffsets();
        // 图源不变也要按传入值刷新(设置页可能刚改了缩放/位置)
        updateMatrix();
    }

    /**
     * 旧版"图片中心位移"→ 锚点(老配置一次性迁移):必须在拿到图片尺寸之后做,而且只在
     * <b>当前这块屏幕的几何</b>上换算一次(当屏视觉不变)。换算完就不再走旧式,
     * 之后转横竖屏按锚点走,不会漂。
     */
    private void resolveLegacyOffsets() {
        if (!legacyOffsets || legacyResolved) return;
        int vw = getWidth();
        int vh = getHeight();
        if (imageW <= 0 || imageH <= 0 || vw <= 0 || vh <= 0) return;
        float scale = BgImageTransform.coverScale(imageW, imageH, vw, vh) * getEffectiveZoom();
        anchorX = BgImageTransform.anchorFromLegacyOffset(anchorX, imageW * scale, vw);
        anchorY = BgImageTransform.anchorFromLegacyOffset(anchorY, imageH * scale, vh);
        legacyResolved = true;
        legacyOffsets = false;
        LegacyMigratedListener l = onLegacyMigrated;
        if (l != null) l.onLegacyMigrated(getEffectiveZoom(), anchorX, anchorY);
    }

    private void applyDim(int percent) {
        dimPercent = Math.max(0, Math.min(100, percent));
        Context ctx = getContext();
        if (ctx == null) return;
        if (dimPercent <= 0) {
            scrim.setVisibility(GONE);
        } else {
            scrim.setVisibility(VISIBLE);
            scrim.setBackgroundColor(withAlpha(ContextCompat.getColor(ctx, R.color.bg_body), dimPercent));
        }
    }

    /** 背景图自身不透明度:直接给 ImageView 设 alpha(与"遮罩色叠在图上"视觉等价,但作用于图片本身) */
    private void applyAlpha(int percent) {
        imageAlpha = Math.max(0, Math.min(100, percent));
        image.setAlpha(imageAlpha / 100f);
    }

    /** 加载背景图:空路径=纯色(不挂背景图,页面就是主题窗底色);否则读图源 */
    private void loadImage(String path) {
        cancelLoad();
        if (path.isEmpty()) {
            // 默认状态:没有背景图 —— 背景层保持透明,页面即主题纯色
            clearImage();
            return;
        }
        // 图源两种:打包素材(内置主题自带的默认背景图,file:///android_asset/...) / 应用内文件(用户选的图)
        boolean bundled = isBundledSource(path);
        if (!bundled && !new File(path).exists()) {
            // 图源失效:清空并回落到纯色
            clearImage();
            source = null;
            return;
        }
        clearImage();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
        int h = dm.heightPixels > 0 ? dm.heightPixels : 1920;
        final String key = path;
        Target target = new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                if (!key.equals(source)) return; // 期间换过图,丢弃这次结果
                // 关掉密度换算:矩阵按像素计算,若不关,AutoSize 改过 densityDpi 后 intrinsic 尺寸会失真
                bitmap.setDensity(Bitmap.DENSITY_NONE);
                imageW = bitmap.getWidth();
                imageH = bitmap.getHeight();
                image.setImageBitmap(bitmap);
                updateMatrix();
                notifyImageReady();
            }

            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                if (key.equals(source)) clearImage();
                notifyImageReady();
            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {
            }
        };
        loadTarget = target;
        try {
            RequestCreator creator = bundled
                    ? Picasso.get().load(Uri.parse(path))   // Picasso 内置 AssetRequestHandler 直接吃 file:///android_asset/
                    : Picasso.get().load(new File(path));
            // 只做"限制解码尺寸":最大 2 倍屏幕(放大后仍够清晰),centerInside 保证不放大、不变形;
            // 真正的缩放/裁切/摆放由上面的矩阵完成
            creator.resize(w * 2, h * 2).centerInside().into(target);
        } catch (Throwable ignored) {
            // 图片库不可用等异常场景:保持透明,页面回落到主题底色,不影响功能
            loadTarget = null;
            clearImage();
        }
    }

    private void cancelLoad() {        Target t = loadTarget;
        loadTarget = null;
        if (t != null) {
            try {
                Picasso.get().cancelRequest(t);
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearImage() {
        imageW = 0;
        imageH = 0;
        image.setImageDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    /** 图源是不是打包素材(内置主题自带的默认背景图);用户自己选的图一律是应用内文件路径 */
    private static boolean isBundledSource(String source) {
        return source != null && source.startsWith("file:///android_asset/");
    }

    /** 通知宿主图片已就绪(主线程) */
    private void notifyImageReady() {
        Runnable r = onImageReady;
        if (r != null) r.run();
    }

    /** 配置为"自动"时的解析值:小图按原始像素,其余铺满 */
    private float resolveZoom() {
        if (imageW <= 0 || imageH <= 0 || getWidth() <= 0 || getHeight() <= 0) return 1f;
        return BgImageTransform.defaultZoom(imageW, imageH, getWidth(), getHeight());
    }

    private void updateMatrix() {
        int vw = getWidth(), vh = getHeight();
        resolveLegacyOffsets();
        if (imageW <= 0 || imageH <= 0 || vw <= 0 || vh <= 0) return;
        float[] r = BgImageTransform.resolve(imageW, imageH, vw, vh, zoom, anchorX, anchorY);
        imageMatrix.setScale(r[0], r[0]);
        imageMatrix.postTranslate(r[1], r[2]);
        image.setImageMatrix(imageMatrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 转横竖屏/换窗口尺寸:锚点与屏幕尺寸无关,直接按新尺寸重算即可(旧版位移在这一次换算)
        updateMatrix();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelLoad();
        super.onDetachedFromWindow();
    }

    /** 把主题底色按百分比(0-100)做成半透明遮罩色 */
    private static int withAlpha(int color, int percent) {
        int p = Math.max(0, Math.min(100, percent));
        int alpha = Math.round(p * 255f / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
