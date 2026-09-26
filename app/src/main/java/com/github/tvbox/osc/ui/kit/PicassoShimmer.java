package com.github.tvbox.osc.ui.kit;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;

/**
 * 骨架屏扫光 shimmer(仅高光扫过,底灰+图标由图片占位背景承担)。
 *
 * <p>做法:作为 ImageView 的 foreground 叠加一层"移动高光带",让占位图有加载动画、
 * 不再"灰底一硬切到实图"。真实图片加载完成/失败后必须调 {@link #stop(ImageView)} 移除。
 * 统一入口见 {@link PicassoShimmer},供海报卡加载(首页/历史/收藏/搜索等)复用。
 */
public final class PicassoShimmer {

    private PicassoShimmer() {
    }

    /** 在 iv 上启动扫光(叠加为 foreground,覆盖在其占位背景之上);已有则先停旧 */
    public static void start(ImageView iv) {
        if (iv == null) return;
        stop(iv);
        ShimmerDrawable d = new ShimmerDrawable();
        iv.setForeground(d);
        d.start();
    }

    /** 停止并移除扫光(图片已加载/失败/复用列表项时调用) */
    public static void stop(ImageView iv) {
        if (iv == null) return;
        android.graphics.drawable.Drawable fg = iv.getForeground();
        if (fg instanceof ShimmerDrawable) {
            ((ShimmerDrawable) fg).stop();
        }
        iv.setForeground(null);
    }

    /** 扫光 Drawable:一条半透明白色高光带从左上向右下反复扫过 */
    static final class ShimmerDrawable extends Drawable implements android.graphics.drawable.Animatable {

        private static final long DURATION_MS = 1400L;

        private final Paint mBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator mAnimator;
        private float mProgress; // 0..1

        ShimmerDrawable() {
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(DURATION_MS);
            mAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.addUpdateListener(animation -> {
                mProgress = (float) animation.getAnimatedValue();
                invalidateSelf();
            });
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            // 光带沿卡片真实对角线方向推进:前沿从"完全在左上角外侧"进入,到"完全在右下角外侧"退出,
            // 视觉即"从左上角往右下角刷"。渐变轴取对角线单位方向,等亮线垂直于对角线,
            // 扫过时呈一条斜向柔光带;渐变两端都是透明色,整卡绘制也只会露出光带部分。
            float diag = (float) Math.sqrt((double) w * w + (double) h * h);
            if (diag <= 0f) return;
            float ux = w / diag;
            float uy = h / diag;
            float band = diag * 0.8f; // 渐变光带沿对角线的总长(含两侧柔边)
            // 0 -> 1:前沿从 -band(卡外、左上角一侧)推到 diag+band(卡外、右下角一侧)
            float front = -band + (diag + 2f * band) * mProgress;
            float x0 = front * ux;
            float y0 = front * uy;
            float x1 = (front + band) * ux;
            float y1 = (front + band) * uy;
            LinearGradient gradient = new LinearGradient(
                    x0, y0, x1, y1,
                    new int[]{0x00000000, 0x2EFFFFFF, 0x59FFFFFF, 0x2EFFFFFF, 0x00000000},
                    new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP);
            mBandPaint.setShader(gradient);
            canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawRect(getBounds(), mBandPaint);
            canvas.restore();
            mBandPaint.setShader(null);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public void start() {
            if (mAnimator.isStarted()) return;
            mAnimator.start();
        }

        @Override
        public void stop() {
            if (mAnimator.isStarted()) mAnimator.cancel();
        }

        @Override
        public boolean isRunning() {
            return mAnimator.isRunning();
        }
    }
}
