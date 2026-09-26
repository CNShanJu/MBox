package com.github.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.constant.IntentKey;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.ui.activity.DetailActivity;

/**
 * 后台播放前台服务(后台播放=开启时承载)。
 *
 * <p>职责:
 * <ol>
 * <li>前台服务通知(标题/集数 + 上一集/播放暂停/下一集/关闭,RemoteViews 大/小布局);</li>
 * <li>{@link MediaSession} 媒体会话:让系统媒体控制中心/锁屏媒体卡识别本播放,提供
 *     播放/暂停/上一集/下一集/拖动;回调经 {@link IntentKey#BROADCAST_ACTION} 广播
 *     转发给播放页控制器消费,与通知按钮同一通道;</li>
 * <li>播放状态/进度轮询:把共享 {@link MyVideoView} 实时状态(播放/暂停/进度)同步到
 *     媒体会话与通知图标。</li>
 * </ol>
 *
 * <p>注意:视频本体仍由 {@link DetailActivity}/PlayFragment 持有,本服务只持共享视图引用
 * (start 时注入),不参与播放器内核生命周期。MediaSession 用平台 API(minSdk 24 满足),
 * 不新增依赖。
 */
public class PlayService extends Service {

    static String videoInfo = "MBox&&第一集";
    private static MyVideoView videoView;
    /** 运行中的服务实例(供播放页直调刷新通知/媒体卡);未启动时为 null。
     *  由 onCreate/onDestroy(主线程)写、播放侧任意线程读,故用 volatile 保证可见性 */
    private static volatile PlayService sInstance;

