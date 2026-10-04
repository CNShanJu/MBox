package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.update.UpdatePromptPolicy.Action;
import com.github.tvbox.osc.util.MdText;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 更新确认弹窗。标题和操作按钮固定在说明区外；说明过长时只有说明区滚动。
 * 高度在首次显示前确定，避免 XPopup 的异步尺寸调整把按钮推出窗口。
 */
public class UpdateNoteDialog extends AppCenterPopupView {

    public static void show(Context context, UpdateInfo info, Runnable onUpdate) {
        new XPopup.Builder(context)
                .isDarkTheme(Utils.isAppDarkTheme())
                .asCustom(new UpdateNoteDialog(context, info, onUpdate))
                .show();
    }

    private final UpdateInfo mInfo;
    public interface ActionListener {
        void onAction(Action action);
    }

    private final ActionListener mOnUpdate;
    private Action action = Action.DOWNLOAD;
    private CharSequence actionLabel = "立即更新";
    private CharSequence actionStatus;
    private TextView updateButton;
    private TextView laterButton;
    private TextView statusView;
    private boolean actionCommitted;

    public UpdateNoteDialog(@NonNull @NotNull Context context, UpdateInfo info, Runnable onUpdate) {
        this(context, info, action -> {
            if (onUpdate != null) onUpdate.run();
        });
    }

    public UpdateNoteDialog(@NonNull @NotNull Context context, UpdateInfo info, ActionListener onUpdate) {
        super(context);
        mInfo = info;
        mOnUpdate = onUpdate;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_update_note;
    }

    /** 更新入口优先保证按钮可见，长说明允许使用窗口的大部分高度。 */
    @Override
    protected int getMaxHeight() {
        int available = getResources().getDisplayMetrics().heightPixels;
        int screen = ScreenUtils.getScreenHeight() - DialogHeightPolicy.systemBarsHeightPx(getContext());
        if (screen > 0) available = Math.min(available, screen);
        View host = getActivityContentView();
        if (host != null && host.getHeight() > 0) available = Math.min(available, host.getHeight());
        View decor = getWindowDecorView();
        if (decor != null) {
            Rect frame = new Rect();
            decor.getWindowVisibleDisplayFrame(frame);
            if (frame.height() > 0) available = Math.min(available, frame.height());
        }
        return Math.max(1, available - dp(24));
    }

    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TextView title = findViewById(R.id.note_title);
        TextView body = findViewById(R.id.note_body);
        String version = mInfo == null || mInfo.versionName == null ? "" : mInfo.versionName;
        title.setText("发现新版本 v" + version);

        String note = mInfo == null ? "" : mInfo.releaseNote;
        body.setText(note != null && !note.trim().isEmpty()
                ? MdText.render(note) : "是否立即下载并安装?");

        updateButton = findViewById(R.id.note_update);
        laterButton = findViewById(R.id.note_later);
        statusView = findViewById(R.id.note_status);
        bindUpdateAction();

        // onCreate 早于 XPopup 的首次布局及其 applyPopupSize 回调。
        // 现在就固定滚动区高度，弹窗的自然高度不会再超过可见窗口。
        sizeBodyBeforeFirstMeasure();

