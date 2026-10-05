/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.SubtitleHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * @author AveryZhong.
 */

public class DefaultSubtitleEngine implements SubtitleEngine {
    private static final String TAG = DefaultSubtitleEngine.class.getSimpleName();
    private static final Object REMOTE_CACHE_WRITE_LOCK = new Object();

    @Nullable
    private SubtitleRefreshLoop mRefreshLoop;
    @Nullable
    private volatile List<Subtitle> mSubtitles;
    @Nullable
    private SubtitleFinder.Index mSubtitleIndex;
    private UIRenderTask mUIRenderTask;
    private Subtitle mLastRenderedSubtitle;
    private boolean mHasRenderedSubtitle;
    private boolean mLoggedFirstPosition;
    private boolean mLoggedFirstMatch;
    private AbstractPlayer mMediaPlayer;
    private OnSubtitlePreparedListener mOnSubtitlePreparedListener;
    private OnSubtitleChangeListener mOnSubtitleChangeListener;
    private final AtomicLong mLoadEpoch = new AtomicLong();
    private final AtomicLong mRenderEpoch = new AtomicLong();
    private String playSubtitleCacheKey;

    public DefaultSubtitleEngine() {

    }

    @Override
    public void bindToMediaPlayer(AbstractPlayer mediaPlayer) {
        mMediaPlayer = mediaPlayer;
    }

    @Override
    public void setSubtitlePath(final String path) {
        setSubtitlePath(path, null);
    }

    @Override
    public void setSubtitlePath(final String path, @Nullable final OnSubtitleLoadListener listener) {
        if (TextUtils.isEmpty(path)) {
            Log.w(TAG, "loadSubtitleFromRemote: path is null.");
            if (listener != null) listener.onFailed("字幕地址为空");
            return;
        }
        // Keep the current subtitle visible while validating a replacement. A failed candidate
        // must not leave the player marked as enabled with no captions.
        final long loadEpoch = mLoadEpoch.incrementAndGet();
        final String cacheKey = playSubtitleCacheKey;

        SubtitleLoader.loadSubtitle(path, new SubtitleLoader.Callback() {
            @Override
            public void onSuccess(final SubtitleLoadSuccessResult subtitleLoadSuccessResult) {
                if (loadEpoch != mLoadEpoch.get()) return;
                if (subtitleLoadSuccessResult == null) {
                    reportLoadFailure(loadEpoch, "字幕文件不存在或无法读取", null, listener);
                    return;
                }
                if (subtitleLoadSuccessResult.timedTextObject == null) {
                    reportLoadFailure(loadEpoch, "字幕文件无法解析，请更换字幕", null, listener);
                    return;
                }
                final TreeMap<Integer, Subtitle> captions = subtitleLoadSuccessResult.timedTextObject.captions;
                if (captions == null || captions.isEmpty()) {
                    reportLoadFailure(loadEpoch, "字幕文件没有可显示的内容", null, listener);
                    return;
                }
                final long committedEpoch = mLoadEpoch.incrementAndGet();
                initRefreshLoop();
                // Drop queued frames from the old track only after the candidate is usable.
                mRenderEpoch.incrementAndGet();
                mUIRenderTask = null;
                mLastRenderedSubtitle = null;
                mHasRenderedSubtitle = false;
                mLoggedFirstPosition = false;
                mLoggedFirstMatch = false;
                mSubtitles = new ArrayList<>(captions.values());
                mSubtitleIndex = new SubtitleFinder.Index(mSubtitles);
                int delay = SubtitleHelper.getTimeDelay();
                setSubtitleDelay(delay);
                logTrackLoaded(delay);
                notifyPrepared();
                if (committedEpoch == mLoadEpoch.get() && !TextUtils.isEmpty(cacheKey)) {
                    String subtitlePath = subtitleLoadSuccessResult.subtitlePath;
                    if (subtitlePath.startsWith("http://") || subtitlePath.startsWith("https://")) {
                        AppTaskExecutor.deskIO().execute(() -> cacheRemoteSubtitle(
                                subtitlePath, subtitleLoadSuccessResult.fileName,
                                subtitleLoadSuccessResult.content, cacheKey, committedEpoch));
                    } else {
                        AppTaskExecutor.deskIO().execute(() -> {
                            try {
                                if (committedEpoch == mLoadEpoch.get()) {
                                    com.github.tvbox.osc.repo.HistoryRepositories.cache().save(
                                            MD5.string2MD5(cacheKey), path);
                                }
                            } catch (RuntimeException e) {
                                Log.e(TAG, "Unable to save subtitle selection", e);
                            }
                            if (listener != null) {
                                AppTaskExecutor.mainThread().execute(() -> {
                                    if (committedEpoch == mLoadEpoch.get()) listener.onLoaded();
                                });
                            }
                        });
                        return;
                    }
                }
                if (listener != null && committedEpoch == mLoadEpoch.get()) listener.onLoaded();
            }

            @Override
            public void onError(final Exception exception) {
                String message = exception instanceof SubtitleInputPolicy.UnsupportedSubtitleFormatException
                        ? exception.getMessage() : "字幕加载失败，请检查网络或更换字幕";
                reportLoadFailure(loadEpoch, message, exception, listener);
            }
        });
    }

