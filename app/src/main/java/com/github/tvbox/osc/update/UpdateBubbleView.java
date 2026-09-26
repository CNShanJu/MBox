package com.github.tvbox.osc.update;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 更新悬浮气泡:自绘圆底 + 外圈环形进度 + 状态中心图标,并驱动对应动画。
 * 状态(仅下载后显示):下载中 / 暂停 / 失败 / 完成。
 * <ul>
 *   <li>下载中:进度环跟随真实进度;托盘横线静止,箭头自上而下走、接触托盘线时淡出消失(循环),不旋转;</li>
 *   <li>暂停:停止所有动画,中心白色暂停双竖线,进度环停在当前;</li>
 *   <li>失败:中心红色错误叉,进度环变红;首次失败可短震(haptic);无循环;</li>
 *   <li>完成:进度环 100%(保持主题色),中心对勾(与图标同色、稍放大),一次性 scale 1.0→1.15→1.0,点按触发安装。</li>
 * </ul>
 * 状态切换必须终止旧动画再启动新动画,避免叠加错乱。
 */
public class UpdateBubbleView extends View {

    public enum BubbleState { IDLE, DOWNLOADING, PAUSED, FAILED, COMPLETED }

    // 默认兜底色(资源缺失/主题解析失败时回落)
    private static final int DEF_BG = 0xFF4C6EF5;         // 盘面:品牌蓝兜底
    private static final int DEF_STROKE = 0x33000000;     // 盘面描边兜底(半透明黑)
    private static final int DEF_TRACK = 0xFFE6E8F0;      // 进度底环兜底
    private static final int DEF_PROGRESS = 0xFF4C6EF5;   // 下载中进度环兜底
    private static final int DEF_ICON = 0xFFFFFFFF;       // 中心图标兜底

    private static final int PROGRESS_FAIL_COLOR = 0xFFF25555;// 失败红(兜底;正常取主题 swipe_red)

    // 主题兼容色(与首页「直播」悬浮钮同源:bg_float_fab 盘面 / btn_stroke 描边 / 文本与状态色取主题)
    private final int mDiscColor;
    private final int mDiscStrokeColor;
    private final int mTrackColor;
    private final int mProgressColor;
    private final int mIconColor;
    private final int mFailColor;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArcRect = new RectF();

    private final Drawable mArrowIcon;      // 下载箭头(仅箭头,不含托盘)
    private final Drawable mCheckIcon;      // 对勾(完成态,绿色)

    private BubbleState mState = BubbleState.IDLE;
    private float mProgress = 0f;           // 0..1
    private float mRingWidthDp;

    // 动画
    private ValueAnimator mDownloadAnim;    // 箭头下落 0..1
    private ValueAnimator mBounceAnim;      // 完成缩放
    private float mAnimP = 0f;              // 箭头下落相位 0..1
    private float mAnimAlpha = 1f;          // 箭头透明度(接触托盘时淡出)
    private float mBounceScale = 1f;
    private long mDownMs;                   // 长按/点击持续时间,用于区分点击
    private boolean mFailHapticDone = false;

