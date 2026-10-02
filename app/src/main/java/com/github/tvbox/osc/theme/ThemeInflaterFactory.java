package com.github.tvbox.osc.theme;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.tvbox.osc.bean.theme.ThemeColorPalette;

import java.lang.reflect.Field;

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
 *       {@code hl_layoutBackground}):逐项调用已经核对过的公开 API。禁止按属性名猜 setter;
 *       {@code scripts/check-theme-res-coverage.mjs} 会让未登记属性直接失败;</li>
 *   <li><b>值是色值选择器的属性</b>:只在明确知道组件类型与公开 API 时处理,不做反射猜测。</li>
 * </ul>
 *
 * <p>怎么装进去:AppCompat 已经在 Activity 的 LayoutInflater 上装过它的 Factory2(负责把
 * {@code TextView} 换成 {@code AppCompatTextView} 这些),而 {@code setFactory2} 不允许二次设置,
 * 所以这里<b>反射替换</b> {@code mFactory2} 并把原 Factory2 链在后面
 * (顺序不能反:先让 AppCompat 造视图,再由本类改颜色)。
 * 反射失败就静默放弃 —— 界面退回内置配色,不会崩。
 */
public final class ThemeInflaterFactory implements LayoutInflater.Factory2 {

    /** 样式属性名(非命名空间属性,{@code style="@style/X"}) */
    private static final String STYLE_ATTR = "style";

    private final LayoutInflater.Factory2 delegate2;
    private final LayoutInflater.Factory delegate1;

    private ThemeInflaterFactory(LayoutInflater.Factory2 delegate2, LayoutInflater.Factory delegate1) {
        this.delegate2 = delegate2;
        this.delegate1 = delegate1;
    }

    /**
     * 给 Activity 的 LayoutInflater 装上本工厂(幂等)。
     * <p>完整主题快照装好后即安装。配方背景无论内置/自定义都交给同一个工厂；
     * 其它颜色在内置主题下重设为同值，不改变视觉。
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
        if (inflater == null || ThemeRuntime.snapshot() == null) return;
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
        String msg = "主题: 布局注入器" + (ok ? "已装 " : "未装上(反射被拦,布局里的颜色不会跟着主题走) ")
                + inflater.getContext().getClass().getSimpleName();
        try {
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, msg);
        } catch (Throwable ignored) {
        }
        // 同时进 logcat(固定 tag):排障时 `adb logcat -s MBoxRadius` 一眼能看到
        // "这个 inflater 到底装上注入器没有" —— 业务日志默认关门控,只落库等于查不到。
        try {
            android.util.Log.i("MBoxRadius", msg);
        } catch (Throwable ignored) {
        }
        // 反射被拦是"主题只生效一半"的典型原因,必须留痕(正常路径不记,避免刷屏)
        if (!ok) {
            try {
                android.util.Log.w("MBoxRadius",
                        "布局注入器未装上(反射被拦):" + inflater.getContext().getClass().getName());
            } catch (Throwable ignored) {
            }
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
        // **_关键**:普通类(含本项目的自定义控件与第三方 ShadowLayout)上面那条链**不造**
        // （AppCompatViewInflater 只认它那张内置控件名表),框架随后会用"类名反射"兜底直接 new ——
        // 那条路不经过任何 factory,{@link #applyTheme} 永远没机会跑。
        // 真机实测:{@code com.github.tvbox.osc.ui.kit.SizedIconTextView} 与
        // {@code com.lihang.ShadowLayout} 都"被问到但未造出",于是首页搜索框的底、
        // 卡片角标的底、悬浮球的底全停在编译期色(用户口径:"很多组件卡片颜色不对")。
        // 这里自己把它们 new 出来,颜色/尺寸行为与框架兜底一致(同样是 (Context, AttributeSet) 构造),
        // 但这样就能走到下一行的 applyTheme。
        if (view == null && name != null) {
            view = createByReflection(name, context, attrs);
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

    /**
     * 用"类名 + (Context, AttributeSet)"构造一个视图 —— 与框架兜底路径同签名。
     *
     * <p><b>为什么连"不带点的标准控件名"也要管</b>(2026-10-01 真机定位):
     * {@code FrameLayout}/{@code RelativeLayout} 这类标签,AppCompat 的 inflater 不认、
     * 我们的 Factory2 又只处理"带点的自定义类名",于是框架最后用**类名反射兜底**直接 new ——
     * 那条路不经过任何 factory,{@link #applyTheme} 永远没机会跑。
     * 真机表现就是"凡是外层壳/悬浮球这类 FrameLayout,底色永远停在编译期色":
     * 直播悬浮球({@code fragment_user.xml} 的 FrameLayout + bg_fab_oval)就是这么漏掉的,
     * 而同一个布局里带包名的自定义控件(改完之后)反而能跟上主题 —— 完全是反直觉的。
     *
     * <p>按框架同款前缀把标准控件名补全;认不出/没有 (Context, AttributeSet) 构造时返回 null,
     * 交回框架原来的兜底,行为与改造前一致。
     */
    private static View createByReflection(String name, Context context, AttributeSet attrs) {
        try {
            Class<?> clazz = resolve(name, context.getClassLoader());
            if (clazz != null && View.class.isAssignableFrom(clazz)) {
                java.lang.reflect.Constructor<?> ctor =
                        clazz.getConstructor(Context.class, AttributeSet.class);
                return (View) ctor.newInstance(context, attrs);
            }
        } catch (Throwable ignored) {
            // 没有 (Context, AttributeSet) 构造、或不是 View:交回框架兜底
        }
        return null;
    }

