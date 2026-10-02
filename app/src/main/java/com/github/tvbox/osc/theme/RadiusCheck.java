package com.github.tvbox.osc.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;

import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 圆角"配置 vs 真正生效"的自检 —— 只记**一行业务日志**(不弹窗,不打扰)。
 *
 * <p>为什么需要它(2026-09-27 查了一整天):圆角与描边是**编译期资源**
 * ({@code theme_radii.json} → Gradle 生成 {@code @dimen} → 编进 APK),而
 * **Android Studio 点 Run 的那条部署链路不带资源**(它只推 dex/assets,会把
 * {@code intermediates/processed_res/<变体>/*.ap_} 删掉,留下一个 149 条目、没有 {@code res/} 的包)。
 * 于是"改圆角 → Run → 看设备"永远是旧值,界面却不会有任何报错 —— 表现成
 * "圆角怎么改都不生效 / 自己写的组件圆角也不对"。
 *
 * <p>这一行把两边并排打出来:左边 = 设备上这份包里**编译进去**的 dp(实际生效的那个数),
 * 右边 = {@code assets/theme/radius/theme_radii.json} 里你现在写的值。
 * <b>两者不一致 = 设备上的资源是旧的,必须完整安装</b>({@code gradlew :app:installDebug},
 * 或卸载后重装完整 APK);一致才说明"改的东西确实上机了"。
 *
 * <p>注:assets 是跟着部署走的(所以这一行能反映"你文件里写了什么"),而 dimen 是编译期资源 ——
 * 这一行正好把两者的差别暴露出来。
 */
public final class RadiusCheck {

    /** logcat 固定 tag:真机排查直接 `adb logcat -s MBoxRadius`,不依赖"运行日志"开关 */
    private static final String TAG = "MBoxRadius";

    /** 参与比对的档位(与 app/build.gradle 的 defaultRadii 同一批键) */
    static final String[] KEYS = {
            "radius_background", "radius_dialog", "radius_card", "radius_btn",
            "radius_widget_btn", "radius_search", "common_corners", "radius_thumb", "stroke_widget_btn"
    };

    private static final String RADII_ASSET = "theme/radius/theme_radii.json";

    private RadiusCheck() {
    }

    /** 启动时记一行"这台机器上跑的圆角 vs 主题文件里的圆角"(只记一次,失败静默) */
    public static void report(Context ctx) {
        if (ctx == null) return;
        String line;
        try {
            String mode = ThemeRuntime.active() ? "自定义主题" : "内置主题";
            line = "圆角自检 [" + mode + "] " + describe(compiledRadii(ctx), fileRadii(ctx))
                    + ";编译期 chip=" + cornerGeometry(
                            compiledDrawable(ctx, com.github.tvbox.osc.R.drawable.selector_widget_btn), ctx)
                    + ";重建 chip=" + cornerGeometry(
                            rebuiltDrawable(ctx, com.github.tvbox.osc.R.drawable.selector_widget_btn), ctx)
                    + ";重建 居中弹窗=" + cornerGeometry(
                            rebuiltDrawable(ctx, com.github.tvbox.osc.R.drawable.bg_dialog), ctx)
                    + ";重建 抽屉=" + cornerGeometry(
                            rebuiltDrawable(ctx, com.github.tvbox.osc.R.drawable.bg_drawer), ctx)
                    + ";密度 app=" + ctx.getResources().getDisplayMetrics().density
                    + " 系统=" + Resources.getSystem().getDisplayMetrics().density;
        } catch (Throwable th) {
            return;
        }
        // 业务日志(结构化落库,受"运行日志"开关控制)
        LogStore.log(Category.SYSTEM, line);
        // **同时打一条 logcat**:换肤问题排查靠的是 `adb logcat`(见 doc/device-regression-checklist.md),
        // 而业务日志默认关门控(RadiusCheck 又跑在 LogConfig 生效之前),只落库等于查不到。
        // 这行是排障用的固定 tag,不是业务日志通道,不受开关影响。
        android.util.Log.i(TAG, line);
    }

    /**
     * 弹窗显示时再打一行(logcat,固定 tag {@link #TAG};业务日志仍只记启动那一次)。
     *
     * <p>把「编译期那份底」与「换肤重建那份底」用**同一个口径(真画一遍量四角几何)**并排打出来 ——
     * 这两行相等,弹窗面板底才算真正跟主题设置一致。真机排查圆角问题时,这一行就是判据,
     * 不要再靠 {@code getCornerRadii}/{@code getCornerRadius} 那两个口径不同的 getter。
     *
     * @param who 弹窗类名(便于一眼看出是哪一类弹窗)
     */
    public static void reportPopup(Context ctx, String who) {
        if (ctx == null) return;
        try {
            int id = com.github.tvbox.osc.R.drawable.bg_dialog;
            android.util.Log.i(TAG, "弹窗圆角 [" + who + "]"
                    + " 编译期圆心角(基准)=" + cornerGeometry(compiledDrawable(ctx, id), ctx)
                    + " 重建居中弹窗=" + cornerGeometry(rebuiltDrawable(ctx, id), ctx)
                    + " 重建底部面板(应为上圆下直角)=" + cornerGeometry(
                            rebuiltDrawable(ctx, com.github.tvbox.osc.R.drawable.bg_bottom_dialog), ctx)
                    + " 重建 chip=" + cornerGeometry(
                            rebuiltDrawable(ctx, com.github.tvbox.osc.R.drawable.selector_widget_btn), ctx)
                    + " 密度=" + ctx.getResources().getDisplayMetrics().density
                    + " radius_dialog=" + ThemeRuntime.shapePalette().radiusDp("radius_dialog")
                    + scaleProbe(ctx)
                    + colorProbe(ctx)
                    + strokeProbe(ctx)
                    + iconTintProbe(ctx)
                    + inputProbe(ctx));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 量出"喂进去的圆角值 → 画出来的弧长"这条关系(线性与否、斜率多少)。
     *
     * <p>为什么要它:编译期那份 XML 的底与代码 new 出来的底,同样的 dp 画出来不是一个大小
     * (实测 26dp:编译期 50px、代码那份 19px)。要按编译期那份对齐,就必须知道这条换算关系;
     * 用两个点(20 / 40)量出斜率与截距,再反解出该喂的值,而不是继续拍脑袋乘 density。
     */
    private static String scaleProbe(Context ctx) {
        try {
            StringBuilder sb = new StringBuilder(";喂值→弧长 ");
            for (float value : new float[]{20f, 40f}) {
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                g.setColor(0xFF000000);
                g.setCornerRadii(new float[]{value, value, value, value,
                        value, value, value, value});
                sb.append('[').append(value).append("→").append(arcPx(g)).append("px] ");
            }
            float dp = ThemeRuntime.shapePalette().radiusDp("radius_dialog");
            android.graphics.drawable.Drawable compiled = compiledDrawable(
                    ctx, com.github.tvbox.osc.R.drawable.bg_dialog);
            sb.append("编译期[").append(dp).append("dp→").append(arcPx(compiled)).append("px] ");
            sb.append("重建[").append(dp).append("dp→").append(arcPx(
                    rebuiltDrawable(ctx, com.github.tvbox.osc.R.drawable.bg_dialog))).append("px]");
            return sb.toString();
        } catch (Throwable th) {
            return ";量关系失败:" + th.getClass().getSimpleName();
        }
    }

    /** 只量左上角弧长(px),供换算关系用 */
    private static int arcPx(android.graphics.drawable.Drawable d) {
        if (d == null) return -1;
        try {
            final int w = 120, h = 120;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new android.graphics.Canvas(bmp));
            int v = insetAt(bmp, true, true, w, h);
            bmp.recycle();
            return v;
        } catch (Throwable th) {
            return -1;
        }
    }

    /**
     * 颜色通道自检:把"调色板里该是什么"与"工厂生成 drawable 时真正填进去什么"并排打出来。
     *
     * <p>判据:两者一致 ⇒ 颜色通道没问题,用户看到"没跟着走"的那个控件用的是**别的**底
     * (或压根没走生成 drawable);两者不一致 ⇒ 颜色在解析/注入环节丢了。
     * 这一行专治"换成自定义主题后某块底色/文字色不跟随"这类报告,不必再猜是哪一层。
     */
    private static String colorProbe(Context ctx) {
        try {
            ThemePalette p = ThemeRuntime.palette();
            if (p == null) return ";颜色=非自定义主题(换肤层未介入)";
            StringBuilder sb = new StringBuilder(";调色板 bg_surface=").append(hex(p.get("bg_surface")))
                    .append(" bg_card=").append(hex(p.get("bg_card")))
                    .append(" bg_float=").append(hex(p.get("bg_float")))
                    .append(" text_main=").append(hex(p.get("text_main")))
                    .append(" text_hint=").append(hex(p.get("text_hint")))
                    .append(";生成底内部填充");
            for (String id : new String[]{"bg_search_round_float", "bg_large_round_float",
                    "bg_bottom_dialog", "bg_float", "bg_theme_field"}) {
                try {
                    android.graphics.drawable.Drawable d =
                            ThemeDrawableFactory.create(id, ctx.getResources());
                    sb.append(' ').append(id).append('=').append(fillOf(d));
                } catch (Throwable th) {
                    sb.append(' ').append(id).append("=异常");
                }
            }
            return sb.toString();
        } catch (Throwable th) {
            return ";颜色自检失败:" + th.getClass().getSimpleName();
        }
    }

    /** 取一个生成 drawable 的填充色:直接画一遍取中心像素(不依赖任何 getter/反射) */
    private static String fillOf(android.graphics.drawable.Drawable d) {
        try {
            if (d == null) return "null";
            final int w = 40, h = 40;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new android.graphics.Canvas(bmp));
            int c = bmp.getPixel(w / 2, h / 2);
            bmp.recycle();
            return hex(c);
        } catch (Throwable th) {
            return "?";
        }
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.ROOT, "#%08X", color);
    }

    /**
     * 把页面里"关心的那些控件"实际生效的底/文字色打出来(真画一遍取像素,不是读 getter)。
     *
     * <p>为什么需要它:"生成 drawable 的颜色对不对"与"这个控件到底用了哪份底"是两件事 ——
     * 前者可以在自检里查,后者只能看真实视图。真机实测曾出现"工厂生成的底颜色完全正确、
     * 但首页某几块看着还是内置色",靠这一行才能定论是哪一层没接上。
     *
     * @param ids      关心的控件 id(只报这些,避免刷屏)
     * @param fallback 这些 id 都没找到时,顺带报一棵树里最像"搜索框/标题"的那几个
     */
    public static void reportViews(View root, String who, int[] ids) {
        if (root == null) return;
        try {
            StringBuilder sb = new StringBuilder("控件实况 [").append(who).append(']');
            for (int id : ids) {
                View v = root.findViewById(id);
                if (v == null) continue;
                sb.append(' ').append(nameOf(v)).append('{');
                sb.append("底=").append(describeBackground(v));
                if (v instanceof android.widget.TextView) {
                    sb.append(" 字=").append(hex(((android.widget.TextView) v).getCurrentTextColor()));
                }
                sb.append('}');
            }
            android.util.Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按需体检:把这棵视图树里**所有带背景的控件**及其真实底色打进 logcat。
     *
     * <p>触发方式(不用改代码):
     * {@code adb shell am start -n <包名>/<Activity> --ez dump_theme true} —— 3 秒后输出。
     * 换自定义主题后想知道"这个页面上到底哪些控件没跟上主题",一次就能看全。
     */
    public static void dumpAllBackgrounds(View root, String who) {
        if (root == null) return;
        try {
            StringBuilder sb = new StringBuilder("全树底色 [").append(who).append(']');
            collectBackgrounds(root, sb, 0);
            android.util.Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void collectBackgrounds(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 60) return;
        try {
            if (v.getBackground() != null && v.getWidth() > 0 && v.getHeight() > 0) {
                String name;
                try {
                    name = v.getId() == 0 ? v.getClass().getSimpleName()
                            : v.getResources().getResourceEntryName(v.getId());
                } catch (Throwable th) {
                    name = v.getClass().getSimpleName();
                }
                sb.append('\n').append("  ").append(name).append('=')
                        .append(describeBackground(v));
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectBackgrounds(g.getChildAt(i), sb, depth + 1);
            }
        }
    }

    /** 从资源名反查 id 的名字,便于读日志 */
    private static String nameOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable th) {
            return "view@" + Integer.toHexString(v.getId());
        }
    }

    /** 把一个视图当前背景的真色打出来(自己画一遍取像素;不是取 drawable 的 getter) */
    private static String describeBackground(View v) {
        try {
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d == null) return "无";
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return d.getClass().getSimpleName() + "(未布局)";
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    v.getWidth(), v.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, v.getWidth(), v.getHeight());
            d.draw(canvas);
            int c = bmp.getPixel(v.getWidth() / 2, v.getHeight() / 2);
            bmp.recycle();
            return d.getClass().getSimpleName() + hex(c);
        } catch (Throwable th) {
            return "?";
        }
    }

    /**
     * 从**某个容器视图**里读输入框颜色(比"扫 Activity 窗口"可靠:弹窗在 onCreate 那一刻
     * 还没挂进 Activity 的 decorView,按窗口搜会搜不到)。
     */
    public static void reportInputsIn(android.view.View root, String who) {
        if (root == null) return;
        try {
            android.widget.EditText et = findEditText(root);
            StringBuilder sb = new StringBuilder(";输入框[").append(who).append(']');
            ThemePalette p = ThemeRuntime.palette();
            if (p != null) {
                sb.append(" 主色=").append(hex(p.get("text_main")))
                        .append(" 主色50%=").append(hex(p.get("text_main_half")));
            }
            if (et == null) {
                android.util.Log.i(TAG, sb.append(" 没找到 EditText").toString());
                return;
            }
            sb.append(";ET正文=").append(colorsOf(et.getTextColors()))
                    .append(" hint=").append(colorsOf(et.getHintTextColors()));
            android.view.ViewGroup parent = et.getParent() instanceof android.view.ViewGroup
                    ? (android.view.ViewGroup) et.getParent() : null;
            if (parent instanceof com.google.android.material.textfield.TextInputLayout) {
                sb.append(";TIL hint=").append(colorsOf(reflectHintColors(parent)));
            }
            android.util.Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 输入框颜色体检:读**当前窗口里真实的 EditText**(见 {@link #inputProbe})。
     * <p>为什么要单独一个入口:输入框只在弹窗里出现,进程启动那一刻界面上没有 EditText ——
     * 必须等弹窗真的弹出来再采样,才拿得到"用户眼前那个输入框"的颜色。
     */
    public static void reportInputs(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            android.util.Log.i(TAG, inputProbe(ctx));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 输入框自检:把"输入框这一族颜色到底装上了什么"打出来 —— 常驻保留。
     *
     * <p>为什么需要:输入框的颜色有**四条互不相干的路** ——
     * ① EditText 的 {@code android:textColor}(正文);② {@code android:textColorHint} /
     * TextInputLayout 的 {@code hintTextColor}(占位/悬浮标签,实际取的是 Material 的
     * {@code colorOnSurfaceVariant} 等主题属性);③ {@code boxStrokeColor}(外框,含聚焦态状态表);
     * ④ {@code placeholderTextColor}。任一条漏接主题,现象都是"看着像写死的颜色",
     * 但改法完全不同 —— 这里一次给出四条的实测值。
     */
    private static String inputProbe(Context ctx) {
        try {
            StringBuilder sb = new StringBuilder(";输入框");
            ThemePalette p = ThemeRuntime.palette();
            if (p != null) {
                sb.append(" 主色=").append(hex(p.get("text_main")))
                        .append(" 主色50%=").append(hex(p.get("text_main_half")))
                        .append(" 主色60%=").append(hex(p.get("text_sub")));
            }
            // **必须读界面上真实的那个控件**:用裸 inflater 重新 inflate 布局不会经过主题工厂,
            // 读到的永远是编译期值(我第一版探针就是这么骗了自己一次)。
            android.widget.EditText et = findEditTextInWindow(ctx);
            if (et == null) return sb.append(" 界面上没有 EditText(先去打开一个输入框弹窗)").toString();
            sb.append(";EditText 正文色组=").append(colorsOf(et.getTextColors()))
                    .append(" hint色组=").append(colorsOf(et.getHintTextColors()))
                    .append(" 当前hint=").append(hex(et.getCurrentHintTextColor()));
            android.view.ViewGroup parent = et.getParent() instanceof android.view.ViewGroup
                    ? (android.view.ViewGroup) et.getParent() : null;
            if (parent instanceof com.google.android.material.textfield.TextInputLayout) {
                sb.append(";TIL hint色组=").append(colorsOf(reflectHintColors(parent)));
            }
            return sb.toString();
        } catch (Throwable th) {
            return ";输入框自检失败:" + th;
        }
    }

    /** 在**当前窗口**里找第一个可见的 EditText(即用户眼前那个输入框) */
    private static android.widget.EditText findEditTextInWindow(Context ctx) {
        try {
            android.app.Activity activity = null;
            Context c = ctx;
            while (c instanceof android.content.ContextWrapper) {
                if (c instanceof android.app.Activity) {
                    activity = (android.app.Activity) c;
                    break;
                }
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
            if (activity == null || activity.getWindow() == null) return null;
            return findEditText(activity.getWindow().getDecorView());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static android.widget.EditText findEditText(android.view.View v) {
        if (v == null) return null;
        if (v instanceof android.widget.EditText && v.getVisibility() == android.view.View.VISIBLE) {
            return (android.widget.EditText) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText found = findEditText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 反射读 TextView 家族的 hint 色组(某些 Material 版本的读法是隐藏 API) */
    private static android.content.res.ColorStateList reflectHintColors(Object view) {
        try {
            java.lang.reflect.Method m = view.getClass().getMethod("getHintTextColors");
            return (android.content.res.ColorStateList) m.invoke(view);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把一个 ColorStateList 的关键状态色列出来。
     *
     * <p>{@code ColorStateList#getColors()} 是隐藏 API(真机反射同样 NoSuchMethodException),
     * 所以改用公开的 {@code getColorForState} 逐个常用状态问。
     */
    private static String colorsOf(android.content.res.ColorStateList csl) {
        if (csl == null) return "null";
        return new StringBuilder("[默认=").append(hex(csl.getDefaultColor()))
                .append(" 聚焦=").append(hex(csl.getColorForState(
                        new int[]{android.R.attr.state_focused}, 0)))
                .append(" 可用=").append(hex(csl.getColorForState(
                        new int[]{android.R.attr.state_enabled}, 0)))
                .append(']').toString();
    }

    /**
     * 图标 tint 自检:把"单色矢量图标 → 主题色"的解析结果打出来 —— 常驻保留。
     *
     * <p>用途:方形勾选框(button_checkbox_square → ic_checkbox_square)真机上描边仍是内置色
     * {@code #1F2937},而它声明的 {@code android:tint="@color/text_foreground"} 本该解析成
     * {@code text_main}。这里直接给出"扫描到的 tint 概念名 + 实际解析色 + 调色板主色",
     * 一眼能看出是"没扫到 tint"、"概念名不对"还是"装上去没生效"。
     */
    private static String iconTintProbe(Context ctx) {
        try {
            // 排障期打开取底留痕(见 ThemeDrawables.TRACE);这是自检的一部分,随自检一起开
            ThemeDrawables.TRACE = true;
            StringBuilder sb = new StringBuilder(";图标tint");
            int[] ids = {
                    com.github.tvbox.osc.R.drawable.button_checkbox_square,
                    com.github.tvbox.osc.R.drawable.ic_checkbox_square,
                    com.github.tvbox.osc.R.drawable.button_checkbox,
                    com.github.tvbox.osc.R.drawable.ic_unchecked_circle};
            String[] names = {"button_checkbox_square", "ic_checkbox_square",
                    "button_checkbox", "ic_unchecked_circle"};
            for (int i = 0; i < ids.length; i++) {
                String key = ThemeDrawables.iconTintKey(ids[i], ctx.getResources());
                sb.append(' ').append(names[i]).append("→")
                        .append(key == null ? "无(不跟主题)" : key);
            }
            ThemePalette p = ThemeRuntime.palette();
            if (p != null) sb.append(";text_main=").append(hex(p.get("text_main")));
            // 关键:光有"概念名"不代表真的装上了 —— 这里把实际取到的 drawable 与它的 tint 一并打出
            try {
                int resId = com.github.tvbox.osc.R.drawable.button_checkbox_square;
                android.graphics.drawable.Drawable d = ctx.getResources().getDrawable(resId);
                String cls = (d == null) ? "null" : d.getClass().getSimpleName();
                sb.append(";实取=").append(cls).append("/tint=").append(tintOf(d));
                if (d instanceof android.graphics.drawable.DrawableContainer) {
                    java.lang.reflect.Method gc = android.graphics.drawable.DrawableContainer.class
                            .getDeclaredMethod("getChildren");
                    gc.setAccessible(true);
                    Object[] kids = (Object[]) gc.invoke(d);
                    sb.append(";子项=").append(kids == null ? "?" : kids.length);
                    if (kids != null) {
                        for (Object k : kids) {
                            if (!(k instanceof android.graphics.drawable.Drawable)) continue;
                            android.graphics.drawable.Drawable kd =
                                    (android.graphics.drawable.Drawable) k;
                            sb.append('[').append(kd.getClass().getSimpleName())
                                    .append("/tint=").append(tintOf(kd)).append(']');
                        }
                    }
                }
            } catch (Throwable th) {
                sb.append(";实取失败:").append(th.getClass().getSimpleName());
            }
            ThemeDrawables.TRACE = false; // 自检结束就关掉取底留痕,别把正常日志刷满
            return sb.toString();
        } catch (Throwable th) {
            return ";图标tint自检失败:" + th.getClass().getSimpleName();
        }
    }

    /** 读一个 drawable 的 tint(反射;编译期 android.jar 里拿不到 getTintList) */
    private static String tintOf(android.graphics.drawable.Drawable d) {
        if (d == null) return "null";
        try {
            java.lang.reflect.Method m = android.graphics.drawable.Drawable.class
                    .getMethod("getTintList");
            Object csl = m.invoke(d);
            if (csl == null) return "无";
            java.lang.reflect.Method def = csl.getClass().getMethod("getDefaultColor");
            return hex(((Number) def.invoke(csl)).intValue());
        } catch (Throwable th) {
            return "读不到";
        }
    }

    /**
     * 描边自检:把"小动作键那两张描边底"实际用的边框色/线宽打出来 —— 常驻保留。
     *
     * <p>为什么需要:这类底走的是**旧资源兼容重建**(解析 XML 里的 {@code @color}/{@code @dimen}),
     * 重建结果对不对只有看真实值才知道。用户口径"自定义主题颜色按钮的边框线和主题文件不一致",
     * 判据就是这里的边框色应当等于主题里的文字主色 {@code text_main},
     * 线宽等于 {@code stroke_widget_btn}。
     */
    private static String strokeProbe(Context ctx) {
        try {
            StringBuilder sb = new StringBuilder(";描边底");
            for (String name : new String[]{"bg_r_common_stroke_primary", "bg_r_25_stroke_primary"}) {
                int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
                android.graphics.drawable.Drawable d = id == 0 ? null
                        : ThemeDrawables.rebuild(id, ctx.getResources());
                sb.append(' ').append(name).append('=').append(strokeOf(d));
            }
            ThemePalette p = ThemeRuntime.palette();
            if (p != null) {
                sb.append(";主题 text_main=").append(hex(p.get("text_main")))
                        .append(" stroke_widget_btn=")
                        .append(ThemeRuntime.shapePalette().strokeDp("stroke_widget_btn"));
            }
            return sb.toString();
        } catch (Throwable th) {
            return ";描边自检失败:" + th.getClass().getSimpleName();
        }
    }

    /**
     * 从重建后的 drawable 里读描边色与线宽。
     *
     * <p>{@code GradientDrawable#getPaint} 是 @hide,反射读不到就**画一遍取边沿像素**兜底 ——
     * 结论必须拿得到,不能因为读法受限就静默。
     */
    private static String strokeOf(android.graphics.drawable.Drawable d) {
        if (!(d instanceof android.graphics.drawable.GradientDrawable)) {
            return d == null ? "null" : d.getClass().getSimpleName();
        }
        android.graphics.drawable.GradientDrawable g =
                (android.graphics.drawable.GradientDrawable) d;
        try {
            java.lang.reflect.Method getPaint = android.graphics.drawable.GradientDrawable.class
                    .getDeclaredMethod("getPaint");
            getPaint.setAccessible(true);
            android.graphics.Paint paint = (android.graphics.Paint) getPaint.invoke(g);
            return "色" + hex(paint.getColor()) + " 宽" + paint.getStrokeWidth() + "px";
        } catch (Throwable ignored) {
        }
        // 兜底:画一遍,取"最外一圈"的像素色(描边色)
        try {
            final int w = 60, h = 60;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            g.setBounds(0, 0, w, h);
            g.draw(canvas);
            int c = bmp.getPixel(1, h / 2); // 左边框中段
            bmp.recycle();
            return "色(边沿采样)" + hex(c);
        } catch (Throwable th) {
            return "读不到:" + th.getClass().getSimpleName();
        }
    }

    /** 某个 drawable 按换肤逻辑重建后的<b>真实几何</b>(画一遍量四角弧长) */
    private static android.graphics.drawable.Drawable rebuiltDrawable(Context ctx, int resId) {
        try {
            return ThemeDrawables.rebuild(resId, ctx.getResources());
        } catch (Throwable th) {
            return null;
        }
    }

    /** 资源里那份(编译期)drawable */
    private static android.graphics.drawable.Drawable compiledDrawable(Context ctx, int resId) {
        try {
            return ctx.getResources().getDrawable(resId);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 逐层读出一个 drawable(含 selector/layer-list 的每个子项)的圆角 */
    private static String radiiByWalk(android.graphics.drawable.Drawable d, Context ctx) {
        if (d == null) return "null";
        android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
        if (cs instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState) {
            android.graphics.drawable.DrawableContainer.DrawableContainerState st =
                    (android.graphics.drawable.DrawableContainer.DrawableContainerState) cs;
            StringBuilder sb = new StringBuilder("states=").append(st.getChildCount());
            for (int i = 0; i < st.getChildCount(); i++) {
                sb.append(' ').append(i).append(':').append(radiiOf(st.getChild(i), ctx));
            }
            return sb.toString();
        }
        return radiiOf(d, ctx);
    }

    /**
     * 把 drawable 真的<b>画一遍</b>,报告"最上面一行/最下面一行,从边往里数第几个像素才有填充" ——
     * 也就是四角圆角的<b>实际几何</b>(像素),不再依赖 {@code getCornerRadii}/{@code getCornerRadius}
     * 这两个口径不同的 getter。
     *
     * <p>为什么要这么干(2026-10-01 的教训):均匀半径时框架把 {@code setCornerRadii(全同值)}
     * 退化成标量 {@code setCornerRadius},于是 {@code getCornerRadii()} 返回 null、
     * 而 {@code getCornerRadius()} 返回<b>密度换算后</b>的值 —— 同一份 drawable 被读成
     * "TL=47px、BR=16px",看着像上下圆角不一致,其实四角完全一样。物理量一下就再也不会被 getter 口径骗。
     *
     * @return 形如 {@code 几何 TL/TR/BR/BL=47/47/47/47px} 或 {@code 几何=全实心}
     */
    private static String cornerGeometry(android.graphics.drawable.Drawable d, Context ctx) {
        if (d == null) return "几何=null";
        try {
            final int w = 120, h = 120;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            int tl = insetAt(bmp, true, true, w, h);
            int tr = insetAt(bmp, true, false, w, h);
            int br = insetAt(bmp, false, false, w, h);
            int bl = insetAt(bmp, false, true, w, h);
            bmp.recycle();
            if (tl < 0) return "几何=全透明(没画出来)";
            return "几何 TL/TR/BR/BL=" + tl + "/" + tr + "/" + br + "/" + bl + "px"
                    + "(dp≈" + num(tl / density(ctx)) + ",按" + w + "px 画布量)";
        } catch (Throwable th) {
            return "几何=量失败:" + th.getClass().getSimpleName();
        }
    }

    /** 从某个角往里扫,返回"该边上第一个被填充的像素偏移"(= 圆角在该边的可见弧长) */
    private static int insetAt(android.graphics.Bitmap bmp, boolean top, boolean left,
                               int w, int h) {
        int edgeY = top ? 0 : h - 1;
        for (int i = 0; i < Math.min(w, h); i++) {
            int x = left ? i : w - 1 - i;
            if (android.graphics.Color.alpha(bmp.getPixel(x, edgeY)) > 8) return i;
        }
        return -1;
    }

    /** 读一个 drawable 的四角圆角(px);不是圆角矩形就说明原因 */
    private static String radiiOf(android.graphics.drawable.Drawable d, Context ctx) {
        if (!(d instanceof android.graphics.drawable.GradientDrawable)) {
            return d == null ? "null" : d.getClass().getSimpleName();
        }
        android.graphics.drawable.GradientDrawable g =
                (android.graphics.drawable.GradientDrawable) d;
        // **两个口径必须说清楚,否则会读出"假的不对称"**(2026-10-01 我就被自己坑过一次):
        //   · getCornerRadii() 返回**原始**圆角数组(密度无关值);均匀半径时它返回 null ——
        //     框架在 setCornerRadii(全同值) 时会退化成标量半径(setCornerRadius);
        //   · getCornerRadius() 返回**标量**半径,且是**密度换算后**的值(16dp → 16×2.95=47px)。
        // 混用两者就会出现"TL=47px、BR=16px"这种看着像 bug 的读数。
        // 这里**统一只报原始值**:均匀半径按标量原值展开成四角,分角才读数组。
        float scalar = g.getCornerRadius();
        float[] r = g.getCornerRadii();
        if (r == null) {
            if (scalar <= 0f) return "直角";
            return "均匀 TL/TR/BR/BL=" + num(scalar) + "(dp等价 " + num(scalar / density(ctx)) + ")";
        }
        return "TL=" + num(r[0]) + " TR=" + num(r[2])
                + " BR=" + num(r[4]) + " BL=" + num(r[6])
                + "(dp等价 " + num(r[0] / density(ctx)) + ")";
    }

    private static float density(Context ctx) {
        return ctx.getResources().getDisplayMetrics().density;
    }

    /** 去掉多余小数位(16.00 → 16、5.42 → 5.42) */
    private static String num(float value) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 纯逻辑:生成对比文本(可 JVM 单测)。
     * 两边都按 dp 字符串比;文件里没有的键算"不一致"(说明这份包与文件对不上)。
     */
    static String describe(Map<String, String> compiled, Map<String, String> fromFile) {
        StringBuilder all = new StringBuilder();
        StringBuilder diff = new StringBuilder();
        for (String key : KEYS) {
            String onDevice = compiled.containsKey(key) ? compiled.get(key) : "-";
            String inFile = fromFile.containsKey(key) ? fromFile.get(key) : "(文件里没有)";
            if (all.length() > 0) all.append(' ');
            all.append(key).append('=').append(onDevice);
            if (!onDevice.equals(inFile)) {
                if (diff.length() > 0) diff.append('、');
                diff.append(key).append(":包内").append(onDevice).append("≠文件").append(inFile);
            }
        }
        String verdict = diff.length() == 0
                ? "一致(说明改的圆角确实上机了)"
                : "**不一致**(" + diff + ")—— 设备上跑的是旧资源;圆角/布局是编译期资源,"
                + "AS 点 Run 不带资源,必须完整安装(gradlew :app:installDebug 或卸载后重装完整 APK)";
        return verdict + ";包内=" + all;
    }

    /** 设备上这份包里编译进去的 dp(实际生效的值) */
    private static Map<String, String> compiledRadii(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        float density = res.getDisplayMetrics().density;
        for (String key : KEYS) {
            int id = res.getIdentifier(key, "dimen", pkg);
            if (id == 0) continue;
            out.put(key, dp(res.getDimension(id) / density));
        }
        return out;
    }

    /** assets/theme/radius/theme_radii.json 里现在写的值(跟着部署走的那个文件) */
    private static Map<String, String> fileRadii(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream is = ctx.getAssets().open(RADII_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            JSONObject json = new JSONObject(sb.toString());
            for (String key : KEYS) {
                String v = json.optString(key, null);
                if (v != null) out.put(key, v);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** px → dp 文本(去掉多余的小数位:12.00dp → 12dp、0.50dp → 0.5dp) */
    private static String dp(float value) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "dp";
    }
}
