package com.github.tvbox.osc.util.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;

public class CacheCatalogTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void onlyKnownStaleFilesAreClearedAndAllBytesRemainAccountedFor() throws Exception {
        File internal = temporaryFolder.newFolder("internal");
        File external = temporaryFolder.newFolder("external");
        File staleImage = write(internal, "image_viewer/img_old", "image old", true);
        File recentImage = write(internal, "image_viewer/img_recent", "image recent", false);
        File staleArchive = write(internal, "mbox_config_123.zip", "archive", true);
        File staleExport = write(external, "subscription_export/list.zip", "export", true);
        File subtitle = write(internal, "zimu/subtitle.srt", "subtitle", true);
        File unknown = write(internal, "unknown.dat", "unknown", true);
        File video = write(external, "exo-video-cache/span", "video", true);
        CacheCatalog catalog = new CacheCatalog(internal, external, () -> {});

        CacheCatalog.Snapshot before = catalog.scan();
        long expected = Arrays.asList(staleImage, recentImage, staleArchive, staleExport,
                subtitle, unknown, video).stream().mapToLong(File::length).sum();
        assertEquals(expected, before.totalBytes);
        assertEquals(expected, before.clearableBytes + before.protectedBytes);
        assertEquals(staleImage.length() + staleArchive.length() + staleExport.length(),
                size(before, CacheCatalog.TEMP_FILES));

        CacheCatalog.ClearResult result = catalog.clear(new HashSet<>(Arrays.asList(
                CacheCatalog.TEMP_FILES)));
        assertTrue(result.failures.isEmpty());
        assertFalse(staleImage.exists());
        assertFalse(staleArchive.exists());
        assertFalse(staleExport.exists());
        assertTrue(recentImage.exists());
        assertTrue(subtitle.exists());
        assertTrue(unknown.exists());
        assertTrue(video.exists());
        assertEquals(recentImage.length() + subtitle.length() + unknown.length() + video.length(),
                result.after.totalBytes);
    }

    @Test public void symlinkCannotEscapeCacheRootDuringScanOrClear() throws Exception {
        File internal = temporaryFolder.newFolder("internal");
        File outside = write(temporaryFolder.getRoot(), "outside.dat", "must survive", true);
        File viewer = new File(internal, "image_viewer");
        assertTrue(viewer.mkdir());
        File link = new File(viewer, "img_old");
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            Assume.assumeNoException("Symbolic links unavailable on this host", error);
        }
        CacheCatalog catalog = new CacheCatalog(internal, null, () -> {});
        assertEquals(0, catalog.scan().totalBytes);
        CacheCatalog.ClearResult result = catalog.clear(
                new HashSet<>(Arrays.asList(CacheCatalog.TEMP_FILES)));
        assertTrue(result.failures.isEmpty());
        assertTrue(outside.exists());
        assertTrue(link.exists());
    }

    @Test public void redirectedCacheRootCannotBeScannedOrCleared() throws Exception {
        File outside = temporaryFolder.newFolder("outside-cache");
        File stale = write(outside, "image_viewer/img_old", "must survive", true);
        File image = write(outside, "image_http_cache/journal", "must survive", true);
        File alias = new File(temporaryFolder.getRoot(), "cache-alias");
        try {
            Files.createSymbolicLink(alias.toPath(), outside.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            Assume.assumeNoException("Symbolic links unavailable on this host", error);
        }
        CacheCatalog catalog = new CacheCatalog(alias, null,
                () -> { throw new AssertionError("Image cache evictor must not run"); });

        assertEquals(0L, catalog.scan().totalBytes);
        CacheCatalog.ClearResult result = catalog.clear(new HashSet<>(Arrays.asList(
                CacheCatalog.TEMP_FILES, CacheCatalog.IMAGE_HTTP)));
        assertTrue(result.failures.containsKey(CacheCatalog.TEMP_FILES));
        assertTrue(result.failures.containsKey(CacheCatalog.IMAGE_HTTP));
        assertTrue(stale.exists());
        assertTrue(image.exists());
    }

    @Test public void aliasInParentPathDoesNotHideRealCacheRoot() throws Exception {
        File realParent = temporaryFolder.newFolder("real-parent");
        File actualRoot = new File(realParent, "cache");
        assertTrue(actualRoot.mkdir());
        File stale = write(actualRoot, "image_viewer/img_old", "safe to clear", true);
        File parentAlias = new File(temporaryFolder.getRoot(), "parent-alias");
        try {
            Files.createSymbolicLink(parentAlias.toPath(), realParent.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            Assume.assumeNoException("Symbolic links unavailable on this host", error);
        }
        CacheCatalog catalog = new CacheCatalog(new File(parentAlias, "cache"), null, () -> {});

        assertEquals(stale.length(), catalog.scan().totalBytes);
        CacheCatalog.ClearResult result = catalog.clear(
                new HashSet<>(Arrays.asList(CacheCatalog.TEMP_FILES)));
        assertTrue(result.failures.isEmpty());
        assertFalse(stale.exists());
    }

    @Test public void sourceCachesAreCountedButProtectedFromCleanup() throws Exception {
        File internal = temporaryFolder.newFolder("internal");
        File files = temporaryFolder.newFolder("files");
        File jar = write(files, "csp.jar", "source jar", true);
        File config = write(files, "0123456789abcdef0123456789abcdef", "source config", true);
        write(files, "poster/cover.jpg", "saved poster", true);
        CacheCatalog catalog = new CacheCatalog(internal, null, files, () -> {});

        CacheCatalog.Snapshot before = catalog.scan();
        assertEquals(jar.length() + config.length(), before.totalBytes);
        assertEquals(0L, before.clearableBytes);
        assertEquals(before.totalBytes, before.protectedBytes);

        CacheCatalog.ClearResult after = catalog.clear(new HashSet<>(Arrays.asList(
                CacheCatalog.TEMP_FILES)));
        assertTrue(after.failures.isEmpty());
        assertTrue(jar.exists());
        assertTrue(config.exists());
        assertEquals(before.totalBytes, after.after.totalBytes);
    }

    private static File write(File root, String relative, String contents, boolean stale)
            throws IOException {
        File target = new File(root, relative);
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create test directory");
        Files.write(target.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        if (stale && !target.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000))
            throw new IOException("Cannot set test file timestamp");
        return target;
    }

    private static long size(CacheCatalog.Snapshot snapshot, String id) {
        for (CacheCatalog.Entry entry : snapshot.entries)
            if (id.equals(entry.id)) return entry.sizeBytes;
        throw new AssertionError("Missing category " + id);
    }
}
