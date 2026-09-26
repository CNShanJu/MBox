package com.github.tvbox.osc.util.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.player.PlayerSession;
import com.github.tvbox.osc.player.PlayerTrackHelper;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.player.controller.SubtitleController;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleFileChooserDialog;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 字幕协调器（改进.txt §三 PlayFragment 拆分）：承载字幕装载 / 字幕设置弹窗 / 音轨与内置字幕切换
 * 的全部交互与状态同步，宿主（PlayFragment）只保留薄转发。
 * <p>
 * 依赖注入：Activity（弹窗/回调 UI 线程）、字幕能力控制器 {@link SubtitleController}（getSubtitleView /
 * openSubtitle / startProgress；在线 VodController 与本地 LocalVideoController 均实现）、
 * {@link PlayerSession}（内核取用 kernel()）。行为与迁出前的 PlayFragment 私有方法逐行等价，
 * 内核差异（轨道/字幕回调）统一经 {@link PlayerTrackHelper}。
 */
public final class SubtitleCoordinator {

    private final Activity mActivity;
    private final SubtitleController mController;
    private final PlayerSession mPlaySession;

    /** 当前播放字幕上下文（每次播放结果变化由宿主 set 一次） */
    @Nullable
    private String mPlaySubtitle;
    @Nullable
    private String mSubtitleCacheKey;

    public SubtitleCoordinator(@NonNull Activity activity, @NonNull SubtitleController controller,
                               @NonNull PlayerSession playSession) {
        mActivity = activity;
        mController = controller;
        mPlaySession = playSession;
    }

    /** 更新当前剧集字幕上下文（playResult.subt / subtKey）；每次切集调用 */
    public void updateSubtitleContext(@Nullable String playSubtitle, @Nullable String subtitleCacheKey) {
        mPlaySubtitle = playSubtitle;
        mSubtitleCacheKey = subtitleCacheKey;
    }

    // ── 字幕装载（内核 prepared 后调用一次）──

    /** 装载字幕：恢复缓存/外部字幕，否则自动选中文内置字幕；显隐跟随"字幕"开关 */
    public void initSubtitleView() {
        AbstractPlayer mediaPlayer = mPlaySession.kernel();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
            mController.getSubtitleView().hasInternal = true;
        }
        PlayerTrackHelper.setOnSubtitleListener(mediaPlayer, new PlayerTrackHelper.SubtitleListener() {
            @Override
            public void onSubtitle(@Nullable String text) {
                if (mController.getSubtitleView().isInternal) {
                    if (text == null) {
                        mController.getSubtitleView().onSubtitleChanged(null);
                    } else {
                        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                        subtitle.content = text;
                        mController.getSubtitleView().onSubtitleChanged(subtitle);
                    }
                }
            }
        });

