package com.github.tvbox.osc.theme

import android.content.Context
import cat.ereza.customactivityoncrash.CrashPageTheme

/**
 * 把"这次启动生效的主题"投影给崩溃页(见 [CrashPageTheme])。
 *
 * <p>崩溃页跑在独立进程 `:error_activity`,那边 `App.onCreate` 是刻意最小化启动的
 * (不装 ThemeRuntime / Room / 爬虫),所以它的颜色不能靠自己去解析主题 —— 由这里(主进程,
 * 主题快照的持有方)把当前生效调色板的几个语义色写进约定的独立 SharedPreferences,
 * 崩溃页只读一次即可上色。
 *
 * <p>**为什么投影"生效主题"而不是"亮暗"**:用户选「暗色」而系统是亮色时,崩溃页按系统画就是白的
 * (这正是"页面没走主题"的来源);选自定义主题时更是完全对不上。投影当前生效调色板的语义色
 * (内置亮/暗主题也有完整调色板,见 ThemeRuntime#runtimePalette)才能两种情形都对得上。
 *
 * <p>调用点是"主题可能变化"的每一处:进程启动(App.initParams)与运行时刷新兜底
 * (BaseActivity.attachBaseContext 里的 ThemeRuntime.refresh)。写入是幂等的(值没变不落盘),
 * 所以挂在这些高频位置没有代价。
 */
object CrashPageThemeSync {

    /** 幂等投影;取不到调色板(尚未装配)时保持上次投影不动。 */
    @JvmStatic
    fun publish(context: Context?) {
        if (context == null) return
        try {
            val colors = ThemeRuntime.colorPalette() ?: return
            CrashPageTheme.write(
                context.applicationContext,
                colors.get("bg_body", 0),
                colors.get("text_main", 0),
                colors.get("text_accent", 0),
                colors.get("btn_confirm_bg", 0),
                colors.get("btn_confirm_text", 0)
            )
        } catch (ignored: Throwable) {
            // 投影失败不该影响任何主流程:崩溃页退化为按主题资源显示
        }
    }
}
