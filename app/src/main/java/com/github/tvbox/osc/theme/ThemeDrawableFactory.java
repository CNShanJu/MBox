package com.github.tvbox.osc.theme;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.view.View;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemeColorPalette;
import com.github.tvbox.osc.bean.theme.ThemeShapePalette;
import com.github.tvbox.osc.bean.theme.ThemeShapeRecipe;
import com.github.tvbox.osc.bean.theme.ThemeSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题背景的唯一运行时渲染器。
 *
 * <p>构建期 XML 与这里读取的运行时表都由 {@code theme_shapes.json} 生成；工厂不解析任意
 * drawable XML。缓存身份包含完整主题指纹、密度、布局方向和配方 id，切主题时整体失效。
 */
public final class ThemeDrawableFactory {

    private static final String RECIPES_ASSET = "theme/radius/theme_shapes_runtime.json";
    private static volatile Map<String, ThemeShapeRecipe> recipes;

    private ThemeDrawableFactory() {
    }

    /** 资源 id 对应的已迁移配方；未登记资源返回空串。 */
    public static String recipeIdFor(int resId) {
        if (resId == R.drawable.selector_widget_btn) return "selector_widget_btn";
        if (resId == R.drawable.theme_btn_primary) return "theme_btn_primary";
        if (resId == R.drawable.theme_btn_secondary) return "theme_btn_secondary";
        if (resId == R.drawable.theme_btn_ghost) return "theme_btn_ghost";
        if (resId == R.drawable.theme_btn_danger) return "theme_btn_danger";
        if (resId == R.drawable.theme_btn_danger_entry) return "theme_btn_danger_entry";
        if (resId == R.drawable.bg_large_round_float) return "bg_large_round_float";
        if (resId == R.drawable.bg_small_round_gray) return "bg_small_round_gray";
        if (resId == R.drawable.bg_thumb_round) return "bg_thumb_round";
        if (resId == R.drawable.bg_dialog) return "bg_dialog";
        if (resId == R.drawable.bg_bubble) return "bg_bubble";
        if (resId == R.drawable.bg_bottom_dialog) return "bg_bottom_dialog";
        if (resId == R.drawable.bg_drawer) return "bg_drawer";
        if (resId == R.drawable.bg_playing_control) return "bg_playing_control";
        if (resId == R.drawable.bg_search_round_float) return "bg_search_round_float";
        if (resId == R.drawable.bg_r_common_solid_select) return "bg_r_common_solid_select";
        if (resId == R.drawable.item_bg_selector_left
                || resId == R.drawable.item_bg_selector_right) return "live_row_selector";
        if (resId == R.drawable.bg_r_common_solid_primary) return "subtitle_result_fill";
        if (resId == R.drawable.shape_setting_sort_focus) return "subtitle_result_focus";
        if (resId == R.drawable.bg_small_round_float) return "bg_small_round_float";
        if (resId == R.drawable.bg_theme_field) return "bg_theme_field";
        if (resId == R.drawable.bg_lan_import_field) return "bg_lan_import_field";
        if (resId == R.drawable.button_detail_quick_search) return "button_detail_quick_search";
        if (resId == R.drawable.bg_swipe_pause) return "bg_swipe_pause";
        // 圆形悬浮钮(首页「直播」/「更新」气泡球):**必须登记** ——
        // 它原来是普通 drawable,换肤层按 drawable 重建不到它,于是底色永远停在编译期色
        // (用户口径:"首页的直播气泡球和更新气泡球的背景色没有跟着 bg_surface 走")。
        if (resId == R.drawable.bg_fab_oval) return "bg_fab_oval";
        // 图片占位底(每张海报卡的首帧形态):原实现是 layer-list(纯色圆角 + 居中图标),
        // 旧兼容重建层**不认 layer-list**,于是换主题后它一直停在编译期灰底 ——
        // 用户口径"很多组件卡片颜色不对"里最普遍的一条(首页宫格/搜索卡/下载卡都用它)。
        // 收进配方后是"主题卡片色 + 卡片圆角"的纯色底;图标由运行时占位绘制层
        // (PosterPlaceholderDrawable / PicassoLoad)负责,不依赖这份布局静态形态。
        if (resId == R.drawable.placeholder_poster) return "placeholder_poster";
        return "";
    }

    /** 使用当前原子主题快照渲染。内置和自定义主题走同一入口。 */
    public static Drawable create(String recipeId, Resources resources) {
        ThemeSnapshot snapshot = ThemeRuntime.snapshot();
        if (snapshot == null || snapshot.colors == null || resources == null) return null;
        return create(recipeId, snapshot.colors, snapshot.shapes, snapshot.fingerprint,
                resources, resources.getConfiguration().getLayoutDirection());
    }

