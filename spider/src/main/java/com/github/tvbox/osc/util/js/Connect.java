package com.github.tvbox.osc.util.js;

import android.util.Base64;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.JSUtils;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Connect {
    /** JS 源请求的 tag 前缀;每个源用 "前缀-源key" 作为自己的 tag(取消时才能只取消这一个源) */
    public static final String JS_TAG = "js_okhttp_tag";

    public static Call to(String url, Req req, String tag) {
        OkHttpClient http = OkHttp.client(req.isRedirect(), req.getTimeout());
        return http.newCall(getRequest(url, req, Headers.of(req.getHeader()), tag));
    }

    public static JSObject success(QuickJSContext ctx, Req req, Response res) {
        // try-with-resources:原实现异常分支直接返回 error(ctx),响应体不消费也不关闭 → 连接/套接字泄漏
        try (Response response = res) {
            JSObject jsObject = ctx.createJSObject();
            JSObject jsHeader = ctx.createJSObject();
            setHeader(ctx, response, jsHeader);
            jsObject.set("headers", jsHeader);
            // status 便于 JS 侧区分"请求失败"与"200 空体"(原来 error() 与 200 空体长得一模一样)
            jsObject.set("status", response.code());
            byte[] bytes = readBytesCapped(response.body());
            if (req.getBuffer() == 0) jsObject.set("content", new String(bytes, charset(req, response)));
            if (req.getBuffer() == 1) {
                JSArray array = ctx.createJSArray();
                for (byte aByte : bytes) array.push((int) aByte);
                jsObject.set("content", array);
            }
            if (req.getBuffer() == 2) jsObject.set("content", Base64.encodeToString(bytes, Base64.DEFAULT));
            return jsObject;
        } catch (Throwable e) {
            // 原来是全静默:JS 只看到空 content,排查时无从下手
            LOG.e("Connect", "读取/解码响应失败: " + e);
            return error(ctx);
        }
    }

    /**
     * 读响应体字节,带大小上限。
     * <p>
     * 不能直接用 {@code body().bytes()}:没有任何上限,而 JS 源请求的地址完全由订阅里的规则决定
     * (可能被重定向到大文件或就是无限流),一个响应就能把内存吃穿。源请求的都是接口 JSON/HTML,
     * 24MB 已远超正常量级;超限抛出的异常由 {@link #success} 的 catch 收成 error 返回给 JS。
     */
    private static byte[] readBytesCapped(okhttp3.ResponseBody body) throws IOException {
        return com.github.tvbox.osc.util.HttpBodyReader.readBytes(
                body, com.github.tvbox.osc.util.HttpBodyReader.MAX_BINARY_BYTES);
    }

    /**
     * 响应解码字符集:以<b>响应头</b> Content-Type 的 charset 为准。
     * <p>
     * 原实现用 {@code req.getCharset()},那读的是<b>请求头</b> —— GET 请求通常没有 Content-Type,
     * 于是恒为 UTF-8,GBK 老站必然乱码、规则全不命中;请求头里显式写了 charset 的源仍按它解码(兼容老写法)。
     */
    private static Charset charset(Req req, Response response) {
        Charset fromResponse = null;
        try {
            MediaType type = response == null || response.body() == null ? null : response.body().contentType();
            if (type != null) fromResponse = type.charset();
        } catch (Throwable ignored) {
        }
        if (fromResponse != null) return fromResponse;
        if (req != null) {
            try {
                return Charset.forName(req.getCharset());
            } catch (Throwable ignored) {
                // 非法字符集名(charset="gbk" 这类写法)不再让整段解码失败
            }
        }
        return StandardCharsets.UTF_8;
    }

    public static JSObject error(QuickJSContext ctx) {
        JSObject jsObject = ctx.createJSObject();
        JSObject jsHeader = ctx.createJSObject();
        jsObject.set("headers", jsHeader);
        jsObject.set("content", "");
        jsObject.set("status", 0);
        return jsObject;
    }

    private static Request getRequest(String url, Req req, Headers headers, String tag) {
        if (req.getMethod().equalsIgnoreCase("post")) {
            return new Request.Builder().url(url).tag(tag).headers(headers).post(getPostBody(req, headers.get("Content-Type"))).build();
        } else if (req.getMethod().equalsIgnoreCase("header")) {
            return new Request.Builder().url(url).tag(tag).headers(headers).head().build();
        } else {
            return new Request.Builder().url(url).tag(tag).headers(headers).get().build();
        }
    }

    private static RequestBody getPostBody(Req req, String contentType) {
        if (req.getData() != null && req.getPostType().equals("json")) return getJsonBody(req);
        if (req.getData() != null && req.getPostType().equals("form")) return getFormBody(req);
        if (req.getData() != null && req.getPostType().equals("form-data")) return getFormDataBody(req);
        if (req.getBody() != null && contentType != null) return RequestBody.create(MediaType.get(contentType), req.getBody());
        return RequestBody.create(null, "");
    }

    private static RequestBody getJsonBody(Req req) {
        return RequestBody.create(MediaType.get("application/json"), req.getData().toString());
    }

    private static RequestBody getFormBody(Req req) {
        FormBody.Builder formBody = new FormBody.Builder();
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) formBody.add(key, params.get(key));
        return formBody.build();
    }

    private static RequestBody getFormDataBody(Req req) {
        String boundary = "--dio-boundary-" + new Random().nextInt(42949) + "" + new Random().nextInt(67296);
        MultipartBody.Builder builder = new MultipartBody.Builder(boundary).setType(MultipartBody.FORM);
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.addFormDataPart(key, params.get(key));
        return builder.build();
    }

    private static void setHeader(QuickJSContext ctx, Response res, JSObject object) {
        for (Map.Entry<String, List<String>> entry : res.headers().toMultimap().entrySet()) {
            if (entry.getValue().size() == 1) object.set(entry.getKey(), entry.getValue().get(0));
            if (entry.getValue().size() >= 2) object.set(entry.getKey(), new JSUtils<String>().toArray(ctx, entry.getValue()));
        }
    }
    public static void cancelByTag(Object tag) {
        try {
            // 必须用根 client 的 Dispatcher:OkHttp.client(timeout) 派生出的 client 与根 client
            // 共享同一个 Dispatcher,枚举 running/queued 才能取消到 JS 源真正发出的 call。
            // 原实现读的是本类一个从未被赋值的静态字段(还被同名局部变量遮蔽)→ 恒 null,纯空转,
            // 于是 FastSearchActivity 的 stopAllSourceTasks 对 JS 源毫无作用。
            OkHttpClient root = OkHttp.client();
            if (root != null) {
                for (Call call : root.dispatcher().queuedCalls()) {
                    if (tag.equals(call.request().tag())) call.cancel();
                }
                for (Call call : root.dispatcher().runningCalls()) {
                    if (tag.equals(call.request().tag())) call.cancel();
                }
            }
            // 兜底:core-network 的 HttpClient 走另一个 Dispatcher(OkGoHelper 默认客户端),看不到上面这些 call
            HttpClient.cancel(tag);
        } catch (Exception e) {
            LOG.e(e);
        }
    }
}
