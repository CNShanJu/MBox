package com.github.tvbox.osc.download.internal;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** 下载会话的有限重试/续期循环；IO、时钟及退避可替换，便于离线验证。 */
public final class HlsSessionRunner {
    public interface IoAction { void run() throws IOException; }
    public interface Delay { void waitFor(long millis) throws IOException; }
    private final HlsRenewalPolicy policy = new HlsRenewalPolicy();
    private final IoAction refresh;
    private final BooleanSupplier stopped;
    private final Delay delay;

    public HlsSessionRunner(IoAction refresh, BooleanSupplier stopped, Delay delay) {
        this.refresh = refresh; this.stopped = stopped; this.delay = delay;
    }
    public void run(int segmentIndex, IoAction action) throws IOException {
        int retries = 0;
        boolean refreshed = false;
        while (!stopped.getAsBoolean()) {
            try { action.run(); return; }
            catch (IOException error) {
                if (stopped.getAsBoolean()) throw error;
                int code = DownloadErrors.httpCode(error);
                HlsRenewalPolicy.Action next = policy.onFailure(code, retries, refreshed);
                switch (next) {
                    case RETRY: retries++; delay.waitFor(retries * 1000L); break;
                    case REFRESH:
                        refresh.run();
                        refreshed = true;
                        break;
                    case GONE:
                        throw new DownloadErrors.SegmentGoneException(segmentIndex,
                                "清单续期后仍缺片（HTTP " + code + "，" + DownloadErrors.SEGMENT_GONE_TEXT + "）");
                    case PAUSE: throw new DownloadErrors.SessionExpired();
                    default: throw error;
                }
            }
        }
        throw new IOException("任务已停止");
    }
}