    private void reportLoadFailure(long loadEpoch, String message, @Nullable Exception cause,
                                   @Nullable OnSubtitleLoadListener listener) {
        if (loadEpoch != mLoadEpoch.get()) return;
        if (cause == null) {
            Log.w(TAG, message);
        } else {
            Log.e(TAG, message, cause);
        }
        if (listener != null) listener.onFailed(message);
    }

    private void cacheRemoteSubtitle(String url, String suggestedName, String content,
                                     String cacheKey, long loadEpoch) {
        if (loadEpoch != mLoadEpoch.get() || content == null) return;
        synchronized (REMOTE_CACHE_WRITE_LOCK) {
            if (loadEpoch != mLoadEpoch.get()) return;
            File cacheDir = new File(App.getInstance().getCacheDir(), "zimu");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                Log.w(TAG, "Unable to create subtitle cache directory");
                return;
            }
            File target = new File(cacheDir, SubtitleFilePolicy.cacheName(url, suggestedName));
            try {
                // The name is URL-derived; this check also rejects a future unsafe naming regression.
                if (!cacheDir.getCanonicalFile().equals(target.getCanonicalFile().getParentFile())) {
                    throw new IOException("Subtitle cache path escaped its directory");
                }
                AtomicFile atomicFile = new AtomicFile(target);
                FileOutputStream stream = null;
                try {
                    stream = atomicFile.startWrite();
                    stream.write(content.getBytes(StandardCharsets.UTF_8));
                    atomicFile.finishWrite(stream);
                } catch (IOException e) {
                    if (stream != null) atomicFile.failWrite(stream);
                    throw e;
                }
                if (loadEpoch == mLoadEpoch.get()) {
                    com.github.tvbox.osc.repo.HistoryRepositories.cache().save(
                            MD5.string2MD5(cacheKey), target.getAbsolutePath());
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to cache remote subtitle", e);
            }
        }
    }

    @Override
    public void setSubtitleDelay(Integer milliseconds) {
        if (milliseconds == 0) {
            return;
        }
        if (mSubtitles == null || mSubtitles.size() == 0) {
            return;
        }
        List<Subtitle> thisSubtitles = mSubtitles;
        mSubtitles = null;
        for (int i = 0; i < thisSubtitles.size(); i++) {
            Subtitle subtitle = thisSubtitles.get(i);
            Time start = subtitle.start;
            Time end = subtitle.end;
            start.mseconds += milliseconds;
            end.mseconds += milliseconds;
            if (start.mseconds <= 0) {
                start.mseconds = 0;
            }
            if (end.mseconds <= 0) {
                end.mseconds = 0;
            }
            subtitle.start = start;
            subtitle.end = end;
        }
        mSubtitles = thisSubtitles;
        mSubtitleIndex = new SubtitleFinder.Index(thisSubtitles);
        mHasRenderedSubtitle = false;
    }

    public void setPlaySubtitleCacheKey(String cacheKey) {
        if (!TextUtils.equals(playSubtitleCacheKey, cacheKey)) reset();
        playSubtitleCacheKey = cacheKey;
    }

    public String getPlaySubtitleCacheKey() {
        return playSubtitleCacheKey;
    }

    @Override
    public void reset() {
        mLoadEpoch.incrementAndGet();
        stopRefreshLoop();
        mRenderEpoch.incrementAndGet();
        mSubtitles = null;
        mSubtitleIndex = null;
        mUIRenderTask = null;
        mLastRenderedSubtitle = null;
        mHasRenderedSubtitle = false;
        mLoggedFirstPosition = false;
        mLoggedFirstMatch = false;
    }