    /** 显式输入调色板的纯渲染入口，供预览和测试使用。 */
    public static Drawable create(String recipeId, ThemeColorPalette colors,
                                  ThemeShapePalette shapes, String themeFingerprint,
                                  Resources resources, int layoutDirection) {
        if (recipeId == null || recipeId.isEmpty() || colors == null || shapes == null
                || resources == null) return null;
        ThemeShapeRecipe recipe = allRecipes(resources).get(recipeId);
        if (recipe == null) return null;
        DisplayMetrics metrics = resources.getDisplayMetrics();
        try {
            // **每次现造,不缓存 Drawable.ConstantState**(血泪:2026-10-01 弹窗"上圆下不圆")。
            // 缓存 ConstantState 再 state.newDrawable(resources) 复用,框架会按密度重缩放圆角,
            // 而且只缩前四个槽:[16×8] 变成 [47,47,47,47,16,16,16,16] —— 同一份 drawable 的
            // 上两角被放大、下两角保持原值,画出来就是"上面很圆、下面很方"。
            // 真机对照实测(见 RadiusCheck):
            //   setCornerRadii(16×8) 现造          → 原始 [16×8]      几何 11/11/11/11 ✓
            //   setCornerRadii(16×8) 后走 state 复用 → 原始 [47,47,47,47,16,16,16,16] 几何 38/38/11/11 ✗
            // 生成 drawable 只在 inflate/上底时创建,现造的开销可接受;换正确性。
            Drawable drawable = render(recipe, colors, shapes, metrics.density, resources,
                    layoutDirection);
            if (drawable == null) {
                // 之前这里静默返回 null,于是"某块底不跟主题走"查不到原因(只会退回编译期那份)
                android.util.Log.w("MBoxRadius", "生成 drawable 失败(render 返回 null):"
                        + recipeId + " —— 调用方会退回编译期那份底");
                return null;
            }
            drawable.setLayoutDirection(layoutDirection);
            return drawable;
        } catch (Throwable th) {
            // 关键留痕:**异常也会静默退回编译期底**,不记日志就永远不知道是哪一条配方/哪个颜色键坏了
            android.util.Log.w("MBoxRadius", "生成 drawable 抛异常:" + recipeId
                    + " → " + th, th);
            return null;
        }
    }

    public static void clearCache() {
        // 不再持有 drawable 缓存;保留入口给切主题调用(配方表本身与主题无关,不必清)
    }

    private static Drawable render(ThemeShapeRecipe recipe, ThemeColorPalette colors,
                                   ThemeShapePalette shapes, float density,
                                   Resources resources, int layoutDirection) {
        Drawable content;
        if (!recipe.layers.isEmpty()) {
            Drawable[] children = new Drawable[recipe.layers.size()];
            for (int i = 0; i < children.length; i++) {
                children[i] = render(recipe.layers.get(i), colors, shapes, density,
                        resources, layoutDirection);
                if (children[i] == null) return null;
            }
            content = new LayerDrawable(children);
        } else if (recipe.states.size() > 1) {
            StateListDrawable selector = new StateListDrawable();
            addState(selector, recipe, "disabled", new int[]{-android.R.attr.state_enabled},
                    colors, shapes, density, layoutDirection);
            addState(selector, recipe, "selected", new int[]{android.R.attr.state_selected},
                    colors, shapes, density, layoutDirection);
            addState(selector, recipe, "checked", new int[]{android.R.attr.state_checked},
                    colors, shapes, density, layoutDirection);
            addState(selector, recipe, "focused", new int[]{android.R.attr.state_focused},
                    colors, shapes, density, layoutDirection);
            addState(selector, recipe, "pressed", new int[]{android.R.attr.state_pressed},
                    colors, shapes, density, layoutDirection);
            addState(selector, recipe, "default", new int[0], colors, shapes, density,
                    layoutDirection);
            content = selector;
        } else {
            ThemeShapeRecipe.PaintState state = recipe.states.get("default");
            content = state == null ? null : shape(recipe, state, colors, shapes, density,
                    layoutDirection);
        }
        if (content != null && !recipe.ripple.isEmpty()) {
            return new RippleDrawable(android.content.res.ColorStateList.valueOf(
                    color(recipe.ripple, colors)), content, null);
        }
        return content;
    }

    private static void addState(StateListDrawable target, ThemeShapeRecipe recipe,
                                 String name, int[] stateSet, ThemeColorPalette colors,
                                 ThemeShapePalette shapes, float density, int layoutDirection) {
        ThemeShapeRecipe.PaintState state = recipe.states.get(name);
        if (state != null) {
            target.addState(stateSet, shape(recipe, state, colors, shapes, density,
                    layoutDirection));
        }
    }

