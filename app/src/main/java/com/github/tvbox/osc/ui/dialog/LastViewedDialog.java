package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    private View viewport;
    private View liveBubbleAnchor;
    private ViewTreeObserver.OnPreDrawListener pendingPlacement;

    public LastViewedDialog(@NonNull Context context, VodInfo vodInfo,
                            @NonNull View viewport, @Nullable View liveBubbleAnchor) {
        super(context);
        this.vodInfo = vodInfo;
        this.viewport = viewport;
        this.liveBubbleAnchor = liveBubbleAnchor;
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
        View card = findViewById(R.id.last_viewed_card);
        if (card != null && pendingPlacement != null) {
            ViewTreeObserver observer = card.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(pendingPlacement);
            pendingPlacement = null;
        }
        viewport = null;
        liveBubbleAnchor = null;
        super.onDismiss();
    }

    @Override
    protected void initAndStartAnimation() {
        View card = findViewById(R.id.last_viewed_card);
        // XPopup 可能在改完 LayoutParams、子视图尚未布局时回调。等首帧绘制前拿到卡片尺寸，
        // 再启动缩放动画，避免按动画过程中的缩放坐标定位。
        if (card != null && liveBubbleAnchor != null && liveBubbleAnchor.isShown()
                && pendingPlacement == null) {
            pendingPlacement = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    card.getViewTreeObserver().removeOnPreDrawListener(this);
                    pendingPlacement = null;
                    placeNearLiveBubble(card);
                    LastViewedDialog.super.initAndStartAnimation();
                    return true;
                }
            };
            card.getViewTreeObserver().addOnPreDrawListener(pendingPlacement);
            return;
        }
        if (pendingPlacement != null) return;
        super.initAndStartAnimation();
    }

    private void placeNearLiveBubble(@Nullable View card) {
        View anchor = liveBubbleAnchor;
        View bounds = viewport;
        if (card != null && anchor != null && bounds != null && anchor.isShown()
                && card.getWidth() > 0 && card.getHeight() > 0
                && anchor.getWidth() > 0 && anchor.getHeight() > 0) {
            Rect viewportRect = new Rect();
            Rect ballRect = new Rect();
            if (bounds.getGlobalVisibleRect(viewportRect)
                    && anchor.getGlobalVisibleRect(ballRect)
                    && viewportRect.contains(ballRect)
                    && ballRect.width() == anchor.getWidth()
                    && ballRect.height() == anchor.getHeight()) {
                int[] cardLocation = new int[2];
                int[] viewportLocation = new int[2];
                int[] ballLocation = new int[2];
                card.getLocationOnScreen(cardLocation);
                bounds.getLocationOnScreen(viewportLocation);
                anchor.getLocationOnScreen(ballLocation);
                float density = getResources().getDisplayMetrics().density;
                ViewGroup popupLayout = (ViewGroup) card.getParent();
                int sideInset = Math.max(popupLayout.getPaddingLeft(), popupLayout.getPaddingRight());
                LastViewedBubblePlacement.Position target = LastViewedBubblePlacement.calculate(
                        viewportLocation[0], viewportLocation[0] + bounds.getWidth(),
                        ballLocation[0], ballLocation[1], ballLocation[1] + anchor.getHeight(),
                        card.getWidth(), card.getHeight(),
                        sideInset, Math.round(4f * density),
                        Math.round(4f * density));
                View popupContent = getPopupContentView();
                popupContent.setTranslationX(popupContent.getTranslationX()
                        + target.left - cardLocation[0]);
                popupContent.setTranslationY(popupContent.getTranslationY()
                        + target.top - cardLocation[1]);
            }
        }
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