    @Override
    public void start() {
        if (mRefreshLoop == null) return;
        Log.d(TAG, "start: ");
        if (mMediaPlayer == null) {
            Log.w(TAG, "MediaPlayer is not bind, You must bind MediaPlayer to "
                    + SubtitleEngine.class.getSimpleName()
                    + " before start() method be called,"
                    + " you can do this by call " +
                    "bindToMediaPlayer(MediaPlayer mediaPlayer) method.");
            return;
        }
        stop();
        mRefreshLoop.start();

    }

    @Override
    public void pause() {
        stop();
    }

    @Override
    public void resume() {
        start();
    }

    @Override
    public void stop() {
        mRenderEpoch.incrementAndGet();
        mLastRenderedSubtitle = null;
        mHasRenderedSubtitle = false;
        if (mRefreshLoop != null) mRefreshLoop.stop();
    }

    @Override
    public void destroy() {
        Log.d(TAG, "destroy: ");
        reset();

    }

    private void initRefreshLoop() {
        stopRefreshLoop();
        // Media3 的进度查询也必须在播放器所属主线程执行；文件读取与解析仍由共享 IO 执行器处理。
        final Handler handler = new Handler(Looper.getMainLooper());
        mRefreshLoop = new SubtitleRefreshLoop(new SubtitleRefreshLoop.Scheduler() {
            @Override
            public void post(Runnable task, long delayMillis) {
                handler.postDelayed(task, delayMillis);
            }

            @Override
            public void cancel(Runnable task) {
                handler.removeCallbacks(task);
            }
        }, () -> {
            if (mMediaPlayer == null) return;
            long renderEpoch = mRenderEpoch.get();
            // 暂停时也按当前位置显示，固定间隔可及时跟随拖动进度和字幕延迟调整。
            long position = mMediaPlayer.getCurrentPosition();
            SubtitleFinder.Index index = mSubtitleIndex;
            Subtitle subtitle = index == null ? null : index.find(position);
            if (!mLoggedFirstPosition) {
                logSubtitleDiagnostic("字幕首次检查: positionMs=" + position
                        + ", matched=" + (subtitle != null)
                        + ", kernel=" + mMediaPlayer.getClass().getSimpleName());
                mLoggedFirstPosition = true;
            }
            if (subtitle != null && !mLoggedFirstMatch) {
                logSubtitleDiagnostic("字幕首次匹配: positionMs=" + position
                        + ", startMs=" + subtitle.start.mseconds
                        + ", endMs=" + subtitle.end.mseconds
                        + ", chars=" + (subtitle.content == null ? 0 : subtitle.content.length()));
                mLoggedFirstMatch = true;
            }
            if (!mHasRenderedSubtitle || subtitle != mLastRenderedSubtitle) {
                notifyRefreshUI(subtitle, renderEpoch);
                mLastRenderedSubtitle = subtitle;
                mHasRenderedSubtitle = true;
            }
        }, error -> Log.w(TAG, "Subtitle refresh failed; will retry", error));
    }

    private void stopRefreshLoop() {
        if (mRefreshLoop != null) mRefreshLoop.stop();
        mRefreshLoop = null;
    }

    private void notifyRefreshUI(final Subtitle subtitle, long renderEpoch) {
        if (mUIRenderTask == null) {
            mUIRenderTask = new UIRenderTask(mOnSubtitleChangeListener, mRenderEpoch::get);
        }
        mUIRenderTask.execute(subtitle, renderEpoch);
    }

    private void notifyPrepared() {
        if (mOnSubtitlePreparedListener != null) {
            mOnSubtitlePreparedListener.onSubtitlePrepared(mSubtitles);
        }
    }

    @Override
    public void setOnSubtitlePreparedListener(final OnSubtitlePreparedListener listener) {
        mOnSubtitlePreparedListener = listener;
    }

    @Override
    public void setOnSubtitleChangeListener(final OnSubtitleChangeListener listener) {
        mOnSubtitleChangeListener = listener;
        mUIRenderTask = null;
    }

    private void logTrackLoaded(int delay) {
        long firstStart = Long.MAX_VALUE;
        long lastEnd = Long.MIN_VALUE;
        for (Subtitle subtitle : mSubtitles) {
            firstStart = Math.min(firstStart, subtitle.start.mseconds);
            lastEnd = Math.max(lastEnd, subtitle.end.mseconds);
        }
        logSubtitleDiagnostic("字幕装载: count=" + mSubtitles.size()
                + ", firstStartMs=" + firstStart + ", lastEndMs=" + lastEnd
                + ", delayMs=" + delay);
    }

    private static void logSubtitleDiagnostic(String detail) {
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER, detail);
        Log.i("MBoxSubtitle", detail);
    }

}
