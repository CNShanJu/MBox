package com.github.tvbox.osc.ui.kit;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>全站二级页头部的唯一实现</b>:标题 + 返回 + 右侧动作,三者的几何/配色都出在这里,
 * 页面布局只声明内容(标题文案、右侧放什么),不再自己拼标题栏 ——
 * 以前每个页面各写一套(标题 padding、关闭/返回图标尺寸、右侧边距各不同),
 * 于是"返回图标比标题高一点""关闭键有的 30dp 有的 24dp"这类偏差反复出现。
 *
 * <h3>统一的东西(改这里 = 全站一起变)</h3>
 * <ul>
 *   <li>背景 = 主题悬浮面 {@code bg_surface};标题 = {@code text_main} + 16sp + 单行省略,<b>水平垂直都居中</b>;</li>
 *   <li>返回键:自绘 {@code ic_seek_left},{@link #BACK_ICON_DP} 图标 + {@link #BACK_TOUCH_DP} 触区,
 *       与标题同轴(不用库的 compound drawable —— 它的垂直位置按"文本行盒"算,永远对不齐标题);</li>
 *   <li>右侧动作:布局里的子 View 会被自动收进一个右对齐、<b>垂直居中</b>的容器,间距统一为
 *       {@link #RIGHT_GAP_DP};页面不再写 {@code layout_gravity} / {@code layout_marginEnd}
 *       (以前有人用 marginEnd=56dp 硬凑间距,就是这类偏差的来源);</li>
 *   <li>标题可用宽度按两侧实际占位自动收窄,长标题不会压到动作上;</li>
 *   <li><b>高度</b>:布局里写 {@code wrap_content} 即可 —— 内容区固定 {@link #CONTENT_HEIGHT_DP},
 *       状态栏高度由 ImmersionBar 以 {@code paddingTop} 叠加,所以总高 = 内容区 + 状态栏。
 *       页面<b>不要再写死高度</b>:写 45dp/60dp 时那个高度是"含状态栏"的,
 *       状态栏一高,留给标题的就只剩十几个 dp,文字直接被切
 *       (用户口径:"顶部标题栏怎么被压缩了,文字都显示不全")。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>
 * &lt;com.github.tvbox.osc.ui.kit.AppTitleBar
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:title="下载管理"&gt;
 *     &lt;!-- 直接写右侧动作:图标 / 文字胶囊都行 --&gt;
 *     &lt;ImageView android:id="@+id/btn_clear" ... /&gt;
 * &lt;/com.github.tvbox.osc.ui.kit.AppTitleBar&gt;
 * </pre>
 * 代码里:{@link #setTitle(CharSequence)} / {@link #setRightIcon(int, float, View.OnClickListener)}
 * / {@link #setOnBackClickListener(View.OnClickListener)}(默认 {@code finish()})。
 */
public class AppTitleBar extends FrameLayout {

    /**
     * 内容区高度(dp):<b>不含状态栏</b>(状态栏由 {@code BaseActivity.initStatusBar()} 里的
     * ImmersionBar 以 {@code paddingTop} 叠加)。布局里写 {@code wrap_content},总高 = 本值 + 状态栏。
     *
     * <p>44dp 是**观感**定的,不是"放得下"定的:16sp 标题行高约 19dp,32dp 内容区放得下但看着偏矮 ——
     * 上方还有 24~34dp 的状态栏,标题带整体显得挤、标题下沿离内容太近(用户口径:
     * "算出来的高度下边距太少了,看着这个标题栏有点矮,不自然,因为上方存在系统状态栏,不协调")。
     * 44dp 与全站 44/45dp 的行高口径一致,常见机型整条 68~78dp。
     */
    public static final int CONTENT_HEIGHT_DP = 44;
    /** 标题字号(sp) */
    private static final int TITLE_SIZE_SP = 16;
    /** 返回键触区宽度(dp):高度 = 内容区高(布局值 MATCH_PARENT),即 40dp 宽 × 整条栏高 */
    private static final int BACK_TOUCH_DP = 40;
    /** 返回图标实际画多大(dp):12dp 在标题栏里显得太小(用户口径),调到 16dp;不随栏高变化 */
    private static final int BACK_ICON_DP = 16;
    /** 右侧动作之间的间隔(dp) */
    private static final int RIGHT_GAP_DP = 10;
    /** 标题与两侧动作之间至少留的缝(dp) */
    private static final int TITLE_SIDE_DP = 8;
    /** 标题上下至少留的余量(dp):字号被系统放大时内容区跟着长,不会把文字压扁 */
    private static final int TITLE_MARGIN_DP = 6;

    /** 返回键触区容器(40dp 宽、居中;里面是 {@link #BACK_ICON_DP} 的图标) */
    private final FrameLayout backView;
    private final TextView titleView;
    private final LinearLayout rightBox;

    /** 返回键行为(不设 = finish 当前页);BackgroundSettingActivity 那种"返回=确认"的页面在这里接管 */
    private View.OnClickListener backClickListener;

    public AppTitleBar(Context context) {
        this(context, null);
    }

    public AppTitleBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        int textColor = ContextCompat.getColor(context, R.color.text_main);
        // 悬浮面:与底部导航栏(bg_surface)同色同透明度,不另调色
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg_surface));

        // 返回键:自绘图标(见类注释:库的 compound drawable 对不齐标题)。
        // 外层 = 触区容器(40dp 宽,高度撑满内容区),内层 = 固定 18dp 的图标 ——
        // 图标尺寸**不随标题栏高度变化**(以前用 padding 挤出来,标题栏一矮图标就跟着缩)。
        backView = new FrameLayout(context);
        ImageView backIcon = new ImageView(context);
        Drawable back = ContextCompat.getDrawable(context, R.drawable.ic_seek_left);
        if (back != null) {
            back.setTint(textColor);
            backIcon.setImageDrawable(back);
        }
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(BACK_ICON_DP), dp(BACK_ICON_DP));
        iconLp.gravity = Gravity.CENTER;
        backView.addView(backIcon, iconLp);
        LayoutParams backLp = new LayoutParams(dp(BACK_TOUCH_DP), LayoutParams.MATCH_PARENT);
        backLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        backView.setLayoutParams(backLp);
        backView.setOnClickListener(v -> performBack());
        addView(backView);

        // 标题:居中 + 单行省略(宽度由 onMeasure 按两侧占位收窄)
        titleView = new TextView(context);
        titleView.setTextSize(TITLE_SIZE_SP);
        titleView.setTextColor(textColor);
        titleView.setSingleLine(true);
        // 关掉字体自带上下 padding:16sp 的行盒从 ~21.5dp 收到 ~19dp,
        // 标题栏不高时也不会被切掉半行(用户口径:"文字都显示不全")
        titleView.setIncludeFontPadding(false);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        LayoutParams titleLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.CENTER;
        titleView.setLayoutParams(titleLp);
        addView(titleView);

        // 右侧动作容器:布局里的子 View 会被收进来(onFinishInflate)
        rightBox = new LinearLayout(context);
        rightBox.setOrientation(LinearLayout.HORIZONTAL);
        rightBox.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rightLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        rightLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        rightBox.setLayoutParams(rightLp);
        addView(rightBox);

        boolean bold = false;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AppTitleBar);
            CharSequence text = a.getText(R.styleable.AppTitleBar_title);
            bold = a.getBoolean(R.styleable.AppTitleBar_titleBold, false);
            boolean showBack = a.getBoolean(R.styleable.AppTitleBar_showBack, true);
            a.recycle();
            if (text != null) titleView.setText(text);
            backView.setVisibility(showBack ? VISIBLE : GONE);
        }
        setTitleBold(bold);
    }

    /**
     * 布局里的子 View = <b>右侧动作</b>:统一搬进右容器并垂直居中,
     * 只规范化对齐与间距,<b>保留各自的尺寸</b>(20dp 图标、铺满高度的文字胶囊都按原样)。
     */
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        List<View> actions = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == backView || child == titleView || child == rightBox) continue;
            actions.add(child);
        }
        for (View child : actions) {
            removeView(child);
        }
        for (int i = 0; i < actions.size(); i++) {
            View child = actions.get(i);
            LayoutParams old = (LayoutParams) child.getLayoutParams();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    old == null ? LayoutParams.WRAP_CONTENT : old.width,
                    old == null ? LayoutParams.WRAP_CONTENT : old.height);
            lp.gravity = Gravity.CENTER_VERTICAL;
            if (i > 0) lp.leftMargin = dp(RIGHT_GAP_DP);
            rightBox.addView(child, lp);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 高度 = 内容区 + 上下 padding。padding 里的上面那份是 ImmersionBar 加的状态栏高度
        // (BaseActivity.initStatusBar():ImmersionBar.titleBar(view) 最终调 View.setPadding(0, 状态栏, 0, 0)),
        // 所以**不能用布局里的绝对高度当总高** —— 45dp 的栏在 28dp 状态栏的机器上只剩 17dp 内容区,
        // 16sp 标题放不下就被切(用户口径:"顶部标题栏怎么被压缩了,文字都显示不全")。
        // 这里反过来算:内容区 ≥ 标题行高 + 余量,且 ≥ CONTENT_HEIGHT_DP;布局写 wrap_content 即可。
        int width = MeasureSpec.getSize(widthMeasureSpec);
        measureChild(titleView,
                MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int content = Math.max(dp(CONTENT_HEIGHT_DP), titleView.getMeasuredHeight() + dp(TITLE_MARGIN_DP));
        int total = getPaddingTop() + getPaddingBottom() + content;
        // 高度**自己定死**(不能交给 wrap_content 自然量):内容区的头部动作有 MATCH_PARENT 的子 View,
        // 在 wrap_content(AT_MOST)下会被量成"整屏那么高";页面明确给了更大的固定高度才随它。
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                && MeasureSpec.getSize(heightMeasureSpec) > total) {
            total = MeasureSpec.getSize(heightMeasureSpec);
        }
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY);
        // 再量两侧动作,把标题的可用宽度收窄到"总宽 - 两侧占位 - 留缝":
        // 标题居中显示,长标题自动省略,不会压到返回键/右侧动作上
        measureChild(backView, widthMeasureSpec, heightMeasureSpec);
        measureChild(rightBox, widthMeasureSpec, heightMeasureSpec);
        // 布局内直接声明的小图标没有 40dp 触区；按最右图标的实际中心补齐尾部留白，
        // 使它到屏幕边缘的距离与左侧返回图标一致。setRightIcon 的 40dp 触区无需补白。
        int rightIconDistance = rightmostIconCenterDistance(rightBox);
        int endPadding = rightIconDistance < 0 ? 0 : Math.max(0,
                rightBox.getPaddingEnd() + dp(BACK_TOUCH_DP) / 2 - rightIconDistance);
        if (rightBox.getPaddingEnd() != endPadding) {
            rightBox.setPaddingRelative(rightBox.getPaddingStart(), rightBox.getPaddingTop(),
                    endPadding, rightBox.getPaddingBottom());
            measureChild(rightBox, widthMeasureSpec, heightMeasureSpec);
        }
        int side = Math.max(
                backView.getVisibility() == VISIBLE ? backView.getMeasuredWidth() : 0,
                rightBox.getMeasuredWidth()) + dp(TITLE_SIDE_DP);
        if (width > 0) {
            titleView.setMaxWidth(Math.max(0, width - side * 2));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** 最右侧可见图标的中心距容器尾端；文字动作返回 -1，不改它的排版。 */
    private int rightmostIconCenterDistance(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int distance;
            if (child instanceof ImageView) {
                distance = (child.getMeasuredWidth() - child.getPaddingStart()
                        + child.getPaddingEnd()) / 2;
            } else if (child instanceof ViewGroup) {
                distance = rightmostIconCenterDistance((ViewGroup) child);
            } else {
                return -1;
            }
            if (distance < 0) return -1;
            ViewGroup.LayoutParams params = child.getLayoutParams();
            int margin = params instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) params).getMarginEnd() : 0;
            return group.getPaddingEnd() + margin + distance;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 设置标题(替代直接操作 TextView:字号/颜色/居中都在这里统一) */
    public void setTitle(CharSequence title) {
        titleView.setText(title == null ? "" : title);
    }

    public CharSequence getTitle() {
        return titleView.getText();
    }

    public void setTitleBold(boolean bold) {
        titleView.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** 是否显示返回键(默认显示) */
    public void setShowBack(boolean show) {
        backView.setVisibility(show ? VISIBLE : GONE);
        requestLayout();
    }

    /**
     * 接管返回键行为。<b>默认 = {@code finish()}</b>(与全站其它二级页一致);
     * 需要"返回=确认"这类语义的页面(如背景图设置页)在这里调 {@code onBackPressed()}。
     */
    public void setOnBackClickListener(View.OnClickListener listener) {
        this.backClickListener = listener;
    }

    /**
     * 右侧放一个主题色图标动作(常用的那种):{@link #BACK_TOUCH_DP} 触区里画 {@code sizeDp} 图标,
     * 与返回键同一套几何,所以左右两侧观感一致。
     *
     * @return 生成的 ImageView(需要动态换图/改可用态时用)
     */
    public ImageView setRightIcon(int resId, float sizeDp, View.OnClickListener listener) {
        ImageView iv = new ImageView(getContext());
        Drawable d = ContextCompat.getDrawable(getContext(), resId);
        if (d != null) {
            d.setTint(ContextCompat.getColor(getContext(), R.color.text_main));
            iv.setImageDrawable(d);
        }
        int touch = dp(BACK_TOUCH_DP);
        int pad = Math.max(0, Math.round((touch - dp(sizeDp)) / 2f));
        iv.setPadding(pad, pad, pad, pad);
        if (listener != null) iv.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(touch, touch);
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (rightBox.getChildCount() > 0) lp.leftMargin = dp(RIGHT_GAP_DP);
        rightBox.addView(iv, lp);
        return iv;
    }

    /** 当前右侧动作容器(需要更复杂的排布时用;一般不需要) */
    public LinearLayout getRightBox() {
        return rightBox;
    }

    private void performBack() {
        if (backClickListener != null) {
            backClickListener.onClick(backView);
            return;
        }
        Context context = getContext();
        if (context instanceof Activity) {
            ((Activity) context).finish();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
