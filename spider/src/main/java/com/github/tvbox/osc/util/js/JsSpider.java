package com.github.tvbox.osc.util.js;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.whl.quickjs.wrapper.Function;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.JSUtils;
import com.whl.quickjs.wrapper.QuickJSContext;
import com.whl.quickjs.wrapper.UriUtil;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import java9.util.concurrent.CompletableFuture;

public class JsSpider extends Spider {

    /** 单次 JS 调用超时:源里的 Promise 不 settle 时,不能让调用线程永久挂在 join() 上 */
    private static final long CALL_TIMEOUT_MS = 30_000;
    /** 模块编译/初次加载超时:超大 JS 源可以慢,但不能无限等(creation 锁按此上限被释放) */
    private static final long INIT_TIMEOUT_MS = 60_000;
    /** 销毁时留给 JS 线程的机会:等不到说明它被卡死的脚本占着(见 destroyNow) */
    private static final long DESTROY_TIMEOUT_MS = 1_000;
    /** 卡死后给 UI 的说法(与 SpiderFaults 里其它"源不可用"措辞同风格,由页面直接展示) */
    private static final String WEDGED_REASON = "该源脚本卡死(疑似死循环),本次已跳过;重载订阅或换源可恢复";

    private final ExecutorService executor;
    private final Class<?> dex;
    private QuickJSContext ctx;
    /** setTimeout 的定时器持有者(销毁时必须停,否则每源留一个常驻线程) */
    private Global global;
    private JSObject jsObject;
    private final String key;
    /** 订阅里的站点 key(未加 "J"+MD5 前缀):卡死登记故障时要用它,UI 是按这个 key 查的 */
    private final String siteKey;
    private final String api;
    /** 本源所有 HTTP 请求的 tag:取消时只取消这一个源(原来所有源共用一个 tag) */
    private final String tag;
    private boolean cat;

    /** 生命周期门:退役判定与"在跑的调用数"必须原子,否则销毁可能撞上刚开始的调用 */
    private final Object lifecycleLock = new Object();
    /** 正在跑的 JS 调用数 */
    private int activeCalls;
    /** 已退役(请求销毁):不再接受新调用,避免踩已销毁的 QuickJS 上下文(native 崩) */
    private boolean retired;
    private final AtomicBoolean destroyed = new AtomicBoolean();
    /** 有一次调用超时:该源的 JS 线程已被卡死脚本占住,后续调用直接失败,别再往队列里堆任务 */
    private volatile boolean wedged;

    public JsSpider(String key, String api, Class<?> cls) throws Exception {
        this.siteKey = key;
        this.key = "J" + MD5.encode(key);
        this.tag = Connect.JS_TAG + "-" + this.key;
        final String threadName = "js-spider-" + this.key;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        this.api = api;
        this.dex = cls;
        try {
            initializeJS();
        } catch (Throwable th) {
            // 构造失败也要回收:这里已经建了 QuickJS 上下文与单线程 executor,而实例引用
            // 永远不会被赋值(构造抛异常) → 不销毁就再也没人能销毁它(反复导入坏订阅即累积泄漏)
            destroyNow();
            throw th instanceof Exception ? (Exception) th : new Exception(th);
        }
    }
    public void cancelByTag() {
        // 同时解除"卡死"标记:卡死多数是被挂住的 HTTP 请求造成的,取消后 JS 线程即释放;
        // 不解除的话这条源要等到重载订阅才能再被使用(而搜索页销毁就会调到这里)
        wedged = false;
        // 故障登记同步撤掉:这是一次"复活尝试",真死了下次调用会再登记(否则冷却期内 UI 会一直说它卡死)
        try {
            com.github.catvod.crawler.SpiderFaults.get().markAvailable(siteKey);
        } catch (Throwable ignored) {
        }
        Connect.cancelByTag(tag);
    }