        mController.getSubtitleView().bindToMediaPlayer(mPlaySession.kernel());
        String cacheKey = mSubtitleCacheKey;
        if (cacheKey != null) {
            mController.getSubtitleView().setPlaySubtitleCacheKey(cacheKey);
            String subtitlePathCache = (String) com.github.tvbox.osc.repo.HistoryRepositories.cache().get(MD5.string2MD5(cacheKey));
            if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
                mController.getSubtitleView().setSubtitlePath(subtitlePathCache);
            } else {
                applyFallbackOrInternalSubtitle(trackInfo);
            }
        } else {
            applyFallbackOrInternalSubtitle(trackInfo);
        }
        // 字幕默认关闭:显隐跟随设置(用户可在播放器字幕设置里打开/关闭)
        mController.getSubtitleView().setVisibility(PlayConfig.isSubtitleOpen() ? View.VISIBLE : View.GONE);
    }

    /** 无字幕缓存时：外部字幕优先，其次自动选中文内置字幕（与原 PlayFragment.initSubtitleView 等价） */
    private void applyFallbackOrInternalSubtitle(@Nullable TrackInfo trackInfo) {
        if (mPlaySubtitle != null && mPlaySubtitle.length() > 0) {
            mController.getSubtitleView().setSubtitlePath(mPlaySubtitle);
        } else if (mController.getSubtitleView().hasInternal) {//有则使用内置字幕
            mController.getSubtitleView().isInternal = true;
            if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                List<TrackInfoBean> subtitleTrackList = trackInfo.getSubtitle();
                int selectedIndex = trackInfo.getSubtitleSelected(true);
                boolean hasCh = false;
                for (TrackInfoBean subtitleTrackInfoBean : subtitleTrackList) {
                    String lowerLang = subtitleTrackInfoBean.language == null ? "" : subtitleTrackInfoBean.language.toLowerCase();
                    if (lowerLang.contains("zh") || lowerLang.contains("ch")) {
                        hasCh = true;
                        if (selectedIndex != subtitleTrackInfoBean.trackId) {
                            PlayerTrackHelper.selectTrack(mPlaySession.kernel(), subtitleTrackInfoBean);
                            break;
                        }
                    }
                }
                if (!hasCh) {
                    PlayerTrackHelper.selectTrack(mPlaySession.kernel(), subtitleTrackList.get(0));
                }
            }
        }
    }

    // ── 字幕设置弹窗（外挂/内置/字号/延迟/样式/开关）──

    /**
     * 外部字幕路径设置（显隐跟随开关）；宿主薄壳 setSubtitle 委托。
     * <p>用户显式选字幕时:①先把字幕引擎重新绑定到<b>当前</b>播放内核(本地播放器 prepared 时绑定的
     * player 实例可能已被重建/替换,拉到真实播放位置才能按时间轴显示),②强制字幕可见并置为开启。
     */
    public void setSubtitlePath(String path) {
        if (path != null && path.length() > 0) {
            AbstractPlayer mediaPlayer = mPlaySession.kernel();
            if (mediaPlayer != null) {
                mController.getSubtitleView().bindToMediaPlayer(mediaPlayer);
            }
            mController.getSubtitleView().setSubtitlePath(path);
            PlayConfig.setSubtitleOpen(true);
            mController.getSubtitleView().setVisibility(View.VISIBLE);
        }
    }

    /** 打开"字幕"设置弹窗（含在线搜索 / 本地选择 / 字号延迟样式 / 内置切换入口） */
    public void openSubtitleDialog(@NonNull VodInfo vodInfo) {
        SubtitleDialog subtitleDialog = new SubtitleDialog(mActivity);
        subtitleDialog.setSubtitleViewListener(new SubtitleDialog.SubtitleViewListener() {
            @Override
            public void setTextSize(int size) {
                mController.getSubtitleView().setTextSize(size);
            }

            @Override
            public void setSubtitleDelay(int milliseconds) {
                mController.getSubtitleView().setSubtitleDelay(milliseconds);
            }

            @Override
            public void selectInternalSubtitle() {
                openInternalSubtitleDialog();
            }

            @Override
            public void setTextStyle(int style) {
                setSubtitleTextStyle(style);
            }

            @Override
            public void subtitleOpen(boolean b) {
                mController.openSubtitle(b);
            }
        });
        subtitleDialog.setSearchSubtitleListener(new SubtitleDialog.SearchSubtitleListener() {
            @Override
            public void openSearchSubtitleDialog() {
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(mActivity);
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                    @Override
                    public void loadSubtitle(Subtitle subtitle) {
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("Remote Subtitle Url: " + zimuUrl);
                                setSubtitlePath(zimuUrl);//设置字幕
                                searchSubtitleDialog.dismiss();
                            }
                        });
                    }
                });
                String searchWord = vodInfo.name;
                if (vodInfo.playFlag != null && (vodInfo.playFlag.contains("Ali") || vodInfo.playFlag.contains("parse"))) {
                    searchWord = vodInfo.playNote;
                }
                searchSubtitleDialog.setSearchWord(TextUtils.isEmpty(searchWord) ? "" : searchWord);
                searchSubtitleDialog.show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                openLocalSubtitleChooser();
            }
        });
        subtitleDialog.show();
    }

    /**
     * 打开本地字幕文件选择器(自研文件浏览器)。
     * <p>替代已不可用的 ChooserDialog(com.github.hedzr:android-file-chooser):
     * 该库经反射读隐藏 API StorageVolume.getPath(),在 Android 11+/targetSdk 34 被拒,
     * 导致本地字幕选择失效。Android 11+ 读公共目录字幕文件需 MANAGE_EXTERNAL_STORAGE,
     * 未授权时先请求,授权后再打开浏览器。
     */
    private void openLocalSubtitleChooser() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            AppBubble.toast("选择本地字幕需要存储访问权限");
            requestAllFilesAccess();
            return;
        }
        showSubtitleFileChooser();
    }

    private void showSubtitleFileChooser() {
        new SubtitleFileChooserDialog(mActivity, "/storage/emulated/0/Download", new SubtitleFileChooserDialog.OnFileChosenListener() {
            @Override
            public void onChosen(String path) {
                LOG.i("Local Subtitle Path: " + path);
                setSubtitlePath(path);//设置字幕
            }
        }).show();
    }

    /** 全文件访问(MANAGE_EXTERNAL_STORAGE)授权;授权成功后再打开字幕文件浏览器 */
    private void requestAllFilesAccess() {
        XXPermissions.with(mActivity)
                .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            showSubtitleFileChooser();
                        } else {
                            AppBubble.toastLong("部分权限未正常授予,请授权");
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        if (never) {
                            AppBubble.toastLong("存储访问权限被永久拒绝,请手动授权");
                            XXPermissions.startPermissionActivity(mActivity, permissions);
                        } else {
                            AppBubble.toast("获取存储权限失败");
                        }
                    }
                });
    }

    /** 字幕文字颜色样式（0=白 / 1=粉） */
    @SuppressLint("UseCompatLoadingForColorStateLists")
    public void setSubtitleTextStyle(int style) {
        if (style == 0) {
            mController.getSubtitleView().setTextColor(mActivity.getResources().getColorStateList(R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.getSubtitleView().setTextColor(mActivity.getResources().getColorStateList(R.color.color_FFB6C1));
        }
    }

    // ── 音轨 / 内置字幕切换（SelectDialog 单选 + 轨道切换 + 进度恢复）──

    /** 切换音轨 */
    public void openAudioTrackDialog() {
        AbstractPlayer mediaPlayer = mPlaySession.kernel();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        if (trackInfo == null) {
            AppBubble.toast("没有音轨");
            return;
        }
        final List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换音轨");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean audio : bean) {
                        audio.selected = audio.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    PlayerTrackHelper.selectTrack(mediaPlayer, value);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换音轨出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                String name = val.name.replace("AUDIO,", "");
                name = name.replace("N/A,", "");
                name = name.replace(" ", "");
                return name + (TextUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getAudioSelected(false));
        dialog.show();
    }

    /** 切换内置字幕 */
    public void openInternalSubtitleDialog() {
        AbstractPlayer mediaPlayer = mPlaySession.kernel();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        if (trackInfo == null) {
            AppBubble.toast("没有内置字幕");
            return;
        }
        final List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换内置字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                mController.getSubtitleView().setVisibility(View.VISIBLE);
                try {
                    for (TrackInfoBean subtitle : bean) {
                        subtitle.selected = subtitle.trackGroupId == value.trackGroupId && subtitle.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    mController.getSubtitleView().destroy();
                    mController.getSubtitleView().clearSubtitleCache();
                    mController.getSubtitleView().isInternal = true;

                    // 轨道切换/进度恢复差异收敛到 PlayerTrackHelper,不感知内核
                    PlayerTrackHelper.selectTrack(mediaPlayer, value);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                            if (PlayerTrackHelper.requiresControllerProgressRestart(mediaPlayer)) {
                                mController.startProgress();
                            }
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换内置字幕出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name + (TextUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TrackInfoBean oldItem, @NonNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getSubtitleSelected(false));
        dialog.show();
    }

    // ── 其它（外部事件驱动的字幕字号变更）──

    /** 全局字幕字号变更事件（设置页调节后广播） */
    public void applySubtitleSize(int size) {
        mController.getSubtitleView().setTextSize(size);
    }
}
