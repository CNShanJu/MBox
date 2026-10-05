package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LoadingAnim;

/** Preview the long-pressed loading animation without changing the selected setting. */
public final class LoadingAnimPreviewDialog extends AppCenterPopupView {
    private final String animName;
    private final String displayName;
    private LottieAnimationView animationView;

    public static void show(Context context, String animName, String displayName) {
        DialogCoordinator.center(context,
                new LoadingAnimPreviewDialog(context, animName, displayName)).show();
    }

    private LoadingAnimPreviewDialog(@NonNull Context context, String animName, String displayName) {
        super(context);
        this.animName = animName;
        this.displayName = displayName;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_loading_anim_preview;
    }

    @Override
    protected int getMaxHeight() {
        return DialogHeightPolicy.maxHeightPxInsideWindow(getContext());
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        ((TextView) findViewById(R.id.tv_preview_title)).setText(displayName);
        findViewById(R.id.iv_preview_close).setOnClickListener(v -> dismiss());

        TextView failureView = findViewById(R.id.tv_preview_unavailable);
        animationView = findViewById(R.id.lottie_preview);
        LottieAnimationView preview = animationView;
        animationView.setFailureListener(error -> {
            preview.cancelAnimation();
            preview.setVisibility(View.INVISIBLE);
            failureView.setVisibility(View.VISIBLE);
        });
        animationView.setAnimation(LoadingAnim.DIR_NAME + "/" + animName + "/" + animName + ".json");
        animationView.setRepeatMode(LottieDrawable.RESTART);
        animationView.setRepeatCount(LottieDrawable.INFINITE);
        animationView.setSpeed(LoadingAnim.getPlaybackSpeed(animName));
        animationView.setClipToCompositionBounds(false);
        LoadingAnim.applyAppearance(animationView, animName, false);
        LoadingAnim.applySize(animationView, animName);
        fitPreviewToWindow();
        animationView.playAnimation();
    }

    private void fitPreviewToWindow() {
        View panel = getPopupImplView();
        FrameLayout stage = findViewById(R.id.preview_animation_stage);
        if (panel == null || stage == null) return;

        float density = getResources().getDisplayMetrics().density;
        int requestedSize = animationView.getLayoutParams().width;
        int preferredWidth = (int) Math.min(Integer.MAX_VALUE, (long) requestedSize
                + panel.getPaddingLeft() + panel.getPaddingRight());
        int panelWidth = Math.min(getMaxWidth(),
                Math.max(Math.round(300 * density), preferredWidth));
        ViewGroup.LayoutParams panelParams = panel.getLayoutParams();
        if (panelParams != null && panelParams.width != panelWidth) {
            panelParams.width = panelWidth;
            panel.setLayoutParams(panelParams);
        }

        int stageHeight = Integer.MAX_VALUE;
        int maxHeight = getMaxHeight();
        if (maxHeight > 0) {
            View titleBar = findViewById(R.id.preview_title_bar);
            int titleHeight = titleBar.getLayoutParams().height;
            stageHeight = Math.max(1, maxHeight - panel.getPaddingTop()
                    - panel.getPaddingBottom() - titleHeight);
        }
        stage.setMinimumHeight(Math.min(Math.round(128 * density), stageHeight));
        int size = Math.max(1, Math.min(requestedSize,
                Math.min(panelWidth - panel.getPaddingLeft() - panel.getPaddingRight(), stageHeight)));
        ViewGroup.LayoutParams animationParams = animationView.getLayoutParams();
        if (animationParams.width != size || animationParams.height != size) {
            animationParams.width = size;
            animationParams.height = size;
            animationView.setLayoutParams(animationParams);
        }
    }

    @Override
    protected void onDismiss() {
        if (animationView != null) {
            animationView.cancelAnimation();
            animationView = null;
        }
        super.onDismiss();
    }
}
