package com.github.tvbox.osc.util;

import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 阅读视频书源中可安全转换的 JSON 接口规则；不执行导入文本中的 JavaScript。 */
public final class LegadoVideoRules {
    private static final Pattern FIELD = Pattern.compile("\\$\\.[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{(.*?)\\}\\}");
    private static final Pattern MEDIA_FIELD = Pattern.compile(
            "\\{\\{\\s*(\\$\\.[A-Za-z0-9_.]*(?:videopath|playurl|playUrl|videoUrl|video_url))\\s*\\}\\}");
    private static final Pattern TITLE_FIELD = Pattern.compile(
            "\\{\\{\\s*(\\$\\.[A-Za-z0-9_.]*title)\\s*\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANDOM_PAGE = Pattern.compile(
            "Math\\.ceil\\(Math\\.random\\(\\)\\s*\\*\\s*(\\d{1,5})\\)");

    private LegadoVideoRules() { }

    public static final class Spec {
        public final String name;
        public final String host;
        public final String key;
        public final String listPath;
        public final String detailTemplate;
        public final String mediaPath;
        public final Map<String, String> headers;
        public final LinkedHashMap<String, String> routes;
        public final String extJson;

        private Spec(String name, String host, String key, String listPath, String detailTemplate,
                     String mediaPath, Map<String, String> headers, LinkedHashMap<String, String> routes,
                     String extJson) {
            this.name = name;
            this.host = host;
            this.key = key;
            this.listPath = listPath;
            this.detailTemplate = detailTemplate;
            this.mediaPath = mediaPath;
            this.headers = headers;
            this.routes = routes;
            this.extJson = extJson;
        }

        public String firstCategoryUrl() {
            if (routes.isEmpty()) return null;
            return probeUrl(routes.values().iterator().next());
        }

        public String probeUrl(String route) {
            return route.replace("{page}", "1").replaceAll("\\{random:\\d+\\}", "1");
        }

