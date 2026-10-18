package com.github.tvbox.osc.util.subscription

import android.content.Context
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.SubscriptionExporter
import java.io.File

/**
 * 订阅导出协调器:勾选 → 描述清单落盘 → 分享。
 * 组装/落盘仍在 {@link SubscriptionExporter}(不抓取、不合并:远端配置由 App 运行时按 url 自行拉取),
 * 这里只负责页面侧的加载态与结果分发。
 */
class SubscriptionExportCoordinator(
    private val context: Context,
    private val host: SubscriptionExportHost
) {

    /** 组装描述清单 + 落盘 + 分享 */
    fun startExport() {
        val selected = host.selectedForExport()
        if (selected.isEmpty()) {
            host.showToast("请先勾选要导出的订阅")
            return
        }
        if (selected.any { it.origin == com.github.tvbox.osc.bean.Subscription.ORIGIN_PACKAGE }) {
            host.showToast("本地包订阅含关联文件，此入口仅导出清单；请使用完整备份或局域网导入导出")
            return
        }
        host.showLoading("正在导出 " + selected.size + " 个订阅…") {
            // 取消:在跑的导出结果一律作废(已发出的单次请求撤不回,但不会再采用)
            SubscriptionExporter.cancel()
            host.showToast("已取消导出")
        }
        SubscriptionExporter.export(context, selected, object : SubscriptionExporter.Callback {
            override fun onDone(file: File, count: Int) {
                if (!host.isPageActive()) return
                host.dismissLoading()
                val text = "已导出 $count 条订阅"
                LogStore.log(Category.SUBSCRIPTION, "订阅: 导出成功 $count 条")
                host.showToast(text)
                host.onExportFinished(file, count)
            }

            override fun onError(message: String) {
                if (!host.isPageActive()) return
                host.dismissLoading()
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 导出失败")
                host.showToast("导出失败")
            }
        })
    }
}
