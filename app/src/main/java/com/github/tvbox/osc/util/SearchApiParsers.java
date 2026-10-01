package com.github.tvbox.osc.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页两个公开接口的解析(纯逻辑,不碰 Android,便于 JVM 单测):
 * <ul>
 *   <li><b>最近热搜</b> = 360影视排行 {@code https://api.web.360kan.com/v1/rank?cat=3}
 *       (cat:1=动漫 2=电影 3=电视剧 4=综艺,与返回里 data[].cat 的编号错一位);
 *       取 {@code data[].title} 前 {@value #HOT_LIMIT} 条,页面按两列带序号展示;</li>
 *   <li><b>相关搜索(输入联想)</b> = 爱奇艺联想 {@code https://suggest.video.iqiyi.com/?if=mobile&key=<关键词>}
 *       (吃拼音出中文,如 fayi → 法医秦明…);取 {@code data[].name}。</li>
 * </ul>
 * 两个接口偶发返回 HTML/错误页,一律 lenient 解析 + 任何异常都返回空列表(页面据此收起对应区块,不刷屏)。
 * <p>口径来源:2026-10-01 用 adb 抓本地 NewBox(com.github.tvbox.osd)搜索页 ——
 * 屏上「最近热搜」10 条与 {@code rank?cat=3} 的返回顺序逐条一致;输入 fayi 的「相关搜索」与
 * {@code suggest.video.iqiyi.com} 返回逐条一致。
 */
public final class SearchApiParsers {

    private SearchApiParsers() {
    }

    /** 360 排行接口地址(cat=3 电视剧;页面要调其它类别时改这一个常量即可) */
    public static final String HOT_RANK_URL = "https://api.web.360kan.com/v1/rank?cat=3";

    /** 爱奇艺联想接口(拼接已编码的关键词) */
    public static final String SUGGEST_URL_PREFIX = "https://suggest.video.iqiyi.com/?if=mobile&key=";

    /** 最近热搜最多展示多少条(NewBox 也是 10 条,两列各 5) */
    public static final int HOT_LIMIT = 10;

    /** 相关搜索最多展示多少条 */
    public static final int SUGGEST_LIMIT = 10;

    /** 解析 360 排行:data[].title,最多 {@link #HOT_LIMIT} 条;失败返回空列表 */
    public static List<String> parseHotRank(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JsonObject root = lenientParse(json).getAsJsonObject();
            JsonElement data = root.get("data");
            if (data == null || !data.isJsonArray()) return out;
            JsonArray arr = data.getAsJsonArray();
            for (JsonElement ele : arr) {
                if (ele == null || !ele.isJsonObject()) continue;
                JsonElement title = ele.getAsJsonObject().get("title");
                if (title == null || title.isJsonNull()) continue;
                String name = title.getAsString().trim();
                if (name.isEmpty() || out.contains(name)) continue;
                out.add(name);
                if (out.size() >= HOT_LIMIT) break;
            }
        } catch (Throwable ignore) {
            // 接口偶发返回非 JSON(HTML/错误页):当作没有热搜,页面收起该区块
        }
        return out;
    }

    /** 解析爱奇艺联想:data[].name,最多 {@link #SUGGEST_LIMIT} 条;失败返回空列表 */
    public static List<String> parseSuggest(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JsonObject root = lenientParse(json).getAsJsonObject();
            JsonElement data = root.get("data");
            if (data == null || !data.isJsonArray()) return out;
            JsonArray arr = data.getAsJsonArray();
            for (JsonElement ele : arr) {
                if (ele == null || !ele.isJsonObject()) continue;
                JsonElement name = ele.getAsJsonObject().get("name");
                if (name == null || name.isJsonNull()) continue;
                String word = name.getAsString().trim();
                if (word.isEmpty() || out.contains(word)) continue;
                out.add(word);
                if (out.size() >= SUGGEST_LIMIT) break;
            }
        } catch (Throwable ignore) {
            // 同上:联想拿不到就不显示,不影响搜索本身
        }
        return out;
    }

    /** 关键词拼进 URL(query 段用 UTF-8 百分号编码,中文/空格/& 都不会破坏 URL) */
    public static String suggestUrl(String keyword) {
        return SUGGEST_URL_PREFIX + encode(keyword == null ? "" : keyword);
    }

    /** query 段编码:只保留 RFC3986 未保留字符,其余按 UTF-8 逐字节 %XX */
    public static String encode(String raw) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes;
        try {
            bytes = raw.getBytes("UTF-8");
        } catch (Throwable th) {
            bytes = raw.getBytes();
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** 两列网格中行优先位置对应的列优先源下标。 */
    public static int columnMajorIndex(int position, int total, int columns) {
        if (position < 0 || total <= 0 || columns <= 0) return 0;
        if (position >= total) return total - 1;   // 位置越界:交给最后一条(调用方本就只在有效位置用)
        int rows = (total + columns - 1) / columns;
        int row = position / columns;
        int col = position % columns;
        int index = col * rows + row;
        return index >= total ? total - 1 : index;
    }

    private static JsonElement lenientParse(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }
}
