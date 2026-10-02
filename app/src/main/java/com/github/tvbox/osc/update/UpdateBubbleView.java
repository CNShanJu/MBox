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
import android.graphics.Typeface;
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
 *   <li>下载中:进度环跟随真实进度;托盘横线静止,箭头自上而下走、接触托盘线时淡出消失(循环),不旋转;
 *       图标按 {@link #ICON_SIZE} 缩小并整体上移,圆盘底部排当前进度百分比({@link #drawPercentText});</li>
 *   <li>暂停:停止所有动画,中心暂停双竖线(同样上移,下方保留百分比),进度环停在当前;</li>
 *   <li>失败:中心红色错误叉,进度环变红;首次失败可短震(haptic);无循环;</li>
 *   <li>完成:进度环 100%,中心对勾,一次性 scale 1.0→1.15→1.0,点按触发安装;</li>
 *   <li>配色与「视频下载」同一套语义色({@link #colorOfState()}):进度环 / 中心图标 / 百分比文字三者同色;</li>
 *   <li><b>进度环紧贴中心盘面</b>:盘面半径取到环的内侧边缘({@code r - 环宽/2}),两者之间不留边距,
 *       盘面也不描边 —— 视觉上"环就是圆的描边",一个整体;
 *       曾经留 4dp 边距 + 盘面细描边,看起来像中间被挖空一段、环与圆各画各的。</li>
 * </ul>
 * 状态切换必须终止旧动画再启动新动画,避免叠加错乱。
 */
public class UpdateBubbleView extends View {

    public enum BubbleState { IDLE, DOWNLOADING, PAUSED, FAILED, COMPLETED }

    // 默认兜底色(资源缺失/主题解析失败时回落)
    private static final int DEF_BG = 0xFF4C6EF5;         // 盘面:品牌蓝兜底
    private static final int DEF_ACTIVE = 0xFF037AFF;     // 下载中兜底(与主题 download_active 同值)
    private static final int DEF_DONE = 0xFF08CA2C;       // 完成兜底(与主题 download_done 同值)

    private static final int PROGRESS_FAIL_COLOR = 0xFFF25555;// 失败红(兜底;正常取主题 swipe_red)

    // 中心图标组整体上移量(圆盘半径的比例):给底部的百分比文字腾位置
    private static final float ICON_GROUP_DY = -0.24f;
    // 图标尺寸(圆盘半径的比例):比原来的 1.05r 缩小约 18%,让上下"图标+百分比"排得下
    private static final float ICON_SIZE = 0.86f;
    // 百分比文字:中心位置与字号(圆盘半径的比例)
    private static final float PERCENT_CY = 0.54f;
    private static final float PERCENT_SIZE = 0.52f;
    /**
     * 中心图标/百分比相对<b>盘面边缘</b>再留出的内边距(dp)。
     * <p>这不是"环与盘面之间的缝"(那个已经取消了),而是"内容别贴到圆边上"的呼吸空间。
     * 之所以要单独一个量:去掉环盘缝隙后盘面半径变大,而图标与百分比的尺寸都以"它们拿到的半径"为基准,
     * 若直接跟着盘面走,内部内容会一起放大 ~26% —— 那是用户没要求的改动。
     * 这里按盘面减去本内边距作为<b>内容基准半径</b>,内容尺寸与改版前保持一致。
     */
    private static final float CONTENT_INSET_DP = 4f;

    // 主题兼容色(首页「直播」悬浮钮同源:bg_float 盘面 / 进度轨道取文字主色 20% 透明度)
    private int mDiscColor;
    private int mTrackColor;
    /** 下载中/暂停:与「视频下载」同一语义色(download_active) */
    private int mActiveColor;
    /** 完成:download_done */
    private int mDoneColor;
    /** 失败:swipe_red */
    private int mFailColor;
    /** 当前状态色(状态切换时由 {@link #applyStateColor()} 刷新):进度环 / 中心图标 / 百分比文字共用同一个色 */
    private int mIconColor;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 气泡内百分比文字专用(独立一份,避免改 typeface/对齐方式污染图形用的 mPaint) */
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    /** 缓存密度(见 onDraw 的说明):每帧取 getResources() 会连带跑一次换肤配置对齐 */
    private float mDensity = 1f;

    public UpdateBubbleView(@NonNull Context context) {
        super(context);
        mDensity = getResources().getDisplayMetrics().density;
        mRingWidthDp = 3 * mDensity; // 圆环粗细 3dp
        refreshThemeColors(context);
        mArrowIcon = loadIcon(context, R.drawable.ic_download_arrow);
        // 完成态用**纯勾**(LiteIcon「勾.svg」,ic_check_24):以前用 ic_check_circle(圈里一个勾),
        // 放进气泡自己的圆盘里就是"圈套圈",看着乱。颜色照旧按状态 tint(download_done 绿)。
        mCheckIcon = loadIcon(context, R.drawable.ic_check_24);
        applyStateColor();
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    /**
     * 按当前主题重取所有颜色(构造时 + 每次挂上窗口/重新显示时都调一次)。
     *
     * <p><b>为什么必须重取,而不是只在构造时取一次</b>(2026-10-01 用户口径:"首页的直播气泡球和
     * 更新气泡球的背景色没有跟着 bg_surface 走"):这些球是
     * {@code ContextCompat.getColor(context, R.color.bg_float)} 在**构造那一刻**把颜色存进字段、
     * 之后每帧自绘用的都是缓存值。而换主题走的是"重载主页"(进程不重启)或视图被复用,
     * 构造时机只要早于主题快照刷新,缓存里就永远是编译期那份色 —— 之后换多少次主题都不会变。
     * 颜色是"每帧画的东西",就不该在构造时定死。
     */
    void refreshThemeColors(Context context) {
        mDiscColor = themeColor(context, R.color.bg_float, DEF_BG);
        // 与管理面板进度条使用同一中性轨道；开关关闭色会带 success 的半透明绿色。
        mTrackColor = UpdateProgressColors.trackColor(context);
        // 状态色与「视频下载」同源:下载中/暂停=download_active、完成=download_done、失败=swipe_red
        mActiveColor = themeColor(context, R.color.download_active, DEF_ACTIVE);
        mDoneColor = themeColor(context, R.color.download_done, DEF_DONE);
        mFailColor = themeColor(context, R.color.swipe_red, PROGRESS_FAIL_COLOR);
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
            return UpdateProgressColors.themeColor(context, resId);
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
            // 状态色只在切换时重算:进度回调很频繁,不该每帧重设 tint
            applyStateColor();
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

    /**
     * 状态 → 语义色(与「视频下载」同一套):下载中/暂停={@code download_active}、
     * 完成={@code download_done}、失败={@code swipe_red}。
     * <p>暂停沿用下载中的色而不是下载页那种"次要灰":气泡里进度环与轨道是灰度相近的两条细弧,
     * 灰环压在灰轨道上分不出进度;暂停半途这个状态已经由中心的暂停双竖线表达。
     * <p>该色同时驱动进度环、中心图标与百分比文字 —— 三者混色正是之前"看着割裂"的原因。
     */
    private int colorOfState(BubbleState state) {
        switch (state) {
            case COMPLETED:
                return mDoneColor;
            case FAILED:
                return mFailColor;
            case DOWNLOADING:
            case PAUSED:
            case IDLE:
            default:
                return mActiveColor;
        }
    }

    /** 把当前状态色落到 mIconColor 并同步两个矢量图标的 tint(只在状态变化时调,不每帧重建色滤) */
    private void applyStateColor() {
        mIconColor = colorOfState(mState);
        if (mArrowIcon != null) mArrowIcon.setTint(mIconColor);
        if (mCheckIcon != null) mCheckIcon.setTint(mIconColor);
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
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // attach 时刷新一次密度(配置变化后重新 attach 的场合);绘制路径上不再取 getResources()
        try {
            mDensity = getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
        // **同时按当前主题重取颜色**:这些颜色原来是构造时定死的,换主题(重载主页、进程不重启)
        // 或视图被复用后就不会再变 —— 用户口径"直播气泡球 / 更新气泡球的底色没跟着主题走"。
        try {
            refreshThemeColors(getContext());
            applyStateColor();
            invalidate();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - mRingWidthDp;
        // 用缓存密度:原来这里每帧都 getResources() —— 而 BaseActivity 覆写了 getResources()(换肤出口),
        // 每次调用都要 syncFrom 对齐 Configuration/DisplayMetrics。下载中气泡是持续重绘的,
        // 这一行等于"每帧做一次配置对齐"。密度只在配置变化时变(那时 Activity 会重建/重新 attach)。
        float density = mDensity;

        // 外圈进度环:先画底环(与弹窗进度条同一条轨道色)
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeWidth(mRingWidthDp);
        mTrackPaint.setColor(mTrackColor);
        mArcRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(mArcRect, -90f, 360f, false, mTrackPaint);

        // 进度环:与中心图标/百分比同色(状态一起换,不再各用各的色)
        if (mProgress > 0f) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(mRingWidthDp);
            mPaint.setColor(mIconColor);
            canvas.drawArc(mArcRect, -90f, mProgress * 360f, false, mPaint);
        }

        // 中心盘面:半径取到进度环的<b>内侧边缘</b>(r - 环宽/2),环紧贴盘面。
        // 历史:这里早先是 2dp、后来被刻意加大到 4dp 边距,当时的理由是"环与盘面挨得太近看着贴在一起";
        // 实际观感相反 —— 中间空出一圈底色,像被挖空了一段,环和圆变成两个不相干的图形。
        // 现在改为不留边距(环就是圆的描边),这是有意的反转,不要再把边距加回来。
        float iconR = r - mRingWidthDp / 2f;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mDiscColor);
        canvas.drawCircle(cx, cy, iconR, mPaint);
        // 盘面不再描边:那条细线会压在紧贴的进度环内侧,在环与圆之间多出一条独立线条,
        // 正是"割裂感"的另一半来源(与上面去掉的 4dp 边距同源)。
        // 盘面边界由进度底环自身表达,不需要再画一圈。

        // 中心图标(带状态缩放)。基准半径用"盘面 - 内容内边距",与改版前的尺寸一致:
        // 盘面这一轮变大了,如果内容跟着等比放大,等于顺手改了用户没提的东西(见 CONTENT_INSET_DP)。
        float contentR = iconR - CONTENT_INSET_DP * density;
        canvas.save();
        canvas.scale(mBounceScale, mBounceScale, cx, cy);
        drawCenterIcon(canvas, cx, cy, contentR);
        canvas.restore();
    }

    private void drawCenterIcon(Canvas canvas, float cx, float cy, float r) {
        float icon = r * 1.05f; // 单图标态(完成/失败)的目标尺寸
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
                // 暂停双竖线(上移,下方留出百分比)
                drawPauseBars(canvas, cx, cy + r * ICON_GROUP_DY, r);
                drawPercentText(canvas, cx, cy, r);
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
                // 图标下方排当前进度百分比(0%~99%;完成态显示对勾,不再排数字)
                drawPercentText(canvas, cx, cy, r);
                break;
            }
        }
    }

    /** 暂停双竖线(主题高亮色,尺寸与其它状态图标一致,不喧宾);组中心由调用方给出,便于上移让位 */
    private void drawPauseBars(Canvas canvas, float cx, float cy, float r) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mIconColor);
        float barW = r * 0.14f;
        float barH = r * 0.32f;
        float gap = r * 0.12f;
        canvas.drawRoundRect(cx - gap - barW, cy - barH, cx - gap, cy + barH, barW, barW, mPaint);
        canvas.drawRoundRect(cx + gap, cy - barH, cx + gap + barW, cy + barH, barW, barW, mPaint);
    }

    /**
     * 气泡底部的进度百分比(下载中/暂停):画在圆盘里、图标组下方。
     * <p>圆盘是个圆,越靠下可用宽度越窄(弦长),所以文字超出该高度可用宽度时等比缩小
     * (下限 {@code 0.34r}),避免"100%"这类窄处被圆边切掉。
     */
    private void drawPercentText(Canvas canvas, float cx, float cy, float r) {
        String text = Math.round(mProgress * 100f) + "%";
        float centerY = cy + r * PERCENT_CY;
        mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setColor(mIconColor);

        float size = r * PERCENT_SIZE;
        mTextPaint.setTextSize(size);
        float d = (centerY - cy) / r;
        // 该高度处的可用宽度(弦长),留 8% 余量
        float maxW = 2f * r * (float) Math.sqrt(Math.max(0.04f, 1f - d * d)) * 0.92f;
        float w = mTextPaint.measureText(text);
        if (w > maxW && w > 0f) {
            mTextPaint.setTextSize(Math.max(r * 0.34f, size * maxW / w));
        }

        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        canvas.drawText(text, cx, centerY - (fm.ascent + fm.descent) / 2f, mTextPaint);
    }

    /** 下载图标动画:托盘横线静止,箭头自上而下走,接触托盘线时淡出消失,消失后自动新一轮 */
    private void drawDownloading(Canvas canvas, float cx, float cy, float r) {
        // 图标比原尺寸缩小(0.86r),图标组整体上移,底部空出来给百分比文字
        float iconSize = r * ICON_SIZE;
        float baseCy = cy + r * ICON_GROUP_DY;
        // 整体下移一点,避免底部留白(视觉重心稍偏下)
        float down = iconSize * 0.08f;
        // 托盘横线(静止,主题高亮色)
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(iconSize * 0.10f);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(mIconColor);
        float trayHalf = iconSize * 0.30f;
        float trayY = baseCy + down + iconSize * 0.22f;
        canvas.drawLine(cx - trayHalf, trayY, cx + trayHalf, trayY, mPaint);

        // 下移箭头:上部起始 → 落到托盘线并淡出
        if (mArrowIcon != null) {
            float travel = iconSize * 0.55f;          // 下移距离
            float dy = -travel * (1f - mAnimP);       // p=0 在上方,p=1 落到托盘
            int alpha = Math.round(255 * mAnimAlpha);
            mArrowIcon.setAlpha(alpha);
            drawIconBound(canvas, mArrowIcon, cx, baseCy + down + dy, iconSize);
            mArrowIcon.setAlpha(255);
        }
    }

    private void drawIconBound(Canvas canvas, Drawable d, float cx, float cy, float size) {
        float half = size / 2f;
        d.setBounds((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
        d.draw(canvas);
    }
}
