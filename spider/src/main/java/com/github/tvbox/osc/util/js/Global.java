package com.github.tvbox.osc.util.js;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;


import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.rsa.RSAEncrypt;
import com.whl.quickjs.wrapper.ContextSetter;
import com.whl.quickjs.wrapper.Function;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.JSUtils;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class Global {
    private QuickJSContext runtime;
    public ExecutorService executor;
    private final Timer timer;
    /** 本源 HTTP 请求的 tag(取消在跑请求时按它匹配,见 Connect.cancelByTag) */
    private final String tag;

    public Global(ExecutorService executor, String tag) {
        this.executor = executor;
        this.tag = tag == null ? Connect.JS_TAG : tag;
        // 守护线程:原来 new Timer() 是非守护线程且从不 cancel,每建一个源就常驻一条线程
        this.timer = new Timer("js-timeout", true);
    }

    /** 源销毁时停掉定时器(见 JsSpider.destroyNow),否则线程与已排队的任务一直留着 */
    public void shutdown() {
        try {
            timer.cancel();
        } catch (Throwable ignored) {
        }
    }

    @Keep
    @Function
    public String getProxy(boolean local) {
        return com.github.tvbox.osc.api.ApiConfig.getLanBase() + "proxy?do=js";
    }

    @Keep
    @Function
    public String js2Proxy(Boolean dynamic, Integer siteType, String siteKey, String url, JSObject headers) {
        // headers 缺省(源只传 4 个参数或传 undefined)时原来直接 NPE:整个 Java 调用失败,
        // JS 侧拿不到代理地址 → 代理播放全线失效,排查时还没有任何线索
        String header = headers == null ? "{}" : headers.toJsonString();
        return getProxy(true) + "&from=catvod" + "&siteType=" + siteType + "&siteKey=" + siteKey + "&header=" + URLEncoder.encode(header) + "&url=" + URLEncoder.encode(url);
    }

    @Keep
    @Function
    public String joinUrl(String parent, String child) {
        return HtmlParser.joinUrl(parent, child);
    }

    @Keep
    @Function
    public String pd(String html, String rule, String add_url) {
        return HtmlParser.parseDomForUrl(html, rule, add_url);
    }

    @Keep
    @Function
    public String pdfh(String html, String rule) {
        return HtmlParser.parseDomForUrl(html, rule, "");
    }

    @Keep
    @Function
    public JSArray pdfa(String html, String rule) {

        return new JSUtils<String>().toArray(runtime, HtmlParser.parseDomForArray(html, rule));
    }

    @Keep
    @Function
    public JSArray pdfla(String html, String p1, String list_text, String list_url, String add_url) {
        return new JSUtils<String>().toArray(runtime, HtmlParser.parseDomForList(html, p1, list_text, list_url, add_url));
    }

    @Keep
    @Function
    public String s2t(String text) {
        try {
            return Trans.s2t(false, text);
        } catch (Exception e) {
            return "";
        }
    }

    @Keep
    @Function
    public String t2s(String text) {
        try {
            return Trans.t2s(false, text);
        } catch (Exception e) {
            return "";
        }
    }

    @Keep
    @Function
    public String aesX(String mode, boolean encrypt, String input, boolean inBase64, String key, String iv, boolean outBase64) {
        String result = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64);
        //LOG.e("aesX",String.format("mode:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:%s\niv:%s\ninput:\n%s\nresult:\n%s", mode, encrypt, inBase64, outBase64, key, iv, input, result));
        return result;
    }

    @Keep
    @Function
    public String rsaX(String mode, boolean pub, boolean encrypt, String input, boolean inBase64, String key, boolean outBase64) {
        String result = Crypto.rsa(pub, encrypt, input, inBase64, key, outBase64);
        //LOG.e("aesX",String.format("mode:%s\npub:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:\n%s\ninput:\n%s\nresult:\n%s", mode, pub, encrypt, inBase64, outBase64, key, input, result));
        return result;
    }

    @Keep
    @Function
    public String rsaEncrypt(String data, String key) {
        return  rsaEncrypt(data, key, null);
    }
    /**
     * RSA 加密
     *
     * @param data    要加密的数据
     * @param key     密钥，type 为 1 则公钥，type 为 2 则私钥
     * @param options 加密的选项，包含加密配置和类型：{ config: "RSA/ECB/PKCS1Padding", type: 1, long: 1 }
     *                config 加密的配置，默认 RSA/ECB/PKCS1Padding （可选）
     *                type 加密类型，1 公钥加密 私钥解密，2 私钥加密 公钥解密（可选，默认 1）
     *                long 加密方式，1 普通，2 分段（可选，默认 1）
     *                block 分段长度，false 固定117，true 自动（可选，默认 true ）
     * @return 返回加密结果
     */

    @Keep
    @Function
    public String rsaEncrypt(String data, String key, JSObject options) {
        int mLong = 1;
        int mType = 1;
        boolean mBlock = true;
        String mConfig = null;
        if (options != null) {
            JSONObject op = options.toJsonObject();
            if (op.has("config")) {
                try {
                    mConfig = (String) op.get("config");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (op.has("type")) {
                try {
                    mType = ((Double) op.get("type")).intValue();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (op.has("long")) {
                try {
                    mLong = ((Double) op.get("long")).intValue();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = (Boolean) op.get("block");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        try {
            switch (mType) {
                case 1:
                    if (mConfig != null) {
                        return RSAEncrypt.encryptByPublicKey(data, key, mConfig, mLong, mBlock);
                    } else {
                        return RSAEncrypt.encryptByPublicKey(data, key, mLong, mBlock);
                    }
                case 2:
                    if (mConfig != null) {
                        return RSAEncrypt.encryptByPrivateKey(data, key, mConfig, mLong, mBlock);
                    } else {
                        return RSAEncrypt.encryptByPrivateKey(data, key, mLong, mBlock);
                    }
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    @Keep
    @Function
    public String rsaDecrypt(String encryptBase64Data, String key) {
        return  rsaDecrypt(encryptBase64Data, key, null);
    }

    /**
     * RSA 解密
     *
     * @param encryptBase64Data 加密后的 Base64 字符串
     * @param key               密钥，type 为 1 则私钥，type 为 2 则公钥
     * @param options           解密的选项，包含解密配置和类型：{ config: "RSA/ECB/PKCS1Padding", type: 1, long: 1 }
     *                          config 解密的配置，默认 RSA/ECB/PKCS1Padding （可选）
     *                          type 解密类型，1 公钥加密 私钥解密，2 私钥加密 公钥解密（可选，默认 1）
     *                          long 解密方式，1 普通，2 分段（可选，默认 1）
     *                          block 分段长度，false 固定128，true 自动（可选，默认 true ）
     * @return 返回解密结果
     */
    @Keep
    @Function
    public String rsaDecrypt(String encryptBase64Data, String key, JSObject options) {
        int mLong = 1;
        int mType = 1;
        boolean mBlock = true;
        String mConfig = null;
        if (options != null) {
            JSONObject op = options.toJsonObject();
            if (op.has("config")) {
                try {
                    mConfig = (String) op.get("config");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (op.has("type")) {
                try {
                    mType = ((Double) op.get("type")).intValue();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (op.has("long")) {
                try {
                    mLong = ((Double) op.get("long")).intValue();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = (Boolean) op.get("block");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        try {
            switch (mType) {
                case 1:
                    if (mConfig != null) {
                        return RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mConfig, mLong, mBlock);
                    } else {
                        return RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mLong, mBlock);
                    }
                case 2:
                    if (mConfig != null) {
                        return RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mConfig, mLong, mBlock);
                    } else {
                        return RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mLong, mBlock);
                    }
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private JSObject req(String url, JSObject options) {
        try {
            Req req = Req.objectFrom(options.toJsonObject().toString());
            Response res = Connect.to(url, req, tag).execute();
            return Connect.success(runtime, req, res);
        } catch (Exception e) {
            // 原来静默返回 error():JS 侧只知道"空内容",看不出是网络失败还是源写错了
            LOG.e("js-http", url + " 请求失败(" + dnsHint() + "): " + e);
            return Connect.error(runtime);
        }
    }

    /**
     * 失败日志里带上"这次用的是哪套解析器"(见 {@link com.github.catvod.net.OkHttp#dnsName()})。
     * <p>
     * {@code UnknownHostException} 这类"App 解析不了、浏览器能打开"的问题只有两个可能:
     * 安全 DNS(腾讯/阿里/360 的 DoH 会对部分域名返回空)或系统 DNS,不写出来就只能靠猜。
     */
    private static String dnsHint() {
        try {
            return com.github.catvod.net.OkHttp.dnsName();
        } catch (Throwable th) {
            return "DNS=未知";
        }
    }

    @Keep
    @Function
    public JSObject _http(String url, JSObject options) {
        JSFunction complete = options.getJSFunction("complete");
        if (complete == null) return req(url, options);
        Req req = Req.objectFrom(options.toJsonObject().toString());
        // 回调要跨线程执行,必须先 hold:否则 JS 侧丢掉引用后 QuickJS 可能回收它,回调时崩 native
        complete.hold();
        try {
            Connect.to(url, req, tag).enqueue(getCallback(complete, req));
        } catch (Throwable th) {
            // 请求构造失败(非法 URL 等):回调永远不会来,必须释放,并把失败明确回给 JS
            LOG.e("js-http", url + " 异步请求发起失败: " + th);
            complete.release();
            return Connect.error(runtime);
        }
        return null;
    }

    @Keep
    @Function
    public void setTimeout(JSFunction func, Integer delay) {
        // JS 少传参数时 delay 为 null,原来直接拆箱成 long → NPE
        long delayMs = delay == null ? 0L : Math.max(0L, delay.longValue());
        if (func == null) return;
        func.hold();
        try {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (executor.isShutdown()) {
                        func.release();
                        return;
                    }
                    try {
                        executor.submit(() -> {
                            try {
                                func.call();
                            } catch (Throwable th) {
                                LOG.e("js-setTimeout", th);
                            } finally {
                                // hold 必须成对 release:轮询型 JS 源(定时器 + 递归 setTimeout 很常见)
                                // 从不释放会让 QuickJS 堆无界增长
                                func.release();
                            }
                        });
                    } catch (Throwable th) {
                        // 执行器已关/队列满:这条回调不会跑了,同样要释放
                        func.release();
                    }
                }
            }, delayMs);
        } catch (Throwable th) {
            // 定时器已 cancel(源已销毁):必须释放,否则这条 JS 函数永远出不去
            func.release();
        }
    }

    private Callback getCallback(JSFunction complete, Req req) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response res) {
                submitComplete(complete, () -> complete.call(Connect.success(runtime, req, res)));
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                LOG.e("js-http", "异步请求失败: " + call.request().url() + " " + e);
                submitComplete(complete, () -> complete.call(Connect.error(runtime)));
            }
        };
    }

    /** 回调统一回到 QuickJS 自己的线程上执行,并在跑完后释放 hold */
    private void submitComplete(JSFunction complete, Runnable task) {
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Throwable th) {
                    LOG.e("js-http", th);
                } finally {
                    complete.release();
                }
            });
        } catch (Throwable th) {
            // 执行器已关(源已销毁):回调不会执行,同样要释放
            complete.release();
        }
    }
    @Keep
    // 声明用于依赖注入的 QuickJSContext
    @ContextSetter
    public void setJSContext(QuickJSContext runtime) {
        this.runtime = runtime;
    }

}