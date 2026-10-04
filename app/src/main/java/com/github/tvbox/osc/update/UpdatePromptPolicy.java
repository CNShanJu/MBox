package com.github.tvbox.osc.update;

import java.util.Objects;

/** Release notes stay readable while an existing download owns the update controls. */
public final class UpdatePromptPolicy {

    public enum Action { DOWNLOAD, VIEW_PROGRESS, INSTALL, RETRY, UNAVAILABLE }

    private UpdatePromptPolicy() { }

    public static Action action(UpdateManager.State state, UpdateInfo current, UpdateInfo requested) {
        if (state == UpdateManager.State.DOWNLOADING || state == UpdateManager.State.PAUSED) {
            return Action.VIEW_PROGRESS;
        }
        if (sameArtifact(current, requested)) {
            if (state == UpdateManager.State.COMPLETED) return Action.INSTALL;
            if (state == UpdateManager.State.FAILED) return Action.RETRY;
        }
        return Action.DOWNLOAD;
    }

    public static Action actionAtExecution(Action selected, UpdateManager.State state,
                                            UpdateInfo current, UpdateInfo requested) {
        // Opening progress must never turn into a new download while the notes dismiss.
        if (selected == Action.VIEW_PROGRESS) return Action.VIEW_PROGRESS;
        Action actual = action(state, current, requested);
        if (selected == Action.INSTALL && actual != Action.INSTALL && actual != Action.VIEW_PROGRESS) {
            return Action.UNAVAILABLE;
        }
        return actual;
    }

    private static boolean sameArtifact(UpdateInfo current, UpdateInfo requested) {
        if (current == null || requested == null) return false;
        boolean hasVersion = current.versionCode > 0
                || (current.versionName != null && !current.versionName.isEmpty())
                || (current.versionTag != null && !current.versionTag.isEmpty());
        // Mirrors may change without changing the APK; do not use candidate URLs as identity.
        return hasVersion && Objects.equals(current.versionName, requested.versionName)
                && Objects.equals(current.versionTag, requested.versionTag)
                && current.versionCode == requested.versionCode
                && Objects.equals(current.apkName, requested.apkName)
                && current.apkSize == requested.apkSize;
    }
}
