package com.whl.quickjs.wrapper;

import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class JSUtils<T> {

    private static final String TAG = "QuickJSBridge";

    public static boolean isEmpty(Object obj) {
        if (obj == null) return true;
        else if (obj instanceof CharSequence) return ((CharSequence) obj).length() == 0;
        else if (obj instanceof Collection) return ((Collection) obj).isEmpty();
        else if (obj instanceof Map) return ((Map) obj).isEmpty();
        else if (obj.getClass().isArray()) return Array.getLength(obj) == 0;

        return false;
    }

    public static boolean isNotEmpty(CharSequence str) {
        return !isEmpty(str);
    }

    public static boolean isNotEmpty(Object obj) {
        return !isEmpty(obj);
    }

    public JSArray toArray(QuickJSContext ctx, List<T> items) {
        JSArray array = ctx.createJSArray();
        if (items == null || items.isEmpty()) return array;
        for (int i = 0; i < items.size(); i++) array.push(items.get(i));
        return array;
    }

    public JSArray toArray(QuickJSContext ctx, byte[] bytes) {
        JSArray array = ctx.createJSArray();
        if (bytes == null || bytes.length == 0) return array;
        for (byte aByte : bytes) array.push((int) aByte);
        return array;
    }

    public JSArray toArray(QuickJSContext ctx, T[] arrays) {
        JSArray array = ctx.createJSArray();
        if (arrays == null || arrays.length == 0) return array;
        for (T t : arrays) {
            array.push(t);
        }
        return array;
    }

    public JSObject toObj(QuickJSContext ctx, Map<String, T> map) {
        JSObject obj = ctx.createJSObject();
        if (map == null || map.isEmpty()) return obj;
        for (String s : map.keySet()) {
            obj.set(s, map.get(s));
        }
        return obj;
    }

    // ------------------------------------------------------------------
    // 本地补丁(非上游 quickjs-wrapper 代码):JS ↔ Java 桥的入参/出参归一化。
    //
    // 背景(实测崩溃链,见 native/cpp/quickjs_wrapper.cpp):
    //   1) native 把 JS 值映射成 Java 时:整数 → Integer,整数值的 float64 → Long,
    //      非整数 → Double,布尔 → Boolean,对象 → JSObject/JSArray/JSFunction;
    //   2) JS 侧少传/多传参数、或用 1/0 当 boolean、把数字传给 String 形参都很常见,
    //      直接 Method.invoke 必抛 IllegalArgumentException(argument type mismatch);
    //   3) 失败后若返回 java.lang.Object,native toJSValue 不认这个类型,会抛
    //      IllegalArgumentException("Unsupported Java type java.lang.Object") 并把它**留在
    //      JNI env 上**(pending exception)。Java 侧拦不住,下一次 JS 调 Java 时撞在
    //      jsFuncCall 的 NewObjectArray 上 → CheckJNI "JNI DETECTED ERROR IN APPLICATION"
    //      → Runtime aborting → 进程被 SIGABRT 干掉(表现为"用着用着整个 app 没了")。
    // ------------------------------------------------------------------

    /**
     * 按目标方法签名归一化 JS 传来的参数,并补齐/截断参数个数(可变参数按数组装配)。
     * 归一化只做"语义等价转换"(数值互转、字符串化、布尔化),拿不准的类型原样返回,
     * 由调用方的 catch 兜底。
     */
    public static Object[] adaptArgs(Method method, Object[] raw) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = raw == null ? new Object[0] : raw;
        Object[] out = new Object[types.length];
        if (method.isVarArgs()) {
            int fixed = Math.max(types.length - 1, 0);
            for (int i = 0; i < fixed; i++) {
                out[i] = i < args.length ? coerce(args[i], types[i]) : defaultValue(types[i]);
            }
            Class<?> component = types[fixed].getComponentType();
            int extra = Math.max(args.length - fixed, 0);
            Object varargs = Array.newInstance(component == null ? Object.class : component, extra);
            for (int i = 0; i < extra; i++) Array.set(varargs, i, coerce(args[fixed + i], component));
            out[fixed] = varargs;
            return out;
        }
        for (int i = 0; i < types.length; i++) {
            out[i] = i < args.length ? coerce(args[i], types[i]) : defaultValue(types[i]);
        }
        return out;
    }

    /**
     * 把 Java 返回值收敛到 native {@code toJSValue} 认识的类型:
     * String / Boolean / Integer / Long / Double / byte[] / JSObject(含 JSArray、JSFunction) / JSCallFunction。
     * <p>
     * 其余类型(包括 java.lang.Object、Float/Short/Byte、Map/List、自定义 bean)一律降级,
     * 避免 native 抛 "Unsupported Java type" 后把异常留在 JNI env 上把进程崩掉。
     */
    public static Object toJsSafe(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Double
                || value instanceof byte[]
                || value instanceof JSObject
                || value instanceof JSCallFunction) {
            return value;
        }
        Object downgraded;
        if (value instanceof Float) downgraded = (double) (Float) value;
        else if (value instanceof Short) downgraded = (int) (Short) value;
        else if (value instanceof Byte) downgraded = (int) (Byte) value;
        else if (value instanceof Character) downgraded = String.valueOf(value);
        else downgraded = String.valueOf(value);
        Log.w(TAG, "返回值 " + value.getClass().getName() + " 不是 QuickJS 支持的 Java 类型,已降级为 "
                + downgraded.getClass().getSimpleName() + " : " + downgraded);
        return downgraded;
    }

    private static Object coerce(Object value, Class<?> type) {
        if (type == null || type == Object.class) return value;
        if (value == null) return defaultValue(type);
        if (type.isInstance(value)) return value;
        if (type == String.class || type == CharSequence.class) return String.valueOf(value);
        if (type == Integer.class || type == int.class) return (int) toLong(value);
        if (type == Long.class || type == long.class) return toLong(value);
        if (type == Double.class || type == double.class) return (double) toDouble(value);
        if (type == Float.class || type == float.class) return (float) toDouble(value);
        if (type == Short.class || type == short.class) return (short) toLong(value);
        if (type == Byte.class || type == byte.class) return (byte) toLong(value);
        if (type == Boolean.class || type == boolean.class) return toBool(value);
        return value;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof Boolean) return (Boolean) value ? 1L : 0L;
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof Boolean) return (Boolean) value ? 1d : 0d;
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0d;
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0d;
        if (value instanceof String) {
            String s = ((String) value).trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return false;
    }
}
