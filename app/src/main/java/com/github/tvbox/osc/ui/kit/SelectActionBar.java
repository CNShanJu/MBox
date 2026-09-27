package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 长按多选的操作栏(全选 / 删除 / 取消全选 这类动作键):**列表页与下载页共用同一个组件**,样式只此一处。
 *
 * <p>为什么要抽出来:以前每个页面各写一条 LinearLayout + 三个 TextView,颜色与背景各写各的 ——
 * 本地视频那条写死 {@code android:background="@color/white"},暗色主题下就是一条白杠,
 * 而且完全不吃主题里的面透明度;下载页那条又没有背景。外观一旦不统一,改一处永远改不齐。
 *
 * <p>两件事在这里定死:
 * <ul>
 *   <li><b>背景</b>=主题悬浮面 {@code @color/bg_surface} —— 颜色与透明度都来自主题文件
 *       ({@code bg_surface} + {@code bg_float_alpha}),与底栏/标题栏/卡片同一个面;</li>
 *   <li><b>键色</b>两档:{@link Kind#NORMAL} 普通键用 {@code text_accent}(即原来的 colorPrimary)、
 *       {@link Kind#DANGER} 危险键(删除/清空)用 {@code text_danger};不可用时统一 {@code text_disable}。
 *       注意"红底 + 红底上的字"是弹窗里的确认键那一档({@code BtnDanger}),不在这条裸文字栏上。</li>
 * </ul>
 *
 * <p>用法:调用方用 {@link #addAction} 现加动作键(等宽排开、按加入顺序从左到右),拿回 TextView 后
 * 用 {@link #setActionEnabled} 改可用态(例如"删除"在没有选中项时置灰,颜色由组件按 Kind 取,
 * 页面不要再自己 setTextColor)。
 */
public class SelectActionBar extends LinearLayout {

    /** 动作键性质:决定"可用"时的文字色 */
    public enum Kind {
        /** 普通动作(全选/取消全选/暂停…):text_accent */
        NORMAL,
        /** 危险动作(删除/清空):text_danger */
        DANGER
    }

    /** 键上记录自己的 Kind,改可用态时据此取色(调用方不必自己记) */
    private static final int TAG_KIND = 0x5AB00001;

    /** 单个动作键高度(dp):列表页与下载页同高 */
    private static final int ACTION_HEIGHT_DP = 44;

    /** 动作键字号(sp) */
    private static final float ACTION_TEXT_SP = 14f;

    private final LinearLayout mRow;

    public SelectActionBar(@NonNull Context context) {
        this(context, null);
    }

    public SelectActionBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        // 背景=主题悬浮面(颜色 + bg_float_alpha 透明度都在主题文件里):不再写死 @color/white
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg_surface));
        LayoutInflater.from(context).inflate(R.layout.view_select_action_bar, this, true);
        mRow = findViewById(R.id.sab_row);
    }

    /**
     * 加一个等宽动作键(从左到右按加入顺序排)。
     *
     * @param kind     动作性质(决定可用时的文字色:普通=text_accent / 危险=text_danger)
     * @param listener 点击回调;null 表示暂不响应(仍可显示)
     * @return 该键的 TextView,交给 {@link #setActionEnabled} 控制可用态
     */
    public TextView addAction(CharSequence label, Kind kind, OnClickListener listener) {
        Kind k = kind == null ? Kind.NORMAL : kind;
        TextView action = new TextView(getContext());
        action.setText(label);
        action.setTextSize(ACTION_TEXT_SP);
        action.setGravity(Gravity.CENTER);
        action.setSingleLine(true);
        action.setTag(TAG_KIND, k);
        action.setBackgroundResource(selectableBorderlessRes());
        action.setTextColor(colorOf(k, true));
        LayoutParams lp = new LayoutParams(0, dp(ACTION_HEIGHT_DP), 1f);
        mRow.addView(action, lp);
        if (listener != null) {
            action.setOnClickListener(listener);
        }
        return action;
    }

    /**
     * 设置某个动作键的可用态:可用=按 Kind 取色(普通 text_accent / 危险 text_danger),
     * 不可用=text_disable。页面只报"能不能点",取色这件事只在这里发生。
     */
    public void setActionEnabled(TextView action, boolean enabled) {
        if (action == null) return;
        action.setEnabled(enabled);
        action.setTextColor(colorOf(kindOf(action), enabled));
    }

    /** 清空全部动作键(同一处复用、换一组动作时用) */
    public void clearActions() {
        mRow.removeAllViews();
    }

    private Kind kindOf(TextView action) {
        Object tag = action.getTag(TAG_KIND);
        return tag instanceof Kind ? (Kind) tag : Kind.NORMAL;
    }

    private int colorOf(Kind kind, boolean enabled) {
        int res;
        if (!enabled) {
            res = R.color.text_disable;
        } else if (kind == Kind.DANGER) {
            res = R.color.text_danger;
        } else {
            res = R.color.text_accent;
        }
        return ContextCompat.getColor(getContext(), res);
    }

    /** 系统"无边界涟漪"资源(?selectableItemBackgroundBorderless),与布局里原来写的保持同一观感 */
    private int selectableBorderlessRes() {
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
