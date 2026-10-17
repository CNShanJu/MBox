package cat.ereza.customactivityoncrash

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 崩溃页配色契约:让"出现意外错误"这页跟随用户当前生效的主题。
 *
 * <p><b>为什么不能直接读主题系统</b>:崩溃页跑在独立进程 `:error_activity`,而 `App.onCreate`
 * 对它是**刻意最小化启动**的(不装 ThemeRuntime / Room / 爬虫 / QuickJS;见 App 里的注释 ——
 * 该进程自身一崩就是静默消失)。为配色把整套业务初始化搬进去是不划算的。
 *
 * <p>所以走一条极轻的通道:**主进程**在主题生效(启动、系统翻明暗、切主题后重启)时,把当前
 * 生效调色板的几个语义色写进本模块约定的独立 SharedPreferences;**错误页进程**只做一次
 * `getSharedPreferences` 读取(不触发任何业务初始化),据此给背景/文案/按钮上色。
 *
 * <p>键名与文件名常量定义在**这里(thirdparty)**,写方在 app 侧 —— 依赖方向 app → thirdparty,
 * 两侧引用同一份常量,不会各自写一份字符串而漂移。没有投影值(首次安装后未启动过主进程、
 * 或投影失败)时本类什么都不做,错误页保持主题资源画出来的默认外观。
 *
 * <p>注意:所有方法都吞异常 —— 这页是崩溃后的最后一道界面,任何着色问题都不能让它再崩一次。
 */
object CrashPageTheme {

    /** 投影文件(独立于应用的配置存储,不参与备份/还原的键集合) */
    const val PREFS_NAME = "mbox_crash_page_theme"

    const val KEY_BG_BODY = "bg_body"
    const val KEY_TEXT_MAIN = "text_main"
    const val KEY_TEXT_ACCENT = "text_accent"
    const val KEY_BTN_BG = "btn_confirm_bg"
    const val KEY_BTN_TEXT = "btn_confirm_text"

    /** 未投影的语义色:ARGB 0 不是有效颜色,据此跳过该项(保持主题默认) */
    private const val UNSET = 0
    @Volatile private var publishedColors: IntArray? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 写方(app 侧)调用:把当前生效主题的语义色投影给崩溃页。
     *
     * <p>幂等:值没变时不产生磁盘写,所以可以放心挂在"每个 Activity 都会走到"的兜底点。
     * 传 0 表示该项不覆盖。
     */
    @JvmStatic
    fun write(
        context: Context?,
        bgBody: Int,
        textMain: Int,
        textAccent: Int,
        btnBg: Int,
        btnText: Int
    ) {
        if (context == null) return
        try {
            publishedColors = intArrayOf(bgBody, textMain, textAccent, btnBg, btnText)
            val sp = prefs(context)
            val unchanged = sp.getInt(KEY_BG_BODY, UNSET) == bgBody &&
                sp.getInt(KEY_TEXT_MAIN, UNSET) == textMain &&
                sp.getInt(KEY_TEXT_ACCENT, UNSET) == textAccent &&
                sp.getInt(KEY_BTN_BG, UNSET) == btnBg &&
                sp.getInt(KEY_BTN_TEXT, UNSET) == btnText
            if (unchanged) return
            sp.edit()
                .putInt(KEY_BG_BODY, bgBody)
                .putInt(KEY_TEXT_MAIN, textMain)
                .putInt(KEY_TEXT_ACCENT, textAccent)
                .putInt(KEY_BTN_BG, btnBg)
                .putInt(KEY_BTN_TEXT, btnText)
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    private const val EXTRA_COLORS = "cat.ereza.customactivityoncrash.THEME_COLORS"

    @JvmStatic
    fun attachToIntent(intent: Intent) {
        try {
            publishedColors?.let { intent.putExtra(EXTRA_COLORS, it.copyOf()) }
        } catch (_: Throwable) {
        }
    }

    private fun read(context: Context, intent: Intent?): IntArray? {
        return try {
            // 错误进程可能复用，不能依赖该进程缓存的 SharedPreferences 刷新。
            val projected = intent?.getIntArrayExtra(EXTRA_COLORS)
            if (projected?.size == 5) return projected.copyOf()
            val sp = prefs(context)
            if (!sp.contains(KEY_BG_BODY)) return null
            intArrayOf(
                sp.getInt(KEY_BG_BODY, UNSET),
                sp.getInt(KEY_TEXT_MAIN, UNSET),
                sp.getInt(KEY_TEXT_ACCENT, UNSET),
                sp.getInt(KEY_BTN_BG, UNSET),
                sp.getInt(KEY_BTN_TEXT, UNSET)
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 错误页进程调用:按投影值给页面着色。任一项为 0 时该项保持主题默认;
     * 没有投影值或读取异常时整页保持主题默认(与未做本能力时完全一致)。
     *
     * @param root 页面根布局(底色)
     * @param message 说明文案(主文字色)
     * @param primary 主按钮("重新启动"这种;底色 + 文字色,取确认按钮那组语义色)
     * @param plain 次要按钮("详细错误信息"这种;文字色取主色)
     */
    @JvmStatic
    fun apply(
        context: Context?,
        intent: Intent?,
        root: View?,
        message: TextView?,
        primary: Button?,
        plain: Button?
    ) {
        if (context == null) return
        try {
            val colors = read(context, intent) ?: return
            val bg = colors[0]
            val text = colors[1]
            val accent = colors[2]
            val btnBg = colors[3]
            val btnText = colors[4]
            if (bg != UNSET) root?.setBackgroundColor(bg)
            if (text != UNSET) message?.setTextColor(text)
            if (accent != UNSET) plain?.setTextColor(accent)
            if (primary != null) {
                if (btnBg != UNSET) primary.backgroundTintList = ColorStateList.valueOf(btnBg)
                if (btnText != UNSET) primary.setTextColor(btnText)
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 错误页进程调用:给"详细错误信息"弹窗按投影值上色,使其与崩溃页主体一致。
     * 弹窗自带主题面板底,故面板底取 `bg_body`、正文/标题取 `text_main`、按钮文字取 `text_accent`
     * (与页面上"详细错误信息"这类次要入口同色)。没有投影值或读取异常时保持系统默认外观。
     */
    @JvmStatic
    fun applyToDialog(context: Context?, intent: Intent?, dialog: AlertDialog?) {
        if (context == null || dialog == null) return
        try {
            val colors = read(context, intent) ?: return
            val bg = colors[0]
            val text = colors[1]
            val accent = colors[2]

            if (bg != UNSET) {
                val radius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
                )
                val panel = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(bg)
                }
                dialog.window?.setBackgroundDrawable(panel)
            }
            if (text != UNSET) {
                dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(text)
                dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(text)
            }
            if (accent != UNSET) {
                tintButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), accent)
                tintButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), accent)
                tintButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), accent)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun tintButton(button: Button?, color: Int) {
        if (button == null) return
        try {
            button.setTextColor(color)
        } catch (ignored: Throwable) {
        }
    }
}
