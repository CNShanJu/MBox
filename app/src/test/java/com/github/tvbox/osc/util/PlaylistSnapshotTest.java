package com.github.tvbox.osc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaylistSnapshotTest {
    private PlaylistSnapshot snapshot(String text) {
        return new PlaylistSnapshot(HlsMediaPlaylist.parse("https://cdn.example/course/media.m3u8", text), 100);
    }
    private String playlist(String query) {
        return "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n#EXT-X-MAP:URI=\"init.mp4?" + query
                + "\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"key?" + query + "\"\n#EXTINF:10,\n1.m4s?"
                + query + "\n#EXTINF:10,\n2.m4s?" + query + "\n#EXT-X-ENDLIST";
    }
    @Test public void tokenRenewalPreservesLayoutAndSequence() {
        PlaylistSnapshot old = snapshot(playlist("auth_key=old")), fresh = snapshot(playlist("auth_key=new"));
        assertTrue(old.canReuse(fresh));
        assertNotEquals(old.requestSignature, fresh.requestSignature);
        assertEquals(42, old.playlist.segments.get(0).mediaSequence);
        assertEquals(43, old.playlist.segments.get(1).mediaSequence);
        assertFalse(old.layoutSignature.contains("auth_key"));
    }
    @Test public void sequencePathIvInitAndDurationChangesRejectReuse() {
        String text = playlist("old");
        PlaylistSnapshot old = snapshot(text);
        for (String changed : new String[]{text.replace(":42", ":43"), text.replace("1.m4s", "other.m4s"),
                text.replace("init.mp4", "init-v2.mp4"), text.replace("METHOD=AES-128", "METHOD=AES-128,IV=0x02"),
                text.replace("#EXTINF:10", "#EXTINF:11"), text.replace("2.m4s", "#EXT-X-DISCONTINUITY\n2.m4s")})
            assertFalse(changed, old.canReuse(snapshot(changed)));
    }
    @Test public void byteRangesCannotMapByArrayIndex() {
        String text = "#EXTM3U\n#EXT-X-BYTERANGE:10@0\nall.ts?old\n#EXT-X-BYTERANGE:10\nall.ts?old";
        assertTrue(snapshot(text).canReuse(snapshot(text.replace("?old", "?fresh"))));
        assertFalse(snapshot(text).canReuse(snapshot(text.replace("10@0", "10@5"))));
        assertFalse(snapshot(text).canReuse(snapshot(text.replace("all.ts", "https://other.example/all.ts"))));
    }
    @Test public void keyIdRotationIsNotMistakenForAuthQueryRenewal() {
        String a = playlist("auth_key=old").replace("key?", "key?id=1&");
        assertTrue(snapshot(a).canReuse(snapshot(a.replace("auth_key=old", "auth_key=fresh"))));
        assertFalse(snapshot(a).canReuse(snapshot(a.replace("id=1", "id=2"))));
    }
}
