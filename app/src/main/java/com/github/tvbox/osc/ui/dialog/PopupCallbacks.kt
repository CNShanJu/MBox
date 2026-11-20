package com.github.tvbox.osc.ui.dialog

import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.XPopupCallback

/**
 * XPopupCallback 的"空实现 + 单个动作"工厂。
 *
 * XPopupCallback 有九个方法且绝大多数场景只用到一个(显示后填数据、关闭后清标记),
 * 每处手写匿名类都是噪音。需要多个动作时用 [onShowAndDismiss]。
 */
object PopupCallbacks {

    @JvmStatic
    fun onShow(action: Runnable): XPopupCallback = Callback(onShowAction = action)

    @JvmStatic
    fun onDismiss(action: Runnable): XPopupCallback = Callback(onDismissAction = action)

    @JvmStatic
    fun onShowAndDismiss(onShow: Runnable?, onDismiss: Runnable?): XPopupCallback =
        Callback(onShow, onDismiss)

    private class Callback(
        private val onShowAction: Runnable? = null,
        private val onDismissAction: Runnable? = null
    ) : XPopupCallback {

        override fun onCreated(popupView: BasePopupView?) {}

        override fun beforeShow(popupView: BasePopupView?) {}

        override fun onShow(popupView: BasePopupView?) {
            onShowAction?.run()
        }

        override fun onDismiss(popupView: BasePopupView?) {
            onDismissAction?.run()
        }

        override fun beforeDismiss(popupView: BasePopupView?) {}

        override fun onBackPressed(popupView: BasePopupView?): Boolean = false

        override fun onKeyBoardStateChanged(popupView: BasePopupView?, height: Int) {}

        override fun onDrag(
            popupView: BasePopupView?,
            value: Int,
            fraction: Float,
            isScrollShadow: Boolean
        ) {
        }

        override fun onClickOutside(popupView: BasePopupView?) {}
    }
}
