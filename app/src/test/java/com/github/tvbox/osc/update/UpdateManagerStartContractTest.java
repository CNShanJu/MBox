package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Android Handler/service wiring is checked without starting a JVM download worker. */
public class UpdateManagerStartContractTest {

    private static String source(String name) throws Exception {
        String relative = "src/main/java/com/github/tvbox/osc/update/" + name + ".java";
        File file = new File(relative);
        if (!file.isFile()) file = new File("../app/" + relative);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void duplicateStartReportsToNewCallerBeforeChangingExistingTask() throws Exception {
        String manager = source("UpdateManager");
        int start = manager.indexOf("public void start(Context context");
        int guard = manager.indexOf("if (state == State.DOWNLOADING || state == State.PAUSED)", start);
        int accepted = manager.indexOf("long workerEpoch = epochs.next();", guard);
        assertTrue(start >= 0 && guard > start && accepted > guard);
        String rejection = manager.substring(guard, accepted);
        assertTrue(rejection.contains("MAIN.post(() ->"));
        assertTrue(rejection.contains("cb.onError(message)"));
        assertTrue(rejection.contains("已有更新正在下载，请查看当前进度"));
        assertTrue(rejection.contains("更新下载已暂停，请查看当前进度"));
        assertTrue(rejection.indexOf("return;") < rejection.indexOf("LOG.i(TAG, \"开始下载"));
        assertFalse(rejection.contains("this.callback ="));
        assertFalse(rejection.contains("this.info ="));
        assertFalse(rejection.contains("this.targetFile ="));
        assertFalse(rejection.contains("this.downloaded ="));
        assertFalse(rejection.contains("cancelCurrentCall()"));
    }

    @Test
    public void startNotificationFollowsServiceAcceptanceAndChecksEpoch() throws Exception {
        String manager = source("UpdateManager");
        int service = manager.indexOf("if (!UpdateDownloadService.start(ctx, workerEpoch))");
        int started = manager.indexOf("if (notifyStart) fireStarted(workerEpoch);", service);
        int worker = manager.indexOf("WorkerRun worker = new WorkerRun();", service);
        assertTrue(service >= 0 && started > service && worker > started);
        String serviceGuard = manager.substring(service, started);
        assertTrue(serviceGuard.contains("onForegroundServiceStartFailed(workerEpoch);"));
        assertTrue(serviceGuard.contains("return;"));

        int callback = manager.indexOf("private void fireStarted(long workerEpoch)");
        int ready = manager.indexOf("private void fireReady(long workerEpoch)", callback);
        assertTrue(callback >= 0 && ready > callback);
        String notification = manager.substring(callback, ready);
        assertTrue(notification.contains("MAIN.post(() ->"));
        assertTrue(notification.indexOf("if (!epochs.isCurrent(workerEpoch)) return;")
                < notification.indexOf("cb.onDownloadStart()"));
        assertTrue(source("Updater").contains("default void onDownloadStart()"));
    }

    @Test
    public void resumeDoesNotRepeatInitialDownloadNotification() throws Exception {
        String manager = source("UpdateManager");
        int start = manager.indexOf("public void start(Context context");
        int pause = manager.indexOf("public void pause()", start);
        int resume = manager.indexOf("public void resume()", pause);
        int cancel = manager.indexOf("public void cancel()", resume);
        assertTrue(start >= 0 && pause > start && resume > pause && cancel > resume);
        assertTrue(manager.substring(start, pause).contains("startDownload(workerEpoch, true)"));
        assertTrue(manager.substring(resume, cancel).contains("startDownload(workerEpoch, false)"));
        assertFalse(manager.substring(resume, cancel).contains("fireStarted("));
    }
}
