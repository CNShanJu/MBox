package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.ArchiveItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PosterCleanupTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void keepsPosterUntilLastEpisodeAndFileAreGone() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File poster = poster(data, "Series");
        DownloadTask first = task("Series");
        ArchiveItem second = archive("Series");
        List<DownloadTask> tasks = new ArrayList<>(Collections.singletonList(first));
        List<ArchiveItem> archives = new ArrayList<>(Collections.singletonList(second));
        File remainingVideo = new File(downloads, "Source/Series/episode.mp4");
        write(remainingVideo);

        assertFalse(prune(data, "Series", tasks, archives, downloads));
        tasks.clear();
        assertFalse(prune(data, "Series", tasks, archives, downloads));
        archives.clear();
        assertFalse(prune(data, "Series", tasks, archives, downloads));
        assertTrue(poster.exists());

        assertTrue(remainingVideo.delete());
        assertTrue(prune(data, "Series", tasks, archives, downloads));
        assertFalse(poster.exists());
    }

    @Test public void samePosterKeyAcrossSourcesAndSanitizedNamesIsShared() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File poster = poster(data, "A_B");
        DownloadTask otherSource = task("A_B");

        assertFalse(prune(data, "A/B", Collections.singletonList(otherSource),
                Collections.emptyList(), downloads));
        assertTrue(poster.exists());
        assertTrue(prune(data, "A/B", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertFalse(poster.exists());
    }

    @Test public void retainedPartOrHlsSegmentBlocksCleanup() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File tmp = new File(data, "download_tmp");
        File poster = poster(data, "Series");
        File part = new File(downloads, "Source/Series/episode.mp4.part");
        write(part);
        assertFalse(prune(data, "Series", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertTrue(part.delete());

        File segment = new File(tmp, "Source/Series/tmp/episode/00001.ts");
        write(segment);
        assertFalse(prune(data, "Series", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertTrue(segment.delete());
        assertTrue(prune(data, "Series", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertFalse(poster.exists());
    }

    @Test public void failedFileRemovalAndInvalidNameCannotRemovePoster() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File poster = poster(data, "Series");
        File fileOutsideKnownRoots = new File(files.getRoot(), "old-location.mp4");
        write(fileOutsideKnownRoots);
        assertFalse(PosterCleanup.deleteIfUnused(data, "Series", Collections.emptyList(),
                Collections.emptyList(), Collections.singletonList(downloads),
                new File(data, "download_tmp"), fileOutsideKnownRoots));
        assertTrue(poster.exists());

        assertFalse(prune(data, "..", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertTrue(new File(data, "poster").isDirectory());
    }

    @Test public void emptyLastKnownTempDirectoryDoesNotKeepPoster() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File poster = poster(data, "Series");
        File emptyTemp = new File(data, "download_tmp/Source/Series/tmp/task");
        assertTrue(emptyTemp.mkdirs());

        assertTrue(PosterCleanup.deleteIfUnused(data, "Series", Collections.emptyList(),
                Collections.emptyList(), Collections.singletonList(downloads),
                new File(data, "download_tmp"), emptyTemp));
        assertFalse(poster.exists());
    }

    @Test public void legacyFragmentsBelongingToAnotherSeriesDoNotBlockCleanup() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File poster = poster(data, "Series");
        File oldTask = new File(data, "download_tmp/tmp/old-task");
        writeText(new File(oldTask, "segments.txt"), "来源=Source\n剧名=Other\n集数=1\n");
        write(new File(oldTask, "00001.ts"));

        assertTrue(prune(data, "Series", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertFalse(poster.exists());
    }

    @Test public void legacyFragmentsOfSameOrUnknownSeriesKeepPoster() throws Exception {
        File data = files.newFolder("data");
        File downloads = files.newFolder("downloads");
        File poster = poster(data, "Series");
        File oldTask = new File(data, "download_tmp/tmp/old-task");
        writeText(new File(oldTask, "segments.txt"), "来源=Source\n剧名=Series\n集数=1\n");
        write(new File(oldTask, "00001.ts"));
        assertFalse(prune(data, "Series", Collections.emptyList(),
                Collections.emptyList(), downloads));

        assertTrue(new File(oldTask, "segments.txt").delete());
        assertFalse(prune(data, "Series", Collections.emptyList(),
                Collections.emptyList(), downloads));
        assertTrue(poster.exists());
    }

    private boolean prune(File data, String name, List<DownloadTask> tasks,
                          List<ArchiveItem> archives, File downloads) {
        return PosterCleanup.deleteIfUnused(data, name, tasks, archives,
                Collections.singletonList(downloads), new File(data, "download_tmp"));
    }

    private static DownloadTask task(String name) {
        DownloadTask task = new DownloadTask();
        task.vodName = name;
        return task;
    }

    private static ArchiveItem archive(String name) {
        ArchiveItem item = new ArchiveItem();
        item.vodName = name;
        return item;
    }

    private static File poster(File data, String name) throws Exception {
        File poster = new File(data, "poster/" + name + "/poster.jpg");
        write(poster);
        return poster;
    }

    private static void write(File file) throws Exception {
        writeText(file, "data");
    }

    private static void writeText(File file, String value) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
