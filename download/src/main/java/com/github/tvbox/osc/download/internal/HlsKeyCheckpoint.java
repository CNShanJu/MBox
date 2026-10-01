package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.util.PlaylistSnapshot;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 只落密钥摘要；恢复时避免把同一 URI 下悄悄轮换的 key 与旧分片混用。 */
public final class HlsKeyCheckpoint {
    private HlsKeyCheckpoint() {}
    public static void verify(File directory, String url, byte[] key, boolean hasCompleted) throws IOException {
        File checkpoint = new File(directory, ".keyhash-" + hash(PlaylistSnapshot.keyIdentity(url).getBytes(StandardCharsets.UTF_8)));
        byte[] digest = hash(key).getBytes(StandardCharsets.US_ASCII);
        if (checkpoint.isFile()) {
            byte[] old = new byte[64];
            try (FileInputStream input = new FileInputStream(checkpoint)) {
                int length = input.read(old);
                if (length == 64 && java.util.Arrays.equals(old, digest)) return;
            }
            if (hasCompleted) throw new DownloadErrors.LayoutChanged();
        }
        try (FileOutputStream output = new FileOutputStream(checkpoint)) { output.write(digest); }
    }
    private static String hash(byte[] value) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value))
                out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
