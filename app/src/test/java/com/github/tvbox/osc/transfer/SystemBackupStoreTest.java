package com.github.tvbox.osc.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SystemBackupStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void contentHashIgnoresManifestZipTimesOrderAndPreferenceKeyOrder() throws Exception {
        File first = zip("first.zip", "{\"b\":2,\"a\":1}", new byte[] { 1, 2, 3 },
                "{\"createdAt\":1}", 1_000L, false);
        File second = zip("second.zip", "{\"a\":1,\"b\":2}", new byte[] { 1, 2, 3 },
                "{\"createdAt\":999999}", 123_456_000L, true);
        assertEquals(BackupContentHash.sha256(first), BackupContentHash.sha256(second));
        assertNotEquals(BackupContentHash.archiveSha256(first), BackupContentHash.archiveSha256(second));

        File changedPrefs = zip("changed-prefs.zip", "{\"a\":1,\"b\":3}",
                new byte[] { 1, 2, 3 }, "{}", 1_000L, false);
        File changedRoom = zip("changed-room.zip", "{\"a\":1,\"b\":2}",
                new byte[] { 1, 2, 4 }, "{}", 1_000L, false);
        assertNotEquals(BackupContentHash.sha256(first), BackupContentHash.sha256(changedPrefs));
        assertNotEquals(BackupContentHash.sha256(first), BackupContentHash.sha256(changedRoom));
    }

    @Test
    public void deduplicatesAcrossInstancesAndRefreshesProtectionTime() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        File first = zip("first.zip", "{\"b\":2,\"a\":1}", new byte[] { 4 }, "{\"createdAt\":1}", 1_000L, false);
        File same = zip("same.zip", "{\"a\":1,\"b\":2}", new byte[] { 4 }, "{\"createdAt\":2}", 2_000L, true);
        long at = 1_800_000_000_000L;
        SystemBackupStore original = new SystemBackupStore(directory);
        File saved = original.ensureSnapshot(first, at);
        SystemBackupStore restarted = new SystemBackupStore(directory);
        File reused = restarted.ensureSnapshot(same, at + SystemBackupStore.RETENTION_MILLIS - 1);

        assertEquals(saved, reused);
        assertEquals(1, restarted.list().size());
        assertTrue(restarted.isProtected(saved));
        assertEquals(0, original.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
        assertTrue(saved.isFile());
        assertEquals(1, restarted.cleanupExpired(at + 2 * SystemBackupStore.RETENTION_MILLIS - 1));
        assertFalse(saved.exists());
    }

    @Test
    public void sameMinuteDifferentPayloadGetsNumberedNames() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        SystemBackupStore store = new SystemBackupStore(directory);
        File first = zip("first.zip", "{\"value\":1}", null, "{}", 1_000L, false);
        File second = zip("second.zip", "{\"value\":2}", null, "{}", 1_000L, false);
        File third = zip("third.zip", "{\"value\":3}", null, "{}", 1_000L, false);
        long at = 1_800_000_000_000L;
        File savedFirst = store.ensureSnapshot(first, at);
        File savedSecond = store.ensureSnapshot(second, at + 1_000L);
        File savedThird = store.ensureSnapshot(third, at + 2_000L);

        assertNotEquals(savedFirst, savedSecond);
        String minute = new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.ROOT).format(new Date(at));
        assertEquals(minute + "-临时备份数据.zip", savedFirst.getName());
        assertEquals(minute + "-2-临时备份数据.zip", savedSecond.getName());
        assertEquals(minute + "-3-临时备份数据.zip", savedThird.getName());
        assertEquals(3, store.list().size());
    }

    @Test
    public void listenerDistinguishesCreatedReusedAndConsumedSnapshots() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        RecordingListener listener = new RecordingListener();
        SystemBackupStore store = new SystemBackupStore(directory, listener);
        File first = zip("first.zip", "{\"b\":2,\"a\":1}", null,
                "{\"createdAt\":1}", 1_000L, false);
        File same = zip("same.zip", "{\"a\":1,\"b\":2}", null,
                "{\"createdAt\":2}", 2_000L, true);
        long at = 1_800_000_000_000L;

        File saved = store.ensureSnapshot(first, at);
        assertEquals(saved, store.ensureSnapshot(same, at + 1_000L));
        assertEquals(1, listener.created.size());
        assertEquals(saved, listener.created.get(0));
        assertEquals(1, listener.reused.size());
        assertEquals(saved, listener.reused.get(0));

        assertTrue(store.deleteConsumed(saved));
        assertEquals(1, listener.consumedDeleted.size());
        assertEquals(saved, listener.consumedDeleted.get(0));
        assertFalse(store.deleteConsumed(saved));
        assertEquals(1, listener.consumedDeleted.size());
        assertTrue(listener.expiredDeleted.isEmpty());
        assertTrue(listener.expiredFailed.isEmpty());
    }

    @Test
    public void expiryReportsEachSuccessBeforeAFollowingDeletionFails() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        RecordingListener listener = new RecordingListener();
        SystemBackupStore store = new SystemBackupStore(directory, listener);
        long at = 1_800_000_000_000L;
        File first = store.ensureSnapshot(zip("first.zip", "{\"value\":1}", null,
                "{}", 1_000L, false), at);
        File second = store.ensureSnapshot(zip("second.zip", "{\"value\":2}", null,
                "{}", 1_000L, false), at);
        listener.afterFirstExpiredDeletion = () -> {
            File remaining = first.equals(listener.expiredDeleted.get(0)) ? second : first;
            assertTrue(remaining.delete());
            assertTrue(remaining.mkdir());
            assertTrue(new File(remaining, "prevent-delete").mkdir());
        };

        IOException failure = assertThrows(IOException.class,
                () -> store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
        assertEquals(1, listener.expiredDeleted.size());
        assertEquals(1, listener.expiredFailed.size());
        File deleted = listener.expiredDeleted.get(0);
        File failed = listener.expiredFailed.get(0);
        assertNotEquals(deleted, failed);
        assertEquals(first.equals(deleted) ? second : first, failed);
        assertTrue(failure.getMessage().contains(failed.getName()));
        assertEquals(failure, listener.expiredErrors.get(0));
        assertFalse(deleted.exists());
        assertFalse(new File(directory, deleted.getName() + ".meta").exists());
        assertTrue(listener.created.contains(deleted));
        assertTrue(listener.created.contains(failed));
        assertTrue(listener.consumedDeleted.isEmpty());
    }

    @Test
    public void expiredButNotYetCleanedMatchingSnapshotIsRenewed() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        SystemBackupStore store = new SystemBackupStore(directory);
        File first = zip("first.zip", "{\"value\":1}", null, "{\"createdAt\":1}", 1_000L, false);
        File same = zip("same.zip", "{\"value\":1}", null, "{\"createdAt\":2}", 2_000L, true);
        long at = 1_800_000_000_000L;
        File saved = store.ensureSnapshot(first, at);

        assertEquals(saved, store.ensureSnapshot(same, at + SystemBackupStore.RETENTION_MILLIS));
        assertEquals(0, store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
        assertEquals(1, store.cleanupExpired(at + 2 * SystemBackupStore.RETENTION_MILLIS));
    }

    @Test
    public void rawArchiveHashRejectsSameSizeTamperingBeforeUse() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        SystemBackupStore store = new SystemBackupStore(directory);
        File candidate = zip("candidate.zip", "{\"value\":1}", null, "{}", 1_000L, false);
        long at = 1_800_000_000_000L;
        File saved = store.ensureSnapshot(candidate, at);
        File ordinary = new File(directory, "2030-01-01-01-01-临时备份数据.zip");
        Files.write(ordinary.toPath(), new byte[] { 7 });
        try (RandomAccessFile changed = new RandomAccessFile(saved, "rw")) {
            changed.seek(0);
            changed.writeByte(0);
        }

        assertFalse(store.isProtected(saved));
        assertThrows(IOException.class, () -> store.acquire(saved));
        assertTrue(saved.isFile());
        assertEquals(1, store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
        assertFalse(saved.exists());
        assertTrue(ordinary.isFile());
    }

    @Test
    public void truncatedRegisteredSnapshotStillExpiresAtOneHour() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        SystemBackupStore store = new SystemBackupStore(directory);
        File candidate = zip("candidate.zip", "{\"value\":1}", null, "{}", 1_000L, false);
        long at = 1_800_000_000_000L;
        File saved = store.ensureSnapshot(candidate, at);
        File ordinary = new File(directory, "2030-01-01-01-01-临时备份数据.zip");
        Files.write(ordinary.toPath(), new byte[] { 7 });
        try (RandomAccessFile changed = new RandomAccessFile(saved, "rw")) {
            changed.setLength(Math.max(1L, changed.length() / 2L));
        }

        assertFalse(store.isProtected(saved));
        assertEquals(0, store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS - 1));
        assertEquals(1, store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
        assertFalse(saved.exists());
        assertTrue(ordinary.isFile());
    }

    @Test
    public void expiryBoundaryLeavesUnregisteredLookalikeUntouched() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        SystemBackupStore store = new SystemBackupStore(directory);
        File candidate = zip("candidate.zip", "{\"value\":1}", null, "{}", 1_000L, false);
        long at = 1_800_000_000_000L;
        File saved = store.ensureSnapshot(candidate, at);
        File lookalike = new File(directory, "2030-01-01-01-01-临时备份数据.zip");
        Files.write(lookalike.toPath(), new byte[] { 1 });

        assertFalse(store.isProtected(lookalike));
        assertEquals(0, store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS - 1));
        assertTrue(saved.isFile());
        assertEquals(1, store.cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
        assertFalse(saved.exists());
        assertTrue(lookalike.isFile());
    }

    @Test
    public void leasePreventsCleanupAndAllowsSuccessfulConsumption() throws Exception {
        File directory = temporary.newFolder("system_restore_backups");
        SystemBackupStore store = new SystemBackupStore(directory);
        File candidate = zip("candidate.zip", "{\"value\":1}", new byte[] { 5 }, "{}", 1_000L, false);
        long at = 1_800_000_000_000L;
        File saved = store.ensureSnapshot(candidate, at);

        try (SystemBackupStore.Lease ignored = store.acquire(saved)) {
            assertEquals(0, new SystemBackupStore(directory)
                    .cleanupExpired(at + SystemBackupStore.RETENTION_MILLIS));
            assertTrue(saved.isFile());
            assertTrue(store.deleteConsumed(saved));
            assertFalse(store.isProtected(saved));
        }
        assertFalse(saved.exists());
        assertFalse(store.deleteConsumed(saved));
        assertEquals(0, store.list().size());
    }

    private File zip(String name, String prefs, byte[] room, String manifest,
                     long entryTime, boolean reverseOrder) throws IOException {
        File file = temporary.newFile(name);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            if (reverseOrder) {
                entry(output, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8), entryTime);
                if (room != null) entry(output, "room.db", room, entryTime);
                entry(output, "prefs.json", prefs.getBytes(StandardCharsets.UTF_8), entryTime);
            } else {
                entry(output, "prefs.json", prefs.getBytes(StandardCharsets.UTF_8), entryTime);
                if (room != null) entry(output, "room.db", room, entryTime);
                entry(output, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8), entryTime);
            }
        }
        return file;
    }

    private static void entry(ZipOutputStream output, String name, byte[] data, long time)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);
        output.putNextEntry(entry);
        output.write(data);
        output.closeEntry();
    }

    private static final class RecordingListener implements SystemBackupStore.OperationListener {
        final List<File> created = new ArrayList<>();
        final List<File> reused = new ArrayList<>();
        final List<File> consumedDeleted = new ArrayList<>();
        final List<File> expiredDeleted = new ArrayList<>();
        final List<File> expiredFailed = new ArrayList<>();
        final List<IOException> expiredErrors = new ArrayList<>();
        Runnable afterFirstExpiredDeletion;

        @Override public void onSnapshotCreated(File file) { created.add(file); }
        @Override public void onSnapshotReused(File file) { reused.add(file); }

        @Override public void onSnapshotDeleted(File file, SystemBackupStore.DeleteReason reason) {
            if (reason == SystemBackupStore.DeleteReason.CONSUMED) {
                consumedDeleted.add(file);
            } else {
                expiredDeleted.add(file);
                if (expiredDeleted.size() == 1 && afterFirstExpiredDeletion != null) {
                    afterFirstExpiredDeletion.run();
                }
            }
        }

        @Override public void onSnapshotDeleteFailed(File file, SystemBackupStore.DeleteReason reason,
                                                     IOException error) {
            if (reason == SystemBackupStore.DeleteReason.EXPIRED) {
                expiredFailed.add(file);
                expiredErrors.add(error);
            }
        }
    }
}