        public List<JsonObject> listItems(String body) {
            try {
                JsonElement value = readPath(JsonParser.parseString(body), listPath);
                if (value == null || !value.isJsonArray()) return Collections.emptyList();
                List<JsonObject> items = new ArrayList<>();
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonObject()) items.add(item.getAsJsonObject());
                }
                return items;
            } catch (RuntimeException ignored) {
                return Collections.emptyList();
            }
        }

        public String detailUrl(JsonObject item) {
            if (item == null) return null;
            Matcher m = TEMPLATE.matcher(detailTemplate);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String path = m.group(1).trim();
                JsonElement value = readPath(item, path);
                if (value == null || !value.isJsonPrimitive()) return null;
                m.appendReplacement(out, Matcher.quoteReplacement(value.getAsString()));
            }
            m.appendTail(out);
            return sameOriginUrl(host, out.toString());
        }

        public boolean hasMedia(String body) {
            try {
                JsonElement value = readPath(JsonParser.parseString(body), mediaPath);
                return value != null && value.isJsonPrimitive()
                        && value.getAsString().matches("(?i)^https?://.+|^/.+");
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    /** 只接受 JSON 列表、详情链接、媒体字段都能由静态规则描述的视频书源。 */
    public static Spec parse(String text) {
        if (!CmsApiRules.looksLikeBookSource(text)) return null;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            JsonObject source = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            if (source == null) return null;
            URI base = URI.create(field(source, "sourceUrl"));
            if (!isHttp(base) || base.getHost() == null) return null;
            String host = base.getScheme() + "://" + base.getRawAuthority();
            String listPath = simplePath(field(source, "ruleArticles"));
            String detailTemplate = field(source, "ruleLink");
            String contentRule = field(source, "ruleContent");
            String mediaPath = mediaPath(contentRule);
            if (listPath == null || detailTemplate.isEmpty() || mediaPath == null
                    || !FIELD.matcher(detailTemplate).find()) return null;
            String titlePath = simplePath(field(source, "ruleTitle").split("##", 2)[0]);
            if (titlePath == null) titlePath = "$.title";
            String imagePath = firstPath(field(source, "ruleImage"));
            String remarksPath = firstPath(field(source, "rulePubDate"));
            String nextPath = simplePath(field(source, "ruleNextPage"));
            String detailParent = mediaPath.substring(0, mediaPath.lastIndexOf('.'));
            Matcher detailTitle = TITLE_FIELD.matcher(contentRule);
            String detailTitlePath = detailTitle.find() ? detailTitle.group(1) : detailParent + ".title";
            String detailImagePath = imagePath == null ? "" : detailParent + imagePath.substring(1);
            String lastPagePath = nextPath != null && nextPath.endsWith(".next_page_url")
                    ? nextPath.substring(0, nextPath.length() - "next_page_url".length()) + "last_page" : "";

            LinkedHashMap<String, String> routes = new LinkedHashMap<>();
            JsonArray classes = new JsonArray();
            String searchRoute = null;
            for (String line : field(source, "sortUrl").split("\\r?\\n")) {
                int at = line.indexOf("::");
                if (at <= 0) continue;
                String label = line.substring(0, at).trim();
                String address = line.substring(at + 2).trim();
                if (label.isEmpty() || address.isEmpty()) continue;
                boolean search = label.contains("搜索");
                String template = routeTemplate(host, address, search);
                if (template == null) continue;
                if (search) {
                    if (searchRoute == null && template.contains("{key}")) searchRoute = template;
                } else if (routes.size() < 40) {
                    String id = String.valueOf(routes.size());
                    routes.put(id, template);
                    JsonObject category = new JsonObject();
                    category.addProperty("type_id", id);
                    category.addProperty("type_name", label);
                    classes.add(category);
                }
            }
            if (routes.isEmpty() || sameOriginUrl(host, detailTemplate.replaceAll(
                    "\\{\\{.*?\\}\\}", "1")) == null) return null;

            Map<String, String> headers = parseHeaders(field(source, "header"), host);
            String name = field(source, "sourceName");
            if (name.isEmpty()) name = base.getHost();
            String key = "legado_" + CmsApiRules.siteKey(host) + "_"
                    + Integer.toHexString((name + detailTemplate).hashCode());
            JsonObject ext = new JsonObject();
            ext.addProperty("siteName", name);
            ext.addProperty("host", host);
            ext.addProperty("listPath", listPath);
            ext.addProperty("titlePath", titlePath);
            ext.addProperty("imagePath", imagePath == null ? "" : imagePath);
            ext.addProperty("remarksPath", remarksPath == null ? "" : remarksPath);
            ext.addProperty("detailTemplate", detailTemplate);
            ext.addProperty("mediaPath", mediaPath);
            ext.addProperty("detailTitlePath", detailTitlePath);
            ext.addProperty("detailImagePath", detailImagePath);
            ext.addProperty("nextPagePath", nextPath == null ? "" : nextPath);
            ext.addProperty("lastPagePath", lastPagePath);
            ext.addProperty("timeout", 15000);
            ext.add("classes", classes);
            JsonObject routeJson = new JsonObject();
            for (Map.Entry<String, String> route : routes.entrySet()) {
                routeJson.addProperty(route.getKey(), route.getValue());
            }
            ext.add("routes", routeJson);
            if (searchRoute != null) ext.addProperty("searchRoute", searchRoute);
            JsonObject headerJson = new JsonObject();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                headerJson.addProperty(header.getKey(), header.getValue());
            }
            ext.add("headers", headerJson);
            return new Spec(name, host, key, listPath, detailTemplate, mediaPath,
                    headers, routes, ext.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String routeTemplate(String host, String address, boolean search) {
        Matcher matcher = TEMPLATE.matcher(address);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement;
            Matcher randomPage = RANDOM_PAGE.matcher(expression);
            if ("page".equals(expression)) replacement = "{page}";
            else if (search && (expression.contains("source.getVariable")
                    || "key".equals(expression) || "searchKey".equals(expression))) replacement = "{key}";
            else if (!search && randomPage.matches()) {
                int bound = Integer.parseInt(randomPage.group(1));
                replacement = "{random:" + Math.max(1, Math.min(10000, bound)) + "}";
            }
            else return null; // 其它任意 JS 不在导入时执行
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        String route = out.toString();
        if (route.contains("{{") || route.length() > 700) return null;
        String checked = sameOriginUrl(host, route.replace("{page}", "1").replace("{key}", "test")
                .replaceAll("\\{random:\\d+\\}", "1"));
        if (checked == null) return null;
        if (route.startsWith("http://") || route.startsWith("https://")) return route;
        return host + (route.startsWith("/") ? "" : "/") + route;
    }

    private static String sameOriginUrl(String host, String value) {
        try {
            URI base = URI.create(host + "/");
            URI result = base.resolve(value);
            if (!isHttp(result) || !base.getScheme().equalsIgnoreCase(result.getScheme())
                    || !base.getRawAuthority().equalsIgnoreCase(result.getRawAuthority())) return null;
            return result.toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isHttp(URI uri) {
        return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static Map<String, String> parseHeaders(String text, String host) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (text.isEmpty()) return headers;
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, JsonElement> field : json.entrySet()) {
                if (!field.getValue().isJsonPrimitive() || headers.size() >= 12) continue;
                String key = field.getKey().trim();
                if (!key.matches("[A-Za-z0-9-]{1,40}")) continue;
                String value = field.getValue().getAsString().replace("{{baseUrl}}", host);
                if (value.length() <= 1000 && !value.contains("\r") && !value.contains("\n")) {
                    headers.put(key, value);
                }
            }
        } catch (RuntimeException ignored) { }
        return headers;
    }

    private static String field(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static String simplePath(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.endsWith("[*]")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        return FIELD.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String firstPath(String value) {
        Matcher match = FIELD.matcher(value == null ? "" : value);
        return match.find() ? match.group() : null;
    }

    private static String mediaPath(String ruleContent) {
        Matcher match = MEDIA_FIELD.matcher(ruleContent);
        return match.find() ? match.group(1) : null;
    }

    private static JsonElement readPath(JsonElement root, String path) {
        if (root == null || path == null || !path.startsWith("$.")) return null;
        JsonElement current = root;
        for (String field : path.substring(2).split("\\.")) {
            if (!current.isJsonObject()) return null;
            current = current.getAsJsonObject().get(field);
            if (current == null || current.isJsonNull()) return null;
        }
        return current;
    }
}
