package com.github.tvbox.osc.util.player;

/** Rules for deciding whether a fetched HLS playlist can be served as a fixed local snapshot. */
public final class HlsPlaybackPolicy {
    private HlsPlaybackPolicy() {
    }

    public static boolean isMaster(String playlist) {
        if (playlist == null) return false;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")
                    || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    || line.startsWith("#EXT-X-MEDIA:")) return true;
        }
        return false;
    }

    public static boolean isRefreshingMedia(String playlist) {
        if (playlist == null || isMaster(playlist)) return false;
        boolean media = false;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-ENDLIST")) return false;
            if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-TARGETDURATION:")
                    || line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) media = true;
        }
        return media;
    }
}
