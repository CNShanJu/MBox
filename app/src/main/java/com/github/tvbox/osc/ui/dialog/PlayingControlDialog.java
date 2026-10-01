package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogPlayingControlBinding;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.PlaybackSettingsController;

import org.jetbrains.annotations.NotNull;

/**
 * 播放设置弹窗（底部样式，竖屏播放器设置入口）。
 * 内容编排全部委托 {@link PlayingControlPanel}；本类只保留 XPopup 底部壳差异。
 */
public class PlayingControlDialog extends AppBottomPopupView {

    private PlayingControlPanel mPanel;

    public PlayingControlDialog(@NonNull @NotNull Context context,
                                PlaybackSettingsController controller,
                                MyVideoView videoView) {
        super(context);
        mPanel = new PlayingControlPanel(this, context, controller, videoView);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_playing_control;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        View root = getPopupImplView();
        if (root != null) {
            int minHeight = Math.round(ScreenUtils.getScreenHeight()
                    * DialogHeightPolicy.SHEET_RATIO_COLLAPSED);
            int maxHeight = getMaxHeight();
            root.setMinimumHeight(maxHeight > 0 ? Math.min(minHeight, maxHeight) : minHeight);
        }
        mPanel.init(DialogPlayingControlBinding.bind(getPopupImplView()));
    }
}
