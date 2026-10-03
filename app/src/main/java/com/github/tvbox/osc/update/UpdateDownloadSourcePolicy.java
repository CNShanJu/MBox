package com.github.tvbox.osc.update;

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
        if (requestedStart <= 0 || contentRange == null) return false;
        Matcher match = CONTENT_RANGE.matcher(contentRange.trim());
        if (!match.matches()) return false;
        try {
            long first = Long.parseLong(match.group(1));
            long last = Long.parseLong(match.group(2));
            if (first != requestedStart || last < first) return false;
            String total = match.group(3);
            return "*".equals(total) || Long.parseLong(total) > last;
        } catch (NumberFormatException e) {
            return false;
        }
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
}