    /**
     * 当前没有任何在跑的调用、且未退役、也<b>没有卡死</b> —— 可被 JsLoader 的 LRU 回收
     * (见 JsLoader.trimLiveSpiders)。
     * <p>
     * 卡死的源必须排除在外:卡死 = 那条 {@code js-spider-*} 线程还在 native 里跑死循环,
     * 回收(从缓存摘掉)之后再有调用就会<b>重建</b>一个新实例 → 新线程 + 新 QuickJS 运行时,
     * 而旧的死循环线程并不会因此停下,等于"多烧一个核"。留在缓存里反而更安全:
     * 后续调用命中 {@link #wedged} 直接返回 null,不会再建运行时、也不会再堆任务。
     */
    public boolean isIdle() {
        synchronized (lifecycleLock) {
            return activeCalls == 0 && !retired && !wedged;
        }
    }

    private void submit(Runnable runnable) {
        executor.submit(runnable);
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    /** 在 JS 自己的线程上跑一段逻辑并等结果(带超时;超时后标记卡死,不再堆任务) */
    private <T> T submitAndWait(Callable<T> callable, long timeoutMs) throws Exception {
        return submitAndWait(callable, timeoutMs, true);
    }

    /**
     * @param registerWedge 超时是否登记"该源卡死"。销毁路径要传 false:那是我们自己发起的销毁在等
     *                      JS 线程(它可能只是正好在跑一个异步回调),并不代表源脚本卡死 ——
     *                      误报会让页面把一个好源显示成"卡死,请换源"。
     */
    private <T> T submitAndWait(Callable<T> callable, long timeoutMs, boolean registerWedge) throws Exception {
        try {
            return submit(callable).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            wedged = true;
            if (registerWedge) markWedged(WEDGED_REASON);
            throw new Exception("JS 线程超时(" + timeoutMs + "ms)", te);
        }
    }

    /**
     * 登记"该源已卡死"给 UI(见 {@link com.github.catvod.crawler.SpiderFaults})。
     * <p>
     * SpiderFaults 的定位是"确定性故障"、明确排除一次性网络失败;卡死属于前者:
     * 那条 {@code js-spider-*} 线程在 native 死循环里出不来,直到重载订阅都不会恢复,
     * 页面必须给出说法(而不是一直显示"暂无数据"),用户才知道该换源。
     */
    private void markWedged(String reason) {
        try {
            com.github.catvod.crawler.SpiderFaults.get().markUnavailable(siteKey, reason);
        } catch (Throwable ignored) {
        }
    }

    private Object call(String func, Object... args) throws Exception {
        // JS 模块未正确初始化(内容缺失/加载失败导致 jsObject==null)时直接返回 null,
        // 而不是走进 Async.run 抛 NPE(一条源挂掉不再波及整次调用)。
        if (jsObject == null) return null;
        synchronized (lifecycleLock) {
            if (retired) return null;
            if (wedged) {
                // 上一次调用超时:这条源的 JS 线程已被卡死脚本占住,继续提交只会排队堆积
                LOG.e("QuJs", key + " " + func + " 跳过:该源已卡死(重载订阅后恢复)");
                return null;
            }
            activeCalls++;
        }
        try {
            long deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS;
            // 注意两层 future:外层只负责"在 JS 线程上发起调用",内层才是源的 Promise。
            // 原来写成 supplyAsync(...).join().get():join() 无超时,源不返回就永久 park 住调用线程,
            // 道(spinner lane)被占死,首页/分类/搜索/播放随之全线无响应且没有任何日志。
            CompletableFuture<Object> promise = CompletableFuture
                    .supplyAsync(() -> Async.run(jsObject, func, args), executor)
                    .get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (promise == null) return null;
            long remain = deadline - System.currentTimeMillis();
            return promise.get(Math.max(remain, 1L), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            wedged = true;
            // 卡死必须让用户看得见:这条源的 JS 线程会一直占着一个核(QuickJS 不吃线程中断,
            // 详见 destroyNow 的说明),页面不能只显示"暂无数据"。登记原因后,详情页/列表页会给出
            // "该源脚本卡死(已跳过)"这类说法,用户能据此换源。
            markWedged(WEDGED_REASON);
            LOG.e("QuJs", key + " " + func + " 超时(" + CALL_TIMEOUT_MS + "ms),该源标记为卡死");
            return null;
        } finally {
            boolean last;
            synchronized (lifecycleLock) {
                last = --activeCalls == 0 && retired;
            }
            if (last) destroyNow();
        }
    }

    private JSObject cfg(String ext) {
        JSObject cfg = ctx.createJSObject();
        cfg.set("stype", 3);
        cfg.set("skey", key);
        if (Json.invalid(ext)) cfg.set("ext", ext);
        else cfg.set("ext", (JSObject) ctx.parse(ext));
        return cfg;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        if (cat) {
            call("init", submitAndWait(() -> cfg(extend), CALL_TIMEOUT_MS));
            return;
        }
        // ext 是 JSON(如抓页面源的站点配置)时要 parse 成对象交给 JS,但 ctx.parse 必须在
        // QuickJS 自己的线程上跑:以前直接在调用线程 parse,抛 "Must be call same thread" →
        // 源初始化失败、首页/分类/搜索全空(线上实例:姐姐视频抓页面源)。
        if (Json.valid(extend)) {
            call("init", submitAndWait(() -> ctx.parse(extend), CALL_TIMEOUT_MS));
        } else {
            call("init", extend);
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return (String) call("home", filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return (String) call("homeVod");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSObject obj = submitAndWait(() -> new JSUtils<String>().toObj(ctx, extend), CALL_TIMEOUT_MS);
        return (String) call("category", tid, pg, filter, obj);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return null;  // ids.get(0) 原来对空表直接 IndexOutOfBounds
        return (String) call("detail", ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return (String) call("search", key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return (String) call("search", key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSArray array = submitAndWait(() -> new JSUtils<String>().toArray(ctx, vipFlags), CALL_TIMEOUT_MS);
        return (String) call("play", flag, id, array);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return (Boolean) call("sniffer");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return (Boolean) call("isVideo", url);
    }

    @Override
    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        // 注意:不要在这里再包一层 submit —— proxy1/proxy2 内部各自已在 JS 线程上执行,
        // 外层再提交一次会在单线程 executor 上自等自己(旧实现的 submit(() -> proxy1(...)) 即此坑)
        if ("catvod".equals(params.get("from"))) return proxy2(params);
        return proxy1(params);
    }

    /**
     * 退役并销毁。默认等"当前没有在跑的调用"时才真正销毁。
     * <p>
     * 背景:清空源实例(JsLoader.load)发生在搜索页销毁时,而首页/详情/播放器手里可能还握着
     * 同一个实例。原实现立刻 {@code shutdownNow()+ctx.destroy()},那些页面随后再调用就是
     * {@code RejectedExecutionException},或者踩到已销毁的 QuickJS 上下文直接崩在 native。
     */
    @Override
    public void destroy() {
        boolean idle;
        synchronized (lifecycleLock) {
            retired = true;
            idle = activeCalls == 0;
        }
        if (idle) destroyNow();
    }

    /** 真正销毁(幂等):停定时器 + 销毁 QuickJS 上下文 + 回收线程 */
    private void destroyNow() {
        if (!destroyed.compareAndSet(false, true)) return;
        // QuickJS 上下文只能在创建它的线程上销毁:优先交给 JS 线程做。
        // 若 JS 线程被卡死的脚本占着(等不到),就放弃销毁上下文:跨线程 destroy() 会崩 native,
        // 这里选择"漏一块 native 内存"而不是闪退,同时仍停掉定时器并回收线程。
        //
        // 代价必须写清楚:这条 js-spider-* 线程会继续在 native 里跑那个死循环 —— 一个核 100%,
        // 息屏也不会停(亮屏动画会被 vsync 停掉,线程不会)。这个封装没有暴露中断接口
        // (见 createCtx 的说明),进程内无法真正打断它。所以配套的约束是:
        // 卡死的实例绝不能被"回收后重建"(isIdle 把 wedged 排除在外),否则每个卡死源会多吃一条线程。
        boolean cleaned = false;
        try {
            submitAndWait(() -> {
                try {
                    if (global != null) global.shutdown();
                    if (ctx != null) ctx.destroy();
                } catch (Throwable th) {
                    LOG.e("QuJs", th);
                }
                return null;
            }, DESTROY_TIMEOUT_MS, false);
            cleaned = true;
        } catch (Throwable th) {
            LOG.e("QuJs", key + " 销毁未完成(JS 线程被卡死脚本占住),放弃上下文销毁并回收线程;"
                    + "该线程会继续占用一个核,直到进程结束(重载订阅只会换新实例,不会停它)");
        }
        executor.shutdownNow();
        if (!cleaned && global != null) global.shutdown();
    }

    private static final String SPIDER_STRING_CODE = "import * as spider from '%s'\n\n" +
            "if (!globalThis.__JS_SPIDER__) {\n" +
            "    if (spider.__jsEvalReturn) {\n" +
            "        globalThis.req = http\n" +
            "        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()\n" +
            "        globalThis.__JS_SPIDER__.is_cat = true\n" +
            "    } else if (spider.default) {\n" +
            "        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default\n" +
            "    }\n" +
            "}";
    private void initializeJS() throws Exception {
        try {
            submit(() -> {
                if (ctx == null) createCtx();
                if (dex != null) createDex();

                String content = FileUtils.loadModule(api);
                if (TextUtils.isEmpty(content)) {
                    LOG.e("QuJs", "JS 模块内容为空: " + api);
                    return null;
                }

                if (content.startsWith("//bb")) {
                    cat = true;
                    byte[] b = Base64.decode(content.replace("//bb", ""), 0);
                    ctx.execute(byteFF(b), key + ".js");
                    ctx.evaluateModule(String.format(SPIDER_STRING_CODE, key + ".js") + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
//                ctx.execute(byteFF(b), key + ".js","__jsEvalReturn");
//                ctx.evaluate("globalThis." + key + " = __JS_SPIDER__;");
                } else {
                    if (content.contains("__JS_SPIDER__")) {
                        content = content.replaceAll("__JS_SPIDER__\\s*=", "export default ");
                    }
                    String moduleExtName = "default";
                    if (content.contains("__jsEvalReturn") && !content.contains("export default")) {
                        moduleExtName = "__jsEvalReturn";
                        cat = true;
                    }
                    ctx.evaluateModule(content, api);
                    ctx.evaluateModule(String.format(SPIDER_STRING_CODE, api) + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
//                ctx.evaluateModule(content, api, moduleExtName);
//                ctx.evaluate("globalThis." + key + " = __JS_SPIDER__;");
                }
                jsObject = (JSObject) ctx.get(ctx.getGlobalObject(), key);
                if (jsObject == null) LOG.e("QuJs", "JS 模块未导出 __JS_SPIDER__: " + api);
                return null;
            }).get(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // 原实现无超时:一个卡死的源会把调用线程(以及创建锁)永远按住
            throw new Exception("JS 模块初始化超时(" + INIT_TIMEOUT_MS + "ms): " + api, te);
        }
    }

    public static byte[] byteFF(byte[] bytes) {
        // 头 5 字节是格式头(版本/长度),载荷从第 5 字节起。短于此长度的字节码原来会
        // 直接 new byte[length-4] → NegativeArraySizeException,或 arraycopy 越界
        if (bytes == null || bytes.length <= 5) {
            LOG.e("QuJs", "byteFF 字节码过短(" + (bytes == null ? 0 : bytes.length) + " 字节),按空模块处理");
            return new byte[0];
        }
        byte[] newBt = new byte[bytes.length - 4];
        newBt[0] = 1;
        System.arraycopy(bytes, 5, newBt, 1, bytes.length - 5);
        return newBt;
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
        // 单个源的 JS 堆上限与调用栈上限。
        // 为什么必须设上限:这个 QuickJS 封装(预编译 .so)没有暴露中断接口 —— 我核对过
        // libquickjs-android-wrapper.so 的 JNI 入口,只有 create/evaluate/call/... 这些,
        // 没有 setInterruptHandler 之类(QuickJS 自己的 JS_SetInterruptHandler 没被暴露到 Java)。
        // 于是"纯死循环"(while(1);)在线程里没有任何办法打断。能做的是两件事:
        // ① 把"会被内存上限打断"的那一类失控脚本挡在 OOM 之前 —— 分配型跑飞(while(1) 里 push 数据)
        //    会命中 QuickJS 的内存上限并抛 RangeError,调用正常失败返回,线程得以释放;
        // ② 剩下的纯自旋型跑飞只能靠"不再重建它"来限制(见 isIdle/destroyNow 的说明)。
        // 值取 64MB:正常源(含 cheerio/crypto-js 这类大依赖)实测远低于此,不至于误伤。
        try {
            ctx.setMemoryLimit(64 * 1024 * 1024);
        } catch (Throwable th) {
            LOG.e("QuJs", "设置 JS 内存上限失败(不影响使用): " + th);
        }
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public byte[] getModuleBytecode(String moduleName) {
                String ss = FileUtils.loadModule(moduleName);
                if (TextUtils.isEmpty(ss)) {return null;}

                if(ss.startsWith("//DRPY")){
                    return Base64.decode(ss.replace("//DRPY",""), Base64.URL_SAFE);
                } else if(ss.startsWith("//bb")){
                    byte[] b = Base64.decode(ss.replace("//bb",""), 0);
                    return byteFF(b);
                } else {
                    if (moduleName.contains("cheerio.min.js")) {
                        FileUtils.setCacheByte("cheerio.min", ctx.compileModule(ss, "cheerio.min.js"));
                    } else if (moduleName.contains("crypto-js.js")) {
                        FileUtils.setCacheByte("crypto-js", ctx.compileModule(ss, "crypto-js.js"));
                    }
                    return ctx.compileModule(ss, moduleName);
                }
            }

            @Override
            public String moduleNormalizeName(String moduleBaseName, String moduleName) {
                return UriUtil.resolve(moduleBaseName, moduleName);
            }
        });
        ctx.setConsole(new QuickJSContext.Console() {
            @Override
            public void log(String s) {
                LOG.i("QuJs", s);
            }
        });

        global = new Global(executor, tag);
        ctx.getGlobalObject().bind(global);

        JSObject local = ctx.createJSObject();
        ctx.getGlobalObject().set("local", local);
        local.bind(new local());

        ctx.getGlobalObject().getContext().evaluate(FileUtils.loadModule("net.js"));
    }

    private void createDex() {
        try {
            JSObject obj = ctx.createJSObject();
            Class<?> clz = dex;
            Class<?>[] classes = clz.getDeclaredClasses();
            ctx.getGlobalObject().set("jsapi", obj);
            if (classes.length == 0) invokeSingle(clz, obj);
            if (classes.length >= 1) invokeMultiple(clz, obj);
        } catch (Throwable e) {
            e.printStackTrace();
            LOG.e(e);
        }
    }

    private void invokeSingle(Class<?> clz, JSObject jsObj) throws Throwable {
        invoke(clz, jsObj, clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx));
    }

    private void invokeMultiple(Class<?> clz, JSObject jsObj) throws Throwable {
        for (Class<?> subClz : clz.getDeclaredClasses()) {
            Object javaObj = subClz.getDeclaredConstructor(clz).newInstance(clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx));
            JSObject subObj = ctx.createJSObject();
            invoke(subClz, subObj, javaObj);
            jsObj.set(subClz.getSimpleName(), subObj);
        }
    }

    private void invoke(Class<?> clz, JSObject jsObj, Object javaObj) {
        for (Method method : clz.getMethods()) {
            if (!method.isAnnotationPresent(Function.class)) continue;
            invoke(jsObj, method, javaObj);
        }
    }

    private void invoke(JSObject jsObj, Method method, Object javaObj) {
        jsObj.set(method.getName(), new JSCallFunction() {
            @Override
            public Object call(Object... objects) {
                try {
                    // 入参按 jsapi 方法签名归一化、返回值收敛到 native 认识的类型(见 JSUtils):
                    // 否则要么 method.invoke 抛参数不匹配,要么 native toJSValue 抛
                    // "Unsupported Java type ..." 并把异常留在 JNI env 上 —— 下一次 JS 调 Java
                    // 就是 "JNI DETECTED ERROR IN APPLICATION",整个进程被 abort(Java 侧拦不住)。
                    return JSUtils.toJsSafe(method.invoke(javaObj, JSUtils.adaptArgs(method, objects)));
                } catch (Throwable e) {
                    // 原来是全静默返回 null:源里 jsapi.xxx() 出错时只表现为"源打不开"却毫无线索
                    LOG.e("jsapi", method.getName() + " 调用失败: " + e);
                    return null;
                }
            }
        });
    }

    /** 本地代理(JS 侧 proxy):构造参数对象、取返回值都在 QuickJS 自己的线程上做(同 init 的坑) */
    private Object[] proxy1(Map<String, String> params) throws Exception {
        if (jsObject == null || retired) return proxyError(500, "js source unavailable");
        JSONArray array = submitAndWait(() -> {
            // 源没有 proxy() 时原来直接 NPE → /proxy 500 且看不出原因
            JSFunction proxy = jsObject.getJSFunction("proxy");
            if (proxy == null) return null;
            JSObject object = new JSUtils<String>().toObj(ctx, params);
            Object result = proxy.call(object);
            return result instanceof JSArray ? ((JSArray) result).toJsonArray() : null;
        }, CALL_TIMEOUT_MS);
        if (array == null) return proxyError(500, "js source has no usable proxy()");
        Object[] result = new Object[3];
        result[0] = array.opt(0);
        result[1] = array.opt(1);
        result[2] = getStream(array.opt(2));
        return result;
    }

    
    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        // 参数缺失原来直接 NPE(url.split)/parse(null),表现为 500 且无日志
        if (TextUtils.isEmpty(url)) return proxyError(400, "proxy 参数缺 url");
        if (jsObject == null || retired) return proxyError(500, "js source unavailable");
        JSArray array = submitAndWait(() -> new JSUtils<String>().toArray(ctx, Arrays.asList(url.split("/"))), CALL_TIMEOUT_MS);
        Object object = TextUtils.isEmpty(header) ? null : submitAndWait(() -> ctx.parse(header), CALL_TIMEOUT_MS);
        String json = (String) call("proxy", array, object);
        Res res = Res.objectFrom(json);
        // proxy() 返回 null/空串时 Gson 给 null,原来紧接着 res.getContentType() 就 NPE
        if (res == null) return proxyError(502, "proxy() 未返回有效结果");
        String contentType = res.getContentType();
        if (TextUtils.isEmpty(contentType)) contentType = "application/octet-stream";
        Object[] result = new Object[3];
        result[0] = 200;
        result[1] = contentType;
        result[2] = res.getStream();
        return result;
    }

    /** 代理失败时的统一返回:code + 纯文本原因(RemoteServer 会把它当响应体发出去,便于排查) */
    private static Object[] proxyError(int code, String msg) {
        Object[] result = new Object[3];
        result[0] = code;
        result[1] = "text/plain; charset=utf-8";
        result[2] = new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8));
        return result;
    }

    private ByteArrayInputStream getStream(Object o) {
        if (o == null) return new ByteArrayInputStream(new byte[0]);
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            byte[] bytes = new byte[a.length()];
            for (int i = 0; i < a.length(); i++) bytes[i] = (byte) a.optInt(i);
            return new ByteArrayInputStream(bytes);
        } else {
            return new ByteArrayInputStream(o.toString().getBytes());
        }
    }
}
