package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdateDownloadRouteTest {

    @Test
    public void identifiesActualCandidateRatherThanEmbeddedGithubUrl() {
        assertEquals("Gitee（gitee.com）", UpdateDownloadRoute.describe(
                "https://gitee.com/CnAyo/MBox/releases/download/v1/MBox.apk"));
        assertEquals("代理（gh-proxy.com）", UpdateDownloadRoute.describe(
                "https://gh-proxy.com/https://github.com/CNShanJu/MBox/releases/download/v1/MBox.apk"));
        assertEquals("GitHub（github.com）", UpdateDownloadRoute.describe(
                "https://github.com/CNShanJu/MBox/releases/download/v1/MBox.apk"));
    }

    @Test
    public void unknownCandidateShowsItsHostWithoutExposingFullUrl() {
        assertEquals("其他（download.example.org）", UpdateDownloadRoute.describe(
                "https://download.example.org/private/path/MBox.apk?token=secret"));
        assertEquals("未知", UpdateDownloadRoute.describe(null));
        assertEquals("未知", UpdateDownloadRoute.describe("bad url"));
    }
}
