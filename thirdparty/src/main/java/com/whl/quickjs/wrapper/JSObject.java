package com.whl.quickjs.wrapper;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class JSObject {

    /** 本地补丁(非上游代码):桥调用失败日志统一走这个 tag,便于按 tag 过滤定位是哪个源/哪个方法 */
    private static final String TAG = "QuickJSBridge";

    private final ConcurrentHashMap<Class<?>, BindingContext> bindingContextMap = new ConcurrentHashMap<>();
    private final QuickJSContext context;
    private final long pointer;
    private boolean isReleased;

    public JSObject(QuickJSContext context, long pointer) {
        this.context = context;
        this.pointer = pointer;
    }

    public void setProperty(String name, String value) {
        context.setProperty(this, name, value);
    }

    public Object getProperty(String name) {
        checkReleased();
        return context.getProperty(this, name);
    }

    public JSONObject toJSONObject() {
        JSONObject jsonObject = new JSONObject();
        String[] keys = getKeys();
        for (String key : keys) {
            Object obj = this.getProperty(key);
            if (obj == null || obj instanceof JSFunction) {
                continue;
            }
            if (obj instanceof Number || obj instanceof String || obj instanceof Boolean) {
                try {
                    jsonObject.put(key, obj);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } else if (obj instanceof JSArray) {
                try {
                    jsonObject.put(key, ((JSArray) obj).toJSONArray());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } else if (obj instanceof JSObject) {
                try {
                    jsonObject.put(key, ((JSObject) obj).toJSONObject());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        return jsonObject;
    }

    public long getPointer() {
        return pointer;
    }

    public QuickJSContext getContext() {
        return context;
    }

    public Object get(String name) {
        checkReleased();
        return context.get(this, name);
    }

    public void set(String name, Object value) {
        checkReleased();
        // 值的类型收敛在 QuickJSContext.set/setProperty 里统一做(见 JSUtils.toJsSafe)
        context.set(this, name, value);
    }

    public String getString(String name) {
        Object value = get(name);
        return value instanceof String ? (String) value : null;
    }

    public Integer getInteger(String name) {
        Object value = get(name);
        return value instanceof Integer ? (Integer) value : null;
    }

    public Boolean getBoolean(String name) {
        Object value = get(name);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    public Double getDouble(String name) {
        Object value = get(name);
        return value instanceof Double ? (Double) value : null;
    }

    public Long getLong(String name) {
        Object value = get(name);
        return value instanceof Long ? (Long) value : null;
    }

    public JSObject getJSObject(String name) {
        Object value = get(name);
        return value instanceof JSObject ? (JSObject) value : null;
    }

    public JSFunction getJSFunction(String name) {
        Object value = get(name);
        return value instanceof JSFunction ? (JSFunction) value : null;
    }

    public JSArray getJSArray(String name) {
        Object value = get(name);
        return value instanceof JSArray ? (JSArray) value : null;
    }

    public JSArray getNames() {
        JSFunction getOwnPropertyNames = (JSFunction) context.evaluate("Object.getOwnPropertyNames");
        return (JSArray) getOwnPropertyNames.call(this);
    }

    public Boolean getHas(String key) {
        JSFunction hasOwnProperty = (JSFunction) context.evaluate("Object.hasOwnProperty");
        return (Boolean) hasOwnProperty.call(key);
    }

    /**
     * JSObject 确定不再使用后，调用该方法可主动释放对 JS 对象的引用。
     * 注意：该方法不能调用多次以及释放后不能再被使用对应的 JS 对象。
     */
    public void release() {
        checkReleased();

        context.freeValue(this);
        isReleased = true;
    }

    public void hold() {
        context.hold(this);
    }

    public boolean contains(String key) {
        checkReleased();
        return context.contains(this, key);
    }

    public String[] getKeys() {
        checkReleased();
        return context.getKeys(this);
    }

    /**
     * 这里与 JavaScript 的 toString 方法保持一致
     * 返回结果参考：https://262.ecma-international.org/14.0/#sec-tostring
     *
     * @return toString in JavaScript.
     */
    @Override
    public String toString() {
        checkReleased();

        JSFunction toString = getJSFunction("toString");
        return (String) toString.call();
    }

    public String toJsonString() {
        return context.stringify(this);
    }

    public JSONObject toJsonObject() {
        return toJsonObject(true);
    }

    public JSONObject toJsonObject(boolean isNative) {
        checkReleased();
        JSONObject jsonObject = new JSONObject();
        if (isNative) {
            String[] keys = getKeys();
            for (String key : keys) {
                Object obj = this.get(key);
                if (obj == null || obj instanceof JSFunction) {
                    continue;
                }
                if (obj instanceof Number || obj instanceof String || obj instanceof Boolean) {
                    try {
                        jsonObject.put(key, obj);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else if (obj instanceof JSArray) {
                    try {
                        jsonObject.put(key, ((JSArray) obj).toJsonArray());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else if (obj instanceof JSObject) {
                    try {
                        jsonObject.put(key, ((JSObject) obj).toJsonObject());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            JSONArray json = getNames().toJsonArray();
            for (int i = 0; i < json.length(); i++) {
                String key = json.optString(i);
                Object obj = this.get(key);
                if (obj == null || obj instanceof JSFunction) {
                    continue;
                }
                if (obj instanceof Number || obj instanceof String || obj instanceof Boolean) {
                    try {
                        jsonObject.put(key, obj);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else if (obj instanceof JSArray) {
                    try {
                        jsonObject.put(key, ((JSArray) obj).toJsonArray());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else if (obj instanceof JSObject) {
                    try {
                        jsonObject.put(key, ((JSObject) obj).toJsonObject());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return jsonObject;
    }

    final void checkReleased() {
        if (isReleased) {
            throw new NullPointerException("This JSObject was Released, Can not call this!");
        }
    }

    public boolean isAlive() {
        return context.isLiveObject(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JSObject jsObject = (JSObject) o;
        return pointer == jsObject.pointer;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new long[]{pointer});
    }

    public void bind(final Object callbackReceiver) throws QuickJSException {
        Objects.requireNonNull(callbackReceiver);
        checkReleased();

        BindingContext bindingContext = getBindingContext(callbackReceiver.getClass());
        Map<String, Method> functionMap = bindingContext.getFunctionMap();

        Method contextSetter = bindingContext.getContextSetter();
        if (contextSetter != null) {
            try {
                contextSetter.invoke(callbackReceiver, this.context);
            } catch (Exception e) {
                throw new QuickJSException(
                        e.getMessage());
            }
        }

        if (!functionMap.isEmpty()) {
            for (Map.Entry<String, Method> entry : functionMap.entrySet()) {
                String functionName = entry.getKey();
                final Method functionMethod = entry.getValue();
                try {
                    set(functionName, new JSCallFunction() {
                        @Override
                        public Object call(Object... args) {
                            try {
                                // 入参先按 Java 签名归一化(JS 的整数值 float64 会变成 Long、
                                // 少传/多传参数是常态,直接 invoke 必抛 argument type mismatch),
                                // 返回值再收敛到 native 认识的类型 —— 见 JSUtils 两个方法上的说明。
                                Object result = functionMethod.invoke(callbackReceiver,
                                        JSUtils.adaptArgs(functionMethod, args));
                                return JSUtils.toJsSafe(result);
                            } catch (Throwable e) {
                                // 本地补丁(原为 return new Object()):java.lang.Object 是 native
                                // toJSValue 不认识的类型,交回去会抛
                                // "Unsupported Java type java.lang.Object" 并把异常留在 JNI env 上,
                                // 下一次 JS 调 Java 就撞出 "JNI DETECTED ERROR IN APPLICATION" → 进程 abort。
                                // 这里改为:把真实原因(解包 InvocationTargetException)打进日志并返回 null。
                                Log.e(TAG, functionName + describeArgs(args) + " 调用失败", unwrap(e));
                                return null;
                            }
                        }
                    });
                } catch (Exception e) {
                    throw new QuickJSException(
                            e.getMessage());
                }
            }
        }
    }

    /** 失败日志里带上 JS 实参类型,便于定位是哪个源哪次调用把桥打崩的 */
    private static String describeArgs(Object[] args) {
        if (args == null || args.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.append(')').toString();
    }

    /** InvocationTargetException 的真实原因在 cause 上(原实现只打 e.getMessage(),经常是 null) */
    private static Throwable unwrap(Throwable e) {
        return e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
    }

    BindingContext getBindingContext(Class<?> callbackReceiverClass) throws QuickJSException {
        Objects.requireNonNull(callbackReceiverClass);
        BindingContext bindingContext = bindingContextMap.get(callbackReceiverClass);
        if (bindingContext == null) {
            bindingContext = new BindingContext();
            Map<String, Method> functionMap = bindingContext.getFunctionMap();
            for (Method method : callbackReceiverClass.getMethods()) {
                boolean methodHandled = false;
                Function fan = method.getAnnotation(Function.class);
                if (fan != null) {
                    Function v8Function = method.getAnnotation(Function.class);
                    String functionName = v8Function.name();
                    if (functionName.length() == 0) {
                        functionName = method.getName();
                    }
                    if (!functionMap.containsKey(functionName)) {
                        functionMap.put(functionName, method);
                        methodHandled = true;
                    }
                }
                if (!methodHandled) {
                    ContextSetter can = method.getAnnotation(ContextSetter.class);
                    if (can != null) {
                        bindingContext.setContextSetter(method);
                    }
                }
            }
            bindingContextMap.put(callbackReceiverClass, bindingContext);
        }
        return bindingContext;
    }

    public String stringify() {
        return context.stringify(this);
    }

    public void setProperty(String name, JSCallFunction value) {
        context.setProperty(this, name, value);
    }
}
