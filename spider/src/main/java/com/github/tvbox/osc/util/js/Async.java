package com.github.tvbox.osc.util.js;

import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

import java9.util.concurrent.CompletableFuture;

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
        if (object == null) {
            future.complete(null);
            return future;
        }
        JSFunction function = object.getJSFunction(name);
        if (function == null) return empty();
        Object result = function.call(args);
        if (result instanceof JSObject) then(result);
        else future.complete(result);
        return future;
    }

    private CompletableFuture<Object> empty() {
        future.complete(null);
        return future;
    }

    private void then(Object result) {
        JSObject promise = (JSObject) result;
        JSFunction then = promise.getJSFunction("then");
        if (then != null) then.call(callback);
    }

    private final JSCallFunction callback = new JSCallFunction() {
        @Override
        public Object call(Object... args) {
            future.complete(args[0]);
            return null;
        }
    };
}