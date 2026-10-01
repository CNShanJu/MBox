package com.github.tvbox.osc.download;

/** 入队结果；排队成功不等同于地址已经解析或文件已经下载。 */
public final class EnqueueResult {
    public enum Code {
        ENQUEUED, DUPLICATE, ALREADY_DOWNLOADED, PERMISSION_DENIED,
        RESOLVE_FAILED, NO_SPACE, STORAGE_ERROR, INVALID_REQUEST
    }
    public final Code code;
    public final String message;
    public final String taskId;

    private EnqueueResult(Code code, String message, String taskId) {
        this.code = code;
        this.message = message;
        this.taskId = taskId;
    }
    public static EnqueueResult of(Code code, String message) {
        return new EnqueueResult(code, message, null);
    }
    public static EnqueueResult queued(String taskId, String message) {
        return new EnqueueResult(Code.ENQUEUED, message, taskId);
    }
    public boolean isQueued() { return code == Code.ENQUEUED; }
}
