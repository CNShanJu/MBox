package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogPlayingControlBinding;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.PlaybackSettingsController;

import org.jetbrains.annotations.NotNull;

/**
 * 播放设置右侧抽屉：在线全屏播放与本地播放共用（{@link PlaybackSettingsController}）。
 * 内容编排全部委托 {@link PlayingControlPanel}；本类只保留 XPopup 抽屉壳差异。
 */
public class PlayingControlRightDialog extends AppDrawerPopupView {

    private PlayingControlPanel mPanel;

    public PlayingControlRightDialog(@NonNull @NotNull Context context,
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
        mPanel.init(DialogPlayingControlBinding.bind(getPopupImplView()));
    }
}
