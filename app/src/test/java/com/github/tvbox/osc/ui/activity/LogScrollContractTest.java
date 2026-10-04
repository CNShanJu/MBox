package com.github.tvbox.osc.ui.activity;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LogScrollContractTest {

    @Test
    public void deferredLatestLogScrollRechecksSearchAndTheActivePage() throws Exception {
        File file = new File("src/main/java/com/github/tvbox/osc/ui/activity/LogActivity.kt");
        if (!file.isFile()) file = new File("app", file.getPath());
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int load = source.indexOf("private fun loadBizLogs()");
        int post = source.indexOf("scroll.post {", load);
        int end = source.indexOf('}', post);
        assertTrue(load >= 0 && post > load && end > post);
        String callback = source.substring(post, end);
        assertTrue(callback.contains("bizEpoch.get() == epoch"));
        assertTrue(callback.contains("currentTab == 0"));
        assertTrue(callback.contains("searchQuery.isEmpty()"));
        assertTrue(callback.contains("scroll === activeScroll()"));
        assertTrue(callback.contains("scroll.fullScroll(View.FOCUS_DOWN)"));
    }
}
