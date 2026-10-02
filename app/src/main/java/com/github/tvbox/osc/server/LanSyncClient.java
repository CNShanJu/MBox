package com.github.tvbox.osc.server;

import android.content.Context;

import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.transfer.ConfigBundle;
import com.github.tvbox.osc.transfer.ConfigImportSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 局域网传输适配：配对、拉取目录及数据；本机合并规则由 ConfigDataExchange 负责。 */
public final class LanSyncClient implements ConfigImportSource {
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private final Context context;
    private final HttpUrl base;
    private final String token;
    private final JsonObject catalog;

    private LanSyncClient(Context context, HttpUrl base, String token, JsonObject catalog) {
        this.context = context.getApplicationContext();
        this.base = base;
        this.token = token;
        this.catalog = catalog;
    }

    public JsonObject catalog() { return catalog; }

    @Override public boolean isConnected() throws Exception {
        Request request = new Request.Builder().url(base.resolve("api/session"))
                .header("X-TVBox-Token", token).build();
        try (Response response = OkGoHelper.getDefaultClient().newCall(request).execute()) {
            if (response.code() == 403) return false;
            if (!response.isSuccessful()) throw new IOException("服务器返回 " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("服务器没有返回连接状态");
            JsonElement parsed = JsonParser.parseString(new String(limitedBytes(body.byteStream(), 1024), StandardCharsets.UTF_8));
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("paired")
                    && parsed.getAsJsonObject().get("paired").getAsBoolean();
        }
    }

    public static LanSyncClient connect(Context context, String address, String code) throws Exception {
        if (context == null || code == null || !code.matches("[0-9]{8}")) throw new IOException("请输入 8 位配对码");
        HttpUrl parsed = HttpUrl.parse(address == null ? "" : address.trim());
        if (parsed == null || !(parsed.scheme().equals("http") || parsed.scheme().equals("https"))
                || !parsed.encodedPath().equals("/") || parsed.query() != null || parsed.username().length() > 0) {
            throw new IOException("请输入另一台 MBox 的完整局域网地址");
        }
        InetAddress host = literalAddress(parsed.host());
        byte[] hostBytes = host.getAddress();
        boolean uniqueLocalV6 = hostBytes.length == 16 && (hostBytes[0] & 0xfe) == 0xfc;
        if (!host.isSiteLocalAddress() && !host.isLoopbackAddress()
                && !host.isLinkLocalAddress() && !uniqueLocalV6) {
            throw new IOException("地址必须是局域网设备");
        }
        HttpUrl base = parsed.newBuilder().encodedPath("/").build();
        Request pair = new Request.Builder().url(base.resolve("api/pair"))
                .header("X-MBox-Client", "mbox")
                .post(new FormBody.Builder().add("code", code).build()).build();
        JsonObject paired = readJson(pair, MAX_JSON_BYTES);
        String token = string(paired, "token");
        if (!token.matches("[0-9a-f]{32}")) throw new IOException("配对失败，请检查配对码");
        JsonObject catalog = readJson(new Request.Builder().url(base.resolve("api/lan/catalog"))
                .header("X-TVBox-Token", token).build(), MAX_JSON_BYTES);
        if (!catalog.has("schema") || catalog.get("schema").getAsInt() != 1) throw new IOException("另一台设备的数据格式不兼容");
        if (!catalog.has("archiveSchema") || catalog.get("archiveSchema").getAsInt() != 1)
            throw new IOException("另一台 MBox 版本较旧，不支持配置 ZIP 传输，请先更新");
        return new LanSyncClient(context, base, token, catalog);
    }

    /** 只接受 IP 字面量，避免校验一次 DNS 后实际请求被重绑定到公网地址。 */
    private static InetAddress literalAddress(String host) throws Exception {
        if (host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = host.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                int value = Integer.parseInt(parts[i]);
                if (value > 255) throw new IOException("局域网地址格式无效");
                bytes[i] = (byte) value;
            }
            return InetAddress.getByAddress(bytes);
        }
        if (host.indexOf(':') >= 0) return InetAddress.getByName(host);
        throw new IOException("请输入局域网 IP 地址");
    }

    /** 逐类导入；已有数据保留，设置类按用户确认覆盖。 */
    public String importSelected(Set<String> selected) throws Exception {
        if (selected == null || selected.isEmpty()) throw new IOException("请至少选一类数据");
        HttpUrl.Builder url = base.newBuilder().addPathSegments("api/lan/archive");
        url.addQueryParameter("categories", android.text.TextUtils.join(",", selected));
        File archive = File.createTempFile("lan_config_", ".zip", context.getCacheDir());
        try {
            download(new Request.Builder().url(url.build()).header("X-TVBox-Token", token).build(),
                    archive, ConfigBundle.MAX_ARCHIVE_BYTES);
            return new ConfigBundle(context).importSelected(archive, selected);
        } finally {
            archive.delete();
        }
    }

    private static void download(Request request, File dest, long maxBytes) throws Exception {
        try (Response response = OkGoHelper.getDefaultClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 403) throw new IOException("配对已失效");
                String detail = response.body() == null ? ""
                        : new String(limitedBytes(response.body().byteStream(), 1024), StandardCharsets.UTF_8).trim();
                throw new IOException(detail.isEmpty() ? "配置包下载失败：" + response.code() : detail);
            }
            if (response.body() == null) throw new IOException("配置包响应为空");
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192]; int read; long total = 0;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > maxBytes) throw new IOException("配置包超过 64 MB");
                    out.write(buffer, 0, read);
                }
            }
        }
    }

    private static String string(JsonObject object, String name) {
        try { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : ""; }
        catch (Throwable ignored) { return ""; }
    }

    private static JsonObject readJson(Request request, int maxBytes) throws Exception {
        try (Response response = OkGoHelper.getDefaultClient().newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException(response.code() == 403 ? "配对码不正确或连接已失效" : "服务器返回 " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("服务器没有返回数据");
            byte[] bytes = limitedBytes(body.byteStream(), maxBytes);
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("服务器数据格式不正确");
            return parsed.getAsJsonObject();
        }
    }

    private static byte[] limitedBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > limit) throw new IOException("数据超过可导入大小");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
