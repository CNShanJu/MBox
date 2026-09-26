package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.AppLog;
import com.github.tvbox.osc.util.MdText;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 更新确认弹窗:展示版本与更新说明(说明支持轻量 Markdown 渲染、左对齐、可滚动),
 * 提供「稍后 / 立即更新」;替代旧版纯文本居中拼接的确认框。
 * 统一经 {@link #show(Context, UpdateInfo, Runnable)} 弹出(内部走 Builder 绑定主题)。
 */
public class UpdateNoteDialog extends AppCenterPopupView {

    /** 统一弹出入口:XPopup.Builder 绑定 popupInfo 后 show,context 须为 Activity */
    public static void show(Context context, UpdateInfo info, Runnable onUpdate) {
        new XPopup.Builder(context)
                .isDarkTheme(Utils.isAppDarkTheme())
                .asCustom(new UpdateNoteDialog(context, info, onUpdate))
                .show();
    }

    private final UpdateInfo mInfo;
    private final Runnable mOnUpdate;
    /** 说明区是否已限高(测量期/post 两条路径只生效一次,避免重复压或把固定区算错) */
    private boolean bodyClampApplied;

    public UpdateNoteDialog(@NonNull @NotNull Context context, UpdateInfo info, Runnable onUpdate) {
        super(context);
        mInfo = info;
        mOnUpdate = onUpdate;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_update_note;
    }

    /**
     * 弹窗上限:按"扣掉状态栏/导航栏后的可用高度"封顶。
     * 更新说明可能很长(跨版本最多拼 5 个版本),按整屏高封顶会让底部按钮被系统栏裁掉。
     */
    @Override
    protected int getMaxHeight() {
        return DialogHeightPolicy.maxHeightPxInsideWindow(getContext());
    }

    /**
     * 内容根是否自带滚动能力(列表/ScrollView)。
     * 默认 false:内容超高时由基类把整卡包进 ScrollView 兜底;
     * 自带滚动区的弹窗(如 SelectDialog 的 TvRecyclerView)应返回 true,
     * 由内容区自行吃掉超高余量滚动,避免"整卡滚动"。
     */
    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TextView tvTitle = findViewById(R.id.note_title);
        TextView tvBody = findViewById(R.id.note_body);
        String version = mInfo == null || mInfo.versionName == null ? "" : mInfo.versionName;
        tvTitle.setText("发现新版本 v" + version);

        String note = mInfo == null ? "" : mInfo.releaseNote;
        if (note != null && !note.trim().isEmpty()) {
            tvBody.setText(MdText.render(note)); // Markdown → 加粗标题/列表圆点,左对齐
        } else {
            tvBody.setText("是否立即下载并安装?");
        }

        // 说明超长时把"更新内容"区限高到 最大高度−标题/按钮固定区,内容区自滚(标题/按钮固定)
        // 注意:必须在测量期就限(见 onMeasure),post 里再改高度太晚——
        // XPopup 的窗口高度已按内容自然高度定下,按钮会被顶出屏幕(实测"两个按钮被挤得看不见")
        requestMeasureClampOnNextLayout();

        findViewById(R.id.note_close).setOnClickListener(v -> dismiss());
        findViewById(R.id.note_later).setOnClickListener(v -> dismiss());
        findViewById(R.id.note_update).setOnClickListener(v -> {
            dismiss();
            if (mOnUpdate != null) mOnUpdate.run();
        });
    }

    /** 首次可布局后按"可用高度"给说明区定高(与 XPopup 自身 doMeasure/post 同帧,先于用户可见) */
    private void requestMeasureClampOnNextLayout() {
        final View content = getPopupImplView();
        if (content == null || !(content instanceof ViewGroup)) return;
        content.post(() -> applyBodyClamp((ViewGroup) content));
    }

    /**
     * 给说明区定高:可用高度 = 弹窗上限 − 卡片内边距 − 其他所有子视图(标题行/按钮行/间距)的自然高度。
     * <p>
     * 关键点:固定区高度**逐个用 UNSPECIFIED 量自然高**,不依赖整卡已测高度——
     * XPopup 的 {@code applyPopupSize} 是 post 出去的,首帧量到的整卡高度可能已被容器钳过,
     * 用它算"固定区"会算错,结果说明区被压错、按钮被顶出可视区(实测"按钮没有露出来")。
     */
    private void applyBodyClamp(ViewGroup card) {
        try {
            if (bodyClampApplied) return;   // 测量期已压过就不重复
            ScrollView scroll = card.findViewById(R.id.note_scroll);
            if (scroll == null) return;
            int maxH = getMaxHeight();
            if (maxH <= 0) return;

            int width = card.getWidth();
            if (width <= 0) width = card.getMeasuredWidth();
            if (width <= 0) return;

            int fixed = card.getPaddingTop() + card.getPaddingBottom();
            int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            for (int i = 0; i < card.getChildCount(); i++) {
                View child = card.getChildAt(i);
                if (child == scroll || child.getVisibility() == View.GONE) continue;
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                int extra = 0;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    extra = mlp.topMargin + mlp.bottomMargin;   // 间距也算固定区
                }
                child.measure(wSpec, hSpec);
                fixed += child.getMeasuredHeight() + extra;
            }

            DialogClamp.Clamp clamp = DialogClamp.clampScrollHeight(maxH, fixed, naturalHeight(scroll, width));
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            if (clamp.clamped) {
                lp.height = clamp.height;
                AppLog.log("更新", "说明区限高 " + clamp.height + "px(弹窗上限 " + maxH + "px,固定区 " + fixed + "px)");
            } else {
                // 说明区在可用高度内:回推自然高度,让卡片按内容撑开(权重布局会把它压在最小值上)
                lp.height = Math.max(clamp.height, minScrollPx());
                AppLog.log("更新", "说明区按内容撑开 " + lp.height + "px(弹窗上限 " + maxH + "px,固定区 " + fixed + "px)");
            }
            scroll.setLayoutParams(lp);
            bodyClampApplied = true;
        } catch (Throwable th) {
            AppLog.log("更新", "说明区限高失败: " + th);
        }
    }

    /** 说明区自然高度(UNSPECIFIED 量,不受容器钳制) */
    private int naturalHeight(View v, int width) {
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        v.measure(wSpec, hSpec);
        return v.getMeasuredHeight();
    }

    /** 说明区最小高度(与布局里的 minHeight 一致):权重布局在高度不受约束时会把它压到很小,故兜底 */
    private int minScrollPx() {
        return Math.round(80f * getResources().getDisplayMetrics().density);
    }

    /**
     * 测量期也限一次高:与 {@link #requestMeasureClampOnNextLayout()} 同源,互为兜底——
     * XPopup 的 applyPopupSize 是 post 出去的,首帧量到的整卡可能已是超高值,
     * 在测量期就把说明区压好,能保证第一帧(用户看到的那一帧)标题与按钮就在屏幕内。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (bodyClampApplied) return;
        View content = getPopupImplView();
        if (!(content instanceof ViewGroup)) return;
        ViewGroup card = (ViewGroup) content;
        ScrollView scroll = card.findViewById(R.id.note_scroll);
        if (scroll == null) return;
        int maxH = getMaxHeight();
        if (maxH <= 0) return;
        int width = card.getMeasuredWidth();
        if (width <= 0) return;

        int fixed = card.getPaddingTop() + card.getPaddingBottom();
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child == scroll || child.getVisibility() == View.GONE) continue;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            int extra = 0;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                extra = mlp.topMargin + mlp.bottomMargin;
            }
            child.measure(wSpec, hSpec);
            fixed += child.getMeasuredHeight() + extra;
        }
        DialogClamp.Clamp clamp = DialogClamp.clampScrollHeight(maxH, fixed, naturalHeight(scroll, width));
        if (!clamp.clamped) return;
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        if (lp == null || lp.height == clamp.height) return;
        lp.height = clamp.height;
        scroll.setLayoutParams(lp);
        bodyClampApplied = true;
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isAppDarkTheme())
                    .asCustom(this).show();
        }
        return super.show();
    }
}
