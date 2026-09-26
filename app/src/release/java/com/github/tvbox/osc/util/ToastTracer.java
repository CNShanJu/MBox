package com.github.tvbox.osc.util;

/**
 * Toast 溯源（release 空实现）：与 debug source set 的 {@link ToastTracer} 同名同类，
 * release 编译时替换 debug 版本——产物不包含任何 toast 溯源逻辑。
 */
final class ToastTracer {

    private ToastTracer() {
    }

    /** release 空实现：无任何行为 */
    static void log(CharSequence msg) {
        // no-op
    }
}
