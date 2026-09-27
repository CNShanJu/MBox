package com.github.tvbox.osc.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.tvbox.osc.bean.theme.ThemePalette;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 运行时换肤的<b>布局属性注入</b>通道(整个换肤层里覆盖面最大的一环)。
 *
 * <p>要解决的问题:布局里 {@code android:textColor="@color/text_foreground"} 这类引用,
 * 颜色由系统在 native 侧解析 {@code TypedArray.getColor},运行时改不了。于是换一条路:
 * <b>在视图刚被创建出来、还没上屏的时候</b>,自己再看一遍 XML 属性 —— 凡是"这个 {@code @color/x}
 * 随主题走"的属性,就用当前调色板的值重新设一遍。
 *
 * <p>覆盖的属性(按实际用量排,见 {@code scripts/check-theme-res-coverage.mjs} 的统计):
 * <ul>
 *   <li>{@code android:textColor} / {@code textColorHint} / {@code textColorLink} —— 占全部引用的六成;</li>
 *   <li>{@code app:tint} / {@code android:tint} —— 图标着色(单色矢量图标靠它,不必重建矢量);</li>
 *   <li>{@code android:background} —— 纯色直接换色,drawable 交给 {@link ThemeDrawables} 重建;</li>
 *   <li>{@code android:src} / {@code app:srcCompat} —— 图标(setImageTintList)或可重建 drawable;</li>
 *   <li>{@code backgroundTint} / {@code drawableTint} / {@code progressTint} / {@code indeterminateTint}
 *       / {@code thumbTint} / {@code trackTint} / {@code buttonTint} / {@code cardBackgroundColor}
 *       / {@code strokeColor} 等;</li>
 *   <li>{@code progressDrawable} —— 进度条的**轨道**色写在 drawable 里(如更新进度条
 *       {@code bg_update_progress}),只给 {@code progressTint} 是改不到它的:这里按原 XML 重建一份
 *       (重建保留 {@code @android:id/progress} 等 layer id,所以随后的 {@code setProgressTintList} 照样生效);</li>
 *   <li>第三方控件自定义的属性(如 TitleBar 的 {@code titleColor}、ShadowLayout 的
 *       {@code hl_layoutBackground}):按属性名反射找 {@code setXxx(int)} 同名 setter 兜底
 *       (调用点很少,失败就跳过,不影响其它属性);</li>
 *   <li><b>值是色值选择器的属性</b>({@code itemTextColor} / {@code itemIconTint} / {@code tabTextColor}
 *       / {@code chipStrokeColor} …):这些 setter 收的是 {@link ColorStateList},不是 int ——
 *       按 setter 名 + 参数的几种组合找(如 {@code setItemIconTintList} / {@code setTabTextColors}),
 *       塞进去的是"按主题重建过的"色值选择器。底栏选中色、顶部导航选中色就靠这条。</li>
 * </ul>
 *
 * <p>怎么装进去:AppCompat 已经在 Activity 的 LayoutInflater 上装过它的 Factory2(负责把
 * {@code TextView} 换成 {@code AppCompatTextView} 这些),而 {@code setFactory2} 不允许二次设置,
 * 所以这里<b>反射替换</b> {@code mFactory2} 并把原 Factory2 链在后面
 * (顺序不能反:先让 AppCompat 造视图,再由本类改颜色)。
 * 反射失败就静默放弃 —— 界面退回内置配色,不会崩。
 */
public final class ThemeInflaterFactory implements LayoutInflater.Factory2 {

    /** 按属性名缓存的反射 setter(第三方控件兜底路径用) */
    private static final Map<String, Method> SETTER_CACHE = new HashMap<>();

