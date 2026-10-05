package com.github.tvbox.osc.util.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.net.Uri;
import android.util.AtomicFile;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleFileChooserDialog;
import com.github.tvbox.osc.subtitle.SubtitleEngine;
import com.github.tvbox.osc.subtitle.SubtitleInputPolicy;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

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

    /** 切换音轨/内置字幕后的进度恢复延迟:内核切轨道会自己快进几秒,等它落定再 seek 回原进度 */
    private static final long TRACK_RESTORE_DELAY_MS = 800L;
    private static final int MAX_IMPORTED_SUBTITLE_BYTES = 8 * 1024 * 1024;

    private final Activity mActivity;
    private final SubtitleController mController;
    private final PlayerSession mPlaySession;
    @Nullable
    private final ActivityResultLauncher<String[]> mOpenSubtitleDocument;
    private int mDocumentPickerEpoch;
    @Nullable
    private String mPendingImportedPath;

    /** 主线程延迟任务(轨道切换后的进度恢复);用它才能被 release() 取消 */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** 待执行的"轨道切换后恢复进度"任务(同一时刻最多一个,新的顶掉旧的) */
    @Nullable
    private Runnable mPendingTrackRestore;
    /** 协调器是否已释放(宿主 onDestroyView):释放后任何延迟任务都不再碰内核 */
    private volatile boolean mReleased;
    /**
     * 播放上下文版本:宿主每次切集/换源调 {@link #updateSubtitleContext} 时递增。
     * 延迟任务执行前比对它,避免 800ms 内已经换了一集、却拿旧进度去 seek(会把新集拉到旧位置)。
     */
    private volatile int mContextEpoch;

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
        mOpenSubtitleDocument = activity instanceof ComponentActivity
                ? ((ComponentActivity) activity).getActivityResultRegistry().register(
                        "subtitle-open-document", new ActivityResultContracts.OpenDocument() {
                            @NonNull
                            @Override
                            public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
                                // 选择字幕属于播放页操作，避免被 onUserLeaveHint 当成切后台进入画中画。
                                return super.createIntent(context, input)
                                        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                            }
                        },
                        this::onSubtitleDocumentPicked)
                : null;
    }

    /**
     * 释放:取消所有待执行的延迟任务。宿主必须在 {@code onDestroyView} 调用。
     * <p>
     * 不取消的话,"点切轨道 → 800ms 内退出播放页/切集"这条路径上,任务照样会打到正在释放的内核上
     * ({@code seekTo}/{@code start} 落到已释放的原生播放器 = native 崩),而且它是 post 在主线程的,
     * 不受外层 try 保护。
     */
    public void release() {
        mReleased = true;
        cancelPendingTrackRestore();
        mController.getSubtitleView().reset();
        discardPendingImportedSubtitle();
        if (mOpenSubtitleDocument != null) mOpenSubtitleDocument.unregister();
    }

    /** 新播放开始时立即作废旧字幕请求；无需等到新内核 prepared。 */
    public void invalidateForPlaybackChange() {
        mContextEpoch++;
        cancelPendingTrackRestore();
        mController.getSubtitleView().reset();
        discardPendingImportedSubtitle();
        mController.getSubtitleView().isInternal = false;
        mController.getSubtitleView().hasInternal = false;
        mController.getSubtitleView().onSubtitleChanged(null);
    }

    /** 更新当前剧集字幕上下文（playResult.subt / subtKey）；每次切集调用 */
    public void updateSubtitleContext(@Nullable String playSubtitle, @Nullable String subtitleCacheKey) {
        mPlaySubtitle = playSubtitle;
        mSubtitleCacheKey = subtitleCacheKey;
        mContextEpoch++; // 新的一集:让在途的"轨道切换后恢复进度"任务失效,不再拿旧进度 seek
    }

    /**
     * 排一个"切换轨道后恢复进度"的延迟任务。
     * <p>
     * 三条护栏:①可被 {@link #release()} 取消;②切集/换源后(epoch 变化)不执行;
     * ③内核被替换或已释放时不执行。任务体自带 try/catch —— 它跑在 post 之后,外层 try 保护不到。
     */
    private void postTrackRestore(@Nullable final AbstractPlayer kernel, final Runnable body) {
        cancelPendingTrackRestore();
        final int epoch = mContextEpoch;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                mPendingTrackRestore = null;
                try {
                    boolean sameKernel = kernel != null && mPlaySession.kernel() == kernel;
                    if (!TrackRestoreGuard.shouldRun(mReleased, epoch, mContextEpoch, sameKernel)) return;
                    body.run();
                } catch (Throwable th) {
                    LOG.e("轨道切换后恢复进度失败: " + th);
                }
            }
        };
        mPendingTrackRestore = task;
        mMainHandler.postDelayed(task, TRACK_RESTORE_DELAY_MS);
    }

    private void cancelPendingTrackRestore() {
        if (mPendingTrackRestore != null) {
            mMainHandler.removeCallbacks(mPendingTrackRestore);
            mPendingTrackRestore = null;
        }
    }

    // ── 字幕装载（内核 prepared 后调用一次）──

    /** 装载字幕：恢复缓存/外部字幕，否则自动选中文内置字幕；显隐跟随"字幕"开关 */
    public void initSubtitleView() {
        AbstractPlayer mediaPlayer = mPlaySession.kernel();
        TrackInfo trackInfo = PlayerTrackHelper.getTrackInfo(mediaPlayer);
        mController.getSubtitleView().reset();
        mController.getSubtitleView().hasInternal = trackInfo != null && !trackInfo.getSubtitle().isEmpty();
        mController.getSubtitleView().isInternal = false;
        mController.getSubtitleView().onSubtitleChanged(null);
        final int subtitleContextEpoch = mContextEpoch;
        PlayerTrackHelper.setOnSubtitleListener(mediaPlayer, new PlayerTrackHelper.SubtitleListener() {
            @Override
            public void onSubtitle(@Nullable String text) {
                mMainHandler.post(() -> {
                    if (mReleased || subtitleContextEpoch != mContextEpoch
                            || mPlaySession.kernel() != mediaPlayer
                            || !mController.getSubtitleView().isInternal) return;
                    if (text == null) {
                        mController.getSubtitleView().onSubtitleChanged(null);
                    } else {
                        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                        subtitle.content = text;
                        mController.getSubtitleView().onSubtitleChanged(subtitle);
                    }
                });
            }
        });

        mController.getSubtitleView().bindToMediaPlayer(mPlaySession.kernel());
        String cacheKey = mSubtitleCacheKey;
        if (cacheKey != null) {
            mController.getSubtitleView().setPlaySubtitleCacheKey(cacheKey);
            String subtitlePathCache = (String) com.github.tvbox.osc.repo.HistoryRepositories.cache().get(MD5.string2MD5(cacheKey));
            if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
                mController.getSubtitleView().setSubtitlePath(subtitlePathCache,
                        new SubtitleEngine.OnSubtitleLoadListener() {
                            @Override
                            public void onLoaded() {
                                // The cached subtitle remains selected.
                            }

                            @Override
                            public void onFailed(String message) {
                                if (mReleased || subtitleContextEpoch != mContextEpoch
                                        || mPlaySession.kernel() != mediaPlayer) return;
                                AppTaskExecutor.deskIO().execute(() -> {
                                    String key = MD5.string2MD5(cacheKey);
                                    Object current = com.github.tvbox.osc.repo.HistoryRepositories.cache().get(key);
                                    if (subtitlePathCache.equals(current)) {
                                        com.github.tvbox.osc.repo.HistoryRepositories.cache()
                                                .delete(key, subtitlePathCache);
                                    }
                                });
                                applyFallbackOrInternalSubtitle(trackInfo);
                            }
                        });
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
        setSubtitlePath(path, new SubtitleEngine.OnSubtitleLoadListener() {
            @Override
            public void onLoaded() {
                AppBubble.toast("字幕已加载");
            }

            @Override
            public void onFailed(String message) {
                AppBubble.toast(message);
            }
        });
    }

    private void setSubtitlePath(String path, @Nullable SubtitleEngine.OnSubtitleLoadListener listener) {
        if (TextUtils.isEmpty(path) || mReleased) {
            if (listener != null && !mReleased) listener.onFailed("字幕地址为空");
            return;
        }
        final int contextEpoch = mContextEpoch;
        AbstractPlayer mediaPlayer = mPlaySession.kernel();
        if (mediaPlayer == null) {
            if (listener != null) listener.onFailed("视频尚未准备好，请稍后重试");
            return;
        }
        mController.getSubtitleView().bindToMediaPlayer(mediaPlayer);
        mController.getSubtitleView().setSubtitlePath(path, new SubtitleEngine.OnSubtitleLoadListener() {
            @Override
            public void onLoaded() {
                if (mReleased || contextEpoch != mContextEpoch) return;
                PlayConfig.setSubtitleOpen(true);
                mController.getSubtitleView().setVisibility(View.VISIBLE);
                if (listener != null) listener.onLoaded();
            }

            @Override
            public void onFailed(String message) {
                if (listener != null && !mReleased && contextEpoch == mContextEpoch) listener.onFailed(message);
            }
        });
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
                final int subtitleContextEpoch = mContextEpoch;
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(mActivity);
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                    @Override
                    public void loadSubtitle(Subtitle subtitle, SearchSubtitleDialog.LoadResult result) {
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (mReleased || subtitleContextEpoch != mContextEpoch) {
                                    result.onFailed("播放已切换，请重新选择字幕");
                                    return;
                                }
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("Remote Subtitle Url: " + zimuUrl);
                                setSubtitlePath(zimuUrl, new SubtitleEngine.OnSubtitleLoadListener() {
                                    @Override
                                    public void onLoaded() {
                                        result.onLoaded();
                                    }

                                    @Override
                                    public void onFailed(String message) {
                                        result.onFailed(message);
                                    }
                                });
                            }
                        });
                    }
                });
                String searchWord = vodInfo.name;
                if (vodInfo.playFlag != null && (vodInfo.playFlag.contains("Ali") || vodInfo.playFlag.contains("parse"))) {
                    searchWord = vodInfo.playNote;
                }
                searchSubtitleDialog.setSearchWord(TextUtils.isEmpty(searchWord) ? "" : searchWord);
                DialogCoordinator.centerInHostView(mActivity, searchSubtitleDialog).show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                openLocalSubtitleChooser();
            }
        });
        DialogCoordinator.centerInHostView(mActivity, subtitleDialog).show();
    }

    /** 系统文件选择器可直接读取用户指定的字幕，无需全文件访问权限。 */
    private void openLocalSubtitleChooser() {
        mDocumentPickerEpoch = mContextEpoch;
        if (mOpenSubtitleDocument != null) {
            try {
                mOpenSubtitleDocument.launch(new String[]{"*/*"});
                return;
            } catch (ActivityNotFoundException ignored) {
                // 极少数设备没有系统文档提供器；已有文件权限时回退到应用内浏览器。
            }
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            AppBubble.toast("系统文件选择器不可用，无法选择字幕文件");
            return;
        }
        showSubtitleFileChooser();
    }

    private void onSubtitleDocumentPicked(@Nullable Uri uri) {
        if (uri == null || mReleased || mDocumentPickerEpoch != mContextEpoch) return;
        final int contextEpoch = mContextEpoch;
        final String cacheKey = mSubtitleCacheKey;
        if (TextUtils.isEmpty(cacheKey)) {
            AppBubble.toast("请等待视频加载完成后再选择字幕");
            return;
        }
        AppBubble.toast("正在导入字幕…");
        AppTaskExecutor.deskIO().execute(() -> {
            try {
                if (mReleased || contextEpoch != mContextEpoch) return;
                String path = copySubtitleDocument(uri, cacheKey, contextEpoch);
                mMainHandler.post(() -> {
                    if (mReleased || contextEpoch != mContextEpoch) {
                        deleteImportedSubtitle(path);
                        return;
                    }
                    discardPendingImportedSubtitle();
                    mPendingImportedPath = path;
                    LOG.i("Local Subtitle Path: " + path);
                    setSubtitlePath(path, new SubtitleEngine.OnSubtitleLoadListener() {
                        @Override
                        public void onLoaded() {
                            if (path.equals(mPendingImportedPath)) mPendingImportedPath = null;
                            AppBubble.toast("字幕已加载");
                            cleanupOlderImportedSubtitles(cacheKey, path);
                        }

                        @Override
                        public void onFailed(String message) {
                            if (path.equals(mPendingImportedPath)) mPendingImportedPath = null;
                            deleteImportedSubtitle(path);
                            AppBubble.toast(message);
                        }
                    });
                });
            } catch (SubtitleInputPolicy.UnsupportedSubtitleFormatException e) {
                mMainHandler.post(() -> {
                    if (mReleased || contextEpoch != mContextEpoch) return;
                    AppBubble.toast(e.getMessage());
                });
            } catch (IOException | SecurityException e) {
                mMainHandler.post(() -> {
                    if (mReleased || contextEpoch != mContextEpoch) return;
                    AppBubble.toast("字幕文件读取失败，请重新选择");
                });
            }
        });
    }

    private String copySubtitleDocument(Uri uri, String cacheKey, int contextEpoch) throws IOException {
        File directory = new File(mActivity.getFilesDir(), "subtitles");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create subtitle directory");
        }
        File target = new File(directory, MD5.string2MD5(cacheKey) + "-"
                + UUID.randomUUID() + ".subtitle");
        if (!directory.getCanonicalFile().equals(target.getCanonicalFile().getParentFile())) {
            throw new IOException("Subtitle path escaped its directory");
        }
        AtomicFile atomicFile = new AtomicFile(target);
        FileOutputStream output = null;
        try (InputStream input = mActivity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Subtitle document is empty");
            BufferedInputStream bufferedInput = new BufferedInputStream(input);
            SubtitleInputPolicy.requireSupportedContent(bufferedInput);
            output = atomicFile.startWrite();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = bufferedInput.read(buffer)) != -1) {
                if (mReleased || contextEpoch != mContextEpoch) throw new IOException("Subtitle selection expired");
                if (count > MAX_IMPORTED_SUBTITLE_BYTES - total) throw new IOException("Subtitle is too large");
                output.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new IOException("Subtitle document is empty");
            atomicFile.finishWrite(output);
            return target.getAbsolutePath();
        } catch (IOException | SecurityException e) {
            if (output != null) atomicFile.failWrite(output);
            throw e;
        }
    }

    private void discardPendingImportedSubtitle() {
        String path = mPendingImportedPath;
        mPendingImportedPath = null;
        if (path != null) deleteImportedSubtitle(path);
    }

    private void deleteImportedSubtitle(String path) {
        AppTaskExecutor.deskIO().execute(() -> new File(path).delete());
    }

    private void cleanupOlderImportedSubtitles(String cacheKey, String keepPath) {
        AppTaskExecutor.deskIO().execute(() -> {
            try {
                Object savedPath = com.github.tvbox.osc.repo.HistoryRepositories.cache()
                        .get(MD5.string2MD5(cacheKey));
                if (!keepPath.equals(savedPath)) return;
            } catch (RuntimeException e) {
                return;
            }
            File directory = new File(mActivity.getFilesDir(), "subtitles");
            File[] files = directory.listFiles();
            if (files == null) return;
            String prefix = MD5.string2MD5(cacheKey) + "-";
            for (File file : files) {
                if (file.getName().startsWith(prefix) && !file.getAbsolutePath().equals(keepPath)) {
                    file.delete();
                }
            }
        });
    }

    private void showSubtitleFileChooser() {
        SubtitleFileChooserDialog dialog = new SubtitleFileChooserDialog(mActivity, "/storage/emulated/0/Download", new SubtitleFileChooserDialog.OnFileChosenListener() {
            @Override
            public void onChosen(String path) {
                LOG.i("Local Subtitle Path: " + path);
                setSubtitlePath(path);//设置字幕
            }
        });
        DialogCoordinator.centerInHostView(mActivity, dialog).show();
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
                    postTrackRestore(mediaPlayer, new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                        }
                    });
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
        DialogCoordinator.centerInHostView(mActivity, dialog).show();
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
        if (bean.isEmpty()) {
            AppBubble.toast("当前视频没有内置字幕");
            return;
        }
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换内置字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean subtitle : bean) {
                        subtitle.selected = subtitle.trackGroupId == value.trackGroupId && subtitle.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    mController.getSubtitleView().destroy();
                    mController.getSubtitleView().clearSubtitleCache();
                    mController.getSubtitleView().onSubtitleChanged(null);
                    mController.getSubtitleView().isInternal = true;

                    // 轨道切换/进度恢复差异收敛到 PlayerTrackHelper,不感知内核
                    PlayerTrackHelper.selectTrack(mediaPlayer, value);
                    PlayConfig.setSubtitleOpen(true);
                    mController.getSubtitleView().setVisibility(View.VISIBLE);
                    postTrackRestore(mediaPlayer, new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                            if (PlayerTrackHelper.requiresControllerProgressRestart(mediaPlayer)) {
                                mController.startProgress();
                            }
                        }
                    });
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
        DialogCoordinator.centerInHostView(mActivity, dialog).show();
    }

    // ── 其它（外部事件驱动的字幕字号变更）──

    /** 全局字幕字号变更事件（设置页调节后广播） */
    public void applySubtitleSize(int size) {
        mController.getSubtitleView().setTextSize(size);
    }
}
