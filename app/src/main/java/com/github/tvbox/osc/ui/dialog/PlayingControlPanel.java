package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogPlayingControlBinding;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.PlaybackSettingsController;
import com.github.tvbox.osc.theme.ThemeDrawables;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.PlayerHelper;
import com.lxj.xpopup.core.BasePopupView;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 播放设置内容协调器（合并 PlayingControlDialog / PlayingControlRightDialog 的重复 UI 逻辑）。
 * <p>
 * 两个弹窗共用同一布局 {@code dialog_playing_control}，差异仅剩"壳"（底部 {@link AppBottomPopupView}
 * vs 抽屉 {@link AppDrawerPopupView}）；全部按钮绑定/倍速/缩放/播放器/解码/字幕/音轨/横竖屏/片头尾
 * 逻辑统一在本协调器，弹窗只做薄壳 + 委托。
 */
final class PlayingControlPanel {

    private static final DiffUtil.ItemCallback<Integer> INT_DIFF = new DiffUtil.ItemCallback<Integer>() {
        @Override
        public boolean areItemsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
            return oldItem.intValue() == newItem.intValue();
        }

        @Override
        public boolean areContentsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
            return oldItem.intValue() == newItem.intValue();
        }
    };

    /** 弹窗壳(XPopup BasePopupView 提供 dismiss/dismissWith),仅作关闭宿主用 */
    private final BasePopupView mHost;
    private final Activity mActivity;
    private final PlaybackSettingsController mController;
    private final MyVideoView mPlayer;
    private DialogPlayingControlBinding mBinding;

    PlayingControlPanel(BasePopupView host, Context context,
                        PlaybackSettingsController controller, MyVideoView videoView) {
        mHost = host;
        mActivity = context instanceof Activity ? (Activity) context : null;
        mController = controller;
        mPlayer = videoView;
    }

    /** 绑定视图并完成初始化(弹窗 onCreate 中调用) */
    void init(DialogPlayingControlBinding binding) {
        mBinding = binding;
        initView();
        initListener();
    }

    private void initView() {
        mBinding.scale.setText(mController.settingsScaleBtn().getText());
        mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
        mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
        mBinding.player.setText(mController.settingsPlayerBtn().getText());
        mBinding.decode.setText(mController.settingsIjkBtn().getText());
        // 播放设置弹窗常显"横竖屏",便于切回横屏
        mBinding.landscapePortrait.setVisibility(View.VISIBLE);
        updateAboutIjkVisible();
        updateSpeedUi();
        // 「点击型」小组件按钮的点击特效(按下整键透明度 80% 再恢复):倍速那几个是"选择型",
        // 靠选中态换色反馈,不套这个特效(用户口径:"一种是选择按钮一种是点击按钮")
        for (int id : new int[]{R.id.scale, R.id.player, R.id.decode, R.id.landscape_portrait,
                R.id.subtitle, R.id.voice, R.id.replay, R.id.refresh, R.id.start_end_reset}) {
            com.github.tvbox.osc.ui.kit.WidgetPressEffect.attach(mBinding.getRoot().findViewById(id));
        }
    }

    private void initListener() {
        // 倍速
        mBinding.speed0.setOnClickListener(view -> setSpeed(mBinding.speed0));
        mBinding.speed1.setOnClickListener(view -> setSpeed(mBinding.speed1));
        mBinding.speed1a.setOnClickListener(view -> setSpeed(mBinding.speed1a));
        mBinding.speed2.setOnClickListener(view -> setSpeed(mBinding.speed2));
        mBinding.speed3.setOnClickListener(view -> setSpeed(mBinding.speed3));
        mBinding.speed4.setOnClickListener(view -> setSpeed(mBinding.speed4));
        mBinding.speed5.setOnClickListener(view -> setSpeed(mBinding.speed5));

        // 缩放:点击直接列出所有选项选择
        mBinding.scale.setOnClickListener(view -> showScaleDialog());
        mBinding.playTimeStart.setOnClickListener(view -> changeAndUpdateText(mBinding.playTimeStart, mController.settingsTimeStartBtn()));
        mBinding.playTimeEnd.setOnClickListener(view -> changeAndUpdateText(mBinding.playTimeEnd, mController.settingsTimeSkipBtn()));
        mBinding.playTimeStart.setOnLongClickListener(view -> {
            mController.settingsTimeStartBtn().performLongClick();
            mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
            return true;
        });
        mBinding.playTimeEnd.setOnLongClickListener(view -> {
            mController.settingsTimeSkipBtn().performLongClick();
            mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
            return true;
        });
        mBinding.increaseStart.setOnClickListener(view -> {
            mController.increaseTime("st");
            updateSkipText(true);
        });
        mBinding.decreaseStart.setOnClickListener(view -> {
            mController.decreaseTime("st");
            updateSkipText(true);
        });
        mBinding.increaseEnd.setOnClickListener(view -> {
            mController.increaseTime("et");
            updateSkipText(false);
        });
        mBinding.decreaseEnd.setOnClickListener(view -> {
            mController.decreaseTime("et");
            updateSkipText(false);
        });
        // 播放器:点击直接列出所有播放器选择
        mBinding.player.setOnClickListener(view -> showPlayerDialog());
        mBinding.decode.setOnClickListener(view -> changeAndUpdateText(mBinding.decode, mController.settingsIjkBtn()));

        // 其他
        mBinding.startEndReset.setOnClickListener(view -> resetSkipStartEnd());
        mBinding.replay.setOnClickListener(view -> changeAndUpdateText(null, mController.settingsRetryBtn()));
        mBinding.refresh.setOnClickListener(view -> changeAndUpdateText(null, mController.settingsRefreshBtn()));
        mBinding.subtitle.setOnClickListener(view -> dismissWith(() -> changeAndUpdateText(null, mController.settingsZimuBtn())));
        mBinding.voice.setOnClickListener(view -> dismissWith(() -> changeAndUpdateText(null, mController.settingsAudioBtn())));
        // 横竖屏:点击切换并同步文案
        mBinding.landscapePortrait.setOnClickListener(view -> dismissWith(() -> changeAndUpdateText(null, mController.settingsLandscapeBtn())));
    }

    private void updateSkipText(boolean start) {
        if (start) {
            mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
        } else {
            mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
        }
    }

    /**
     * 点击直接调用 controller 里声明好的点击事件(不改动原逻辑,隐藏 controller 里的设置 view,全由弹窗设置)。
     *
     * @param view 不为空变更配置文字(如更换播放器/缩放);为空只触发点击(如刷新/重播)
     */
    private void changeAndUpdateText(TextView view, TextView targetView) {
        targetView.performClick();
        if (view != null) {
            view.setText(targetView.getText());
            if (view == mBinding.player) {
                updateAboutIjkVisible();
            }
        }
    }

    private void setSpeed(TextView textView) {
        mController.setSpeed(textView.getText().toString().replace("x", ""));
        updateSpeedUi();
    }

    /** 缩放:列出所有选项直接选择 */
    private void showScaleDialog() {
        final int cur = mController.getScaleType();
        SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("选择缩放");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
            @Override
            public void click(Integer value, int pos) {
                dialog.cancel();
                if (value != cur) {
                    mController.setScaleType(value);
                }
                mBinding.scale.setText(PlayerHelper.getScaleName(value));
            }

            @Override
            public String getDisplay(Integer val) {
                return PlayerHelper.getScaleName(val);
            }
        }, INT_DIFF, new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4, 5)), cur);
        dialog.show();
    }

    /** 播放器:列出所有可用播放器直接选择 */
    private void showPlayerDialog() {
        final int cur = mController.getPlayerType();
        final ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
        SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("选择播放器");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
            @Override
            public void click(Integer value, int pos) {
                dialog.cancel();
                int type = players.get(pos);
                if (type != cur) {
                    mController.setPlayerType(type);
                }
                mBinding.player.setText(PlayerHelper.getPlayerName(type));
            }

            @Override
            public String getDisplay(Integer val) {
                // val 就是播放器类型值(如 0/1/2),直接取名称,不能再当索引
                return PlayerHelper.getPlayerName(val);
            }
        }, INT_DIFF, players, players.indexOf(cur));
        dialog.show();
    }

    private void updateSpeedUi() {
        for (int i = 0; i < mBinding.containerSpeed.getChildCount(); i++) {
            TextView tv = (TextView) mBinding.containerSpeed.getChildAt(i);
            boolean selected = String.valueOf(mPlayer.getSpeed()).equals(tv.getText().toString().replace("x", ""));
            // 倍速是"选择型"小组件按钮:只切选中态 —— 底与文字色都由 style/WidgetBtn 的
            // selector_widget_btn + widget_btn_text 一对选择器给(选中 = 纯色按钮那一档),
            // 代码不再手动换底/换字色(用户口径:"选择按钮点击选中背景色和文字颜色都切换成纯色按钮")
            tv.setSelected(selected);
        }
    }

    /** 如切换/使用的是 ijk,解码和音轨按钮才显示 */
    public void updateAboutIjkVisible() {
        mBinding.decode.setVisibility(mController.settingsIjkBtn().getVisibility());
    }

    /** 重置片头/尾,刷新文字 */
    private void resetSkipStartEnd() {
        changeAndUpdateText(null, mController.settingsTimeResetBtn());
        mBinding.playTimeStart.setText(mController.settingsTimeStartBtn().getText());
        mBinding.playTimeEnd.setText(mController.settingsTimeSkipBtn().getText());
    }

    /** 关闭宿主弹窗并执行收尾 */
    private void dismissWith(Runnable after) {
        if (mHost != null) {
            mHost.dismissWith(after);
        } else if (after != null) {
            after.run();
        }
    }
}