    /**
     * 名字本身表示"文本"的属性:<b>不走 int setter 兜底</b>。
     *
     * <p>原因:{@code setText(int)} / {@code setTitle(int)} 收的是<b>字符串资源 id</b> ——
     * 若某个布局把这类属性写成颜色({@code app:text="@color/text_foreground"}),按名字反射命中
     * {@code setText(int)} 就会把颜色当成资源 id 塞进去,界面冒出一段乱七八糟的文字。
     * 颜色对应的是 {@code textColor} / {@code titleColor} 这类名字,不在这里面。
     */
    private static final java.util.Set<String> TEXT_LIKE_ATTRS = new java.util.HashSet<>(
            java.util.Arrays.asList("text", "hint", "title", "label", "contentDescription", "tag"));

    /** 样式属性名(非命名空间属性,{@code style="@style/X"});由 {@link ThemeStyles} 那趟单独处理 */
    private static final String STYLE_ATTR = "style";

    private final LayoutInflater.Factory2 delegate2;
    private final LayoutInflater.Factory delegate1;

    private ThemeInflaterFactory(LayoutInflater.Factory2 delegate2, LayoutInflater.Factory delegate1) {
        this.delegate2 = delegate2;
        this.delegate1 = delegate1;
    }

    /**
     * 给 Activity 的 LayoutInflater 装上本工厂(幂等)。
     * <p>换肤没介入时什么都不做(不装),让内置主题走原生路径。
     */
    public static void install(Activity activity) {
        if (activity == null) return;
        install(activity.getLayoutInflater());
    }

    /**
     * 给任意一份 LayoutInflater 装上本工厂(幂等)。
     *
     * <p>为什么要按 inflater 而不是只按 Activity:视图是由**被 inflate 时那份 inflater** 决定的,
     * 而除了 Activity 自己的 inflater,还有"应用上下文的 inflater"这条路 ——
     * 部分适配器/第三方弹窗用 {@code LayoutInflater.from(appContext)} 造视图,那些视图
     * 原来完全吃不到主题(现象:标题栏这类代码取色的变了,而某些布局属性的底仍是内置浅色面)。
     */
    public static void install(LayoutInflater inflater) {
        if (inflater == null || !ThemeRuntime.active()) return;
        Object current = readField(inflater, "mFactory2");
        if (current instanceof ThemeInflaterFactory) return;
        LayoutInflater.Factory2 existing2 = current instanceof LayoutInflater.Factory2
                ? (LayoutInflater.Factory2) current : null;
        Object current1 = readField(inflater, "mFactory");
        LayoutInflater.Factory existing1 = current1 instanceof LayoutInflater.Factory
                ? (LayoutInflater.Factory) current1 : null;
        ThemeInflaterFactory factory = new ThemeInflaterFactory(existing2, existing1);
        if (writeField(inflater, "mFactory2", factory)) {
            log(inflater, true);
            return;
        }
        // 反射改不了(极少数 ROM/被加固的包):退回"二次设置"这条会被系统拒绝的路,失败即放弃
        try {
            inflater.setFactory2(factory);
            log(inflater, true);
        } catch (Throwable ignored) {
            // 装不上 = 布局里那些 @color 引用不会跟着自定义主题走(只剩代码取色/窗口底色两条通道),
            // 页面上会表现为"主题只变了一部分"。写进日志,便于和"主题压根没生效"区分开
            log(inflater, false);
        }
    }

    /** 把"注入器到底装上没有"写进运行日志(见 install 的说明) */
    private static void log(LayoutInflater inflater, boolean ok) {
        try {
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                    "主题: 布局注入器" + (ok ? "已装 " : "未装上(反射被拦,布局里的颜色不会跟着主题走) ")
                            + inflater.getContext().getClass().getSimpleName());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Factory2
    // ------------------------------------------------------------------

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        View view = null;
        if (delegate2 != null) {
            view = delegate2.onCreateView(parent, name, context, attrs);
        }
        if (view == null && delegate1 != null) {
            view = delegate1.onCreateView(name, context, attrs);
        }
        if (view != null) {
            applyTheme(view, context, attrs);
        }
        return view;
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        // Factory2 的实现会走上面那个方法;这里只作兼容(某些调用方直接用 Factory 接口)
        return onCreateView(null, name, context, attrs);
    }

    // ------------------------------------------------------------------
    // 改色
    // ------------------------------------------------------------------

