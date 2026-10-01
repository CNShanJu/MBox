package com.github.tvbox.osc.download.internal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class HlsKeyCheckpointTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Test public void renewedKeyUrlKeepsCheckpointButChangedBytesCannotMix() throws Exception {
        File dir = files.newFolder();
        byte[] old = new byte[16], changed = new byte[16]; changed[0] = 1;
        HlsKeyCheckpoint.verify(dir, "https://cdn.example/key?id=1&auth_key=old", old, false);
        HlsKeyCheckpoint.verify(dir, "https://cdn.example/key?id=1&auth_key=fresh", old, true);
        try { HlsKeyCheckpoint.verify(dir, "https://cdn.example/key?id=1&auth_key=fresh", changed, true); fail(); }
        catch (DownloadErrors.LayoutChanged expected) { }
        HlsKeyCheckpoint.verify(dir, "https://cdn.example/key?id=2&auth_key=fresh", changed, true);
        assertEquals(2, dir.listFiles().length);
    }
    @Test public void errorClassificationUsesDistinctStrategies() {
        assertEquals(DownloadErrors.Kind.NETWORK, DownloadErrors.classify(new java.net.SocketTimeoutException()));
        assertEquals(DownloadErrors.Kind.AUTHENTICATION, DownloadErrors.classify(new DownloadErrors.HttpFailure(403, "分片")));
        assertEquals(DownloadErrors.Kind.STORAGE, DownloadErrors.classify(new java.io.IOException("ENOSPC")));
        assertEquals(DownloadErrors.Kind.SOURCE, DownloadErrors.classify(new DownloadErrors.HttpFailure(404, "分片")));
        assertEquals(DownloadErrors.Kind.MERGE, DownloadErrors.classify(new DownloadErrors.MergeFailure("无法合并", null)));
    }
}
