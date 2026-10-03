package com.github.tvbox.osc.config;

import com.google.gson.annotations.SerializedName;

/** A receiver explicitly connected by address, rechecked before reuse on a LAN. */
public final class CastReceiverConfig {
    private static final String KEY = "cast_last_manual_receiver";

    private CastReceiverConfig() { }

    public static final class Remembered {
        public final String address;
        public final String deviceId;

        public Remembered(String address, String deviceId) {
            this.address = address;
            this.deviceId = deviceId;
        }
    }

    private static final class Snapshot {
        @SerializedName("address") String address;
        @SerializedName("deviceId") String deviceId;

        Snapshot(String address, String deviceId) {
            this.address = address;
            this.deviceId = deviceId;
        }
    }

    public static Remembered lastManual() {
        Snapshot saved = PrefsDataStore.getJson(KEY, Snapshot.class, null);
        return saved != null && saved.address != null && saved.deviceId != null
                ? new Remembered(saved.address, saved.deviceId) : null;
    }

    public static void rememberManual(String address, String deviceId) {
        if (address == null || address.length() > 160 || deviceId == null
                || deviceId.isEmpty() || deviceId.length() > 256) return;
        PrefsDataStore.putJson(KEY, new Snapshot(address, deviceId));
    }
}
