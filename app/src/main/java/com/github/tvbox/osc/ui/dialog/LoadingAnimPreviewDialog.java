package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
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
    protected void onCreate() {
        super.onCreate();
        ((TextView) findViewById(R.id.tv_preview_title)).setText(displayName + " · 放大预览");
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
        animationView.playAnimation();
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
