package com.github.tvbox.osc.theme;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;

import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.bean.theme.ThemeColorPalette;
import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.bean.theme.ThemeShapePalette;
import com.github.tvbox.osc.bean.theme.ThemeSnapshot;
import com.github.tvbox.osc.bean.theme.ThemeType;
import com.github.tvbox.osc.storage.theme.ThemeStore;

/**
 * 运行时换肤的<b>装配点与当前调色板持有者</b>。
 *
 * <p>它回答一个问题:"这次启动,界面该按哪套颜色和形状画?"。颜色、圆角、描边、主题类型、
 * 自定义标记和指纹始终封装在同一份 {@link ThemeSnapshot} 中,切换时只做一次原子替换:
 * <ul>
 *   <li><b>内置亮/暗主题</b>:配方背景经 {@link ThemeDrawableFactory} 渲染,首帧 XML 也由同一份配方生成;</li>
 *   <li><b>自定义主题</b>:快照带上自定义颜色与形状,于是
 *       {@link ThemeResources}(代码取色)、{@link ThemeInflaterFactory}(布局属性)、
 *       {@link ThemeDrawables}(drawable/图标)三条通道一起把它铺到界面上。</li>
 * </ul>
 * {@link #palette()} 只保留给尚未迁移的旧颜色包装层:内置主题仍返回 {@code null},不代表统一配方渲染停用。
 *
 * <p>"这次启动"是关键字:主题改动一律<b>不实时生效</b>,而是写配置 + 重启 App
 * (与既有的浅色/深色切换同一条链路)。所以这里在进程启动时解析一次,之后全程只读 ——
 * 没有热切换带来的"半个界面新色半个界面旧色"问题。
 *
 * <p><b>唯一的例外是「跟随系统」</b>:手机自己从亮切到暗(或反之)时进程不会重启,而生效主题
 * 是按系统明暗解析出来的("该类型的默认主题"),于是快照会与真实生效的那套脱节 ——
 * 最坏的情况是<b>用户只配了暗色默认主题</b>:进程在亮色时启动,快照是"不介入"(palette=null),
 * 切到暗色后换肤层仍然不介入,弹窗/开关一类全画成内置暗色,和配置的默认暗色对不上。
 * 所以 {@link #refresh()} 会在配置变化时重新解析一次,亮暗/主题真变了才换快照并清派生缓存。
 *
 * <p>解析不出/异常一律退化为"不介入":宁可显示内置配色,也不能让换肤层把界面搞坏。
 */
public final class ThemeRuntime {

    private static volatile ThemeSnapshot snapshot;
    private static volatile boolean installed;
    /**
     * 快照对应的生效主题指纹(亮暗类型 + 主题 id + 内容哈希,见 {@link #fingerprint})。
     * <p>只看 id 不够:在编辑页改了<b>当前生效主题</b>的颜色后 id 不变,但配色已经变了。
     */
    private static volatile String snapshotKey = "";

    private ThemeRuntime() {
    }

