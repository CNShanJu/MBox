package com.github.tvbox.osc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 局域网配对令牌的应用私有存储；私有键不进入配置导入导出。 */
public final class LanSessionConfig {
    private static final String KEY = "_private_lan_sessions";

    public static final class Record {
        public String id;
        public String token;
        public String name;
        public String ip;
        public String kind;
        public long connectedAt;
        public long lastSeen;

        public Record(String id, String token, String name, String ip, String kind,
                      long connectedAt, long lastSeen) {
            this.id = id;
            this.token = token;
            this.name = name;
            this.ip = ip;
            this.kind = kind;
            this.connectedAt = connectedAt;
            this.lastSeen = lastSeen;
        }
    }

    private static final class Snapshot {
        String pairingCode;
        List<Record> sessions;

        Snapshot(String pairingCode, List<Record> sessions) {
            this.pairingCode = pairingCode;
            this.sessions = sessions;
        }
    }

    private LanSessionConfig() { }

    public static List<Record> load(String pairingCode) {
        Snapshot saved = PrefsDataStore.getJson(KEY, Snapshot.class, null);
        if (saved == null || pairingCode == null || !pairingCode.equals(saved.pairingCode)
                || saved.sessions == null) return Collections.emptyList();
        return new ArrayList<>(saved.sessions);
    }

    public static void save(String pairingCode, List<Record> sessions) {
        if (pairingCode == null || sessions == null || sessions.isEmpty()) {
            clear();
            return;
        }
        PrefsDataStore.putJson(KEY, new Snapshot(pairingCode, new ArrayList<>(sessions)));
    }

    public static void clear() { PrefsDataStore.delete(KEY); }
}
