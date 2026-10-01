package com.github.tvbox.osc.download.internal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class DownloadStoragePolicyTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Test public void android11PrivateDownloadsDoNotRequireAllFilesPermission() throws Exception {
        File internal = files.newFolder("files"), external = files.newFolder("external");
        assertTrue(DownloadStoragePolicy.canWrite(new File(internal, "downloads/video.mp4"), internal, external, 30, false));
        assertTrue(DownloadStoragePolicy.canWrite(new File(external, "Download/MBox/video.mp4"), internal, external, 35, false));
        File publicFile = new File(files.getRoot(), "Download/MBox/video.mp4");
        assertFalse(DownloadStoragePolicy.canWrite(publicFile, internal, external, 30, false));
        assertTrue(DownloadStoragePolicy.canWrite(publicFile, internal, external, 30, true));
    }
    @Test public void siblingPrefixAndTraversalDoNotCountAsPrivate() throws Exception {
        File internal = files.newFolder("files");
        assertFalse(DownloadStoragePolicy.canWrite(new File(files.getRoot(), "files-other/video.mp4"), internal, null, 30, false));
        assertFalse(DownloadStoragePolicy.canWrite(new File(internal, "../public/video.mp4"), internal, null, 30, false));
        assertEquals("", DownloadManager.sanitizeName(".."));
        assertEquals("", DownloadManager.sanitizeName("."));
    }
}
