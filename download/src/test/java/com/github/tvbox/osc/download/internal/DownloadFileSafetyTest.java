package com.github.tvbox.osc.download.internal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DownloadFileSafetyTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void hlsKeyRejectsOversizeAfterOnlySeventeenBytes() throws Exception {
        final int[] bytesRead = {0};
        InputStream endless = new InputStream() {
            @Override public int read() {
                bytesRead[0]++;
                return 1;
            }
        };
        try {
            HlsDownloader.readAes128Key(endless);
            fail("Oversized key must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(">16"));
        }
        assertTrue(bytesRead[0] == 17);
    }

    @Test public void hlsKeyRequiresExactlySixteenBytes() throws Exception {
        byte[] key = new byte[16];
        assertArrayEquals(key, HlsDownloader.readAes128Key(new ByteArrayInputStream(key)));
        try {
            HlsDownloader.readAes128Key(new ByteArrayInputStream(new byte[15]));
            fail("Short key must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("15B"));
        }
    }

    @Test public void failedMigrationLeavesSourceIntact() throws Exception {
        File src = files.newFolder("old");
        File dst = files.newFolder("new");
        File sourceSegment = new File(src, "0001.ts");
        write(sourceSegment, "segment");
        assertTrue(new File(dst, "0001.ts").mkdir()); // 文件目标被目录占用，复制必须失败

        try {
            FileCleaner.moveContent(src, dst);
            fail("Conflicting destination must reject migration");
        } catch (IOException expected) {
            assertTrue(sourceSegment.isFile());
            assertArrayEquals("segment".getBytes(StandardCharsets.UTF_8), read(sourceSegment));
        }
    }

    @Test public void successfulMigrationPublishesAllFilesBeforeDeletingSource() throws Exception {
        File src = files.newFolder("old");
        File dst = new File(files.getRoot(), "new");
        File nested = new File(src, "nested");
        assertTrue(nested.mkdir());
        write(new File(nested, "0001.ts"), "segment");

        FileCleaner.moveContent(src, dst);

        assertFalse(src.exists());
        assertArrayEquals("segment".getBytes(StandardCharsets.UTF_8),
                read(new File(dst, "nested/0001.ts")));
    }

    @Test public void posterFailureKeepsExistingFileAndRemovesPart() throws Exception {
        File target = new File(files.getRoot(), "poster.jpg");
        write(target, "existing");
        InputStream broken = new InputStream() {
            private int count;
            @Override public int read() throws IOException {
                if (count++ == 3) throw new IOException("network interrupted");
                return 1;
            }
        };

        try {
            DownloadStore.savePoster(broken, target, -1);
            fail("Interrupted response must not publish poster");
        } catch (IOException expected) {
            assertArrayEquals("existing".getBytes(StandardCharsets.UTF_8), read(target));
            assertFalse(new File(files.getRoot(), "poster.jpg.part").exists());
        }
    }

    @Test public void posterUnknownLengthIsCapped() throws Exception {
        File target = new File(files.getRoot(), "poster.jpg");
        InputStream endless = new InputStream() {
            @Override public int read() { return 1; }
        };

        try {
            DownloadStore.savePoster(endless, target, -1);
            fail("Oversized poster must be rejected");
        } catch (IOException expected) {
            assertFalse(target.exists());
            assertFalse(new File(files.getRoot(), "poster.jpg.part").exists());
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] read(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int done = 0;
            while (done < bytes.length) {
                int count = in.read(bytes, done, bytes.length - done);
                if (count == -1) throw new IOException("Unexpected EOF");
                done += count;
            }
        }
        return bytes;
    }
}
