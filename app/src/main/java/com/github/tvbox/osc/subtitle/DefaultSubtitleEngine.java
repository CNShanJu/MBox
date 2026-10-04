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
import android.os.HandlerThread;
import android.os.Message;
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
    private static final int MSG_REFRESH = 0x888;
    private static final int REFRESH_INTERVAL = 100;
    private static final Object REMOTE_CACHE_WRITE_LOCK = new Object();

    @Nullable
    private HandlerThread mHandlerThread;
    @Nullable
    private Handler mWorkHandler;
    @Nullable
    private List<Subtitle> mSubtitles;
    private UIRenderTask mUIRenderTask;
    private AbstractPlayer mMediaPlayer;
    private OnSubtitlePreparedListener mOnSubtitlePreparedListener;
    private OnSubtitleChangeListener mOnSubtitleChangeListener;
    private final AtomicLong mLoadEpoch = new AtomicLong();
    private String playSubtitleCacheKey;

    public DefaultSubtitleEngine() {

    }

    @Override
    public void bindToMediaPlayer(AbstractPlayer mediaPlayer) {
        mMediaPlayer = mediaPlayer;
    }

    @Override
    public void setSubtitlePath(final String path) {
        initWorkThread();
        reset();
        final long loadEpoch = mLoadEpoch.get();
        final String cacheKey = playSubtitleCacheKey;
        if (TextUtils.isEmpty(path)) {
            Log.w(TAG, "loadSubtitleFromRemote: path is null.");
            return;
        }

        SubtitleLoader.loadSubtitle(path, new SubtitleLoader.Callback() {
            @Override
            public void onSuccess(final SubtitleLoadSuccessResult subtitleLoadSuccessResult) {
                if (loadEpoch != mLoadEpoch.get()) return;
                if (subtitleLoadSuccessResult == null) {
                    Log.d(TAG, "onSuccess: subtitleLoadSuccessResult is null.");
                    return;
                }
                if (subtitleLoadSuccessResult.timedTextObject == null) {
                    Log.d(TAG, "onSuccess: timedTextObject is null.");
                    return;
                }
                final TreeMap<Integer, Subtitle> captions = subtitleLoadSuccessResult.timedTextObject.captions;
                if (captions == null) {
                    Log.d(TAG, "onSuccess: captions is null.");
                    return;
                }
                mSubtitles = new ArrayList<>(captions.values());
                setSubtitleDelay(SubtitleHelper.getTimeDelay());
                notifyPrepared();
                if (loadEpoch != mLoadEpoch.get() || TextUtils.isEmpty(cacheKey)) return;

                String subtitlePath = subtitleLoadSuccessResult.subtitlePath;
                if (subtitlePath.startsWith("http://") || subtitlePath.startsWith("https://")) {
                    AppTaskExecutor.deskIO().execute(() -> cacheRemoteSubtitle(
                            subtitlePath, subtitleLoadSuccessResult.fileName,
                            subtitleLoadSuccessResult.content, cacheKey, loadEpoch));
                } else {
                    com.github.tvbox.osc.repo.HistoryRepositories.cache().save(MD5.string2MD5(cacheKey), path);
                }
            }

            @Override
            public void onError(final Exception exception) {
                if (loadEpoch == mLoadEpoch.get()) Log.e(TAG, "Subtitle load failed", exception);
            }
        });
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
        stop();
        mSubtitles = null;
        mUIRenderTask = null;
    }

    @Override
    public void start() {
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
        if (mWorkHandler != null) {
            mWorkHandler.sendEmptyMessageDelayed(MSG_REFRESH, REFRESH_INTERVAL);
        }

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
        if (mWorkHandler != null) {
            mWorkHandler.removeMessages(MSG_REFRESH);
        }
    }

    @Override
    public void destroy() {
        Log.d(TAG, "destroy: ");
        stopWorkThread();
        reset();

    }

    private void initWorkThread() {
        stopWorkThread();
        mHandlerThread = new HandlerThread("SubtitleFindThread");
        mHandlerThread.start();
        mWorkHandler = new Handler(mHandlerThread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(final Message msg) {
                try {
                    long delay = REFRESH_INTERVAL;
                    if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                        long position = mMediaPlayer.getCurrentPosition();
                        Subtitle subtitle = SubtitleFinder.find(position, mSubtitles);
                        notifyRefreshUI(subtitle);
                        if (subtitle != null) {
                            delay = subtitle.end.mseconds - position;
                        }

                    }
                    if (mWorkHandler != null) {
                        mWorkHandler.sendEmptyMessageDelayed(MSG_REFRESH, delay);
                    }
                } catch (Exception e) {
                    // ignored
                }
                return true;
            }
        });
    }

    private void stopWorkThread() {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }
        if (mWorkHandler != null) {
            mWorkHandler.removeCallbacksAndMessages(null);
            mWorkHandler = null;
        }
    }

    private void notifyRefreshUI(final Subtitle subtitle) {
        if (mUIRenderTask == null) {
            mUIRenderTask = new UIRenderTask(mOnSubtitleChangeListener, mLoadEpoch::get);
        }
        mUIRenderTask.execute(subtitle);
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

}
