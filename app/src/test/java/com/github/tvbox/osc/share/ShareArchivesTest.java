package com.github.tvbox.osc.share;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShareArchivesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void roundTripManifestAndData() throws Exception {
        File data = temporary.newFile("settings.json");
        try (FileOutputStream out = new FileOutputStream(data)) {
            out.write("{\"schema\":1}".getBytes(StandardCharsets.UTF_8));
        }
        File manifest = temporary.newFile("manifest.json");
        ShareManifest header = new ShareManifest(1, "mbox-config.zip", 0, "", 1L, "", 0,
                Collections.singletonList("settings"));
        try (FileOutputStream out = new FileOutputStream(manifest)) {
            out.write(header.toJson().getBytes(StandardCharsets.UTF_8));
        }
        File zip = new File(temporary.getRoot(), "transfer.zip");
        java.util.Map<String, File> entries = new java.util.LinkedHashMap<>();
        entries.put("settings.json", data);
        entries.put("manifest.json", manifest);
        ShareArchives.zip(entries, zip);
        assertEquals("settings", ShareArchives.manifest(zip).domains().get(0));
        ShareArchives.check(zip, ShareArchives.manifest(zip));
        File extracted = temporary.newFolder("extracted");
        ShareArchives.extract(zip, extracted);
        assertTrue(new File(extracted, "settings.json").isFile());
    }

    @Test public void rejectsTraversalBeforeWritingOutsideDirectory() throws Exception {
        File zip = new File(temporary.getRoot(), "traversal.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.txt"));
            out.write(1);
            out.closeEntry();
        }
        File extracted = temporary.newFolder("safe");
        boolean rejected = false;
        try { ShareArchives.extract(zip, extracted); }
        catch (SecurityException expected) { rejected = true; }
        assertTrue(rejected);
        assertFalse(new File(temporary.getRoot(), "escaped.txt").exists());
    }

    @Test public void rejectsDuplicateEntries() throws Exception {
        File zip = new File(temporary.getRoot(), "duplicate.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("a/../settings.json"));
            out.write(1);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("settings.json"));
            out.write(2);
            out.closeEntry();
        }
        boolean rejected = false;
        try { ShareArchives.extract(zip, temporary.newFolder("duplicate-target")); }
        catch (SecurityException expected) { rejected = true; }
        assertTrue(rejected);
    }

    @Test public void respectsDomainExtractionLimit() throws Exception {
        File data = temporary.newFile("oversized.json");
        try (FileOutputStream out = new FileOutputStream(data)) {
            out.write(new byte[]{1, 2, 3});
        }
        File zip = new File(temporary.getRoot(), "limited.zip");
        ShareArchives.zip(Collections.singletonMap("oversized.json", data), zip);
        boolean rejected = false;
        try { ShareArchives.extract(zip, temporary.newFolder("limited-target"), 2, 10); }
        catch (SecurityException expected) { rejected = true; }
        assertTrue(rejected);
    }

    @Test public void roundTripDirectoryTrees() throws Exception {
        File tree = temporary.newFolder("tree");
        File nested = new File(tree, "nested");
        assertTrue(nested.mkdir());
        writeFile(new File(tree, "root.txt"), "root");
        writeFile(new File(nested, "inner.txt"), "inner");
        File second = temporary.newFolder("second");
        writeFile(new File(second, "other.txt"), "other");
        java.util.Map<String, File> directories = new java.util.LinkedHashMap<>();
        directories.put("a", tree);
        directories.put("b", second);
        File zip = new File(temporary.getRoot(), "trees.zip");
        ShareArchives.zipDirectories(directories, zip, 1024, 4096, 100);
        File extracted = temporary.newFolder("tree-target");
        ShareArchives.extract(zip, extracted);
        assertTrue(new File(extracted, "a/root.txt").isFile());
        assertTrue(new File(extracted, "a/nested/inner.txt").isFile());
        assertTrue(new File(extracted, "b/other.txt").isFile());
    }

    @Test public void rejectsOversizedTreeFile() throws Exception {
        File tree = temporary.newFolder("big");
        writeFile(new File(tree, "big.txt"), "0123456789");
        File zip = new File(temporary.getRoot(), "big-tree.zip");
        boolean rejected = false;
        try { ShareArchives.zipDirectories(Collections.singletonMap("a", tree), zip, 4, 1024, 100); }
        catch (java.io.IOException expected) { rejected = true; }
        assertTrue(rejected);
    }

    @Test public void rejectsIllegalTreeRootName() throws Exception {
        File tree = temporary.newFolder("plain");
        File zip = new File(temporary.getRoot(), "bad-root.zip");
        boolean rejected = false;
        try { ShareArchives.zipDirectories(Collections.singletonMap("a/../b", tree), zip, 1024, 1024, 100); }
        catch (java.io.IOException expected) { rejected = true; }
        assertTrue(rejected);
    }

    private static void writeFile(File file, String content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