    public UpdateBubbleView(@NonNull Context context) {
        super(context);
        mRingWidthDp = 3 * getResources().getDisplayMetrics().density; // 圆环细 1dp
        mDiscColor = themeColor(context, R.color.bg_float_fab, DEF_BG);
        mDiscStrokeColor = themeColor(context, R.color.fab_stroke, DEF_STROKE);
        mTrackColor = themeColor(context, R.color.fab_stroke, DEF_TRACK);
        mProgressColor = themeColor(context, R.color.text_highlight, DEF_PROGRESS);
        mIconColor = themeColor(context, R.color.text_highlight, DEF_ICON);
        // 失败色与全局状态色同源(swipe_red),不再另写一套红
        mFailColor = themeColor(context, R.color.swipe_red, PROGRESS_FAIL_COLOR);
        mArrowIcon = loadIcon(context, R.drawable.ic_download_arrow);
        mCheckIcon = loadIcon(context, R.drawable.ic_check_circle);
        if (mArrowIcon != null) mArrowIcon.setTint(mIconColor);
        if (mCheckIcon != null) mCheckIcon.setTint(mIconColor); // 完成态对勾与其它图标同色(主题色)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    /**
     * XML inflate 构造(layout float_update_indicator 以全限定类名引用本视图)。
     * LayoutInflater 只能经 (Context, AttributeSet) 构造创建自定义标签视图,
     * 缺此构造会抛 InflateException "Error inflating class ...";
     * 本视图全自绘、不读 attrs,直接委托单参构造即可。
     */
    public UpdateBubbleView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context);
    }

    /** 带主题 defStyleAttr 的构造,兼容 inflate/样式框架调用;同样忽略 attrs。 */
    public UpdateBubbleView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context);
    }

    /** 取主题资源色;缺失/异常回落兜底色(保证不崩,且浅色/深色主题自适应) */
    private int themeColor(Context context, int resId, int fallback) {
        try {
            return ContextCompat.getColor(context, resId);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 图标加载失败不致命(圆环/圆底仍可绘制),置 null 并记录 */
    private Drawable loadIcon(Context context, int resId) {
        try {
            return ContextCompat.getDrawable(context, resId);
        } catch (Throwable t) {
            com.github.tvbox.osc.util.LOG.e("UpdateBubble",
                    "load icon res 0x" + Integer.toHexString(resId) + " failed: " + t);
            return null;
        }
    }

    /** 更新状态与进度(0..1);状态切换会终止旧动画再启动新动画 */
    public void setState(BubbleState state, float progress) {
        if (mState != state) {
            stopAll();
            mState = state;
            if (state == BubbleState.FAILED) {
                if (!mFailHapticDone) {
                    mFailHapticDone = true;
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            } else {
                mFailHapticDone = false;
            }
        }
        mProgress = Math.max(0f, Math.min(1f, progress));
        if (mState == BubbleState.DOWNLOADING) {
            startDownloadAnim();
        } else if (mState == BubbleState.COMPLETED) {
            mProgress = 1f;
            startBounceOnce();
        }
        invalidate();
    }

    private void stopAll() {
        if (mDownloadAnim != null) { mDownloadAnim.cancel(); mDownloadAnim = null; }
        if (mBounceAnim != null) { mBounceAnim.cancel(); mBounceAnim = null; }
        mAnimP = 0f;
        mAnimAlpha = 1f;
        mBounceScale = 1f;
    }

    /** 下载中:托盘横线静止,箭头从上往下走,接触托盘线时淡出消失,消失完自动开始新一轮(循环) */
    private void startDownloadAnim() {
        if (mDownloadAnim != null && mDownloadAnim.isRunning()) return;
        mDownloadAnim = ValueAnimator.ofFloat(0f, 1f);
        mDownloadAnim.setDuration(850);
        mDownloadAnim.setRepeatCount(ValueAnimator.INFINITE);
        mDownloadAnim.setInterpolator(new LinearInterpolator());
        mDownloadAnim.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            mAnimP = p;
            // 前 60% 全程可见;后 40% 随接近托盘而淡出(接触即消失)
            mAnimAlpha = p <= 0.6f ? 1f : Math.max(0f, 1f - (p - 0.6f) / 0.4f);
            invalidate();
        });
        mDownloadAnim.start();
    }

    /** 完成:一次性 scale 1.0→1.15→1.0 */
    private void startBounceOnce() {
        if (mBounceAnim != null && mBounceAnim.isRunning()) return;
        mBounceAnim = ValueAnimator.ofFloat(1f, 1.15f, 1f);
        mBounceAnim.setDuration(440);
        mBounceAnim.setInterpolator(new DecelerateInterpolator());
        mBounceAnim.addUpdateListener(a -> {
            mBounceScale = (float) a.getAnimatedValue();
            invalidate();
        });
        mBounceAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { mBounceScale = 1f; invalidate(); }
        });
        mBounceAnim.start();
    }

    /** 页面不可见时暂停全部动画(由宿主在 detach 时调用) */
    public void pauseAnimations() {
        stopAll();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - mRingWidthDp;
        float density = getResources().getDisplayMetrics().density;

        // 外圈进度环:先画底环(主题弱化色)
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeWidth(mRingWidthDp);
        mTrackPaint.setColor(mTrackColor);
        mArcRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(mArcRect, -90f, 360f, false, mTrackPaint);

        // 进度环:按状态取色(完成态保持主题色,不变绿——只有中心对勾变绿)
        if (mProgress > 0f) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(mRingWidthDp);
            mPaint.setColor(mState == BubbleState.FAILED ? mFailColor : mProgressColor);
            canvas.drawArc(mArcRect, -90f, mProgress * 360f, false, mPaint);
        }

        // 中心盘面(主题兼容色,圆环与盘面间距 +1dp;参考首页「直播」悬浮钮)
        float iconR = r - mRingWidthDp / 2f - (2 * density);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mDiscColor);
        canvas.drawCircle(cx, cy, iconR, mPaint);
        // 盘面描边(主题化细边框,薄而清晰)
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(density);
        mPaint.setColor(mDiscStrokeColor);
        canvas.drawCircle(cx, cy, iconR, mPaint);

        // 中心图标(带状态缩放)
        canvas.save();
        canvas.scale(mBounceScale, mBounceScale, cx, cy);
        drawCenterIcon(canvas, cx, cy, iconR);
        canvas.restore();
    }

    private void drawCenterIcon(Canvas canvas, float cx, float cy, float r) {
        float icon = r * 1.05f; // 图标目标尺寸
        switch (mState) {
            case FAILED: {
                // 错误叉号(与进度环同红色)
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(r * 0.16f);
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setColor(mFailColor);
                float l = r * 0.30f;
                canvas.drawLine(cx - l, cy - l, cx + l, cy + l, mPaint);
                canvas.drawLine(cx + l, cy - l, cx - l, cy + l, mPaint);
                break;
            }
            case PAUSED: {
                // 暂停双竖线(主题高亮色,尺寸与其它状态图标一致,不喧宾)
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(mIconColor);
                float barW = r * 0.14f;
                float barH = r * 0.32f;
                float gap = r * 0.12f;
                canvas.drawRoundRect(cx - gap - barW, cy - barH, cx - gap, cy + barH, barW, barW, mPaint);
                canvas.drawRoundRect(cx + gap, cy - barH, cx + gap + barW, cy + barH, barW, barW, mPaint);
                break;
            }
            case COMPLETED: {
                if (mCheckIcon != null) {
                    drawIconBound(canvas, mCheckIcon, cx, cy, icon * 1.08f); // 完成对勾稍放大
                }
                break;
            }
            case DOWNLOADING:
            case IDLE:
            default: {
                drawDownloading(canvas, cx, cy, r);
                break;
            }
        }
    }

    /** 下载图标动画:托盘横线静止,箭头自上而下走,接触托盘线时淡出消失,消失后自动新一轮 */
    private void drawDownloading(Canvas canvas, float cx, float cy, float r) {
        float iconSize = r * 1.05f;
        // 整体下移一点,避免底部留白(视觉重心稍偏下)
        float down = iconSize * 0.08f;
        // 托盘横线(静止,主题高亮色)
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(iconSize * 0.10f);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(mIconColor);
        float trayHalf = iconSize * 0.30f;
        float trayY = cy + down + iconSize * 0.22f;
        canvas.drawLine(cx - trayHalf, trayY, cx + trayHalf, trayY, mPaint);

        // 下移箭头:上部起始 → 落到托盘线并淡出
        if (mArrowIcon != null) {
            float travel = iconSize * 0.55f;          // 下移距离
            float dy = -travel * (1f - mAnimP);       // p=0 在上方,p=1 落到托盘
            int alpha = Math.round(255 * mAnimAlpha);
            mArrowIcon.setAlpha(alpha);
            drawIconBound(canvas, mArrowIcon, cx, cy + down + dy, iconSize);
            mArrowIcon.setAlpha(255);
        }
    }

    private void drawIconBound(Canvas canvas, Drawable d, float cx, float cy, float size) {
        float half = size / 2f;
        d.setBounds((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
        d.draw(canvas);
    }
}
