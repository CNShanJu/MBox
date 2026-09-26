package com.github.tvbox.osc.player.api;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 播放会话注册表（roadmap 2.1 playback 会话层原型）。
 * <p>
 * 一个"会话"把引擎无关的 {@link PlayerApi} 与一个业务键(如 sourceKey|vodId|playFlag|index)
 * 绑定，供 UI/小窗/后台播放按键取回、统一观察与释放；本类只做登记+转发，不持有内核细节。
 * <ul>
 *   <li>{@link #bind(String, PlayerApi, boolean)}：登记会话(可同时挂一个观察者)；owns=false 表示
 *       释放会话时不释放内核(适配器共享外部持有的 VideoView 等场景)，默认 false。</li>
 *   <li>{@link #observe(String, PlayListener)}：给已有会话追加观察者(进度/状态/错误统一落日志与回调)。</li>
 *   <li>{@link #unbind(String, boolean)}：解除登记；release 参数控制是否顺带释放内核。</li>
 *   <li>会话内建日志：bind/unbind/state/error 会写 {@link Category#PLAYER} 与 logcat，方便链路排查。</li>
 * </ul>
 * 说明：这是原型层，PlayFragment 当前仍由 mVideoView/Controller 直接驱动；
 * 本类先承载"会话键 + 观察 + 生命周期"骨架，收敛到 session.play/pause/observe 属后续真机回归项。
 */
public final class PlaybackSessions {

    private static final String TAG = "PlaybackSession";

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private PlaybackSessions() {
    }

    /** 单个播放会话：封装键 + PlayerApi + 观察者集合 */
    public static final class Session {
        public final String key;
        public final PlayerApi api;
        public final boolean ownsApi;
        private final List<PlayListener> observers = new CopyOnWriteArrayList<>();
        private final PlayListener forwarder = new PlayListener() {
            @Override
            public void onStateChanged(PlayState state) {
                for (PlayListener l : observers) {
                    try {
                        l.onStateChanged(state);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onProgress(long positionMs, long durationMs, int bufferedPercent) {
                for (PlayListener l : observers) {
                    try {
                        l.onProgress(positionMs, durationMs, bufferedPercent);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onBufferingStart() {
                for (PlayListener l : observers) {
                    try {
                        l.onBufferingStart();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onBufferingEnd() {
                for (PlayListener l : observers) {
                    try {
                        l.onBufferingEnd();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onError(int code, String message) {
                LogStore.log(Category.PLAYER, "会话[" + key + "] 播放错误 code=" + code + " msg=" + message);
                for (PlayListener l : observers) {
                    try {
                        l.onError(code, message);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onCompletion() {
                for (PlayListener l : observers) {
                    try {
                        l.onCompletion();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(int width, int height) {
                for (PlayListener l : observers) {
                    try {
                        l.onVideoSizeChanged(width, height);
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        Session(String key, PlayerApi api, boolean ownsApi) {
            this.key = key;
            this.api = api;
            this.ownsApi = ownsApi;
        }

        void attach() {
            if (api != null) api.subscribe(forwarder);
        }

        void detach() {
            if (api != null) api.unsubscribe(forwarder);
        }

        /** 追加观察者（去重） */
        public void observe(PlayListener listener) {
            if (listener != null && !observers.contains(listener)) {
                observers.add(listener);
            }
        }

        /** 移除观察者 */
        public void unobserve(PlayListener listener) {
            observers.remove(listener);
        }

        /** 会话内是否有观察者（供进度轮询按需开关） */
        public boolean hasObservers() {
            return !observers.isEmpty();
        }

        /** 会话当前播放状态（委托内核） */
        public PlayState state() {
            return api == null ? PlayState.IDLE : api.getState();
        }

        public void play() {
            if (api != null) api.play();
        }

        public void pause() {
            if (api != null) api.pause();
        }

        public void seekTo(long positionMs) {
            if (api != null) api.seekTo(positionMs);
        }

        public void setSpeed(float speed) {
            if (api != null) api.setSpeed(speed);
        }

        public void setScale(int scaleType) {
            if (api != null) api.setScale(scaleType);
        }
    }

    /** 登记会话；owns=false 时 {@link #unbind} 不释放内核（默认安全值） */
    public static Session bind(String key, PlayerApi api, boolean owns) {
        if (key == null) return null;
        // 同键先解除旧会话，避免残留监听/双驱动
        unbind(key, true);
        Session s = new Session(key, api, owns);
        s.attach();
        sessions.put(key, s);
        LogStore.log(Category.PLAYER, "播放会话 bind: " + key + (owns ? " (own)" : " (shared)"));
        android.util.Log.d(TAG, "bind key=" + key + " owns=" + owns);
        return s;
    }

    /** 按键取会话；无则 null */
    public static Session get(String key) {
        return key == null ? null : sessions.get(key);
    }

    /** 给会话追加观察者（会话不存在则忽略并记日志） */
    public static void observe(String key, PlayListener listener) {
        Session s = get(key);
        if (s == null) {
            android.util.Log.w(TAG, "observe 会话不存在: " + key);
            return;
        }
        s.observe(listener);
    }

    /**
     * 解除登记。
     *
     * @param release 是否顺带释放内核（仅当 bind 时 owns=true 且此处 release=true 才真正 release）
     */
    public static void unbind(String key, boolean release) {
        if (key == null) return;
        Session s = sessions.remove(key);
        if (s == null) return;
        s.detach();
        if (release && s.ownsApi && s.api != null) {
            try {
                s.api.release();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        LogStore.log(Category.PLAYER, "播放会话 unbind: " + key + " release=" + (release && s.ownsApi));
        android.util.Log.d(TAG, "unbind key=" + key + " release=" + (release && s.ownsApi));
    }

    /** 解除登记且释放内核（仅当 owns=true） */
    public static void release(String key) {
        unbind(key, true);
    }

    /** 当前活动会话数（调试用） */
    public static int activeCount() {
        return sessions.size();
    }
}
