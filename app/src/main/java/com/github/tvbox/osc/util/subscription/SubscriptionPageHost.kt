package com.github.tvbox.osc.util.subscription

import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import java.io.File

/**
 * 订阅页协调器对页面的窄依赖集合(由 SubscriptionActivity 实现):
 * 协调器只做流程与数据逻辑,弹窗/加载框/列表刷新等视图动作经宿主回投。
 */
interface SubscriptionPageHost {
    /** 页面是否还活着(回调落地前统一自检,替代 isFinishing/isDestroyed) */
    fun isPageActive(): Boolean

    /** 视图消息队列 post(列表逐条导入的驱动) */
    fun post(runnable: Runnable)

    /** 主线程执行(异步结果落地;宿主转发 Activity#runOnUiThread,改名避免与宿主方法撞名自递归) */
    fun runOnUi(runnable: Runnable)
}

/** 导入协调器宿主:加载框/提示 + 订阅列表刷新 + 多仓选择弹窗 */
interface SubscriptionImportHost : SubscriptionPageHost {
    fun showToast(text: String)

    /** onCancel 为 null 时是普通加载框;非 null 时点"取消"执行 onCancel,调用方负责作废在跑任务 */
    fun showLoading(hint: String, onCancel: (() -> Unit)? = null)

    /** 更新加载框状态文本(宿主转发 BaseActivity#updateLoadingHint,改名避免撞名自递归) */
    fun setLoadingHint(hint: String)

    fun dismissLoading()

    /** 订阅列表数据变了:适配器整表刷新 + 空态更新(不落库) */
    fun onSubscriptionListChanged()

    /** 订阅增删改后统一刷新 + 立即落库(避免 onPause 前被强杀导致改动丢失) */
    fun persistSubscriptions()

    fun refreshLiveSources()

    /** 多仓列表让用户选一条(弹窗的展示归宿主) */
    fun showStoreHouseChoose(sources: List<Source>, onPick: (name: String, url: String) -> Unit, onDismiss: () -> Unit)
}

/** 直播源协调器宿主:渲染请求(异步预览结果落地后重绘列表) */
interface SubscriptionLiveHost : SubscriptionPageHost {
    fun onLiveSourcesRenderNeeded()
}

/** 导出协调器宿主:取勾选 + 分享落地 */
interface SubscriptionExportHost : SubscriptionPageHost {
    fun showToast(text: String)

    /** 默认值只声明在 Import 侧(两个接口共用一个实现,重复声明默认值会编译不过) */
    fun showLoading(hint: String, onCancel: (() -> Unit)?)

    fun dismissLoading()

    fun selectedForExport(): List<Subscription>

    /** 导出成功:退出导出态并拉起分享 */
    fun onExportFinished(file: File, count: Int)
}
