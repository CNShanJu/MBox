package com.github.tvbox.osc.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class ImportFileCheckpointTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void restoresChangedDeletedAndNewFilesAcrossAllTargets() throws Exception {
        File files = temporary.newFolder("files");
        File external = temporary.newFolder("external");
        File work = temporary.newFolder("checkpoint");
        File themes = new File(files, "themes");
        File background = new File(files, "theme_bg");
        File subscriptions = new File(external, "subscription_import");
        assertTrue(themes.mkdir());
        assertTrue(background.mkdir());
        assertTrue(subscriptions.mkdir());
        File oldTheme = new File(themes, "old.json");
        File oldBackground = new File(background, "old.webp");
        File oldSubscription = new File(subscriptions, "saved.json");
        File emptyDirectory = new File(themes, "empty");
        assertTrue(emptyDirectory.mkdir());
        byte[] themeBytes = "原主题".getBytes(StandardCharsets.UTF_8);
        byte[] backgroundBytes = new byte[] { 0, 1, 2, (byte) 255 };
        write(oldTheme, themeBytes);
        write(oldBackground, backgroundBytes);
        write(oldSubscription, "原订阅".getBytes(StandardCharsets.UTF_8));

        ImportFileCheckpoint checkpoint = ImportFileCheckpoint.prepare(work,
                Arrays.asList(themes, background, subscriptions));
        write(oldTheme, "导入主题".getBytes(StandardCharsets.UTF_8));
        write(new File(themes, "new.json"), "新增".getBytes(StandardCharsets.UTF_8));
        assertTrue(oldBackground.delete());
        write(new File(background, "new.webp"), new byte[] { 9 });
        assertTrue(oldSubscription.delete());
        write(new File(subscriptions, "imported.json"), new byte[] { 8 });
        assertTrue(emptyDirectory.delete());

        checkpoint.rollback();
        assertArrayEquals(themeBytes, Files.readAllBytes(oldTheme.toPath()));
        assertArrayEquals(backgroundBytes, Files.readAllBytes(oldBackground.toPath()));
        assertArrayEquals("原订阅".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(oldSubscription.toPath()));
        assertTrue(emptyDirectory.isDirectory());
        assertFalse(new File(themes, "new.json").exists());
        assertFalse(new File(background, "new.webp").exists());
        assertFalse(new File(subscriptions, "imported.json").exists());
        assertTrue(work.exists()); // Other state can still fail; the file snapshot remains.
        checkpoint.cleanup();
        assertFalse(work.exists());
    }

    @Test
    public void originallyMissingDirectoryBecomesMissingAgain() throws Exception {
        File files = temporary.newFolder("files");
        File work = temporary.newFolder("checkpoint");
        File target = new File(files, "themes");
        ImportFileCheckpoint checkpoint = ImportFileCheckpoint.prepare(work,
                Collections.singletonList(target));
        assertTrue(target.mkdir());
        write(new File(target, "imported.json"), new byte[] { 1 });

        checkpoint.rollback();
        assertFalse(target.exists());
        checkpoint.cleanup();
    }

    @Test
    public void emptyTargetListIsValidForNonFileImports() throws Exception {
        File work = temporary.newFolder("checkpoint");
        ImportFileCheckpoint checkpoint = ImportFileCheckpoint.prepare(work, Collections.emptyList());
        checkpoint.rollback();
        assertTrue(work.exists());
        checkpoint.cleanup();
        assertFalse(work.exists());
    }

    @Test
    public void oversizedPrepareFailsWithoutChangingLiveFiles() throws Exception {
        File files = temporary.newFolder("files");
        File work = temporary.newFolder("checkpoint");
        File themes = new File(files, "themes");
        assertTrue(themes.mkdir());
        File old = new File(themes, "old.json");
        byte[] original = "原文件".getBytes(StandardCharsets.UTF_8);
        write(old, original);
        File oversized = new File(themes, "sparse.bin");
        try (RandomAccessFile random = new RandomAccessFile(oversized, "rw")) {
            random.setLength(512L * 1024L * 1024L + 1);
        }

        expectIOException(() -> ImportFileCheckpoint.prepare(work,
                Collections.singletonList(themes)));
        assertArrayEquals(original, Files.readAllBytes(old.toPath()));
        assertTrue(oversized.isFile());
        assertTrue(oversized.length() > 512L * 1024L * 1024L);
        assertFalse(new File(work, "file_checkpoint").exists());
    }

    @Test
    public void rejectsTraversalAndLeavesOriginalUntouched() throws Exception {
        File files = temporary.newFolder("files");
        File work = temporary.newFolder("checkpoint");
        File themes = new File(files, "themes");
        assertTrue(themes.mkdir());
        File old = new File(themes, "old.json");
        write(old, new byte[] { 5 });
        File escaped = new File(new File(files, "elsewhere"), "../themes");

        expectIOException(() -> ImportFileCheckpoint.prepare(work,
                Collections.singletonList(escaped)));
        assertArrayEquals(new byte[] { 5 }, Files.readAllBytes(old.toPath()));
        assertFalse(new File(work, "file_checkpoint").exists());
    }

    @Test
    public void rejectsSymbolicLinkWithoutReadingItsTarget() throws Exception {
        File files = temporary.newFolder("files");
        File outside = temporary.newFolder("outside");
        File work = temporary.newFolder("checkpoint");
        File themes = new File(files, "themes");
        assertTrue(themes.mkdir());
        File secret = new File(outside, "private.json");
        write(secret, new byte[] { 7 });
        try {
            Files.createSymbolicLink(new File(themes, "linked.json").toPath(), secret.toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeNoException(unavailable);
        }

        expectIOException(() -> ImportFileCheckpoint.prepare(work,
                Collections.singletonList(themes)));
        assertArrayEquals(new byte[] { 7 }, Files.readAllBytes(secret.toPath()));
        assertFalse(new File(work, "file_checkpoint").exists());
    }

    @Test
    public void failedRollbackKeepsSnapshotAndCanBeRetried() throws Exception {
        File files = temporary.newFolder("files");
        File work = temporary.newFolder("checkpoint");
        File themes = new File(files, "themes");
        assertTrue(themes.mkdir());
        File original = new File(themes, "old.json");
        write(original, new byte[] { 1 });
        ImportFileCheckpoint checkpoint = ImportFileCheckpoint.prepare(work,
                Collections.singletonList(themes));
        write(original, new byte[] { 2 });
        File backup = new File(work, "file_checkpoint/root_0/old.json");
        File held = new File(work, "held.json");
        assertTrue(backup.renameTo(held));

        expectIOException(checkpoint::rollback);
        assertTrue(work.isDirectory());
        assertArrayEquals(new byte[] { 2 }, Files.readAllBytes(original.toPath()));
        expectIOException(checkpoint::cleanup);
        assertTrue(held.renameTo(backup));
        checkpoint.rollback();
        assertArrayEquals(new byte[] { 1 }, Files.readAllBytes(original.toPath()));
        checkpoint.cleanup();
    }

    @Test
    public void failedSnapshotCopyRemovesStagingWithoutChangingLiveFiles() throws Exception {
        File files = temporary.newFolder("files");
        File work = temporary.newFolder("checkpoint");
        File themes = new File(files, "themes");
        assertTrue(themes.mkdir());
        File live = new File(themes, "old.json");
        write(live, new byte[] { 1 });
        ImportFileCheckpoint checkpoint = ImportFileCheckpoint.prepare(work,
                Collections.singletonList(themes));
        write(live, new byte[] { 2 });
        File backup = new File(work, "file_checkpoint/root_0/old.json");
        assertTrue(backup.delete());

        expectIOException(checkpoint::rollback);

        assertArrayEquals(new byte[] { 2 }, Files.readAllBytes(live.toPath()));
        File[] staging = files.listFiles((dir, name) -> name.startsWith(".themes.restore-"));
        assertNotNull(staging);
        assertEquals(0, staging.length);
        assertTrue(new File(work, "file_checkpoint").isDirectory());
        expectIOException(checkpoint::cleanup);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        Files.write(file.toPath(), bytes);
    }

    private static void expectIOException(ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
