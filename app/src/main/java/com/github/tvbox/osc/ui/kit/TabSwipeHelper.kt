package com.github.tvbox.osc.ui.kit

import android.content.Context
import android.view.MotionEvent

/**
 * 内容区左右滑动切 tab 的通用挂件(2026-10-01)。
 *
 * 用户口径:"订阅管理和下载那块,tab 为啥没法从底下那些区域左右滑动切 tab" ——
 * 之前只有点 tab 能切页,内容区(列表/空态)上左右滑没有任何反应。
 *
 * **为什么挂在页面的 dispatchTouchEvent 上,而不是给列表 setOnTouchListener**:
 * 列表条目自己也消费触摸,ViewGroup 一旦把事件交给子视图处理,父容器(以及列表自己)的
 * OnTouchListener 就再也不会被调用 —— 挂在列表上只有"点在条目之间的空白"才有反应。
 * 而 `Activity.dispatchTouchEvent` 是整棵视图树**最外层**的入口,任何子视图都藏不住事件,
 * 且这里只观察、不消费(始终 `return super.dispatchTouchEvent(ev)`),
 * 所以列表的纵向滚动、条目点击、左滑操作区、长按多选全都照旧。
 *
 * 用法(页面里):
 * ```
 * private val swipe = TabSwipeHelper.tracker(this) { dir ->
 *     if (dir < 0) switchTab(NEXT) else switchTab(PREV)   // 左滑=下一个 tab,右滑=上一个
 * }
 * override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
 *     swipe.onTouch(ev)
 *     return super.dispatchTouchEvent(ev)
 * }
 * ```
 */
object TabSwipeHelper {

    /** 触发所需的最小横向位移(dp) */
    private const val MIN_DP = 56f

    /** 横向位移要达到纵向的多少倍才算"左右滑"(避免斜着滚列表被误判) */
    private const val RATIO = 1.6f

    /** 切页方向回调:-1 = 左滑(下一个 tab);1 = 右滑(上一个 tab)。用 fun interface,Java 侧可直接写 lambda */
    fun interface OnDir {
        fun onDir(dir: Int)
    }

    /** 一个页面持有一个:把 [MotionEvent] 喂进来,命中就回调 */
    class Tracker internal constructor(private val minPx: Float, private val cb: OnDir) {
        private var downX = 0f
        private var downY = 0f

        fun onTouch(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dir = direction(event.x - downX, event.y - downY, minPx)
                    if (dir != 0) cb.onDir(dir)
                }
            }
        }
    }

    /** 按屏幕密度算好阈值建一个 [Tracker](用 [context] 的 density) */
    @JvmStatic
    fun tracker(context: Context, cb: OnDir): Tracker =
        Tracker(MIN_DP * context.resources.displayMetrics.density, cb)

    /**
     * 位移 → 切页方向(**纯函数,带 JVM 单测** [com.github.tvbox.osc.ui.kit.TabSwipeHelperTest])。
     *
     * @return -1 = 左滑(下一个 tab);1 = 右滑(上一个 tab);0 = 不算滑动,别切
     */
    @JvmStatic
    fun direction(dx: Float, dy: Float, minPx: Float): Int {
        if (Math.abs(dx) < minPx) return 0
        if (Math.abs(dx) < Math.abs(dy) * RATIO) return 0   // 斜着/竖着滑:让列表自己滚
        return if (dx < 0) -1 else 1
    }
}
