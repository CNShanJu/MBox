package com.github.catvod.crawler;

import com.github.tvbox.osc.bean.ParseBean;

import java.net.URL;
import java.util.List;

/** 将播放端的解析器选择映射成可加载的嗅探页，不把内部集标识伪装成网址。 */
public final class SniffPagePlanner {
    private SniffPagePlanner() { }

    public static ParseBean webParser(String playUrl, boolean useDefault, ParseBean defaultParse,
                                      List<ParseBean> parsers) {
        if (useDefault) return defaultParse;
        String prefix = playUrl == null ? "" : playUrl;
        if (prefix.startsWith("json:")) return null;
        if (prefix.startsWith("parse:")) {
            String name = prefix.substring(6);
            if (parsers != null) {
                for (ParseBean parser : parsers) {
                    if (parser != null && name.equals(parser.getName())) return parser;
                }
            }
            return null;
        }
        ParseBean parser = new ParseBean();
        parser.setType(0);
        parser.setUrl(prefix);
        return parser;
    }

    public static String pageUrl(String parserUrl, String playInput) {
        if (playInput == null || playInput.isEmpty()) return null;
        String prefix = parserUrl == null ? "" : parserUrl;
        if (!prefix.isEmpty() && !isHttpUrl(prefix)) return null;
        String page = prefix + playInput;
        return isHttpUrl(page) ? page : null;
    }

    public static boolean isHttpUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return false;
        try {
            String host = new URL(url).getHost();
            return host != null && !host.isEmpty() && !host.contains("|") && !host.contains("%");
        } catch (Exception ignored) {
            return false;
        }
    }
}
