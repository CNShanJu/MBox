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

import java.lang.ref.WeakReference;

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
    /**
     * 共享播放视图:后台播放期间服务只"借用"它来响应通知栏/媒体卡控制,用弱引用持有。
     *
     * <p>原来用静态强引用,会把承载该视图的 Activity 一直钉在进程里 —— 后台播放中 Activity
     * 被回收后,通知栏按钮操作的其实是一张已经死掉的视图(既漏内存又行为错乱)。
     * 弱引用只保证"视图还活着时能用",不再由服务决定 Activity 的存活;
     * 视图被回收后控制指令安全空转(见 {@link #currentVideoView()} 的调用点),
     * 播放页回到前台时会重新注入。
     */
    private static WeakReference<MyVideoView> videoViewRef = new WeakReference<>(null);
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
        videoViewRef = new WeakReference<>(controller);
        ContextCompat.startForegroundService(App.getInstance(), new Intent(App.getInstance(), PlayService.class));
    }

    public static void stop() {
        App.getInstance().stopService(new Intent(App.getInstance(), PlayService.class));
    }

    /**
     * 播放宿主 Activity 销毁时直调:只解除服务对视图的借用,<b>不</b>停服务
     * (后台播放是用户显式开启的能力,不能把"宿主销毁"等同于"停止播放")。
     *
     * <p>弱引用已能保证不泄漏,这里再做一次显式摘除,是为了让"视图已死"立刻生效:
     * 通知栏/媒体卡的控制指令无需等 GC 就会安全空转,而不是打到一张正在销毁的视图上。
     */
    public static void onHostDestroyed(android.content.Context host) {
        MyVideoView current = videoViewRef.get();
        if (current == null) {
            return;
        }
        // 只摘自己这张:重进播放页的实例可能已经注入新视图,别把新的抹掉
        if (current.getContext() == host) {
            videoViewRef.clear();
        }
    }

    /** 取当前可用的共享视图;已被回收时为 null(调用方必须按 null 安全处理,控制指令空转) */
    private static MyVideoView currentVideoView() {
        return videoViewRef.get();
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
        // 视图可能已被回收/宿主已销毁(弱引用跨生命周期),判空避免 NPE
        MyVideoView videoView = currentVideoView();
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
                    MyVideoView videoView = currentVideoView();
                    if (videoView == null) {
                        // 视图已随宿主销毁:通知栏/锁屏按钮安全空转并留痕,不再操作死视图
                        logViewGone("播放");
                        return;
                    }
                    if (!videoView.isPlaying()) {
                        videoView.start();
                    }
                    syncPlaybackState();
                }

                @Override
                public void onPause() {
                    MyVideoView videoView = currentVideoView();
                    if (videoView == null) {
                        logViewGone("暂停");
                        return;
                    }
                    if (videoView.isPlaying()) {
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
                    MyVideoView videoView = currentVideoView();
                    if (videoView == null) {
                        logViewGone("拖动进度");
                        return;
                    }
                    videoView.seekTo(pos);
                    syncPlaybackState();
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

    /** 视图已被回收时的空转留痕:说明"点了但没生效"是视图没了,而不是播放器坏了 */
    private static void logViewGone(String action) {
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER,
                "后台播放控制[" + action + "]忽略: 共享播放视图已随宿主销毁");
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
            // 视图已被回收时按"未播放/进度 0"上报,不额外打日志(轮询会反复触发,避免刷屏)
            MyVideoView videoView = currentVideoView();
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
        // 视图已被回收时按"暂停"显示:通知仍可见(服务还在),但不再假装能控制播放
        MyVideoView videoView = currentVideoView();
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
        // 视图引用也必须一并放进这个判断 —— 原来无条件置 null,会把新实例刚注入的共享视图抹掉,
        // 之后通知栏/锁屏控制拿不到视图,播放状态与进度就失灵了。
        if (sInstance == this) {
            sInstance = null;
            videoViewRef.clear();
        }
        stopForeground(true);
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
