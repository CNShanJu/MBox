package com.github.tvbox.osc.ui.kit

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 给页面根布局让出底部导航栏/手势条。
 *
 * BaseActivity 的 ImmersionBar 只处理了状态栏（`titleBar(...)`），底部不会自动留白：
 * 内容撑满整屏的页面（列表贴底、底部按钮）必须自己补，否则最后一行会被导航栏盖住。
 */
internal fun View.applyNavigationBarBottomPadding() {
    val basePadding = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        // 沉浸态下 navigationBars 常为 0，但手势条仍在，取两者较大值。
        val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
        view.updatePadding(bottom = basePadding + maxOf(bars, gestures))
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
