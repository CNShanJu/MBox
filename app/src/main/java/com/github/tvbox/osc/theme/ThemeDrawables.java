package com.github.tvbox.osc.theme;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;

import com.github.tvbox.osc.bean.theme.ThemePalette;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 运行时换肤的 <b>drawable / 色值选择器重建</b>通道。
 *
 * <p>为什么需要它:布局里 {@code android:background="@drawable/bg_dialog"} 这种引用,
 * 颜色写在 drawable 的 {@code <solid android:color="@color/bg_float"/>} 里 ——
 * 编译期就烤进了资源表,既不是属性级颜色(改不了),也不走 Java 层 getDrawable
 * (系统的 {@code TypedArray.getDrawable} 是 native 实现)。唯一的办法是<b>按原样重建一份</b>。
 *
 * <p>做法(没有手写表、也不会跟资源脱节):把 drawable 的 XML 用 {@link Resources#getXml(int)} 读出来
 * (打包时它就是二进制 XML,运行时能读),按元素重建:
 * <ul>
 *   <li>{@code <shape>} → {@link GradientDrawable}(solid/stroke/corners/size/padding/gradient);</li>
 *   <li>{@code <selector>} → 有 {@code android:color} 的建成 {@link ColorStateList},
 *       否则建成 {@link StateListDrawable};</li>
 *   <li>{@code <layer-list>} / {@code <clip>} / {@code <inset>} / {@code <ripple>} → 对应 Drawable;</li>
 *   <li>颜色一律先看"这个 {@code @color/x} 是不是随主题走"({@link ThemeColorAliases}),
 *       是就换成当前调色板的值,不是就原样取系统值。</li>
 * </ul>
 *
 * <p><b>安全阀</b>:只重建"确实用到了随主题颜色"的那几种(一个都没用到就直接交回系统);
 * 遇到不认识的元素/解析异常一律返回 null,调用方(Resources 包装或 inflater 注入)退回系统 drawable ——
 * 最差的情况是这一处保持内置配色,绝不会画错或崩。
 *
 * <p><b>性能</b>:每个资源 id 只解析/构建一次,缓存 {@code ConstantState}(不是实例!),
 * 每次取用 {@code newDrawable()} 出一份新的 —— 直接复用同一个实例会让所有视图共享 bounds。
 */
public final class ThemeDrawables {

    private static final String NS = "http://schemas.android.com/apk/res/android";

    /** 缓存里的一条:要么是"可重建的 ConstantState",要么是"单色图标的 tint 概念名",要么是"不适用" */
    private static final class Scan {
        final Drawable.ConstantState state;
        final ColorStateList colorList;
        final String tintKey;

        Scan(Drawable.ConstantState state, ColorStateList colorList, String tintKey) {
            this.state = state;
            this.colorList = colorList;
            this.tintKey = tintKey;
        }

        static final Scan NONE = new Scan(null, null, null);
    }

    private static final Map<Integer, Scan> CACHE = new HashMap<>();
    /** 正在构建的 id(嵌套 drawable 引用自身时防死循环) */
    private static final ThreadLocal<Set<Integer>> BUILDING = new ThreadLocal<>();

    private ThemeDrawables() {
    }

    // ------------------------------------------------------------------
    // 对外的三个入口
    // ------------------------------------------------------------------

    /**
     * 重建一个含主题色的 drawable。
     *
     * @return 重建好的 drawable(每次调用都是新实例);{@code null} = 这个 drawable 不随主题走,
     *         调用方应当用系统给的
     */
    public static Drawable rebuild(int resId, Resources res) {
        if (resId == 0 || res == null || ThemeRuntime.palette() == null) return null;
        Scan scan = scan(resId, res);
        if (scan.state == null) return null;
        try {
            return scan.state.newDrawable(res);
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 重建一个含主题色的 {@link ColorStateList}(如 {@code res/color/chip_text.xml})。
     *
     * @return 重建好的色值选择器;{@code null} = 不随主题走
     */
    public static ColorStateList rebuildColorStateList(int resId, Resources res) {
        if (resId == 0 || res == null || ThemeRuntime.palette() == null) return null;
        Scan scan = scan(resId, res);
        return scan.colorList;
    }

    /**
     * 单色主题矢量图标对应的概念名(如 {@code ic_search} → {@code text_main})。
     * <p>图标把主题色写在自己的 {@code fillColor} 里,重建矢量太不划算;单色字形用 tint 覆盖即可。
     *
     * @return 概念名;{@code null} = 不是"单色 + 主题色"的图标(多色图标、写死颜色的一律不动)
     */
    public static String iconTintKey(int resId, Resources res) {
        if (resId == 0 || res == null || ThemeRuntime.palette() == null) return null;
        return scan(resId, res).tintKey;
    }

    /**
     * 代码里设底(代替 {@code View.setBackgroundResource})时用这个。
     *
     * <p>为什么必须换掉 {@code setBackgroundResource}:它按**编译期**资源取 drawable,
     * 而 {@code android:background="@drawable/x"} 走的是 inflater 注入那条通道 ——
     * 代码里再 {@code setBackgroundResource} 一次,等于把注入好的主题底**又覆盖回内置色**。
     * 弹窗/抽屉/卡片那些底全是这么设的,所以"布局改色生效、弹窗卡片却纹丝不动"
     * (用户口径:"透明度和卡片背景还是没生效")。
     *
     * @return 按主题重建过的那份;没在用自定义主题或该 drawable 与主题无关时返回系统那份
     */
    public static Drawable themedDrawable(int resId, Resources res) {
        if (resId == 0 || res == null) return null;
        Drawable rebuilt = rebuild(resId, res);
        if (rebuilt != null) return rebuilt;
        try {
            return res.getDrawable(resId);
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 代码里设底的**安全入口**(代替 {@code View.setBackgroundResource})。
     *
     * <p>三级兜底,保证"绝不会把底弄丢":按主题重建那份 → 系统那份 → 直接
     * {@link android.view.View#setBackgroundResource(int)}(理论上到不了这一步)。
     * {@code resId == 0} 表示清掉背景。
     */
    public static void applyBackground(android.view.View view, int resId) {
        if (view == null) return;
        if (resId == 0) {
            view.setBackground(null);
            return;
        }
        Drawable themed = themedDrawable(resId, view.getResources());
        if (themed != null) {
            view.setBackground(themed);
        } else {
            view.setBackgroundResource(resId);
        }
    }

    /**
     * 清缓存(切换主题后调;理论上一套主题一次进程,但热更新/调试时会重新装)
     */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // ------------------------------------------------------------------
    // 扫描 + 构建
    // ------------------------------------------------------------------

    private static Scan scan(int resId, Resources res) {
        synchronized (CACHE) {
            Scan cached = CACHE.get(resId);
            if (cached != null) return cached;
        }
        Scan built = build(resId, res);
        synchronized (CACHE) {
            CACHE.put(resId, built);
        }
        return built;
    }

    private static Scan build(int resId, Resources res) {
        Set<Integer> building = BUILDING.get();
        if (building == null) {
            building = new HashSet<>();
            BUILDING.set(building);
        }
        if (!building.add(resId)) return Scan.NONE;
        try (XmlResourceParser parser = res.getXml(resId)) {
            int event = parser.getEventType();
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next();
            }
            if (event != XmlPullParser.START_TAG) return Scan.NONE;
            String root = parser.getName();
            if ("vector".equals(root)) {
                // 单色矢量图标:取第一个"随主题走"的 fillColor 作为 tint 依据;
                // 一旦发现第二个不同颜色就判定为多色图标(不动它,免得涂坏)
                String key = singleVectorTintKey(parser, res);
                return key == null ? Scan.NONE : new Scan(null, null, key);
            }
            Builder b = new Builder(res);
            if ("selector".equals(root)) {
                Object result = b.buildSelector(parser);
                if (result instanceof ColorStateList) {
                    if (!b.usedThemed) return Scan.NONE;
                    return new Scan(null, (ColorStateList) result, null);
                }
                if (result instanceof Drawable) {
                    if (!b.usedThemed) return Scan.NONE;
                    Drawable.ConstantState st = ((Drawable) result).getConstantState();
                    return st == null ? Scan.NONE : new Scan(st, null, null);
                }
                return Scan.NONE;
            }
            Drawable d = b.buildDrawable(parser, root);
            if (d == null || !b.usedThemed) return Scan.NONE;
            Drawable.ConstantState st = d.getConstantState();
            return st == null ? Scan.NONE : new Scan(st, null, null);
        } catch (Throwable th) {
            return Scan.NONE;
        } finally {
            building.remove(resId);
        }
    }

    /**
     * 单色矢量图标"该被着成哪个主题色"。
     *
     * <p><b>两种写法都要认</b>(少了任何一种,那一类图标就永远不跟主题走 ——
     * 现象是"有的图标变了、有的没变",用户清单第 1 条"我的界面的那些图标没变"就是漏了第 ① 种):
     * <ol>
     *   <li>{@code <vector android:tint="@color/text_foreground">} —— 本仓库最常用的写法
     *       (20 个图标,路径统一白底 + 整体着色)。它把主题色声明在<b>矢量根节点</b>上,
     *       原来只扫 {@code <path fillColor>},于是这 20 个图标一个都没跟上主题;</li>
     *   <li>{@code <path android:fillColor="@color/x">} —— 单色字形直接写颜色(5 个图标);
     *       这种还要求"所有 path 同一种主题色",多色图标不碰。</li>
     * </ol>
     */
    private static String singleVectorTintKey(XmlResourceParser parser, Resources res) throws Exception {
        // ① 矢量根节点上的 android:tint:它就是"这个图标该是主题色 X"的直白声明,优先取它
        int rootTint = parser.getAttributeResourceValue(NS, "tint", 0);
        if (rootTint != 0) {
            String rootKey = ThemeColorAliases.paletteNameOf(rootTint);
            if (rootKey != null) return rootKey;
        }
        // ② 退回到逐 path 看颜色(单色才认):fillColor 与 strokeColor 都算 ——
        //    纯描边型图标(如多选圆环 ic_select_ring:fill=transparent + stroke=select_fill)
        //    只写 strokeColor,不看它就永远不跟主题走;@android:color/transparent 视作"没颜色"跳过,
        //    其余"写死的颜色"仍按老规矩直接放弃(避免把多色/带白底的图标涂坏)。
        String found = null;
        int depth = 0;
        String[] colorAttrs = {"fillColor", "strokeColor"};
        while (true) {
            int event = parser.next();
            if (event == XmlPullParser.END_DOCUMENT) break;
            if (event == XmlPullParser.START_TAG) {
                depth++;
                for (String attr : colorAttrs) {
                    int colorRes = parser.getAttributeResourceValue(NS, attr, 0);
                    if (colorRes == 0 || colorRes == android.R.color.transparent) continue;
                    String key = ThemeColorAliases.paletteNameOf(colorRes);
                    if (key == null) return null; // 写死的颜色:不动
                    if (found == null) {
                        found = key;
                    } else if (!found.equals(key)) {
                        return null; // 多色
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
                if (depth <= 0) break;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // 构建器
    // ------------------------------------------------------------------

    private static final class Builder {
        private final Resources res;
        /** 本次构建是否真的替换过主题色(没替换过就交回系统,避免多做一份 drawable) */
        boolean usedThemed = false;

        Builder(Resources res) {
            this.res = res;
        }

        /** 按根元素名构建;不认识的元素返回 null(调用方退回系统) */
        Drawable buildDrawable(XmlResourceParser p, String tag) {
            if ("shape".equals(tag)) return shape(p);
            if ("layer-list".equals(tag)) return layerList(p);
            if ("clip".equals(tag)) return clip(p);
            if ("inset".equals(tag)) return inset(p);
            if ("ripple".equals(tag)) return ripple(p);
            if ("selector".equals(tag)) {
                Object o = buildSelector(p);
                return o instanceof Drawable ? (Drawable) o : null;
            }
            return null;
        }

        // ── shape ──

        private GradientDrawable shape(XmlResourceParser p) {
            GradientDrawable d = new GradientDrawable();
            String shapeName = str(p, "shape", "rectangle");
            int shapeType = GradientDrawable.RECTANGLE;
            if ("oval".equals(shapeName)) shapeType = GradientDrawable.OVAL;
            else if ("line".equals(shapeName)) shapeType = GradientDrawable.LINE;
            else if ("ring".equals(shapeName)) shapeType = GradientDrawable.RING;
            d.setShape(shapeType);

            Integer solid = null;
            Integer strokeColor = null;
            int strokeWidth = 0;
            float dashWidth = 0f;
            float dashGap = 0f;
            Integer gradientType = null;
            Integer[] gradientColors = null;
            int angle = 0;
            Float centerX = null;
            Float centerY = null;
            Float gradientRadius = null;

            float[] corners = null;
            Integer sizeW = null;
            Integer sizeH = null;
            int padL = -1, padT = -1, padR = -1, padB = -1;

            try {
                int outer = p.getDepth();
                while (true) {
                    int event = p.next();
                    if (event == XmlPullParser.END_DOCUMENT) break;
                    if (event == XmlPullParser.END_TAG && p.getDepth() <= outer) break;
                    if (event != XmlPullParser.START_TAG) continue;
                    String tag = p.getName();
                    if ("solid".equals(tag)) {
                        solid = color(p, "color");
                    } else if ("stroke".equals(tag)) {
                        strokeWidth = dimen(p, "width", 0);
                        strokeColor = color(p, "color");
                        dashWidth = dimenFloat(p, "dashWidth", 0f);
                        dashGap = dimenFloat(p, "dashGap", 0f);
                    } else if ("corners".equals(tag)) {
                        corners = corners(p);
                    } else if ("size".equals(tag)) {
                        sizeW = optionalDimenOrNull(p, "width");
                        sizeH = optionalDimenOrNull(p, "height");
                    } else if ("padding".equals(tag)) {
                        padL = dimen(p, "left", -1);
                        padT = dimen(p, "top", -1);
                        padR = dimen(p, "right", -1);
                        padB = dimen(p, "bottom", -1);
                    } else if ("gradient".equals(tag)) {
                        String type = str(p, "type", "linear");
                        if ("radial".equals(type)) gradientType = GradientDrawable.RADIAL_GRADIENT;
                        else if ("sweep".equals(type)) gradientType = GradientDrawable.SWEEP_GRADIENT;
                        else gradientType = GradientDrawable.LINEAR_GRADIENT;
                        Integer start = color(p, "startColor");
                        Integer center = color(p, "centerColor");
                        Integer end = color(p, "endColor");
                        if (start != null && end != null) {
                            gradientColors = center != null
                                    ? new Integer[]{start, center, end}
                                    : new Integer[]{start, end};
                        }
                        angle = intAttr(p, "angle", 0);
                        if (isSet(p, "centerX")) centerX = dimenFloat(p, "centerX", 0f);
                        if (isSet(p, "centerY")) centerY = dimenFloat(p, "centerY", 0f);
                        if (isSet(p, "gradientRadius")) gradientRadius = dimenFloat(p, "gradientRadius", 0f);
                    }
                }
            } catch (Throwable th) {
                return null;
            }

            if (solid != null) d.setColor(solid);
            if (strokeColor != null) {
                d.setStroke(strokeWidth, strokeColor, dashWidth, dashGap);
            }
            if (corners != null) {
                d.setCornerRadii(corners);
            }
            if (sizeW != null || sizeH != null) {
                d.setSize(sizeW == null ? -1 : sizeW, sizeH == null ? -1 : sizeH);
            }
            if (padL >= 0 || padT >= 0 || padR >= 0 || padB >= 0) {
                d.setPadding(Math.max(0, padL), Math.max(0, padT), Math.max(0, padR), Math.max(0, padB));
            }
            if (gradientColors != null) {
                d.setGradientType(gradientType == null ? GradientDrawable.LINEAR_GRADIENT : gradientType);
                d.setColors(toIntArray(gradientColors));
                if (gradientType == null || gradientType == GradientDrawable.LINEAR_GRADIENT) {
                    d.setOrientation(orientationOf(angle));
                } else if (gradientType == GradientDrawable.RADIAL_GRADIENT) {
                    if (centerX != null && centerY != null) d.setGradientCenter(centerX, centerY);
                    if (gradientRadius != null) d.setGradientRadius(gradientRadius);
                }
            }
            return d;
        }

        /** corners:统一 radius 或四个角分别给;都没有则 null(沿用默认直角) */
        private float[] corners(XmlResourceParser p) {
            int r = optionalDimen(p, "radius", -1);
            if (r >= 0) {
                float v = r;
                return new float[]{v, v, v, v, v, v, v, v};
            }
            int tl = optionalDimen(p, "topLeftRadius", 0);
            int tr = optionalDimen(p, "topRightRadius", 0);
            int br = optionalDimen(p, "bottomRightRadius", 0);
            int bl = optionalDimen(p, "bottomLeftRadius", 0);
            if (tl == 0 && tr == 0 && br == 0 && bl == 0) return null;
            return new float[]{tl, tl, tr, tr, br, br, bl, bl};
        }

        private GradientDrawable.Orientation orientationOf(int angle) {
            // 与框架一致:角度按 45° 档映射(只支持 8 个方向,圆形/斜向渐变由框架同样近似)
            int a = ((angle % 360) + 360) % 360;
            switch (a) {
                case 0:
                    return GradientDrawable.Orientation.LEFT_RIGHT;
                case 45:
                    return GradientDrawable.Orientation.BL_TR;
                case 90:
                    return GradientDrawable.Orientation.BOTTOM_TOP;
                case 135:
                    return GradientDrawable.Orientation.BR_TL;
                case 180:
                    return GradientDrawable.Orientation.RIGHT_LEFT;
                case 225:
                    return GradientDrawable.Orientation.TR_BL;
                case 270:
                    return GradientDrawable.Orientation.TOP_BOTTOM;
                case 315:
                    return GradientDrawable.Orientation.TL_BR;
                default:
                    return GradientDrawable.Orientation.LEFT_RIGHT;
            }
        }

        // ── selector ──

        /** @return {@link ColorStateList}(纯色项)或 {@link Drawable}(drawable 项);不认识返回 null */
        private Object buildSelector(XmlResourceParser p) {
            int outer = p.getDepth();
            java.util.List<int[]> states = new java.util.ArrayList<>();
            java.util.List<Integer> colors = new java.util.ArrayList<>();
            java.util.List<Drawable> drawables = new java.util.ArrayList<>();
            boolean colorMode = false;
            boolean mixed = false;

            try {
                while (true) {
                    int event = p.next();
                    if (event == XmlPullParser.END_DOCUMENT) break;
                    if (event == XmlPullParser.END_TAG && p.getDepth() <= outer) break;
                    if (event != XmlPullParser.START_TAG || !"item".equals(p.getName())) continue;
                    int[] state = stateSpec(p);
                    Integer c = hasAttr(p, "color") ? color(p, "color") : null;
                    if (c != null) {
                        if (!drawables.isEmpty()) mixed = true;
                        colorMode = true;
                        states.add(state);
                        colors.add(c);
                        continue;
                    }
                    if (colorMode) mixed = true;
                    Drawable child = buildItemChild(p);
                    if (child == null) return null;
                    states.add(state);
                    drawables.add(child);
                }
            } catch (Throwable th) {
                return null;
            }
            if (mixed) return null;

            if (colorMode) {
                if (colors.isEmpty()) return null;
                int[][] spec = states.toArray(new int[0][]);
                int[] cols = toIntArray(colors);
                return new ColorStateList(spec, cols);
            }
            if (drawables.isEmpty()) return null;
            StateListDrawable sd = new StateListDrawable();
            for (int i = 0; i < drawables.size(); i++) {
                sd.addState(states.get(i), drawables.get(i));
            }
            return sd;
        }

        /** selector 的 item 内容:属性上的 drawable,或内嵌一个 shape/layer-list/... */
        private Drawable buildItemChild(XmlResourceParser p) {
            int drawableRes = p.getAttributeResourceValue(NS, "drawable", 0);
            if (drawableRes != 0) return res.getDrawable(drawableRes);
            int depth = p.getDepth();
            try {
                while (true) {
                    int event = p.next();
                    if (event == XmlPullParser.END_DOCUMENT) return null;
                    if (event == XmlPullParser.END_TAG && p.getDepth() <= depth) return null;
                    if (event == XmlPullParser.START_TAG) {
                        return buildDrawable(p, p.getName());
                    }
                }
            } catch (Throwable th) {
                return null;
            }
        }

        // ── layer-list ──

        private LayerDrawable layerList(XmlResourceParser p) {
            int outer = p.getDepth();
            java.util.List<Drawable> layers = new java.util.ArrayList<>();
            java.util.List<int[]> insets = new java.util.ArrayList<>();
            java.util.List<Integer> gravities = new java.util.ArrayList<>();
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            try {
                while (true) {
                    int event = p.next();
                    if (event == XmlPullParser.END_DOCUMENT) break;
                    if (event == XmlPullParser.END_TAG && p.getDepth() <= outer) break;
                    if (event != XmlPullParser.START_TAG || !"item".equals(p.getName())) continue;
                    int itemDepth = p.getDepth();
                    int drawableRes = p.getAttributeResourceValue(NS, "drawable", 0);
                    int id = p.getAttributeResourceValue(NS, "id", 0);
                    int gravity = attrEnumGravity(p);
                    int t = optionalDimen(p, "top", 0);
                    int l = optionalDimen(p, "left", 0);
                    int r = optionalDimen(p, "right", 0);
                    int b = optionalDimen(p, "bottom", 0);

                    Drawable child = null;
                    if (drawableRes != 0) {
                        child = res.getDrawable(drawableRes);
                    } else {
                        // 内嵌 drawable:继续往下读第一个 START_TAG
                        while (true) {
                            int ev = p.next();
                            if (ev == XmlPullParser.END_DOCUMENT) break;
                            if (ev == XmlPullParser.END_TAG && p.getDepth() <= itemDepth) break;
                            if (ev == XmlPullParser.START_TAG) {
                                child = buildDrawable(p, p.getName());
                                break;
                            }
                        }
                    }
                    if (child == null) continue;
                    layers.add(child);
                    insets.add(new int[]{l, t, r, b});
                    gravities.add(gravity);
                    ids.add(id);
                }
            } catch (Throwable th) {
                return null;
            }
            if (layers.isEmpty()) return null;
            LayerDrawable ld = new LayerDrawable(layers.toArray(new Drawable[0]));
            for (int i = 0; i < layers.size(); i++) {
                int[] in = insets.get(i);
                if (in[0] != 0 || in[1] != 0 || in[2] != 0 || in[3] != 0) {
                    ld.setLayerInset(i, in[0], in[1], in[2], in[3]);
                }
                if (gravities.get(i) != -1) {
                    try {
                        ld.setLayerGravity(i, gravities.get(i));
                    } catch (Throwable ignored) {
                    }
                }
                if (ids.get(i) != 0) {
                    try {
                        ld.setId(i, ids.get(i));
                    } catch (Throwable ignored) {
                    }
                }
            }
            return ld;
        }

        // ── clip / inset / ripple ──

        private ClipDrawable clip(XmlResourceParser p) {
            int orientation = "vertical".equals(str(p, "clipOrientation", "horizontal"))
                    ? ClipDrawable.VERTICAL : ClipDrawable.HORIZONTAL;
            int depth = p.getDepth();
            try {
                while (true) {
                    int event = p.next();
                    if (event == XmlPullParser.END_DOCUMENT) return null;
                    if (event == XmlPullParser.END_TAG && p.getDepth() <= depth) return null;
                    if (event == XmlPullParser.START_TAG) {
                        Drawable child = buildDrawable(p, p.getName());
                        return child == null ? null : new ClipDrawable(child, attrEnumGravity(p), orientation);
                    }
                }
            } catch (Throwable th) {
                return null;
            }
        }

        private InsetDrawable inset(XmlResourceParser p) {
            Drawable child = null;
            int drawableRes = p.getAttributeResourceValue(NS, "drawable", 0);
            if (drawableRes != 0) {
                child = res.getDrawable(drawableRes);
            } else {
                int depth = p.getDepth();
                try {
                    while (true) {
                        int event = p.next();
                        if (event == XmlPullParser.END_DOCUMENT) break;
                        if (event == XmlPullParser.END_TAG && p.getDepth() <= depth) break;
                        if (event == XmlPullParser.START_TAG) {
                            child = buildDrawable(p, p.getName());
                            break;
                        }
                    }
                } catch (Throwable th) {
                    return null;
                }
            }
            if (child == null) return null;
            int all = optionalDimen(p, "inset", -1);
            if (all >= 0) return new InsetDrawable(child, all);
            return new InsetDrawable(child,
                    optionalDimen(p, "insetLeft", 0), optionalDimen(p, "insetTop", 0),
                    optionalDimen(p, "insetRight", 0), optionalDimen(p, "insetBottom", 0));
        }

        private RippleDrawable ripple(XmlResourceParser p) {
            Integer c = color(p, "color");
            ColorStateList csl = c == null ? null : ColorStateList.valueOf(c);
            Drawable content = null;
            int depth = p.getDepth();
            try {
                while (true) {
                    int event = p.next();
                    if (event == XmlPullParser.END_DOCUMENT) break;
                    if (event == XmlPullParser.END_TAG && p.getDepth() <= depth) break;
                    if (event == XmlPullParser.START_TAG) {
                        content = buildDrawable(p, p.getName());
                        break;
                    }
                }
            } catch (Throwable th) {
                return null;
            }
            return new RippleDrawable(csl == null ? ColorStateList.valueOf(0x33000000) : csl, content, null);
        }

        // ── 属性读取 ──

        /** 读取颜色属性:随主题走就用运行时的值(并标记 usedThemed),否则取系统的 */
        private Integer color(XmlResourceParser p, String name) {
            int resId = p.getAttributeResourceValue(NS, name, 0);
            if (resId != 0) {
                String key = ThemeColorAliases.paletteNameOf(resId);
                if (key != null) {
                    ThemePalette palette = ThemeRuntime.palette();
                    if (palette != null) {
                        usedThemed = true;
                        return palette.get(key);
                    }
                }
                try {
                    return res.getColor(resId);
                } catch (Throwable th) {
                    return null;
                }
            }
            String raw = p.getAttributeValue(NS, name);
            if (raw == null) return null;
            try {
                return android.graphics.Color.parseColor(raw);
            } catch (Throwable th) {
                return null;
            }
        }

        private boolean hasAttr(XmlResourceParser p, String name) {
            return p.getAttributeValue(NS, name) != null
                    || p.getAttributeResourceValue(NS, name, 0) != 0;
        }

        private boolean isSet(XmlResourceParser p, String name) {
            return hasAttr(p, name);
        }

        private String str(XmlResourceParser p, String name, String def) {
            String v = p.getAttributeValue(NS, name);
            return v == null ? def : v;
        }

        private int intAttr(XmlResourceParser p, String name, int def) {
            String v = p.getAttributeValue(NS, name);
            if (v == null) return def;
            try {
                return Integer.parseInt(v.trim());
            } catch (Throwable th) {
                return def;
            }
        }

        private int dimen(XmlResourceParser p, String name, int def) {
            return optionalDimen(p, name, def);
        }

        /** 该尺寸属性没写时返回 null(与"写了 0"区分开:size 写了 0 是有意义的) */
        private Integer optionalDimenOrNull(XmlResourceParser p, String name) {
            if (!hasAttr(p, name)) return null;
            return optionalDimen(p, name, 0);
        }

        /** 尺寸属性:优先按资源引用解析,其次按字面量("12dp"/"2mm")换算 */
        private int optionalDimen(XmlResourceParser p, String name, int def) {
            int resId = p.getAttributeResourceValue(NS, name, 0);
            if (resId != 0) {
                try {
                    return res.getDimensionPixelSize(resId);
                } catch (Throwable th) {
                    return def;
                }
            }
            String raw = p.getAttributeValue(NS, name);
            if (raw == null) return def;
            return parseDimension(raw, def);
        }

        private float dimenFloat(XmlResourceParser p, String name, float def) {
            int v = optionalDimen(p, name, Integer.MIN_VALUE);
            return v == Integer.MIN_VALUE ? def : v;
        }

        private int attrEnumGravity(XmlResourceParser p) {
            String v = p.getAttributeValue(NS, "gravity");
            if (v == null) {
                int resId = p.getAttributeResourceValue(NS, "gravity", 0);
                if (resId == 0) return -1;
                try {
                    return res.getInteger(resId);
                } catch (Throwable th) {
                    return -1;
                }
            }
            // 二进制 XML 里枚举值已还原成名字(如 "center"、"left|top")
            int g = 0;
            for (String part : v.split("\\|")) {
                g |= gravityOf(part.trim());
            }
            return g == 0 ? -1 : g;
        }
    }

    // ------------------------------------------------------------------
    // 静态小工具
    // ------------------------------------------------------------------

    /** 一个 item 的 state 规格:android:state_xxx="true/false" → ±attrId(与框架同口径) */
    private static int[] stateSpec(XmlResourceParser p) {
        java.util.List<Integer> spec = new java.util.ArrayList<>();
        int count = p.getAttributeCount();
        for (int i = 0; i < count; i++) {
            int nameRes = p.getAttributeNameResource(i);
            if (nameRes == 0) continue;
            String name = p.getAttributeName(i);
            if (name == null || !name.startsWith("state_")) continue;
            String value = p.getAttributeValue(i);
            boolean on = "true".equalsIgnoreCase(value)
                    || "1".equals(value)
                    || (value == null);
            spec.add(on ? nameRes : -nameRes);
        }
        int[] out = new int[spec.size()];
        for (int i = 0; i < out.length; i++) out[i] = spec.get(i);
        return out;
    }

    private static int gravityOf(String name) {
        switch (name) {
            case "center":
                return android.view.Gravity.CENTER;
            case "center_horizontal":
                return android.view.Gravity.CENTER_HORIZONTAL;
            case "center_vertical":
                return android.view.Gravity.CENTER_VERTICAL;
            case "left":
            case "start":
                return android.view.Gravity.START;
            case "right":
            case "end":
                return android.view.Gravity.END;
            case "top":
                return android.view.Gravity.TOP;
            case "bottom":
                return android.view.Gravity.BOTTOM;
            case "fill":
                return android.view.Gravity.FILL;
            case "fill_horizontal":
                return android.view.Gravity.FILL_HORIZONTAL;
            case "fill_vertical":
                return android.view.Gravity.FILL_VERTICAL;
            case "clip_horizontal":
                return android.view.Gravity.CLIP_HORIZONTAL;
            case "clip_vertical":
                return android.view.Gravity.CLIP_VERTICAL;
            default:
                return 0;
        }
    }

    /** "12dp"/"2mm"/"8px"/"14sp" → 像素(与 TypedValue 同口径) */
    static int parseDimension(String raw, int def) {
        if (raw == null) return def;
        String s = raw.trim();
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-')) i++;
        if (i == 0) return def;
        float value;
        try {
            value = Float.parseFloat(s.substring(0, i));
        } catch (Throwable th) {
            return def;
        }
        String unit = s.substring(i).trim().toLowerCase(java.util.Locale.ROOT);
        android.util.DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        float px;
        switch (unit) {
            case "":
            case "px":
                px = value;
                break;
            case "dp":
            case "dip":
                px = value * dm.density;
                break;
            case "sp":
                px = value * dm.scaledDensity;
                break;
            case "pt":
                px = value * dm.xdpi * (1f / 72f);
                break;
            case "in":
                px = value * dm.xdpi;
                break;
            case "mm":
                px = value * dm.xdpi * (1f / 25.4f);
                break;
            default:
                px = value;
                break;
        }
        return (int) (px + 0.5f);
    }

    private static int[] toIntArray(java.util.List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static int[] toIntArray(Integer[] arr) {
        int[] out = new int[arr.length];
        for (int i = 0; i < out.length; i++) out[i] = arr[i];
        return out;
    }
}
