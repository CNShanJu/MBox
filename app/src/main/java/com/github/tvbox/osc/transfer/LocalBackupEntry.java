package com.github.tvbox.osc.transfer;

/** Immutable backup row; storage paths and deletion rules stay in the repository. */
public final class LocalBackupEntry {
    private final String key;
    private final String name;
    private final boolean system;

    LocalBackupEntry(String key, String name, boolean system) {
        this.key = key;
        this.name = name;
        this.system = system;
    }

    public String key() { return key; }
    public String name() { return name; }
    public boolean isSystem() { return system; }
}
