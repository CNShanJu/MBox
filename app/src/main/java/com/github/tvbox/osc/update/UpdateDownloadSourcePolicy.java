package com.github.tvbox.osc.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A partial APK belongs to the URL that produced its bytes. Range requests against another
 * candidate cannot safely append to it, even when both candidates advertise the same APK size.
 */
final class UpdateDownloadSourcePolicy {
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private String partialSourceUrl;

    /** A resumed response may only be appended when the server starts at the requested byte. */
    static boolean matchesContentRangeStart(String contentRange, long requestedStart) {
        return matchesContentRangeStart(contentRange, requestedStart, -1);
    }

    /** When the release reports a size, the resumed response must belong to that same size. */
    static boolean matchesContentRangeStart(String contentRange, long requestedStart,
                                            long expectedTotal) {
        if (requestedStart <= 0 || contentRange == null) return false;
        Matcher match = CONTENT_RANGE.matcher(contentRange.trim());
        if (!match.matches()) return false;
        try {
            long first = Long.parseLong(match.group(1));
            long last = Long.parseLong(match.group(2));
            if (first != requestedStart || last < first) return false;
            String total = match.group(3);
            if ("*".equals(total)) return expectedTotal <= 0;
            long actualTotal = Long.parseLong(total);
            return actualTotal > last && (expectedTotal <= 0 || actualTotal == expectedTotal);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A server may ignore Range and return the whole APK. Compare every saved byte with that
     * response before appending its unread suffix; never trust only a short sample or size.
     */
    static boolean consumeMatchingPrefix(File partial, InputStream response, long bytes) throws IOException {
        if (partial == null || response == null || bytes <= 0 || partial.length() != bytes) return false;
        try (FileInputStream saved = new FileInputStream(partial)) {
            byte[] local = new byte[8192];
            byte[] remote = new byte[8192];
            long remaining = bytes;
            while (remaining > 0) {
                int wanted = (int) Math.min(local.length, remaining);
                if (!readFully(saved, local, wanted)) return false;
                if (!readFully(response, remote, wanted)) {
                    throw new EOFException("完整响应短于已保存的 APK 片段");
                }
                for (int i = 0; i < wanted; i++) if (local[i] != remote[i]) return false;
                remaining -= wanted;
            }
            return true;
        }
    }

    private static boolean readFully(InputStream input, byte[] buffer, int count) throws IOException {
        int offset = 0;
        while (offset < count) {
            int read = input.read(buffer, offset, count - offset);
            if (read < 0) return false;
            if (read == 0) continue;
            offset += read;
        }
        return true;
    }

    synchronized boolean mustDiscardPartial(String nextUrl, long existingBytes) {
        boolean discard = existingBytes > 0 && !nextUrl.equals(partialSourceUrl);
        partialSourceUrl = nextUrl;
        return discard;
    }

    /** Resume the source that produced the partial bytes before the normal fallback order. */
    synchronized List<String> orderedCandidates(List<String> urls, long existingBytes) {
        if (existingBytes <= 0 || partialSourceUrl == null || !urls.contains(partialSourceUrl)) return urls;
        List<String> ordered = new ArrayList<>(urls.size());
        ordered.add(partialSourceUrl);
        for (String url : urls) {
            if (!partialSourceUrl.equals(url)) ordered.add(url);
        }
        return ordered;
    }

    synchronized void reset() {
        partialSourceUrl = null;
    }

    /** Restore the owner of bytes left by an earlier app process. */
    synchronized void restore(String url) {
        partialSourceUrl = url;
    }
}
