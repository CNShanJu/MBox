package com.github.tvbox.osc.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class JarCacheFilesTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void successfulLoadCommitsNewCacheAndRemovesBackup() throws Exception {
        File cache = file("csp.jar", "old jar");
        File download = file("csp.jar.download", "new jar");

        JarCacheFiles.Replacement replacement = JarCacheFiles.replace(download, cache);
        assertContents(cache, "new jar");
        assertContents(backup(), "old jar");
        replacement.commit();

        assertContents(cache, "new jar");
        assertFalse(download.exists());
        assertFalse(backup().exists());
    }

    @Test
    public void failedLoadRollsBackToPreviousCache() throws Exception {
        File cache = file("csp.jar", "old jar");
        File download = file("csp.jar.download", "invalid jar");

        JarCacheFiles.Replacement replacement = JarCacheFiles.replace(download, cache);
        replacement.rollback();

        assertContents(cache, "old jar");
        assertFalse(download.exists());
        assertFalse(backup().exists());
    }

    @Test
    public void failedFirstLoadDoesNotLeaveInvalidCache() throws Exception {
        File cache = new File(files.getRoot(), "csp.jar");
        File download = file("csp.jar.download", "invalid jar");

        JarCacheFiles.replace(download, cache).rollback();

        assertFalse(cache.exists());
        assertFalse(backup().exists());
    }

    @Test
    public void interruptedLoadIsRecoveredBeforeCacheIsReused() throws Exception {
        File cache = file("csp.jar", "old jar");
        File download = file("csp.jar.download", "uncommitted jar");

        JarCacheFiles.replace(download, cache);
        assertContents(cache, "uncommitted jar");
        JarCacheFiles.recover(cache);

        assertContents(cache, "old jar");
        assertFalse(backup().exists());
    }

    @Test
    public void missingDownloadLeavesExistingCacheIntact() throws Exception {
        File cache = file("csp.jar", "old jar");
        File download = new File(files.getRoot(), "missing.download");

        assertThrows(IOException.class, () -> JarCacheFiles.replace(download, cache));

        assertContents(cache, "old jar");
        assertFalse(backup().exists());
    }

    @Test
    public void missingDownloadWithoutCacheDoesNotCreateCache() {
        File cache = new File(files.getRoot(), "csp.jar");
        File download = new File(files.getRoot(), "missing.download");

        assertThrows(IOException.class, () -> JarCacheFiles.replace(download, cache));

        assertFalse(cache.exists());
        assertFalse(backup().exists());
    }

    private File file(String name, String text) throws IOException {
        File file = files.newFile(name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private File backup() {
        return new File(files.getRoot(), "csp.jar.previous");
    }

    private static void assertContents(File file, String expected) throws IOException {
        assertTrue(file.isFile());
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file.toPath()));
    }
}
