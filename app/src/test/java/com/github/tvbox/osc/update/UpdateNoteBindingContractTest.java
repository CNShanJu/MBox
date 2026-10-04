package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Guards Android popup wiring that cannot run in the plain JVM test environment. */
public class UpdateNoteBindingContractTest {

    private static String source(String path) throws Exception {
        File file = new File("src/main/java/com/github/tvbox/osc/" + path + ".java");
        if (!file.isFile()) file = new File("app", file.getPath());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void downloadListenerIsReleasedOnDismissAndHostDestruction() throws Exception {
        String check = source("update/UpdateCheck");
        int binding = check.indexOf("UpdateManager.Listener listener =");
        int end = check.indexOf("private static void bindNoteAction", binding);
        String popup = check.substring(binding, end);
        assertTrue(popup.contains("manager.addListener(listener)"));
        assertTrue(popup.contains("removeListenerOnDestroy(dialog, manager, listener)"));
        int destroy = popup.indexOf("if (event == Lifecycle.Event.ON_DESTROY)");
        int observe = popup.indexOf("owner.getLifecycle().addObserver(cleanup)");
        assertTrue(destroy >= 0 && observe > destroy);
        assertTrue(popup.substring(destroy, observe).contains("manager.removeListener(listener)"));
        assertTrue(popup.substring(destroy, observe).contains("removeObserver(this)"));
        int dismiss = popup.indexOf("public void onDismiss(BasePopupView popupView)");
        assertTrue(dismiss >= 0);
        assertTrue(popup.substring(dismiss).contains("manager.removeListener(listener)"));
        assertTrue(popup.substring(dismiss).contains("removeObserver(cleanup)"));
        assertTrue(check.contains("removeListenerOnDestroy(progress, manager, progress)"));
    }

    @Test
    public void popupCapturesOneActionBeforeDismissalWithoutReadingTheManager() throws Exception {
        String dialog = source("ui/dialog/UpdateNoteDialog");
        assertFalse(dialog.contains("UpdateManager.get()"));
        int click = dialog.indexOf("updateButton.setOnClickListener");
        int committed = dialog.indexOf("if (actionCommitted) return;", click);
        int selected = dialog.indexOf("Action selected = action;", click);
        int disabled = dialog.indexOf("v.setEnabled(false);", click);
        int dismiss = dialog.indexOf("dismissWith(() ->", click);
        assertTrue(committed > click && selected > committed && disabled > selected && dismiss > disabled);
        assertTrue(dialog.substring(dismiss).contains("mOnUpdate.onAction(selected)"));
    }
}
