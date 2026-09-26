package com.github.tvbox.osc.util.js;

import com.github.tvbox.osc.util.LOG;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

import java9.util.concurrent.CompletableFuture;

/**
 * 把 JS 源方法(Promise)桥接成 Java 侧 {@link CompletableFuture}。
 * <p>
 * 硬约束:<b>每条路径都必须让 future 完成</b>。原实现只挂 then 的成功回调,JS 侧 reject
 * 或返回普通对象(取不到 then)时 future 永不完成,调用线程(JsSpider.call)永久挂死,
 * 日志一行都没有 —— 4 个这样的源就能把 spinner 道占满,首页/分类/搜索/播放全线无响应。
 */
public class Async {

    private final CompletableFuture<Object> future;

    public static CompletableFuture<Object> run(JSObject object, String name, Object[] args) {
        return new Async().call(object, name, args);
    }

    private Async() {
        this.future = new CompletableFuture<>();
    }

    private CompletableFuture<Object> call(JSObject object, String name, Object[] args) {
        // JS 模块未正确导出 __JS_SPIDER__(内容缺失/初始化失败)时 jsObject 为 null:
        // 返回空结果而非 NPE,避免一条源挂掉把整次搜索/首页搞挂(见 JsSpider.initializeJS 空内容提前 return)。
        if (object == null) return empty();
        JSFunction function = object.getJSFunction(name);
        if (function == null) return empty();
        Object result = function.call(args);
        if (result instanceof JSObject) then((JSObject) result, name);
        else future.complete(result);
        return future;
    }

    private CompletableFuture<Object> empty() {
        future.complete(null);
        return future;
    }

    /**
     * Promise 分支:成功与失败两个回调都要接。
     * <p>
     * 返回的不是 Promise(没有 then)时立即按空结果完成:源写错了要表现为"这条源没数据",
     * 而不是把调用线程挂在那里等一个永远不会来的回调。
     */
    private void then(JSObject promise, String name) {
        JSFunction then;
        try {
            then = promise.getJSFunction("then");
        } catch (Throwable th) {
            LOG.e("Async", name + " 取 then 异常: " + th);
            empty();
            return;
        }
        if (then == null) {
            LOG.e("Async", name + " 返回的不是 Promise,按空结果处理");
            empty();
            return;
        }
        try {
            then.call(onFulfilled, onRejected);
        } catch (Throwable th) {
            LOG.e("Async", name + " then 调用异常: " + th);
            empty();
        }
    }

    private final JSCallFunction onFulfilled = new JSCallFunction() {
        @Override
        public Object call(Object... args) {
            // JS 的 then 回调被源写成零参数时,原来直接读 args[0] 会数组越界
            future.complete(args != null && args.length > 0 ? args[0] : null);
            return null;
        }
    };

    private final JSCallFunction onRejected = new JSCallFunction() {
        @Override
        public Object call(Object... args) {
            // reject 一律按"空结果"完成(源失败不能拖死调用线程);原因写日志便于排查"源打不开"
            Object reason = args != null && args.length > 0 ? args[0] : null;
            LOG.e("Async", "JS 源 Promise reject: " + (reason == null ? "unknown" : String.valueOf(reason)));
            future.complete(null);
            return null;
        }
    };
}
