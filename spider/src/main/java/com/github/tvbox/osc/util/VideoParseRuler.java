package com.github.tvbox.osc.util;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Video sniffing rules are built off thread and published as one immutable snapshot. */
public class VideoParseRuler {

    private static final RuleSet EMPTY = new RuleSet(Collections.emptyMap(), Collections.emptyMap());
    private static volatile RuleSet activeRules = EMPTY;

    /** Immutable rule data. The nested lists and maps are copied when a builder is built. */
    public static final class RuleSet {
        private final Map<String, List<List<String>>> hostRules;
        private final Map<String, List<List<String>>> hostFilters;

        private RuleSet(Map<String, List<List<String>>> hostRules,
                        Map<String, List<List<String>>> hostFilters) {
            this.hostRules = hostRules;
            this.hostFilters = hostFilters;
        }
    }

    /** Local, mutable builder; do not share one builder between loading threads. */
    public static final class Builder {
        private final Map<String, List<List<String>>> hostRules = new HashMap<>();
        private final Map<String, List<List<String>>> hostFilters = new HashMap<>();

        public Builder() {
        }

        public Builder addHostRule(String host, List<String> rule) {
            hostRules.computeIfAbsent(host, ignored -> new ArrayList<>())
                    .add(rule == null ? null : new ArrayList<>(rule));
            return this;
        }

        public Builder addHostFilter(String host, List<String> filter) {
            hostFilters.computeIfAbsent(host, ignored -> new ArrayList<>())
                    .add(filter == null ? null : new ArrayList<>(filter));
            return this;
        }

        public RuleSet build() {
            return new RuleSet(freeze(hostRules), freeze(hostFilters));
        }
    }

    /** Constant time publication after the caller has built every rule off thread. */
    public static synchronized void replaceRules(RuleSet rules) {
        activeRules = rules == null ? EMPTY : rules;
    }

    /** Legacy entry point. */
    public static synchronized void clearRule() {
        activeRules = EMPTY;
    }

    /** Legacy entry point. */
    public static synchronized void addHostRule(String host, ArrayList<String> rule) {
        RuleSet current = activeRules;
        activeRules = new RuleSet(append(current.hostRules, host, rule), current.hostFilters);
    }

    /** Return a detached copy so callers cannot mutate the published rules. */
    public static ArrayList<ArrayList<String>> getHostRules(String host) {
        return mutableCopy(activeRules.hostRules.get(host));
    }

    /** Legacy entry point. */
    public static synchronized void addHostFilter(String host, ArrayList<String> filter) {
        RuleSet current = activeRules;
        activeRules = new RuleSet(current.hostRules, append(current.hostFilters, host, filter));
    }

    /** Return a detached copy so callers cannot mutate the published rules. */
    public static ArrayList<ArrayList<String>> getHostFilters(String host) {
        return mutableCopy(activeRules.hostFilters.get(host));
    }

    public static boolean checkIsVideoForParse(String webUrl, String url) {
        try {
            RuleSet rules = activeRules;
            boolean isVideo = DefaultConfig.isVideoFormat(url);
            if (!rules.hostRules.isEmpty() && !isVideo && webUrl != null) {
                String host = Uri.parse(webUrl).getHost();
                isVideo = checkVideoForOneHostRules(rules,
                        rules.hostRules.containsKey(host) ? host : "*", url);
            }
            return isVideo;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean checkVideoForOneHostRules(RuleSet rules, String host, String url) {
        List<List<String>> hostRules = rules.hostRules.get(host);
        if (hostRules == null) return false;
        for (List<String> rule : hostRules) {
            if (rule == null || rule.isEmpty()) continue;
            boolean matches = true;
            for (String pattern : rule) {
                if (!Pattern.compile(String.valueOf(pattern)).matcher(url).find()) {
                    matches = false;
                    break;
                }
                LOG.i("VIDEO RULE:" + pattern);
            }
            if (matches) return true;
        }
        return false;
    }

    public static boolean isFilter(String webUrl, String url) {
        try {
            RuleSet rules = activeRules;
            if (!rules.hostFilters.isEmpty() && webUrl != null) {
                String host = Uri.parse(webUrl).getHost();
                return checkIsFilterForOneHostRules(rules, host, url);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean checkIsFilterForOneHostRules(RuleSet rules, String host, String url) {
        List<List<String>> hostFilters = rules.hostFilters.get(host);
        if (hostFilters == null) return false;
        for (List<String> filter : hostFilters) {
            if (filter == null || filter.isEmpty()) continue;
            boolean matches = true;
            for (String pattern : filter) {
                if (!Pattern.compile(String.valueOf(pattern)).matcher(url).find()) {
                    matches = false;
                    break;
                }
                LOG.i("FILTER RULE:" + pattern);
            }
            if (matches) return true;
        }
        return false;
    }

    private static Map<String, List<List<String>>> freeze(Map<String, List<List<String>>> source) {
        Map<String, List<List<String>>> result = new HashMap<>();
        for (Map.Entry<String, List<List<String>>> entry : source.entrySet()) {
            List<List<String>> groups = new ArrayList<>();
            for (List<String> group : entry.getValue()) {
                groups.add(group == null ? null : Collections.unmodifiableList(new ArrayList<>(group)));
            }
            result.put(entry.getKey(), Collections.unmodifiableList(groups));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<List<String>>> append(
            Map<String, List<List<String>>> source, String host, List<String> rule) {
        Map<String, List<List<String>>> copy = new HashMap<>(source);
        List<List<String>> groups = new ArrayList<>();
        List<List<String>> existing = source.get(host);
        if (existing != null) groups.addAll(existing);
        groups.add(rule == null ? null : Collections.unmodifiableList(new ArrayList<>(rule)));
        copy.put(host, Collections.unmodifiableList(groups));
        return Collections.unmodifiableMap(copy);
    }

    private static ArrayList<ArrayList<String>> mutableCopy(List<List<String>> groups) {
        if (groups == null) return null;
        ArrayList<ArrayList<String>> copy = new ArrayList<>(groups.size());
        for (List<String> group : groups) {
            copy.add(group == null ? null : new ArrayList<>(group));
        }
        return copy;
    }
}