    /** 与框架 LayoutInflater 同款:带点按全限定名找,不带点按三个前缀找 */
    private static Class<?> resolve(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name.indexOf('.') > 0) {
            return Class.forName(name, false, loader);
        }
        for (String prefix : new String[]{"android.widget.", "android.view.", "android.webkit."}) {
            try {
                return Class.forName(prefix + name, false, loader);
            } catch (Throwable ignored) {
                // 换下一个前缀
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 改色
    // ------------------------------------------------------------------

    private void applyTheme(View view, Context context, AttributeSet attrs) {
        if (view == null || attrs == null) return;
        ThemeColorPalette palette = ThemeRuntime.colorPalette();
        if (palette == null) return;
        // **先内联、后 style**:Android 的规则是 style < inline(内联赢),
        // 而"style 解析"那一趟拿到的是 style 与内联**展开后**的最终资源,它会覆盖同名的内联处理结果。
        // 2026-10-01 真机踩到:输入框的外框色有 ① 内联 boxStrokeColor(带"聚焦=纯色按钮底"的状态表)
        // 与 ② style 里的 boxStrokeColor(Material 默认的 colorPrimary 状态表)两份 ——
        // 原来顺序是 style 在后,于是内联那份被覆盖,聚焦时外框又变回主题高亮蓝 #1890FF(用户口径:
        // "输入框选中的边框色为啥是蓝色的")。顺序调过来后,内联的意图才真正生效。
        int count = attrs.getAttributeCount();
        for (int i = 0; i < count; i++) {
            String attrName = attrs.getAttributeName(i);
            if (attrName == null || STYLE_ATTR.equals(attrName)) continue; // style 由下面那一趟处理
            int resId = attrs.getAttributeResourceValue(i, 0);
            if (resId == 0) continue;
            try {
                if (!applyAttribute(view, context, attrName, resId, palette)) {
                    continue;
                }
            } catch (Throwable th) {
                // 单个属性失败不影响其它属性,更不该影响视图创建
                // —— 但**必须留痕**:静默吞掉就是"某块底/文字不跟主题走却查无实据"的常见来源
                try {
                    android.util.Log.w("MBoxRadius", "注入属性失败 " + attrName
                            + " @ " + view.getClass().getSimpleName() + " → " + th);
                } catch (Throwable ignored) {
                }
            }
        }
        // style 与父 style 的收尾一趟(内联已在上面处理完,这里只补"布局没写、但 style 里有"的那些)
        applyResolvedStyleAttrs(view, context, attrs, palette);
        if (view instanceof EditText && android.os.Build.VERSION.SDK_INT >= 29) {
            tintTextHandlesAndCursor((EditText) view, palette.get("text_main"));
        }
    }

    /** API 29+ 可直接设置句柄与闪烁光标，覆盖系统主题缓存里的旧强调色。 */
    @androidx.annotation.RequiresApi(29)
    private static void tintTextHandlesAndCursor(EditText editText, int color) {
        try {
            Drawable middle = tintedHandle(editText.getTextSelectHandle(), editText, color);
            Drawable left = tintedHandle(editText.getTextSelectHandleLeft(), editText, color);
            Drawable right = tintedHandle(editText.getTextSelectHandleRight(), editText, color);
            Drawable cursor = tintedHandle(editText.getTextCursorDrawable(), editText, color);
            if (middle != null) editText.setTextSelectHandle(middle);
            if (left != null) editText.setTextSelectHandleLeft(left);
            if (right != null) editText.setTextSelectHandleRight(right);
            if (cursor != null) editText.setTextCursorDrawable(cursor);
        } catch (Throwable ignored) {
            // 某些系统没有提供默认句柄或光标时，保留平台原样。
        }
    }

    private static Drawable tintedHandle(Drawable original, EditText editText, int color) {
        if (original == null) return null;
        Drawable.ConstantState state = original.getConstantState();
        Drawable copy = (state == null ? original : state.newDrawable(editText.getResources())).mutate();
        androidx.core.graphics.drawable.DrawableCompat.setTint(copy, color);
        return copy;
    }

    /**
     * style 与父 style 的收尾一趟:**只补"布局没写、但 style 里有"的那些属性**。
     *
     * <p>为什么要按"布局有没有显式写"来跳过(2026-10-01):
     * {@code obtainStyledAttributes} 返回的是 style 与内联**合并后**的值,所以这一趟会无条件把
     * 内联已经处理好的结果再覆盖一次 —— 实测就踩到:布局里写了 {@code app:hintTextColor=50%主色},
     * 内联通道(经 TextInputLayout#setHintTextColor)已经把 50% 装上了,这一趟又用
     * Material 样式里的 {@code android:textColorHint}(编译期 {@code #611C1B1F})盖了回去,
     * 现象就是"布局明明写了 50%,占位却还是老灰"。
     * 判据:该属性在内联里出现过(再算上 TextInputLayout 的 {@code hintTextColor},两者同义)就跳过。
     */
    // resolvedAttrs 是合法的 attr ID 数组；Lint 把该重载的参数误判成 R.styleable。
    @SuppressLint("ResourceType")
    private void applyResolvedStyleAttrs(View view, Context context, AttributeSet attrs,
                                         ThemeColorPalette palette) {
        final int[] resolvedAttrs = {android.R.attr.textColor, android.R.attr.background};
        final String[] names = {"textColor", "background"};
        TypedArray values = null;
        try {
            values = context.obtainStyledAttributes(attrs, resolvedAttrs);
            for (int i = 0; i < resolvedAttrs.length; i++) {
                String name = names[i];
                if (hasInlineAttr(attrs, name)) continue; // 内联已经赢过一次,别让 style 翻盘
                int resId = values.getResourceId(i, 0);
                if (resId != 0) applyAttribute(view, context, name, resId, palette);
            }
        } catch (Throwable ignored) {
            // 单个 ROM 若无法展开样式，内联属性通道仍继续工作。
        } finally {
            if (values != null) values.recycle();
        }
    }

    /** 布局里是否显式写了这个属性(hintTextColor 与 textColorHint 同义,一并认) */
    private static boolean hasInlineAttr(AttributeSet attrs, String name) {
        if (attrs == null) return false;
        for (int i = 0; i < attrs.getAttributeCount(); i++) {
            String n = attrs.getAttributeName(i);
            if (n == null) continue;
            if (n.equals(name)) return true;
            if ("textColorHint".equals(name) && "hintTextColor".equals(n)) return true;
        }
        return false;
    }

    /**
     * 处理一个属性。
     *
     * @return 是否"认得并处理过"(false = 与本类无关,交回系统当初设的值)
     */
    private boolean applyAttribute(View view, Context context, String attrName, int resId, ThemeColorPalette palette) {
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
            case "hintTextColor": {
                // **悬停标签(上移后的 hint)**走这里:Material 1.9 的正式 API 是
                // {@code setDefaultHintTextColor}(setHintTextColor 只改框内那份)。
                // 原来这里没有这个分支,属性名对不上就整条静默跳过 ——
                // 于是"布局明明写了 50% 主色,标签却还是主题里的 text_hint(40%)"。
                // 规则(用户口径):未聚焦 = 文字主色 50%,聚焦 = 文字主色**不透明**。
                ColorStateList csl = textInputColors(resId, palette);
                if (csl == null) return false;
                if (view instanceof com.google.android.material.textfield.TextInputLayout) {
                    com.google.android.material.textfield.TextInputLayout til =
                            (com.google.android.material.textfield.TextInputLayout) view;
                    til.setDefaultHintTextColor(colorStates(csl));
                    til.setPlaceholderTextColor(csl);
                    return true;
                }
                if (view instanceof TextView) {
                    ((TextView) view).setHintTextColor(csl);
                    return true;
                }
                return false;
            }
            case "button": {
                // CompoundButton 的勾选圈/单选圈:值是个 drawable(常见是"选中/未选中"两个矢量的 selector)。
                // 这条属性注入器原来完全不碰 —— 圈的颜色/描边就永远停在内置配色
                // (用户口径:弹窗列表里那些圈"颜色和主题对不上")。
                // 取 themedDrawable:selector 本身没有主题色时它会退回系统那份并给内部矢量上主题 tint。
                if (!(view instanceof android.widget.CompoundButton)) return false;
                Drawable themed = ThemeDrawables.themedDrawable(resId, context.getResources());
                if (themed == null) return false;
                android.widget.CompoundButton cb = (android.widget.CompoundButton) view;
                cb.setButtonDrawable(themed);
                // **View 层的 buttonTintList 才是选择器型勾选框的正解**:
                // android:button 通常是个 selector(选中/未选中两个矢量),而 StateListDrawable
                // 换状态时画的是**子 drawable**,drawable 上的 tint 不会下传;本运行时的 android.jar
                // 又把 DrawableContainer.getChildren()/Drawable.getTintList() 这些隐藏 API 剥掉了,
                // 公开面上拿不到子项去逐一着色。ButtonTint 由 View 自己在绘制时套用,
                // 天然覆盖 selector 的每个状态 —— 真机实证:方形勾选框描边原来一直停在编译期 #1F2937。
                String tintKey = ThemeDrawables.iconTintKey(resId, context.getResources());
                Integer tintColor = tintKey == null ? null : colorOfConcept(tintKey, palette);
                if (tintColor != null) {
                    cb.setButtonTintList(android.content.res.ColorStateList.valueOf(tintColor));
                }
                try {
                    if (ThemeDrawables.TRACE) {
                        android.util.Log.i("MBoxRadius", "勾选圈 " + resName(context, resId)
                                + " tintKey=" + tintKey
                                + " 实取=" + themed.getClass().getSimpleName()
                                + " buttonTint=" + (tintColor == null ? "无"
                                : String.format(java.util.Locale.ROOT, "#%08X", tintColor)));
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }
            case "background":
                if (resId == com.github.tvbox.osc.R.drawable.theme_btn_primary
                        || resId == com.github.tvbox.osc.R.drawable.theme_btn_secondary
                        || resId == com.github.tvbox.osc.R.drawable.theme_btn_ghost
                        || resId == com.github.tvbox.osc.R.drawable.theme_btn_danger
                        || resId == com.github.tvbox.osc.R.drawable.theme_btn_danger_entry
                        || resId == com.github.tvbox.osc.R.drawable.selector_widget_btn) {
                    com.github.tvbox.osc.ui.kit.WidgetPressEffect.attach(view);
                }
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
            case "placeholderTextColor": {
                // TextInputLayout 的**框内占位**(hint 还没上移时)走 setPlaceholderTextColor;
                // 上移之后的悬浮标签走 setDefaultHintTextColor(见下面 hintTextColor 分支)。
                // 这两条是 Material 1.9 的正式 API,不是同一条路 —— 只写 android:textColorHint
                // 时占位色实际仍取 Material 主题里的角色(真机实测一直是内置灰)。
                ColorStateList csl = textInputColors(resId, palette);
                if (csl == null) return false;
                if (view instanceof com.google.android.material.textfield.TextInputLayout) {
                    ((com.google.android.material.textfield.TextInputLayout) view)
                            .setPlaceholderTextColor(csl);
                    return true;
                }
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setHintTextColor(csl);
                    return true;
                }
                return false;
            }
            case "boxStrokeColor": {
                ColorStateList csl = textInputColors(resId, palette);
                if (csl == null || !(view instanceof com.google.android.material.textfield.TextInputLayout)) {
                    return false;
                }
                // 外框:聚焦 = 文字主色**不透明**,其余 = 文字主色 50%
                ((com.google.android.material.textfield.TextInputLayout) view)
                        .setBoxStrokeColorStateList(colorStates(csl));
                return true;
            }
            case "shadowColor": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof TextView)) return false;
                TextView tv = (TextView) view;
                tv.setShadowLayer(tv.getShadowRadius(), tv.getShadowDx(), tv.getShadowDy(), color);
                return true;
            }
            case "hl_layoutBackground":
            case "hl_layoutBackground_true": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof com.lihang.ShadowLayout)) return false;
                com.lihang.ShadowLayout shadow = (com.lihang.ShadowLayout) view;
                if ("hl_layoutBackground_true".equals(attrName)) shadow.setLayoutBackgroundTrue(color);
                else shadow.setLayoutBackground(color);
                return true;
            }
            case "titleColor":
            case "leftTitleColor":
            case "rightTitleColor": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof com.hjq.bar.TitleBar)) return false;
                com.hjq.bar.TitleBar bar = (com.hjq.bar.TitleBar) view;
                if ("leftTitleColor".equals(attrName)) bar.setLeftTitleColor(color);
                else if ("rightTitleColor".equals(attrName)) bar.setRightTitleColor(color);
                else bar.setTitleColor(color);
                return true;
            }
            case "tab_select_color":
            case "tab_deselect_color": {
                Integer color = colorOf(resId, palette);
                if (color == null || !(view instanceof com.angcyo.tablayout.DslTabLayout)) return false;
                com.angcyo.tablayout.DslTabLayout tabs = (com.angcyo.tablayout.DslTabLayout) view;
                com.angcyo.tablayout.DslTabLayoutConfig config = tabs.getTabLayoutConfig();
                if (config == null) return false;
                if ("tab_select_color".equals(attrName)) config.setTabSelectColor(color);
                else config.setTabDeselectColor(color);
                return true;
            }
            default:
                return false;
        }
    }

    /** 背景:纯色换色;drawable 交给 {@link ThemeDrawables} 按原 XML 重建 */
    private boolean applyBackground(View view, Context context, int resId, ThemeColorPalette palette) {
        Integer color = colorOf(resId, palette);
        if (color != null) {
            view.setBackgroundColor(color);
            return true;
        }
        Drawable rebuilt = ThemeDrawables.rebuild(resId, context.getResources());
        if (rebuilt == null) {
            // 只有"这个 drawable 里确实写了随主题走的颜色"时才值得告警 ——
            // 固定黑渐变(bg_gradient_black_b2t)之类的资源本来就该原样用,返回 null 是安全阀正常工作,
            // 一律告警会把它刷成噪音(真机上正好刷了一屏)。判定用的是同一个别名表,不会误报。
            if (ThemeDrawables.refersThemedColor(resId, context.getResources())) {
                try {
                    android.util.Log.w("MBoxRadius", "背景重建失败,退回编译期底:" + resName(context, resId)
                            + " @ " + resName(context, view.getId()));
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
        view.setBackground(rebuilt);
        return true;
    }

    /** 按概念名取当前主题色(取不到返回 null,调用方跳过) */
    private static Integer colorOfConcept(String concept, ThemeColorPalette palette) {
        if (concept == null || palette == null || !palette.has(concept)) return null;
        return palette.get(concept);
    }

    /**
     * 输入框的"未聚焦色"状态表:布局里给的那个颜色 → 未聚焦用,**聚焦换成它的不透明版**。
     *
     * <p>用户口径(2026-10-01):"hint 在输入框的时候颜色是文字主色 50%,边框颜色也是文字主色 50%;
     * 选中之后边框主色为文字主色没有透明度,移动到输入框上方的 hint 也是文字主色没有透明度"。
     * 布局里写的正是"文字主色 50%",所以聚焦态取它的**不透明版**,规则一处生效、不用再配第二档。
     */
    private static android.content.res.ColorStateList colorStates(
            android.content.res.ColorStateList unfocused) {
        int idle = unfocused.getDefaultColor();
        int focused = (idle & 0x00FFFFFF) | 0xFF000000; // 去掉透明度
        return new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{}},
                new int[]{focused, idle});
    }

    /** 把布局给的颜色解析成"聚焦不透明 / 未聚焦原样"的状态表 */
    private android.content.res.ColorStateList textInputColors(
            int resId, ThemeColorPalette palette) {
        ColorStateList csl = colorStateList(resId, null, palette);
        return csl == null ? null : colorStates(csl);
    }

    private static String resName(Context context, int resId) {        if (resId == 0) return "-";
        try {
            return context.getResources().getResourceEntryName(resId);
        } catch (Throwable th) {
            return "0x" + Integer.toHexString(resId);
        }
    }

    /** {@code src/srcCompat}:可重建的 drawable 直接换;单色主题图标用 tint 覆盖 */
    private boolean applyImageSource(View view, Context context, int resId, ThemeColorPalette palette) {
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

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    /** 该资源是不是"随主题走的单色",是则返回运行时颜色 */
    private Integer colorOf(int resId, ThemeColorPalette palette) {
        String key = ThemeColorAliases.paletteNameOf(resId);
        return key == null ? null : palette.get(key);
    }

    /** 颜色选择器:随主题的单色直接换成同值 CSL;随主题的 selector(如 widget_btn_text)按原 XML 重建 */
    private ColorStateList colorStateList(int resId, Context context, ThemeColorPalette palette) {
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
