package com.github.tvbox.osc.ui.startup

/**
 * 桌面图标长按菜单(`res/xml/shortcuts.xml`)的落地目标。
 *
 * 快捷方式不直接指向搜索页/历史页：冷启动时订阅配置与首页数据源都还没装配完，
 * 直接拉起会读到空源、空列表。这里只定义"要做什么"，由 `SplashActivity` 透传、
 * `MainActivity` 在开屏退场且首页首帧就绪后再执行(与通知详情同一套排队口径)。
 *
 * action 常量只用于 `shortcuts.xml` 与 `AndroidManifest` 的 intent-filter 配对，
 * 与实际 applicationId(.debug 等变体)无关，因此可安全写死。
 */
enum class ShortcutTarget(val action: String) {
    /** 全部数据源聚合搜索 */
    SEARCH("com.github.tvbox.osc.action.SHORTCUT_SEARCH"),

    /** 观看历史(继续观看) */
    CONTINUE_WATCHING("com.github.tvbox.osc.action.SHORTCUT_HISTORY");

    companion object {
        /** SplashActivity 转发给主页的目标动作(用 action 字符串传递，避免多进程/重建时枚举序号错位) */
        const val EXTRA_TARGET = "com.github.tvbox.osc.SHORTCUT_TARGET"

        fun fromAction(action: String?): ShortcutTarget? =
            entries.firstOrNull { it.action == action }
    }
}