    /**
     * 解析并装上当前的调色板快照。
     *
     * <p>调用点有三处,都是"生效"这一刻:进程启动({@code App.initParams})、主题改动提交
     * ({@code Utils.initTheme()})、系统明暗翻转({@code App.onConfigurationChanged} /
     * {@code BaseActivity.attachBaseContext},见 {@link #refresh()})。
     */
    public static void install() {
        ThemeType t = ThemeType.BRIGHT;
        ThemePalette p = null;
        ThemeShapePalette shapes = ThemeShapePalette.defaults();
        ThemeSnapshot nextSnapshot = null;
        String key = "";
        String customId = "";
        String trouble = "";
        try {
            t = ThemeStore.activeType();
            customId = com.github.tvbox.osc.config.SystemConfig.getThemeCustomId();
            ThemeDef def = ThemeStore.resolveActive();
            if (def != null) {
                p = ThemeStore.paletteOf(def);
                shapes = ThemeStore.shapePaletteOf(def);
                // 自检:别名表里每个概念名都要能在调色板里取到,否则说明两侧对不上(改了 colors.xml 忘了改表),
                // 这种情况不介入比"换一半颜色"更好定位
                if (!ThemeColorAliases.namesResolvable(p)) {
                    p = null;
                    trouble = "调色板缺少别名表里的概念名";
                }
            } else if (!customId.isEmpty()) {
                // 选中了自定义主题却解析不出来 = 主题文件不在了(被删/写坏),这也是"改了没效果"的一种原因
                trouble = "选中的自定义主题[" + customId + "]找不到(主题文件缺失?)";
            }
            boolean useCustom = def != null && p != null;
            ThemePalette completeColors = useCustom ? p : ThemeStore.builtinPalette(t);
            if (!useCustom) shapes = ThemeStore.builtinShapes(t);
            key = fingerprint(t, def);
            nextSnapshot = new ThemeSnapshot(completeColors, shapes, t, useCustom, key);
        } catch (Throwable th) {
            p = null;
            trouble = "解析异常:" + th.getClass().getSimpleName()
                    + (th.getMessage() == null ? "" : (" " + th.getMessage()));
            try {
                ThemePalette fallbackColors = ThemeStore.builtinPalette(t);
                shapes = ThemeStore.builtinShapes(t);
                key = fingerprint(t, null);
                nextSnapshot = new ThemeSnapshot(fallbackColors, shapes, t, false, key);
            } catch (Throwable ignored) {
                nextSnapshot = null;
            }
        }
        // 颜色、圆角、类型与身份只在这里做一次 volatile 引用替换，读方永远看见完整快照。
        snapshot = nextSnapshot;
        snapshotKey = key;
        installed = true;
        // 让"换了主题却没看出变化"这类问题能在运行日志里一眼定位:
        // 是"没生效"(解析出的还是内置/根本没选中自定义主题/解析失败),还是"生效了但某处没跟着走"
        try {
            String msg = "主题: 生效[" + (p == null ? "内置" : "自定义") + "] " + key;
            if (p != null) {
                msg += ";换肤层已介入,bg_float=" + Integer.toHexString(p.get("bg_float"))
                        + " bg_surface=" + Integer.toHexString(p.get("bg_surface"));
            } else {
                msg += customId.isEmpty() ? "(未选中自定义主题)" : ";原因=" + trouble;
            }
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 重新解析一次,只在"生效主题真的变了"时才换快照并清派生缓存。
     *
     * <p><b>为什么必须有这一步</b>:"切主题后重启应用"走的是带标志重载主页({@code jumpActivity(MainActivity)}),
     * <b>进程并没有重启</b>;而换肤层的答案存在这个快照里。不刷新的话,换完主题界面仍按进程启动那一刻的
     * 调色板画 —— 最典型的是「跟随系统」下只配了暗色默认主题:进程在亮色时启动,快照是"不介入",
     * 手机翻到暗色后换肤层依旧不介入,弹窗/开关一类全画成内置暗色,和用户配的默认暗色对不上。
     */
    public static void refresh() {
        try {
            ThemeDef def = ThemeStore.resolveActive();
            String key = fingerprint(ThemeStore.activeType(), def);
            if (installed && key.equals(snapshotKey)) return;   // 没变:不白清缓存
            install();
            // 派生缓存按 resId 索引、内容绑当前主题:换了必须清,否则按钮/选择器还是旧色
            ThemeDrawables.clearCache();
        } catch (Throwable ignored) {
            // 解析失败保持原快照:宁可短暂用旧配色,也不能把换肤层拆了导致界面黑掉
        }
    }

    /** 快照指纹:亮暗类型 + 主题 id + 颜色与形状内容哈希(内置时 id 为空串) */
    private static String fingerprint(ThemeType t, ThemeDef def) {
        String id = def == null ? "" : def.getId() + "@" + def.colors().hashCode()
                + "@" + def.radii().hashCode() + "@" + def.strokes().hashCode();
        return (t == null ? "?" : t.name()) + "|" + id;
    }

    /** 当前换肤调色板;{@code null} = 不用自定义主题(不介入) */
    public static ThemePalette palette() {
        ThemeSnapshot current = snapshot;
        if (current == null || !current.custom || current.colors == null) return null;
        return current.colors instanceof ThemePalette
                ? (ThemePalette) current.colors : new ThemePalette(current.colors.asMap());
    }

    /** 完整颜色快照;内置与自定义主题都非空(尚未 install 时除外)。 */
    public static ThemeColorPalette colorPalette() {
        ThemeSnapshot current = snapshot;
        return current == null ? null : current.colors;
    }

    public static ThemeShapePalette shapePalette() {
        ThemeSnapshot current = snapshot;
        return current == null ? ThemeShapePalette.defaults() : current.shapes;
    }

    public static ThemeSnapshot snapshot() {
        return snapshot;
    }

    /** 当前生效的亮暗类型(夜间模式与弹窗深浅的依据) */
    public static ThemeType type() {
        ThemeSnapshot current = snapshot;
        return current == null ? ThemeType.BRIGHT : current.type;
    }

    /** 换肤层是否在介入(只影响"要不要多做一层包装",不影响正确性) */
    public static boolean active() {
        ThemeSnapshot current = snapshot;
        return current != null && current.custom;
    }

    /**
     * 界面明暗是否"由系统说了算"(模式=跟随系统,且没选自定义主题)。
     *
     * <p>只有这种模式下,手机自己翻明暗才该带着界面一起变:
     * <ul>
     *   <li>显式选了浅色/深色 → 界面固定那套,系统怎么翻都跟本 App 无关;</li>
     *   <li>选了自定义主题 → 它的类型就是答案(见 {@code Utils.initTheme} 把夜间模式强制成它的类型)。</li>
     * </ul>
     *
     * <p>调用方是 {@code BaseActivity}:内置主题的颜色是 inflate 那一刻从
     * {@code values/values-night} 取回来的资源,光换快照不够 —— 声明了 {@code uiMode} 的页面
     * (主页/直播/详情)系统不会重建,不自己重建就一直是翻明暗之前那套(观感="跟随系统没生效")。
     */
    public static boolean followsSystem() {
        try {
            ThemeStore.Selection s = ThemeStore.selection();
            return s.customId.isEmpty() && s.mode == ThemeStore.Selection.MODE_FOLLOW_SYSTEM;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 是否已经装配过(测试/调试用;没装配时一律按"不介入"处理) */
    public static boolean installed() {
        return installed;
    }

    /** 切主题后清派生缓存(下一次启动才会重新 install,这里只在同进程重装时用) */
    public static void reset() {
        ThemeDrawables.clearCache();
        snapshot = null;
        snapshotKey = "";
        installed = false;
    }

    /**
     * 把主题铺到窗口这一层:窗口底色用主题的 {@code bg_body}。
     *
     * <p>为什么必须做:所有页面布局的根节点都是透明的,内容实际浮在窗口底色上 ——
     * 底色不对,整个页面就会透出编译期的旧色(浅色主题下最明显:faf8ff 的底 vs 用户设的深底)。
     *
     * <p><b>不用自定义主题时这里直接返回,绝不碰窗口背景</b>:窗口背景是主题
     * {@code android:windowBackground} 给的(就是 {@code bg_body}),
     * 主动 `setBackgroundDrawable(null)` 会把它清掉(页面根节点全透明,清了就是黑屏/透出下层),
     * 这正是"内置亮/暗模式下不介入"的含义 —— 不介入就要彻底不碰。
     */
    public static void applyTo(Activity activity) {
        if (activity == null) return;
        ThemeColorPalette p = colorPalette();
        ThemeSnapshot current = snapshot;
        if (current == null || !current.custom) return; // 内置主题窗口底走原生资源
        if (p == null) return; // 不介入
        Window window = activity.getWindow();
        if (window == null) return;
        try {
            window.setBackgroundDrawable(new ColorDrawable(p.get("bg_body")));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 布局注入器:给 Activity 的 LayoutInflater 装上"按主题改属性色"的工厂。
     * <p>幂等;同一个 inflater 只装一次(Activity 重建会拿到新的 inflater)。
     */
    public static void installInflaterFactory(Activity activity) {
        if (activity == null) return;
        try {
            ThemeInflaterFactory.install(activity);
            // **应用上下文的 inflater 也装一份**:视图是由"inflate 它的那份 inflater"决定的,
            // 有些适配器/第三方弹窗用 LayoutInflater.from(appContext) 造视图 —— 那条路上的视图
            // 原来完全吃不到主题(现象:标题栏这种代码取色的变了,而某些布局属性的底仍是内置浅色面,
            // 用户口径:"二级页标题栏都变了,我的页卡片和底部导航栏却没变")。
            android.content.Context app = activity.getApplicationContext();
            if (app != null && app != activity) {
                ThemeInflaterFactory.install(android.view.LayoutInflater.from(app));
            }
        } catch (Throwable ignored) {
        }
    }
}
