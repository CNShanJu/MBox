package com.github.tvbox.osc.transfer;

/**
 * 本地备份列表项。
 *
 * <p>两套名字要分开：{@link #fileName()} 是磁盘上真实文件名（内容摘要，用于定位与去重），
 * {@link #name()} 是展示给用户看的名字（用户起的，或按时间兜底）。改名字只动展示名，
 * 文件本身不改名 —— 所以不能用展示名去拼路径。
 */
public final class LocalBackupEntry {
    private final String fileName;
    private final String name;
    private final boolean system;
    private final long createdAt;

    LocalBackupEntry(String fileName, String name, boolean system, long createdAt) {
        this.fileName = fileName;
        this.name = name;
        this.system = system;
        this.createdAt = createdAt;
    }

    /** 仓储层用的路径标识：目录前缀 + 真实文件名，由 {@link #fileName()} 派生，不会与展示名混淆。 */
    public String key() { return (system ? "system/" : "manual/") + fileName; }

    public String fileName() { return fileName; }
    public String name() { return name; }
    public boolean isSystem() { return system; }
    public long createdAt() { return createdAt; }
}
