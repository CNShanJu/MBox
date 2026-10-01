package com.github.tvbox.osc.download.internal;

/** 每个会话的续期预算；401/403 立即刷新，404/410 最多短重试两次后刷新一次。 */
public final class HlsRenewalPolicy {
    public enum Action { RETRY, REFRESH, GONE, PAUSE, PROPAGATE }
    public Action onFailure(int code, int shortRetries, boolean alreadyRefreshed) {
        if (code != 401 && code != 403 && code != 404 && code != 410) return Action.PROPAGATE;
        if ((code == 404 || code == 410) && shortRetries < 2 && !alreadyRefreshed) return Action.RETRY;
        if (alreadyRefreshed) return code == 401 || code == 403 ? Action.PAUSE : Action.GONE;
        return Action.REFRESH;
    }
}