    private void applyTheme(View view, Context context, AttributeSet attrs) {
        if (view == null || attrs == null) return;
        ThemePalette palette = ThemeRuntime.palette();
        if (palette == null) return;
        // 先按 ThemeStyles 补一遍"写在样式里"的颜色(布局里只有一句 style="…",本工厂看不到它内部),
        // 再跑内联属性 —— 顺序不能反:内联优先(如日志页「清空」= 样式给底色 + 内联给 text_danger)
        applyStyleAttrs(view, context, attrs, palette);
        int count = attrs.getAttributeCount();
        for (int i = 0; i < count; i++) {
            String attrName = attrs.getAttributeName(i);
            if (attrName == null || STYLE_ATTR.equals(attrName)) continue; // style 由上面那一趟处理
            int resId = attrs.getAttributeResourceValue(i, 0);
            if (resId == 0) continue;
            try {
                if (!applyAttribute(view, context, attrName, resId, palette)) {
                    continue;
                }
            } catch (Throwable ignored) {
                // 单个属性失败不影响其它属性,更不该影响视图创建
            }
        }
    }

    /**
     * 样式通道:布局里的 {@code style="@style/BtnSecondary"} 把颜色写在共享样式里,
     * 本工厂遍历 {@code attrs} 只看得到"这一句 style",看不到它内部的颜色 ——
     * 于是自定义主题下按钮会停在编译期那套颜色(用户口径:"删除按钮样式和主题都对不上")。
     * 这里按 {@link ThemeStyles} 的表把该样式里随主题走的属性补一遍。
     *
     * <p>布局内联写了同名属性的**跳过**(内联是用户在这一处显式写的,优先级更高;
     * 且内联的值可能是 {@code text_danger} 这类不随主题走的固定字面量,盖掉就错了)。
     */
    private void applyStyleAttrs(View view, Context context, AttributeSet attrs, ThemePalette palette) {
        int styleRes = 0;
        int count = attrs.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (STYLE_ATTR.equals(attrs.getAttributeName(i))) {
                styleRes = attrs.getAttributeResourceValue(i, 0);
                break;
            }
        }
        if (styleRes == 0) return;
        java.util.List<ThemeStyles.Attr> list = ThemeStyles.of(styleRes);
        if (list.isEmpty()) return;
        for (ThemeStyles.Attr attr : list) {
            if (hasInlineAttr(attrs, attr.name)) continue;
            try {
                applyAttribute(view, context, attr.name, attr.resId, palette);
            } catch (Throwable ignored) {
                // 单个属性失败不影响其它属性
            }
        }
    }

    /** 布局里内联写过这个属性吗(样式通道据此让位) */
    private static boolean hasInlineAttr(AttributeSet attrs, String name) {
        int count = attrs.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (name.equals(attrs.getAttributeName(i))) return true;
        }
        return false;
    }

    /**
     * 处理一个属性。
     *
     * @return 是否"认得并处理过"(false = 与本类无关,交回系统当初设的值)
     */
    private boolean applyAttribute(View view, Context context, String attrName, int resId, ThemePalette palette) {
        switch (attrName) {
            case "textColor":
            case "textColorHint":
            case "textColorLink": {
                ColorStateList csl = colorStateList(resId, context, palette);
                if (csl == null) return false;
                if (!(view instanceof TextView)) return false;
                TextView tv = (TextView) view;
                if ("textColor".equals(attrName)) tv.setTextColor(csl);
                else if ("textColorHint".equals(attrName)) tv.setHintTextColor(csl);
                else tv.setLinkTextColor(csl);
                return true;
            }
            case "button": {
                // CompoundButton 的勾选圈/单选圈:值是个 drawable(常见是"选中/未选中"两个矢量的 selector)。
                // 这条属性注入器原来完全不碰 —— 圈的颜色/描边就永远停在内置配色
                // (用户口径:弹窗列表里那些圈"颜色和主题对不上")。
                // 取 themedDrawable:selector 本身没有主题色时它会退回系统那份并给内部矢量上主题 tint。
                if (!(view instanceof android.widget.CompoundButton)) return false;
                Drawable themed = ThemeDrawables.themedDrawable(resId, context.getResources());
                if (themed == null) return false;
                ((android.widget.CompoundButton) view).setButtonDrawable(themed);
                return true;
            }
            case "background":
                return applyBackground(view, context, resId, palette);
            case "src":
            case "srcCompat":
                return applyImageSource(view, context, resId, palette);
            case "tint":
            case "imageTint": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof ImageView)) return false;
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(color));
                return true;
            }
            case "backgroundTint": {
                Integer color = colorOf(resId, palette);
                if (color == null) return false;
                view.setBackgroundTintList(ColorStateList.valueOf(color));
                return true;
            }
            case "drawableTint": {
                ColorStateList csl = colorStateList(resId, context, palette);
                if (csl == null || !(view instanceof TextView)) return false;
                ((TextView) view).setCompoundDrawableTintList(csl);
                return true;
            }
            case "foregroundTint": {
                Integer color = colorOf(resId, palette);
                if (color == null) return false;
                view.setForegroundTintList(ColorStateList.valueOf(color));
                return true;
            }
            case "progressTint":
            case "indeterminateTint":
            case "secondaryProgressTint": {
                ColorStateList csl = colorStateList(resId, context, palette);
                if (csl == null || !(view instanceof android.widget.ProgressBar)) return false;
                android.widget.ProgressBar pb = (android.widget.ProgressBar) view;
                if ("progressTint".equals(attrName)) pb.setProgressTintList(csl);
                else if ("indeterminateTint".equals(attrName)) pb.setIndeterminateTintList(csl);
                else pb.setSecondaryProgressTintList(csl);
                return true;
            }
            case "progressDrawable": {
                // 进度条的**轨道**色写在 drawable 里(如更新进度条 bg_update_progress 的 switch_track_off),
                // 光靠 progressTint 只能改进度那一层 → 自定义主题下轨道仍是内置色。这里按原 XML 重建一份
                // (重建会保留 @android:id/progress 等 layer id,所以后面的 setProgressTintList 照样生效)。
                Drawable rebuilt = ThemeDrawables.rebuild(resId, context.getResources());
                if (rebuilt == null || !(view instanceof android.widget.ProgressBar)) return false;
                ((android.widget.ProgressBar) view).setProgressDrawable(rebuilt);
                return true;
            }
            case "thumbTint":
            case "trackTint": {
                ColorStateList csl = colorStateList(resId, context, palette);
                if (csl == null) return false;
                if (view instanceof androidx.appcompat.widget.SwitchCompat) {
                    androidx.appcompat.widget.SwitchCompat sw = (androidx.appcompat.widget.SwitchCompat) view;
                    if ("thumbTint".equals(attrName)) sw.setThumbTintList(csl);
                    else sw.setTrackTintList(csl);
                    return true;
                }
                if (view instanceof android.widget.SeekBar) {
                    // SeekBar 没有独立的"轨道色"入口:轨道就是它的进度底,用 progressTint 覆盖
                    if ("thumbTint".equals(attrName)) ((android.widget.SeekBar) view).setThumbTintList(csl);
                    else ((android.widget.SeekBar) view).setProgressTintList(csl);
                    return true;
                }
                return false;
            }
            case "buttonTint": {
                ColorStateList csl = colorStateList(resId, context, palette);
                if (csl == null || !(view instanceof android.widget.CompoundButton)) return false;
                ((android.widget.CompoundButton) view).setButtonTintList(csl);
                return true;
            }
            case "cardBackgroundColor": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof com.google.android.material.card.MaterialCardView)) return false;
                ((com.google.android.material.card.MaterialCardView) view).setCardBackgroundColor(color);
                return true;
            }
            case "strokeColor": {
                ColorStateList csl = colorStateList(resId, context, palette);
                if (csl == null) return false;
                if (view instanceof com.google.android.material.card.MaterialCardView) {
                    ((com.google.android.material.card.MaterialCardView) view).setStrokeColor(csl);
                    return true;
                }
                // 按钮的描边也要跟着主题走:暗色主题下按钮本身就是"无底色 + 1dp 描边",
                // 描边不换色 = 按钮在深色底上直接消失(线宽由样式里的 strokeWidth 给,这里只换色)
                if (view instanceof com.google.android.material.button.MaterialButton) {
                    ((com.google.android.material.button.MaterialButton) view).setStrokeColor(csl);
                    return true;
                }
                return false;
            }
            case "boxStrokeColor": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof com.google.android.material.textfield.TextInputLayout)) return false;
                ((com.google.android.material.textfield.TextInputLayout) view).setBoxStrokeColor(color);
                return true;
            }
            default:
                return applyBySetter(view, context, attrName, resId, palette);
        }
    }

    /** 背景:纯色换色;drawable 交给 {@link ThemeDrawables} 按原 XML 重建 */
    private boolean applyBackground(View view, Context context, int resId, ThemePalette palette) {
        Integer color = colorOf(resId, palette);
        if (color != null) {
            view.setBackgroundColor(color);
            return true;
        }
        Drawable rebuilt = ThemeDrawables.rebuild(resId, context.getResources());
        if (rebuilt == null) return false;
        view.setBackground(rebuilt);
        return true;
    }

    /** {@code src/srcCompat}:可重建的 drawable 直接换;单色主题图标用 tint 覆盖 */
    private boolean applyImageSource(View view, Context context, int resId, ThemePalette palette) {
        Drawable rebuilt = ThemeDrawables.rebuild(resId, context.getResources());
        if (rebuilt != null && view instanceof ImageView) {
            ((ImageView) view).setImageDrawable(rebuilt);
            return true;
        }
        String key = ThemeDrawables.iconTintKey(resId, context.getResources());
        if (key == null || !(view instanceof ImageView)) return false;
        ((ImageView) view).setImageTintList(ColorStateList.valueOf(palette.get(key)));
        return true;
    }

    /**
     * 第三方控件兜底:按属性名猜 setter 并调用(结果按"类#属性"缓存)。
     *
     * <p>为什么要猜好几个名字:各家自定义控件的属性命名并不统一 ——
     * {@code ShadowLayout} 的 {@code hl_layoutBackground} 对应 {@code setLayoutBackground(int)}
     * (带库前缀),{@code hl_layoutBackground_true} 对应 {@code setLayoutBackgroundTrue(int)}
     * (库前缀 + 下划线分段)。所以按"去前缀 / 去下划线 / 逐段首字母大写"几种写法各试一次,
     * 命中就用;都不命中就跳过(那一处保持内置配色,不影响其它属性)。
     */
    private boolean applyBySetter(View view, Context context, String attrName, int resId, ThemePalette palette) {
        // 1) 单色属性(值是随主题走的纯色):塞给收 int 的 setter
        Integer color = colorOf(resId, palette);
        if (color != null && !TEXT_LIKE_ATTRS.contains(attrName)) {
            Method m = findSetter(view.getClass(), attrName, int.class);
            if (m != null && invokeQuietly(m, view, color)) return true;
        }
        // 2) **色值选择器属性**({@code itemTextColor} / {@code itemIconTint} / {@code tabTextColor} /
        //    {@code chipStrokeColor} 这类 Material 属性):值是个 {@code <selector>},
        //    setter 收的是 {@link ColorStateList} —— 以前只找 int setter,这类属性一个都改不到。
        //    底栏「选中那一项的文字/图标颜色」就是这么漏掉的(用户口径:"底部导航栏的文字选中颜色为啥没走")。
        ColorStateList csl = themedColorStateList(context, resId, palette);
        if (csl != null) {
            Method m = findSetter(view.getClass(), attrName, ColorStateList.class);
            if (m != null && invokeQuietly(m, view, csl)) return true;
        }
        return false;
    }

    /** 把资源解析成"按主题重建过"的 ColorStateList;与主题无关/不是色值选择器时返回 null */
    private ColorStateList themedColorStateList(Context context, int resId, ThemePalette palette) {
        Integer color = colorOf(resId, palette);
        if (color != null) return ColorStateList.valueOf(color);
        ColorStateList rebuilt = ThemeDrawables.rebuildColorStateList(resId, context.getResources());
        if (rebuilt != null) return rebuilt;
        // 兜底交给系统:自定义主题生效时,Resources 被包装过,取到的也是主题色
        try {
            return androidx.core.content.ContextCompat.getColorStateList(context, resId);
        } catch (Throwable th) {
            return null;
        }
    }

    private static boolean invokeQuietly(Method m, View view, Object arg) {
        try {
            m.invoke(view, arg);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private Method findSetter(Class<?> clazz, String attrName, Class<?> param) {
        String cacheKey = clazz.getName() + "#" + attrName + "#" + param.getSimpleName();
        synchronized (SETTER_CACHE) {
            if (SETTER_CACHE.containsKey(cacheKey)) return SETTER_CACHE.get(cacheKey);
            Method found = null;
            for (String candidate : setterCandidates(attrName)) {
                found = findMethod(clazz, candidate, param);
                if (found != null) break;
            }
            SETTER_CACHE.put(cacheKey, found);
            return found;
        }
    }

    /**
     * 由属性名推出几个可能的 setter 名(见 {@link #applyBySetter} 的说明)。
     *
     * <p>后缀变体是给 Material 那批属性用的:属性名与 setter 名并不一一对应 ——
     * {@code itemIconTint} → {@code setItemIconTintList}、{@code tabTextColor} → {@code setTabTextColors}。
     * 名字都不命中就跳过(那一处保持内置配色,不影响其它属性)。
     */
    private static java.util.List<String> setterCandidates(String attrName) {
        java.util.List<String> out = new java.util.ArrayList<>(8);
        String cap = capitalize(attrName);
        out.add("set" + cap);
        out.add("set" + cap + "List");
        out.add("set" + cap + "s");
        out.add("set" + cap + "Colors");
        out.add("set" + cap + "ColorStateList");
        String[] parts = attrName.split("_");
        if (parts.length > 1) {
            StringBuilder sb = new StringBuilder("set");
            for (String p : parts) {
                if (p.isEmpty()) continue;
                sb.append(capitalize(p));
            }
            out.add(sb.toString());
            // 去掉库前缀那一段(hl_layoutBackground → setLayoutBackground;tab_select_color → setSelectColor)
            StringBuilder dropped = new StringBuilder("set");
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].isEmpty()) continue;
                dropped.append(capitalize(parts[i]));
            }
            if (dropped.length() > 3) out.add(dropped.toString());
        }
        return out;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 沿继承链找一个"只吃一个 {@code param} 参数"的公开/私有方法 */
    private static Method findMethod(Class<?> clazz, String name, Class<?> param) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, param);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    /** 该资源是不是"随主题走的单色",是则返回运行时颜色 */
    private Integer colorOf(int resId, ThemePalette palette) {
        String key = ThemeColorAliases.paletteNameOf(resId);
        return key == null ? null : palette.get(key);
    }

    /** 颜色选择器:随主题的单色直接换成同值 CSL;随主题的 selector(如 chip_text)按原 XML 重建 */
    private ColorStateList colorStateList(int resId, Context context, ThemePalette palette) {
        Integer color = colorOf(resId, palette);
        if (color != null) return ColorStateList.valueOf(color);
        return ThemeDrawables.rebuildColorStateList(resId, context.getResources());
    }

    // ------------------------------------------------------------------
    // 反射读写 LayoutInflater 私有字段
    // ------------------------------------------------------------------

    private static Object readField(LayoutInflater inflater, String field) {
        try {
            Field f = LayoutInflater.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(inflater);
        } catch (Throwable th) {
            return null;
        }
    }

    private static boolean writeField(LayoutInflater inflater, String field, Object value) {
        try {
            Field f = LayoutInflater.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(inflater, value);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }
}
