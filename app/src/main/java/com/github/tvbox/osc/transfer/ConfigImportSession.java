package com.github.tvbox.osc.transfer;

import android.os.Handler;
import android.os.Looper;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** 一次配置导入连接由模块持有，页面与抽屉销毁不会丢失配对会话。 */
public final class ConfigImportSession {
    public enum State { DISCONNECTED, CONNECTED, UNREACHABLE, KICKED }
    public interface Listener { void onChanged(); }
    public interface ImportCallback { void onComplete(String result, String error); }

    private static final ConfigImportSession INSTANCE = new ConfigImportSession();
    private static final long CHECK_INTERVAL_MS = 6000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private ConfigImportSource source;
    private String address = "";
    private State state = State.DISCONNECTED;
    private int epoch;

    private final Runnable monitor = () -> {
        ConfigImportSource candidate = source;
        int request = epoch;
        if (candidate == null) return;
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            State checked;
            try { checked = candidate.isConnected() ? State.CONNECTED : State.KICKED; }
            catch (Exception ignored) { checked = State.UNREACHABLE; }
            State result = checked;
            main.post(() -> {
                if (request != epoch || source != candidate) return;
                if (result == State.KICKED) {
                    source = null;
                    epoch++;
                    state = State.KICKED;
                    emit();
                    return;
                }
                if (state != result) { state = result; emit(); }
                scheduleNextCheck();
            });
        });
    };

    private ConfigImportSession() { }

    public static ConfigImportSession get() { return INSTANCE; }

    /** 调用方在主线程提交成功配对的来源；监测工作使用模块共享执行器。 */
    public void connect(ConfigImportSource connected, String serverAddress) {
        main.removeCallbacks(monitor);
        epoch++;
        source = connected;
        address = serverAddress == null ? "" : serverAddress.trim();
        state = State.CONNECTED;
        emit();
        scheduleNextCheck();
    }

    public State state() { return state; }
    public String address() { return address; }
    public boolean hasConnection() { return source != null; }
    public JsonObject catalog() { return source == null ? null : source.catalog(); }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void importSelected(Set<String> categories, ImportCallback callback) {
        ConfigImportSource candidate = source;
        int request = epoch;
        if (candidate == null) {
            callback.onComplete(null, "连接已断开，请重新连接服务器");
            return;
        }
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            String result = null;
            String error = null;
            try { result = candidate.importSelected(categories); }
            catch (Exception failure) { error = failure.getMessage(); }
            String completed = result;
            String message = error;
            main.post(() -> {
                if (request != epoch || source != candidate) {
                    callback.onComplete(null, "连接已失效，请重新连接服务器");
                } else {
                    callback.onComplete(completed, message);
                    if (message != null) main.removeCallbacks(monitor);
                    if (message != null) main.post(monitor);
                }
            });
        });
    }

    private void emit() {
        for (Listener listener : listeners) listener.onChanged();
    }

    private void scheduleNextCheck() {
        main.postDelayed(monitor, CHECK_INTERVAL_MS);
    }
}