        findViewById(R.id.note_close).setOnClickListener(v -> dismiss());
        findViewById(R.id.note_later).setOnClickListener(v -> dismiss());
        updateButton.setOnClickListener(v -> {
            if (actionCommitted) return;
            actionCommitted = true;
            Action selected = action;
            v.setEnabled(false);
            dismissWith(() -> {
                if (mOnUpdate != null) mOnUpdate.onAction(selected);
            });
        });
    }

    /** 状态由更新入口传入,弹窗不访问下载控制器。须在主线程调用。 */
    public void setUpdateAction(Action action, CharSequence label, CharSequence status) {
        this.action = action;
        if (Objects.equals(actionLabel, label) && Objects.equals(actionStatus, status)) return;
        actionLabel = label;
        actionStatus = status;
        if (updateButton != null) {
            bindUpdateAction();
            sizeBodyBeforeFirstMeasure();
        }
    }

    private void bindUpdateAction() {
        updateButton.setText(actionLabel);
        boolean hasStatus = actionStatus != null && actionStatus.length() > 0;
        statusView.setText(hasStatus ? actionStatus : "");
        statusView.setVisibility(hasStatus ? View.VISIBLE : View.GONE);
        laterButton.setText(hasStatus ? "关闭" : "稍后");
    }

    private void sizeBodyBeforeFirstMeasure() {
        View impl = getPopupImplView();
        if (!(impl instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) impl;
        ScrollView scroll = card.findViewById(R.id.note_scroll);
        if (scroll == null) return;

        ViewGroup.LayoutParams cardParams = card.getLayoutParams();
        if (cardParams == null) return;
        int hostWidth = getActivityContentView() == null ? 0 : getActivityContentView().getWidth();
        if (hostWidth <= 0) hostWidth = getResources().getDisplayMetrics().widthPixels;
        int width = cardParams.width > 0 ? cardParams.width : dp(320);
        width = Math.min(width, Math.min(getMaxWidth(), Math.max(1, hostWidth - dp(24))));
        cardParams.width = width;
        card.setLayoutParams(cardParams);

        int innerWidth = Math.max(1, width - card.getPaddingLeft() - card.getPaddingRight());
        int maxHeight = getMaxHeight();
        int fixed = measureFixedHeight(card, scroll, innerWidth);
        if (fixed >= maxHeight) {
            // 分屏或超大系统字号下优先给标题和按钮腾空间。
            card.setPadding(card.getPaddingLeft(), dp(8), card.getPaddingRight(), dp(8));
            LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
            scrollParams.topMargin = dp(4);
            scroll.setLayoutParams(scrollParams);
            View footer = card.getChildAt(card.getChildCount() - 1);
            LinearLayout.LayoutParams footerParams = (LinearLayout.LayoutParams) footer.getLayoutParams();
            footerParams.topMargin = dp(8);
            footer.setLayoutParams(footerParams);
            fixed = measureFixedHeight(card, scroll, innerWidth);
        }
        if (fixed >= maxHeight) {
            // 极窄的分屏高度下，版本号可以暂时不显示，更新入口不能消失。
            card.getChildAt(0).setVisibility(View.GONE);
            fixed = measureFixedHeight(card, scroll, innerWidth);
        }
        if (fixed >= maxHeight) {
            card.setPadding(card.getPaddingLeft(), 0, card.getPaddingRight(), 0);
            LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
            scrollParams.topMargin = 0;
            scroll.setLayoutParams(scrollParams);
            View footer = card.getChildAt(card.getChildCount() - 1);
            LinearLayout.LayoutParams footerParams = (LinearLayout.LayoutParams) footer.getLayoutParams();
            footerParams.topMargin = 0;
            footer.setLayoutParams(footerParams);
            fixed = measureFixedHeight(card, scroll, innerWidth);
        }

        LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        int bodyWidth = Math.max(1, innerWidth - scrollParams.leftMargin - scrollParams.rightMargin);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(bodyWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        DialogClamp.Clamp clamp = DialogClamp.clampScrollHeight(maxHeight, fixed, scroll.getMeasuredHeight());
        scrollParams.height = clamp.height;
        scrollParams.weight = 0;
        scroll.setLayoutParams(scrollParams);
    }

    /** 固定区包含卡片内边距、标题、按钮，以及滚动区自身的外边距。 */
    private int measureFixedHeight(LinearLayout card, ScrollView scroll, int innerWidth) {
        int fixed = card.getPaddingTop() + card.getPaddingBottom();
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            fixed += lp.topMargin + lp.bottomMargin;
            if (child == scroll) continue;
            int childWidth = Math.max(1, innerWidth - lp.leftMargin - lp.rightMargin);
            child.measure(View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            fixed += child.getMeasuredHeight();
        }
        return fixed;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return show(null);
        }
        return super.show();
    }

    public BasePopupView show(XPopupCallback callback) {
        XPopup.Builder builder = new XPopup.Builder(getContext())
                .isDarkTheme(Utils.isAppDarkTheme());
        if (callback != null) builder.setPopupCallback(callback);
        return builder.asCustom(this).show();
    }
}
