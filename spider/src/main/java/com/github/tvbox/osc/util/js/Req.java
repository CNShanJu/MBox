package com.github.tvbox.osc.util.js;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Req {

    @SerializedName("buffer")
    private Integer buffer;
    @SerializedName("redirect")
    private Integer redirect;
    @SerializedName("timeout")
    private Integer timeout;
    @SerializedName("postType")
    private String postType;
    @SerializedName("method")
    private String method;
    @SerializedName("body")
    private String body;
    @SerializedName("data")
    private JsonElement data;
    @SerializedName("headers")
    private JsonElement headers;

    public static Req objectFrom(String json) {
        return new Gson().fromJson(json, Req.class);
    }

    public int getBuffer() {
        return buffer == null ? 0 : buffer;
    }

    public Integer getRedirect() {
        return redirect == null ? 1 : redirect;
    }

    public Integer getTimeout() {
        return timeout == null ? 10000 : timeout;
    }

    public String getPostType() {
        return TextUtils.isEmpty(postType) ? "json" : postType;
    }

    public String getMethod() {
        return TextUtils.isEmpty(method) ? "get" : method;
    }

    public String getBody() {
        return body;
    }

    public JsonElement getData() {
        return data;
    }

    private JsonElement getHeaders() {
        return headers;
    }

    public Map<String, String> getHeader() {
        return Json.toMap(getHeaders());
    }

    public boolean isRedirect() {
        return getRedirect() == 1;
    }

    public String getCharset() {
        Map<String, String> header = getHeader();
        List<String> keys = Arrays.asList("Content-Type", "content-type");
        for (String key : keys) {
            String value = header.get(key);
            if (value != null) return getCharset(value);
        }
        return "UTF-8";
    }

    /**
     * 从 Content-Type 值里取字符集名。
     * <p>
     * 原实现 {@code text.split("=")[1]}:写着 {@code charset=} 而值为空时数组越界,
     * {@code charset="gbk"} 这种带引号的写法又不是合法字符集名 —— 两种都被 Connect.success
     * 吞成 {@code content:""},表现为"分类/首页空白且没有任何日志"。
     */
    private String getCharset(String value) {
        if (value == null) return "UTF-8";
        for (String text : value.split(";")) {
            int idx = text.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx < 0) continue;
            String charset = text.substring(idx + "charset=".length()).trim();
            if (charset.length() >= 2 && ((charset.startsWith("\"") && charset.endsWith("\""))
                    || (charset.startsWith("'") && charset.endsWith("'")))) {
                charset = charset.substring(1, charset.length() - 1).trim();
            }
            if (!charset.isEmpty()) return charset;
        }
        return "UTF-8";
    }
}
