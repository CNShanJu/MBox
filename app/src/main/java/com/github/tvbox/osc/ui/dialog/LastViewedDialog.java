package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.HistoryEntryNavigator;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.lxj.xpopup.core.PositionPopupView;
import com.lxj.xpopup.enums.DragOrientation;

public class LastViewedDialog extends PositionPopupView {
    private static final long MARQUEE_START_DELAY_MS = 2000L;
    private final VodInfo vodInfo;
    private TextView marqueeTextView;
    private Runnable startMarquee;

    public LastViewedDialog(@NonNull Context context, VodInfo vodInfo) {
        super(context);
        this.vodInfo = vodInfo;
    }

    @Override
    protected int getMaxWidth() {
        return DialogStyle.centerWidthPx(getContext());
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_last_viewed;
    }

    @Override
    protected void onCreate() {
        PopupKeyboardPolicy.onCreate(this);
        super.onCreate();
        View card = findViewById(R.id.last_viewed_card);
        TextView textView = findViewById(R.id.tv);
        // 显示时明确重放气泡资源,不按原像素猜 bg_float。
        com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(card, R.drawable.bg_bubble);
        String name = vodInfo.name == null ? "" : vodInfo.name.trim();
        String note = vodInfo.note == null ? "" : vodInfo.note.trim();
        textView.setText(note.isEmpty() ? name : name + " " + note);
        // 先留给用户读开头，再启动超长单行文字的跑马灯。
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setSelected(false);
        marqueeTextView = textView;
        startMarquee = () -> {
            if (isShow() && textView.isAttachedToWindow()) {
                textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                textView.setSelected(true);
            }
        };
        textView.postDelayed(startMarquee, MARQUEE_START_DELAY_MS);
        card.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            dismiss();
            HistoryEntryNavigator.open(getContext(), vodInfo);
        });
    }

    @Override
    protected void onDismiss() {
        if (marqueeTextView != null && startMarquee != null) {
            marqueeTextView.removeCallbacks(startMarquee);
        }
        super.onDismiss();
    }

    @Override
    public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        PopupKeyboardPolicy.afterFocus(this);
    }

    @Override
    protected DragOrientation getDragOrientation() {
        return DragOrientation.DragToRight;
    }
}
