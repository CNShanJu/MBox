package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdatePromptPolicyTest {

    private static UpdateInfo update(String version, String file, long size, String url) {
        return new UpdateInfo(version, "v" + version, -1, url, file, size, "notes");
    }

    private final UpdateInfo current = update("3.6.6", "MBox.apk", 1000, "https://mirror/apk");
    private final UpdateInfo same = update("3.6.6", "MBox.apk", 1000, "https://github/apk");
    private final UpdateInfo newer = update("3.6.7", "MBox.apk", 2000, "https://mirror/new");

    @Test
    public void repeatedClickDuringDownloadOnlyOpensExistingProgress() {
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.action(UpdateManager.State.DOWNLOADING, current, same));
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.action(UpdateManager.State.PAUSED, current, same));
    }

    @Test
    public void anotherReleaseCannotReplaceAnActiveOrPausedDownload() {
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.action(UpdateManager.State.DOWNLOADING, current, newer));
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.action(UpdateManager.State.PAUSED, current, newer));
    }

    @Test
    public void completedArtifactInstallsWithoutStartingAnotherDownload() {
        assertEquals(UpdatePromptPolicy.Action.INSTALL,
                UpdatePromptPolicy.action(UpdateManager.State.COMPLETED, current, same));
        assertEquals(UpdatePromptPolicy.Action.DOWNLOAD,
                UpdatePromptPolicy.action(UpdateManager.State.COMPLETED, current, newer));
    }

    @Test
    public void sameVersionButDifferentAssetIsNotInstalledAsTheRequestedPackage() {
        assertEquals(UpdatePromptPolicy.Action.DOWNLOAD,
                UpdatePromptPolicy.action(UpdateManager.State.COMPLETED, current,
                        update("3.6.6", "MBox-debug.apk", 1000, "https://mirror/debug")));
        assertEquals(UpdatePromptPolicy.Action.DOWNLOAD,
                UpdatePromptPolicy.action(UpdateManager.State.COMPLETED, current,
                        update("3.6.6", "MBox.apk", 2000, "https://mirror/replaced")));
    }

    @Test
    public void failuresRetryWhileIdleAndCancelledStatesAllowANewDownload() {
        assertEquals(UpdatePromptPolicy.Action.RETRY,
                UpdatePromptPolicy.action(UpdateManager.State.FAILED, current, same));
        assertEquals(UpdatePromptPolicy.Action.DOWNLOAD,
                UpdatePromptPolicy.action(UpdateManager.State.FAILED, current, newer));
        assertEquals(UpdatePromptPolicy.Action.DOWNLOAD,
                UpdatePromptPolicy.action(UpdateManager.State.IDLE, null, same));
        assertEquals(UpdatePromptPolicy.Action.DOWNLOAD,
                UpdatePromptPolicy.action(UpdateManager.State.CANCELLED, null, same));
    }

    @Test
    public void progressClickCannotBecomeADownloadIfTheTaskChangesDuringDismissal() {
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.actionAtExecution(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                        UpdateManager.State.COMPLETED, current, newer));
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.actionAtExecution(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                        UpdateManager.State.CANCELLED, null, same));
    }

    @Test
    public void downloadClickRechecksForAStartedOrCompletedTask() {
        assertEquals(UpdatePromptPolicy.Action.VIEW_PROGRESS,
                UpdatePromptPolicy.actionAtExecution(UpdatePromptPolicy.Action.DOWNLOAD,
                        UpdateManager.State.DOWNLOADING, current, same));
        assertEquals(UpdatePromptPolicy.Action.INSTALL,
                UpdatePromptPolicy.actionAtExecution(UpdatePromptPolicy.Action.DOWNLOAD,
                        UpdateManager.State.COMPLETED, current, same));
    }

    @Test
    public void installClickCannotInstallAnotherReleaseOrStartANewDownload() {
        assertEquals(UpdatePromptPolicy.Action.UNAVAILABLE,
                UpdatePromptPolicy.actionAtExecution(UpdatePromptPolicy.Action.INSTALL,
                        UpdateManager.State.COMPLETED, newer, same));
        assertEquals(UpdatePromptPolicy.Action.UNAVAILABLE,
                UpdatePromptPolicy.actionAtExecution(UpdatePromptPolicy.Action.INSTALL,
                        UpdateManager.State.CANCELLED, null, same));
    }
}
