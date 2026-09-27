package com.github.tvbox.osc.ui.kit;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
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
        ShimmerDrawable d = new ShimmerDrawable(iv);
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

    /**
     * 扫光 Drawable:一条半透明白色高光带从左上向右下反复扫过。
     * <p>
     * <b>必须能自停</b>:本动画器是 {@code INFINITE} 的,而"停止"原先只有 Picasso 的
     * onSuccess/onError 一条路 —— 请求被取消(Picasso 取消后不再回调)、延迟启动的任务在图片已经
     * 出图之后才跑到(见 FastSearchAdapter 的孤儿 run)等情况下,这个动画器会永久按 60fps 挂在主线程上,
     * 并且通过 Drawable→Callback 强引用把整个 ImageView(及其 Activity)一起留住。浏览越多累积越多,
     * 表现就是"越用越烫"。这里补三道自愈:
     * <ol>
     *   <li>宿主已经 detach / 窗口不可见 → 立刻停(动画没有意义,且引用链要断开);</li>
     *   <li>超过 {@link #MAX_LIFE_MS} 还没人来停 → 自己停(没有哪种"加载中"值得扫一分钟);</li>
     *   <li>停的时候顺手把 foreground 摘掉,断开 Drawable→View 的强引用。</li>
     * </ol>
     */
    static final class ShimmerDrawable extends Drawable implements android.graphics.drawable.Animatable {

        private static final long DURATION_MS = 1400L;
        /** 单次扫光最长存活时间:没人来 stop 时的兜底(30s 后只剩下灰底占位,不再燃烧主线程) */
        private static final long MAX_LIFE_MS = 30_000L;

        private final Paint mBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator mAnimator;
        /** 宿主:用于"detach/不可见就自停"和"停时摘掉 foreground" */
        private final ImageView mHost;
        private final android.graphics.Matrix mMatrix = new android.graphics.Matrix();
        /** 缓存的渐变(按尺寸复用):原来每帧 draw 都 new LinearGradient + 两个数组 */
        private LinearGradient mGradient;
        private int mGradientW = -1;
        private int mGradientH = -1;
        private float mBand;
        private float mUx;
        private float mUy;
        private long mStartedAt;
        private float mProgress; // 0..1

        ShimmerDrawable(ImageView host) {
            mHost = host;
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(DURATION_MS);
            mAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.addUpdateListener(animation -> {
                mProgress = (float) animation.getAnimatedValue();
                if (!hostUsable()) {
                    // 宿主没了/不可见了,或活得太久:自己停(见类注释)
                    stopSelf();
                    return;
                }
                invalidateSelf();
            });
        }

        /** 宿主是否还能/还需要显示扫光(未 detach + 窗口可见 + 未超时) */
        private boolean hostUsable() {
            if (SystemClock.uptimeMillis() - mStartedAt > MAX_LIFE_MS) return false;
            if (mHost == null) return true;
            return mHost.isAttachedToWindow() && mHost.getWindowVisibility() == android.view.View.VISIBLE;
        }

        /** 内部自停:取消动画 + 摘掉宿主 foreground(必须在主线程调用) */
        private void stopSelf() {
            if (mAnimator.isStarted()) mAnimator.cancel();
            if (mHost != null && mHost.getForeground() == this) {
                mHost.setForeground(null);
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            int w = bounds.width();
            int h = bounds.height();
            if (w <= 0 || h <= 0) return;
            // 光带沿卡片真实对角线方向推进:前沿从"完全在左上角外侧"进入,到"完全在右下角外侧"退出,
            // 视觉即"从左上角往右下角刷"。渐变轴取对角线单位方向,等亮线垂直于对角线,
            // 扫过时呈一条斜向柔光带;渐变两端都是透明色,整卡绘制也只会露出光带部分。
            ensureGradient(w, h);
            float diag = (float) Math.sqrt((double) w * w + (double) h * h);
            // 0 -> 1:前沿从 -band(卡外、左上角一侧)推到 diag+band(卡外、右下角一侧)
            float front = -mBand + (diag + 2f * mBand) * mProgress;
            // 平移由 shader 的局部矩阵施加:渐变本身只在尺寸变化时重建,draw 里不再分配对象
            mMatrix.setTranslate(front * mUx, front * mUy);
            mGradient.setLocalMatrix(mMatrix);
            mBandPaint.setShader(mGradient);
            canvas.save();
            canvas.clipRect(bounds);
            canvas.drawRect(bounds, mBandPaint);
            canvas.restore();
            mBandPaint.setShader(null);
        }

        /** 按尺寸构建一次渐变(轴向 0..band,实际位置靠 localMatrix 平移) */
        private void ensureGradient(int w, int h) {
            if (mGradient != null && w == mGradientW && h == mGradientH) return;
            mGradientW = w;
            mGradientH = h;
            float diag = (float) Math.sqrt((double) w * w + (double) h * h);
            if (diag <= 0f) return;
            mUx = w / diag;
            mUy = h / diag;
            mBand = diag * 0.8f; // 渐变光带沿对角线的总长(含两侧柔边)
            mGradient = new LinearGradient(
                    0f, 0f, mBand * mUx, mBand * mUy,
                    new int[]{0x00000000, 0x2EFFFFFF, 0x59FFFFFF, 0x2EFFFFFF, 0x00000000},
                    new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP);
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
            mStartedAt = SystemClock.uptimeMillis();
            mAnimator.start();
        }

        @Override
        public void stop() {
            if (mAnimator.isStarted()) mAnimator.cancel();
            mProgress = 0f;
        }

        @Override
        public boolean isRunning() {
            return mAnimator.isRunning();
        }
    }
}
