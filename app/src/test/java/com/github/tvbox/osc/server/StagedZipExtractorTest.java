package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class StagedZipExtractorTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void completeArchiveReplacesFilesAndCreatesDirectories() throws Exception {
        File root = temporaryFolder.newFolder("target");
        File old = new File(root, "existing.txt");
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("folder/", "");
        entries.put("empty/", "");
        entries.put("folder/new.txt", "new");
        entries.put("existing.txt", "replacement");

        StagedZipExtractor.unzip(archive(entries), root);

        assertEquals("replacement", read(old));
        assertEquals("new", read(new File(root, "folder/new.txt")));
        assertTrue(new File(root, "empty").isDirectory());
        assertFalse(new File(root, "folder/.tvbox_folder").exists());
        assertNoWorkDirectories(root);
    }

    @Test public void traversalRejectsWholeArchiveBeforeReplacingExistingFile() throws Exception {
        File root = temporaryFolder.newFolder("target");
        File old = new File(root, "existing.txt");
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("existing.txt", "replacement");
        entries.put("../escaped.txt", "escaped");

        expectRejected(archive(entries), root, StagedZipExtractor.DEFAULT_LIMITS);

        assertEquals("old", read(old));
        assertFalse(new File(temporaryFolder.getRoot(), "escaped.txt").exists());
        assertNoWorkDirectories(root);
    }

    @Test public void measuredExpansionLimitProtectsExistingFiles() throws Exception {
        File root = temporaryFolder.newFolder("target");
        File old = new File(root, "existing.txt");
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("existing.txt", "replacement");

        expectRejected(archive(entries), root, new StagedZipExtractor.Limits(1024, 10, 5, 10));

        assertEquals("old", read(old));
        assertNoWorkDirectories(root);
    }

    @Test public void destinationConflictLeavesEarlierArchiveFilesUntouched() throws Exception {
        File root = temporaryFolder.newFolder("target");
        File old = new File(root, "existing.txt");
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "folder").toPath(), "occupied".getBytes(StandardCharsets.UTF_8));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("existing.txt", "replacement");
        entries.put("folder/new.txt", "new");

        expectRejected(archive(entries), root, StagedZipExtractor.DEFAULT_LIMITS);

        assertEquals("old", read(old));
        assertEquals("occupied", read(new File(root, "folder")));
        assertNoWorkDirectories(root);
    }

    @Test public void ordinaryUploadCopiesBeforeReplacement() throws Exception {
        File root = temporaryFolder.newFolder("target");
        File old = new File(root, "movie.txt");
        File source = temporaryFolder.newFile("source.txt");
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(source.toPath(), "complete".getBytes(StandardCharsets.UTF_8));

        StagedZipExtractor.copyFileAtomically(source, old);

        assertEquals("complete", read(old));
        assertNoWorkDirectories(root);
    }

    private File archive(Map<String, String> entries) throws Exception {
        File archive = temporaryFolder.newFile("upload-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                if (!entry.getKey().endsWith("/")) {
                    out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return archive;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void expectRejected(File zip, File root, StagedZipExtractor.Limits limits)
            throws Exception {
        try {
            StagedZipExtractor.unzip(zip, root, limits);
            fail("archive should have been rejected");
        } catch (StagedZipExtractor.InvalidArchiveException expected) {
            // The destination remains untouched.
        }
    }

    private static void assertNoWorkDirectories(File root) {
        File[] children = root.listFiles();
        assertTrue(children != null);
        for (File child : children) {
            assertFalse(child.getName().startsWith(".mbox-upload-stage-"));
            assertFalse(child.getName().startsWith(".mbox-upload-backup-"));
        }
    }
}
