package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class UpdateApkFilePolicyTest {
    @Test
    public void releaseAssetStaysInsideUpdateDirectory() throws IOException {
        File directory = new File("build/test-update-cache").getCanonicalFile();
        assertEquals(new File(directory, "MBox_v3.5.5_release.apk"),
                UpdateApkFilePolicy.resolve(directory, "MBox_v3.5.5_release.apk"));
    }

    @Test
    public void rejectsTraversalAbsoluteAndNonApkNames() {
        File directory = new File("build/test-update-cache");
        assertNull(UpdateApkFilePolicy.resolve(directory, "../other.apk"));
        assertNull(UpdateApkFilePolicy.resolve(directory, "..\\other.apk"));
        assertNull(UpdateApkFilePolicy.resolve(directory, "C:\\other.apk"));
        assertNull(UpdateApkFilePolicy.resolve(directory, "/tmp/other.apk"));
        assertNull(UpdateApkFilePolicy.resolve(directory, "MBox.zip"));
    }
}
