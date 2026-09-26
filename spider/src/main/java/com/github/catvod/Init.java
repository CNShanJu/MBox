package com.github.catvod;

import android.content.Context;

import java.lang.ref.WeakReference;

public class Init {

    /** 注入的 application context(由 app 侧 ApiConfig.setAppContext 同步设置) */
    private volatile WeakReference<Context> context;

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }

    private static Init get() {
        return Loader.INSTANCE;
    }

    public static void set(Context context) {
        // 统一持有 application context:合并 jar 里若塞进来的是 Activity,避免把页面泄漏在这份全局引用上
        get().context = new WeakReference<>(context == null ? null : context.getApplicationContext());
    }

    /**
     * 当前 context(未注入时为 null)。
     * <p>
     * 原来是 {@code get().context.get()}:字段本身还是 null 的 WeakReference 时直接
     * NPE("Attempt to invoke virtual method 'Object WeakReference.get()' on a null object reference"),
     * 而这个异常发生在本模块多个类的**静态初始化**里(如 catvod.net.OkHttp 的 DoH 初始化)——
     * 类初始化一旦失败就被永久标记为错误,之后每次访问都是 NoClassDefFoundError,
     * 表现为"该进程内所有 JS/JAR 源永远取不到页面"。这里改为返回 null,由调用方决定怎么退化。
     */
    public static Context context() {
        WeakReference<Context> ref = get().context;
        return ref == null ? null : ref.get();
    }
}
