package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class UpdateArchivePolicyTest {
    @Test
    public void debugInstallAcceptsReleasePackageWithoutAcceptingOtherApps() {
        String release = "com.github.tvbox.osc.mbox";
        assertTrue(UpdateArchivePolicy.acceptsPackageName(release, release));
        assertTrue(UpdateArchivePolicy.acceptsPackageName(release + ".debug", release));
        assertTrue(UpdateArchivePolicy.acceptsPackageName(release + ".debug", release + ".debug"));
        assertFalse(UpdateArchivePolicy.acceptsPackageName(release, release + ".debug"));
        assertFalse(UpdateArchivePolicy.acceptsPackageName(release + ".debug", "com.example.other"));
        assertFalse(UpdateArchivePolicy.acceptsPackageName(release + ".debug", null));
    }

    @Test
    public void knownVersionRequiresExactApkVersionCode() {
        assertTrue(UpdateArchivePolicy.acceptsVersion(74, 74));
        assertFalse(UpdateArchivePolicy.acceptsVersion(74, 73));
        assertFalse(UpdateArchivePolicy.acceptsVersion(74, 75));
        assertFalse(UpdateArchivePolicy.acceptsVersion(74, -1));
    }

    @Test
    public void unknownProviderVersionStillRequiresParsableApk() {
        assertTrue(UpdateArchivePolicy.acceptsVersion(-1, 74));
        assertFalse(UpdateArchivePolicy.acceptsVersion(-1, -1));
        assertFalse(UpdateArchivePolicy.acceptsVersion(-1, 0));
    }

    @Test
    public void onlyTruncatedZipWithApkHeaderKeepsItsPartialBytes() throws Exception {
        File file = File.createTempFile("update-partial", ".apk");
        try {
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
                output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                output.write(new byte[]{1, 2, 3, 4});
                output.closeEntry();
            }
            assertFalse(UpdateArchivePolicy.isLikelyInterruptedApk(file));
            byte[] complete = Files.readAllBytes(file.toPath());
            Files.write(file.toPath(), java.util.Arrays.copyOf(complete, complete.length / 2));
            assertTrue(UpdateArchivePolicy.isLikelyInterruptedApk(file));
            Files.write(file.toPath(), "<html>error</html>".getBytes("UTF-8"));
            assertFalse(UpdateArchivePolicy.isLikelyInterruptedApk(file));
        } finally {
            file.delete();
        }
    }
}