    private static GradientDrawable shape(ThemeShapeRecipe recipe,
                                          ThemeShapeRecipe.PaintState state,
                                          ThemeColorPalette colors, ThemeShapePalette shapes,
                                          float density, int layoutDirection) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape("oval".equals(recipe.shape)
                ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        drawable.setColor(color(state.fill, colors));
        float[] radii = cornerRadii(recipe, shapes, density, layoutDirection);
        if (radii != null) drawable.setCornerRadii(radii);
        float widthDp = recipe.strokeWidth.isEmpty()
                ? recipe.strokeWidthDp : shapes.strokeDp(recipe.strokeWidth);
        int widthPx = Math.round(widthDp * Math.max(0f, density));
        if (widthPx > 0 && state.stroke != null && !state.stroke.isEmpty()) {
            drawable.setStroke(widthPx, color(state.stroke, colors));
        }
        return drawable;
    }

    private static float[] cornerRadii(ThemeShapeRecipe recipe, ThemeShapePalette shapes,
                                       float density, int layoutDirection) {
        if (!recipe.radius.isEmpty()) {
            // **不要在这里乘 density**:GradientDrawable 的圆角是"密度无关值",
            // 它自己会按 drawable 的 density 缩放一次(真机实测:工厂写进 setCornerRadii 的
            // 47.2px 在 density=2.95 的 drawable 上读回来是 139px = 47.2 × 2.95)。
            // 这里再乘一次 = 乘两遍,表现就是"弹窗/按钮的圆角凭空大了 3 倍,而且上圆下不圆"
            // (分角写法里 0dp 的角乘出来还是 0,于是只有写了半径的角被放大 —— 用户口径
            // "上部分圆角比下部分的大得多")。编译期那份 XML 之所以对,就是 @dimen 只解析一次。
            // **圆角要按密度换算后再喂给 setCornerRadii**(2026-10-01 真机实测口径):
            // 编译期那份 XML 走 aapt2 资源解析,框架**不再**二次换算 —— 26dp 画出来就是 26dp 的弧;
            // 而代码里 new GradientDrawable() + setCornerRadii 得到的 drawable,框架会把它当
            // "已按本机密度缩放过的像素值"再处理一遍,直接喂 dp 会比编译期那份小约一个 density
            // (实测:radius_dialog=26 时,编译期底几何 50px、喂 dp 的重建底只有 19px,
            //  约 19 × 2.95 ≈ 56 ≈ 编译期那份)。
            // 所以这里用 radiusPx(圆角档 × density),让两条路的最终视觉半径一致。
            float radius = shapes.radiusPx(recipe.radius, density);
            return new float[]{radius, radius, radius, radius,
                    radius, radius, radius, radius};
        }
        if (recipe.corners.isEmpty() && recipe.cornerDp.isEmpty()) return null;
        float topStart = corner(recipe, "topStart", shapes, density);
        float topEnd = corner(recipe, "topEnd", shapes, density);
        float bottomEnd = corner(recipe, "bottomEnd", shapes, density);
        float bottomStart = corner(recipe, "bottomStart", shapes, density);
        float topLeft = corner(recipe, "topLeft", shapes, density);
        float topRight = corner(recipe, "topRight", shapes, density);
        float bottomRight = corner(recipe, "bottomRight", shapes, density);
        float bottomLeft = corner(recipe, "bottomLeft", shapes, density);
        if (hasCorner(recipe, "topStart")) {
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) topRight = topStart;
            else topLeft = topStart;
        }
        if (hasCorner(recipe, "topEnd")) {
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) topLeft = topEnd;
            else topRight = topEnd;
        }
        if (hasCorner(recipe, "bottomEnd")) {
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) bottomLeft = bottomEnd;
            else bottomRight = bottomEnd;
        }
        if (hasCorner(recipe, "bottomStart")) {
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) bottomRight = bottomStart;
            else bottomLeft = bottomStart;
        }
        return new float[]{topLeft, topLeft, topRight, topRight,
                bottomRight, bottomRight, bottomLeft, bottomLeft};
    }

    private static boolean hasCorner(ThemeShapeRecipe recipe, String name) {
        return recipe.corners.containsKey(name) || recipe.cornerDp.containsKey(name);
    }

    /**
     * 仅供 JVM 单测调用:把 {@link #cornerRadii} 的真实算术暴露出来。
     *
     * <p>为什么要开这个口:圆角被放大这类 bug 只在"真正算一遍"时才暴露,而
     * {@code cornerRadii} 是私有的、{@code create} 又需要 Android 的 {@link Resources},
     * 于是原有绊线只能去正则匹配 XML —— 整套单测全绿却把"16dp 被乘两遍密度"放上了真机
     * (用户口径"弹窗上圆角比下圆角大得多")。给测试一个纯函数入口,这条算术就再也躲不过单测。
     *
     * <p><b>口径</b>:返回的是**已按 density 换算的像素值**(与编译期 XML 那份 drawable 的观感对齐)——
     * 代码里 {@code new GradientDrawable()} + {@code setCornerRadii} 得到的 drawable,框架会把它当
     * "已缩放的像素"再处理一遍,喂 dp 会比编译期那份小约一个 density(真机实测 26 时 50px vs 19px)。
     * 因此这里必须乘 density;{@code ThemeDrawableFactoryRadiusTest} 按同一口径断言。
     */
    static float[] cornerRadiiForTest(ThemeShapeRecipe recipe, ThemeShapePalette shapes,
                                      float density, boolean rtl) {
        return cornerRadii(recipe, shapes, density,
                rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }

    private static float corner(ThemeShapeRecipe recipe, String name,
                                ThemeShapePalette shapes, float density) {
        String token = recipe.corners.get(name);
        // 同 uniform 分支:代码构造的 drawable 要按密度换算,才能与编译期 XML 那份同观感
        if (token != null) return shapes.radiusPx(token, density);
        Float dp = recipe.cornerDp.get(name);
        return (dp == null ? 0f : Math.max(0f, dp)) * Math.max(0f, density);
    }

    private static int color(String value, ThemeColorPalette colors) {
        if (value == null || value.isEmpty() || "transparent".equals(value)) {
            return Color.TRANSPARENT;
        }
        if (value.startsWith("#")) return Color.parseColor(value);
        if (!colors.has(value)) throw new IllegalArgumentException("未知主题颜色:" + value);
        return colors.get(value);
    }

    private static Map<String, ThemeShapeRecipe> allRecipes(Resources resources) {
        Map<String, ThemeShapeRecipe> current = recipes;
        if (current != null) return current;
        synchronized (ThemeDrawableFactory.class) {
            if (recipes != null) return recipes;
            LinkedHashMap<String, ThemeShapeRecipe> parsed = new LinkedHashMap<>();
            try (InputStreamReader reader = new InputStreamReader(
                    resources.getAssets().open(RECIPES_ASSET), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        parsed.put(entry.getKey(), parse(entry.getKey(), entry.getValue().getAsJsonObject()));
                    }
                }
            } catch (Throwable ignored) {
            }
            recipes = Collections.unmodifiableMap(parsed);
            return recipes;
        }
    }

    private static ThemeShapeRecipe parse(String id, JsonObject json) {
        String shape = string(json, "shape");
        String radius = string(json, "radius");
        String strokeWidth = string(json, "strokeWidth");
        float strokeWidthDp = number(json, "strokeWidthDp", 0f);
        String ripple = string(json, "ripple");
        LinkedHashMap<String, String> cornerTokens = new LinkedHashMap<>();
        LinkedHashMap<String, Float> cornerDp = new LinkedHashMap<>();
        JsonObject corners = object(json, "corners");
        if (corners != null) {
            for (Map.Entry<String, JsonElement> entry : corners.entrySet()) {
                if (entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isString()) {
                    cornerTokens.put(entry.getKey(), entry.getValue().getAsString());
                } else if (entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    String name = entry.getKey().endsWith("Dp")
                            ? entry.getKey().substring(0, entry.getKey().length() - 2) : entry.getKey();
                    cornerDp.put(name, Math.max(0f, entry.getValue().getAsFloat()));
                }
            }
        }
        LinkedHashMap<String, ThemeShapeRecipe.PaintState> states = new LinkedHashMap<>();
        JsonObject stateObject = object(json, "states");
        if (stateObject != null) {
            for (Map.Entry<String, JsonElement> entry : stateObject.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject paint = entry.getValue().getAsJsonObject();
                states.put(entry.getKey(), new ThemeShapeRecipe.PaintState(
                        string(paint, "fill"), string(paint, "stroke")));
            }
        }
        List<ThemeShapeRecipe> layers = new ArrayList<>();
        JsonElement layerElement = json.get("layers");
        if (layerElement != null && layerElement.isJsonArray()) {
            JsonArray array = layerElement.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) layers.add(parse(id + "#" + i, array.get(i).getAsJsonObject()));
            }
        }
        return new ThemeShapeRecipe(id, shape, radius, cornerTokens, cornerDp, strokeWidth,
                strokeWidthDp, ripple, states, layers);
    }

    private static JsonObject object(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static float number(JsonObject json, String name, float fallback) {
        JsonElement value = json.get(name);
        try {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsFloat() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
