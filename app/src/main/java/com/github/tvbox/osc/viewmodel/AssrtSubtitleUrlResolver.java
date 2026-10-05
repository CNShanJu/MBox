package com.github.tvbox.osc.viewmodel;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Response;

/** Resolves assrt download responses without mistaking an error page for a subtitle URL. */
final class AssrtSubtitleUrlResolver {
    private AssrtSubtitleUrlResolver() {
    }

    static String resolve(Response response) throws IOException {
        int status = response.code();
        if (status >= 300 && status < 400) {
            String location = response.header("Location");
            HttpUrl target = location == null ? null : response.request().url().resolve(location);
            if (target == null || !("http".equals(target.scheme()) || "https".equals(target.scheme()))) {
                throw new IOException("Subtitle redirect has no valid destination");
            }
            return target.toString();
        }
        if (status == 200 || status == 206) {
            String contentType = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
            if (contentType.startsWith("text/html")) {
                throw new IOException("Subtitle download returned an HTML page");
            }
            return response.request().url().toString();
        }
        throw new IOException("Subtitle download failed: HTTP " + status);
    }
}
