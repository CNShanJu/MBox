package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

public class UpdatePartialSourceStoreTest {
    private static final String MIRROR = "https://gitee.com/release.apk";
    private static final String DIRECT = "https://github.com/release.apk";

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void validPartialRestoresOnlyTheSourceThatProducedIt() throws Exception {
        File apk = temp.newFile("MBox.apk");
        writeBytes(apk, 512);
        UpdateInfo original = info("v4.0", 1024, MIRROR, DIRECT);
        UpdatePartialSourceStore.Record saved = UpdatePartialSourceStore.record(apk, original, MIRROR);

        assertEquals(MIRROR, UpdatePartialSourceStore.matchingSource(
                UpdatePartialSourceStore.decode(UpdatePartialSourceStore.encode(saved)), apk, original));
        // A changed candidate order is fine when the original URL remains available.
        assertEquals(MIRROR, UpdatePartialSourceStore.matchingSource(saved, apk,
                info("v4.0", 1024, DIRECT, MIRROR)));
    }

    @Test
    public void staleMetadataCannotAuthorizeAppendingToAnotherApk() throws Exception {
        File apk = temp.newFile("MBox.apk");
        File other = temp.newFile("other.apk");
        writeBytes(apk, 512);
        writeBytes(other, 512);
        UpdateInfo original = info("v4.0", 1024, MIRROR, DIRECT);
        UpdatePartialSourceStore.Record saved = UpdatePartialSourceStore.record(apk, original, MIRROR);

        assertNull(UpdatePartialSourceStore.matchingSource(saved, other, original));
        assertNull(UpdatePartialSourceStore.matchingSource(saved, apk, info("v4.1", 1024, MIRROR)));
        assertNull(UpdatePartialSourceStore.matchingSource(saved, apk, info("v4.0", 2048, MIRROR)));
        assertNull(UpdatePartialSourceStore.matchingSource(saved, apk, info("v4.0", 1024, DIRECT)));
        assertNull(UpdatePartialSourceStore.record(apk, original, "https://unknown.example/apk"));
    }

    @Test
    public void emptyOversizedAndMalformedPartialsAreRejected() throws Exception {
        File apk = temp.newFile("MBox.apk");
        UpdateInfo info = info("v4.0", 1024, MIRROR);
        UpdatePartialSourceStore.Record saved = UpdatePartialSourceStore.record(apk, info, MIRROR);

        assertNull(UpdatePartialSourceStore.matchingSource(saved, apk, info));
        writeBytes(apk, 1025);
        assertNull(UpdatePartialSourceStore.matchingSource(saved, apk, info));
        assertNull(UpdatePartialSourceStore.decode("{broken"));
        assertNull(UpdatePartialSourceStore.decode(null));
    }

    private static UpdateInfo info(String tag, long size, String... urls) {
        return new UpdateInfo(tag.substring(1), tag, -1, Arrays.asList(urls),
                "MBox.apk", size, "");
    }

    private static void writeBytes(File file, int size) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[size]);
        }
    }
}