    private MediaSession mediaSession;
    private Handler mainHandler;
    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            syncPlaybackState();
            mainHandler.postDelayed(this, 800);
        }
    };
    private boolean lastPlaying;

    /** "标题&&集数" 分段读取,越界/缺段返回空串,避免 split 后越界崩溃 */
    private static String splitPart(String info, int index) {
        if (info == null) return "";
        String[] parts = info.split("&&", -1);
        if (parts.length > index && parts[index] != null) {
            return parts[index].trim();
        }
        return parts.length > 0 && parts[0] != null ? parts[0].trim() : "";
    }

    public static void start(MyVideoView controller, String currentVideoInfo) {
        if (currentVideoInfo != null) {
            videoInfo = currentVideoInfo;
        }
        PlayService.videoView = controller;
        ContextCompat.startForegroundService(App.getInstance(), new Intent(App.getInstance(), PlayService.class));
    }

    public static void stop() {
        App.getInstance().stopService(new Intent(App.getInstance(), PlayService.class));
    }

    /**
     * 播放页(切集/换源/播放状态变化)直调:同步锁屏媒体卡标题与通知图标。
     * 替代历史 EventBus RefreshEvent(TYPE_REFRESH_NOTIFY) 广播。
     * 服务未启动时静默跳过(与原无订阅者行为一致);统一 post 到主线程(原 ThreadMode.MAIN 语义)。
     *
     * @param newVideoInfo "标题&&集数",null 表示仅刷新通知(不改标题)
     */
    public static void onPlaybackNotify(String newVideoInfo) {
        PlayService service = sInstance;
        if (service == null || service.mainHandler == null) return;
        service.mainHandler.post(() -> service.applyPlaybackNotify(newVideoInfo));
    }

    private void applyPlaybackNotify(String newVideoInfo) {
        if (newVideoInfo != null) {
            videoInfo = newVideoInfo;
            syncMediaMetadata();
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    private static final String CHANNEL_ID = "MyChannelId";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        sInstance = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "My Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);
        }
        initMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
        // videoView 可能已被界面销毁(静态引用跨生命周期),判空避免 NPE
        if (videoView != null) {
            videoView.start();
        }
        syncMediaMetadata();
        syncPlaybackState();
        mainHandler.removeCallbacks(stateTicker);
        mainHandler.post(stateTicker);
        return START_NOT_STICKY;
    }

    // ── 媒体会话(系统媒体控制中心/锁屏媒体卡)──

    private void initMediaSession() {
        try {
            mediaSession = new MediaSession(this, "MBoxPlayback");
            mediaSession.setSessionActivity(getPendingIntentActivity());
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    if (videoView != null && !videoView.isPlaying()) {
                        videoView.start();
                    }
                    syncPlaybackState();
                }

                @Override
                public void onPause() {
                    if (videoView != null && videoView.isPlaying()) {
                        videoView.pause();
                    }
                    syncPlaybackState();
                }

                @Override
                public void onSkipToNext() {
                    broadcastControl(IntentKey.BROADCAST_ACTION_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    broadcastControl(IntentKey.BROADCAST_ACTION_PREV);
                }

                @Override
                public void onSeekTo(long pos) {
                    if (videoView != null) {
                        videoView.seekTo(pos);
                        syncPlaybackState();
                    }
                }

                @Override
                public void onStop() {
                    broadcastControl(IntentKey.BROADCAST_ACTION_CLOSE);
                }
            }, mainHandler);
            syncMediaMetadata();
        } catch (Throwable th) {
            // 个别 ROM/环境创建失败不阻断前台通知(退化为仅通知按钮控制)
            mediaSession = null;
        }
    }

    /** 把控制指令广播给播放页(与通知按钮/小窗按钮同一通道,由 PipHelper 等消费) */
    private void broadcastControl(int actionCode) {
        try {
            sendBroadcast(new Intent(IntentKey.BROADCAST_ACTION)
                    .putExtra("action", actionCode)
                    .setPackage(getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    private String videoTitle() {
        String t = splitPart(videoInfo, 0);
        return t == null || t.trim().isEmpty() ? "MBox" : t.trim();
    }

    private String videoSubtitle() {
        String e = splitPart(videoInfo, 1);
        return e == null ? "" : e.trim();
    }

    private void syncMediaMetadata() {
        if (mediaSession == null) return;
        try {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, videoTitle())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, videoSubtitle())
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, videoTitle())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "MBox")
                    .build());
        } catch (Throwable th) {
            // 元数据同步失败不影响功能
        }
    }

    /** 轮询共享播放器状态 → 媒体会话 PlaybackState + 通知图标(暂停/播放) */
    private void syncPlaybackState() {
        if (mediaSession == null) return;
        try {
            boolean playing = videoView != null && videoView.isPlaying();
            long position = videoView != null ? videoView.getCurrentPosition() : 0L;
            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SEEK_TO;
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            position, 1.0f)
                    .build());
            if (playing != lastPlaying) {
                lastPlaying = playing;
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable th) {
            // 播放器释放竞态等瞬态,忽略
        }
    }

    // ── 通知(RemoteViews 大/小布局 + 媒体会话关联)──

    private Notification buildNotification() {

        String title = splitPart(videoInfo, 0);
        String episodes = splitPart(videoInfo, 1);
        if (title == null || title.trim().isEmpty()) title = "MBox";
        if (episodes == null) episodes = "";
        boolean playing = videoView != null && videoView.isPlaying();

        // 展开布局
        RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.notification_player);
        remoteViews.setTextViewText(R.id.tv_title, title);
        remoteViews.setTextViewText(R.id.tv_subtitle, "正在播放: " + episodes);
        remoteViews.setImageViewResource(R.id.iv_play_pause, playing ? R.drawable.ic_notify_pause : R.drawable.ic_notify_play);
        // 创建通知栏操作(RemoteViews 大布局按钮)
        remoteViews.setOnClickPendingIntent(R.id.iv_previous, getPendingIntent(IntentKey.BROADCAST_ACTION_PREV));
        remoteViews.setOnClickPendingIntent(R.id.iv_play_pause, getPendingIntent(IntentKey.BROADCAST_ACTION_PLAYPAUSE));
        remoteViews.setOnClickPendingIntent(R.id.iv_next, getPendingIntent(IntentKey.BROADCAST_ACTION_NEXT));
        remoteViews.setOnClickPendingIntent(R.id.iv_close, getPendingIntent(IntentKey.BROADCAST_ACTION_CLOSE));

        // 普通(折叠)布局
        RemoteViews remoteViewsSmall = new RemoteViews(getPackageName(), R.layout.notification_player_small);
        remoteViewsSmall.setTextViewText(R.id.tv_title, title);
        remoteViewsSmall.setTextViewText(R.id.tv_subtitle, "正在播放: " + episodes);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContent(remoteViews)
                .setCustomContentView(remoteViewsSmall)
                .setCustomBigContentView(remoteViews)
                .setContentIntent(getPendingIntentActivity())
                .setOngoing(true)
                // 媒体会话通知类别:系统据此把本通知归类到媒体/控制中心
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(new NotificationCompat.BigTextStyle().bigText("默认展开"));
        return builder.build();
    }

    private PendingIntent getPendingIntentActivity() {
        Intent intent = new Intent(this, DetailActivity.class);
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static PendingIntent getPendingIntent(int actionCode) {
        return PendingIntent.getBroadcast(App.getInstance(), actionCode,
                new Intent(IntentKey.BROADCAST_ACTION).putExtra("action", actionCode)
                        .setPackage(App.getInstance().getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(stateTicker);
        }
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Throwable ignored) {
            }
            mediaSession = null;
        }
        // 只清自己:重建场景下旧实例的 onDestroy 可能晚于新实例的 onCreate。
        // videoView 也必须一并放进这个判断 —— 原来无条件置 null,会把新实例刚注入的共享视图抹掉,
        // 之后通知栏/锁屏控制拿不到视图,播放状态与进度就失灵了。
        if (sInstance == this) {
            sInstance = null;
            videoView = null;
        }
        stopForeground(true);
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
