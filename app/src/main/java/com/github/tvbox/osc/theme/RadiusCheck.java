package com.github.tvbox.osc.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

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
 * 右边 = {@code assets/theme/theme_radii.json} 里你现在写的值。
 * <b>两者不一致 = 设备上的资源是旧的,必须完整安装</b>({@code gradlew :app:installDebug},
 * 或卸载后重装完整 APK);一致才说明"改的东西确实上机了"。
 *
 * <p>注:assets 是跟着部署走的(所以这一行能反映"你文件里写了什么"),而 dimen 是编译期资源 ——
 * 这一行正好把两者的差别暴露出来。
 */
public final class RadiusCheck {

    /** 参与比对的档位(与 app/build.gradle 的 defaultRadii 同一批键) */
    static final String[] KEYS = {
            "radius_background", "radius_dialog", "radius_card", "radius_btn",
            "radius_widget_btn", "radius_search", "common_corners", "stroke_widget_btn"
    };

    private static final String RADII_ASSET = "theme/theme_radii.json";

    private RadiusCheck() {
    }

    /** 启动时记一行"这台机器上跑的圆角 vs 主题文件里的圆角"(只记一次,失败静默) */
    public static void report(Context ctx) {
        if (ctx == null) return;
        try {
            String mode = ThemeRuntime.active() ? "自定义主题" : "内置主题";
            LogStore.log(Category.SYSTEM, "圆角自检 [" + mode + "] " + describe(compiledRadii(ctx), fileRadii(ctx))
                    + ";编译期 chip=" + radiiByWalk(compiledDrawable(ctx, com.github.tvbox.osc.R.drawable.selector_widget_btn), ctx)
                    + ";换肤重建 chip=" + rebuiltChipRadii(ctx)
                    + ";换肤重建 面板=" + rebuiltRadiiOf(ctx, com.github.tvbox.osc.R.drawable.bg_bottom_dialog)
                    + ";密度 app=" + ctx.getResources().getDisplayMetrics().density
                    + " 系统=" + Resources.getSystem().getDisplayMetrics().density);
        } catch (Throwable ignored) {
        }
    }

    /**
     * **换肤那条路真正画出来的 chip 圆角**:把 {@code selector_widget_btn} 按换肤逻辑重建一遍,
     * 再把每个状态里 {@code GradientDrawable} 的圆角读出来(px + 按 dp 折算)。
     *
     * <p>为什么要它:内置主题直接用**编译期**那份 drawable,自定义主题走的是
     * {@link ThemeDrawables#rebuild} 重建的那份 —— 用户口径"内置主题圆角是对的、切到自定义主题就不对",
     * 差别只可能在这条重建路径上(尺寸由 {@code res.getDimensionPixelSize} 在 Java 侧解析,
     * 与框架 native 解析不是同一条路)。把重建结果里真实的圆角打出来,就不用再靠肉眼猜。
     */
    private static String rebuiltChipRadii(Context ctx) {
        return rebuiltRadiiOf(ctx, com.github.tvbox.osc.R.drawable.selector_widget_btn);
    }

    /** 某个 drawable 按换肤逻辑重建后,各状态的实际圆角(px/折算 dp) */
    private static String rebuiltRadiiOf(Context ctx, int resId) {
        try {
            android.graphics.drawable.Drawable d = ThemeDrawables.rebuild(resId, ctx.getResources());
            if (d == null) return "未重建(直接用编译期那份 = 与内置主题同观感)";
            return radiiByWalk(d, ctx);
        } catch (Throwable th) {
            return "重建失败:" + th.getClass().getSimpleName();
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

    /** 读一个 drawable 的四角圆角(同时报 px 与按 app 密度折算的 dp,便于发现"密度不对齐");不是圆角矩形就说明原因 */
    private static String radiiOf(android.graphics.drawable.Drawable d, Context ctx) {
        if (!(d instanceof android.graphics.drawable.GradientDrawable)) {
            return d == null ? "null" : d.getClass().getSimpleName();
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return "读不到(API<24)";
        float density = ctx.getResources().getDisplayMetrics().density;
        float[] r = ((android.graphics.drawable.GradientDrawable) d).getCornerRadii();
        if (r == null) {
            float one = ((android.graphics.drawable.GradientDrawable) d).getCornerRadius();
            return one <= 0 ? "直角" : dp(one / density) + "/" + Math.round(one) + "px";
        }
        // 只报四个角的实际值(同时暴露"上下不一致"这种情形)
        return "TL=" + dp(r[0] / density) + "/" + Math.round(r[0]) + "px"
                + " TR=" + dp(r[2] / density) + " BR=" + dp(r[4] / density)
                + " BL=" + dp(r[6] / density);
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

    /** assets/theme/theme_radii.json 里现在写的值(跟着部署走的那个文件) */
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
