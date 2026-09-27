package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLogBinding
import com.github.tvbox.osc.util.LogViewAssembler
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import java.io.File
import java.util.Locale

/**
 * 运行日志页（双 Tab，数据组装下沉到 [LogViewAssembler]，页面只做交互与展示）
 * <ul>
 *   <li>Tab1 业务日志：LogStore(Room) 结构化日志，模块筛选（全部/下载/播放/订阅/系统/仅失败）；</li>
 *   <li>Tab2 错误日志：本应用 logcat ERROR 级（按天文件），日期选择、复制、清空、导出——
 *       文件读取全部走 LogStore 门面（旧的 AppLog 按天文件通道已退役删除）。</li>
 * </ul>
 */
class LogActivity : BaseVbActivity<ActivityLogBinding>() {

    private val dayFiles = ArrayList<File>()
    private var selectedFile: File? = null
    /** 0=业务日志 1=错误日志 */
    private var currentTab = 0
    /** 业务日志筛选：大类型（Category.name()），null=全部 */
    private var filterCategory: String? = null
    /** 业务日志筛选：仅失败（fail/异常打点） */
    private var filterErrorOnly = false

    // ── 全文搜索(类浏览器 Ctrl+F)──
    /** 当前展示的未高亮全文 */
    private var rawText = ""
    /** 当前搜索关键词(trim;空=未在搜索) */
    private var searchQuery = ""
    /** 全部命中起始下标 */
    private val matches = ArrayList<Int>()
    /** 当前命中下标 */
    private var matchIndex = 0

    override fun init() {
        mBinding.btnClear.setOnClickListener { confirmClear() }
        mBinding.btnExport.setOnClickListener { export() }
        mBinding.btnScrollBottom.setOnClickListener { scrollBottom() }
        mBinding.btnCopy.setOnClickListener { copyContent() }
        mBinding.llDatePicker.setOnClickListener { showDatePicker() }
        wireSearch()

        // Tab 切换
        mBinding.tvTabBiz.setOnClickListener { switchTab(0) }
        mBinding.tvTabAll.setOnClickListener { switchTab(1) }

        // 业务日志模块筛选
        mBinding.ftAll.setOnClickListener { setBizFilter(null, false) }
        mBinding.ftDownload.setOnClickListener { setBizFilter(Category.DOWNLOAD.name, false) }
        mBinding.ftPlayer.setOnClickListener { setBizFilter(Category.PLAYER.name, false) }
        mBinding.ftSubscription.setOnClickListener { setBizFilter(Category.SUBSCRIPTION.name, false) }
        mBinding.ftSystem.setOnClickListener { setBizFilter(Category.SYSTEM.name, false) }
        mBinding.ftError.setOnClickListener { setBizFilter(filterCategory, true) }

        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        refreshContent()
    }

    // ------------------------------------------------------------------
    // Tab 切换
    // ------------------------------------------------------------------

    private fun switchTab(tab: Int) {
        currentTab = tab
        // Tab 是"选择型"小组件按钮:只切 isSelected —— 底与文字色由 style/WidgetBtn 的
        // selector_widget_btn + widget_btn_text 一对选择器给(选中 = 纯色按钮那一档)
        mBinding.tvTabBiz.isSelected = tab == 0
        mBinding.tvTabAll.isSelected = tab == 1
        val isBiz = tab == 0
        mBinding.llFilter.visibility = if (isBiz) View.VISIBLE else View.GONE
        mBinding.llDatePicker.visibility = if (isBiz) View.GONE else View.VISIBLE
        refreshContent()
    }

    private fun setBizFilter(category: String?, errorToggle: Boolean) {
        if (errorToggle) {
            filterErrorOnly = !filterErrorOnly
        } else {
            filterCategory = category
            filterErrorOnly = false
        }
        updateFilterButtons()
        refreshContent()
    }

    private fun updateFilterButtons() {
        setFilterSelected(mBinding.ftAll, filterCategory == null && !filterErrorOnly)
        setFilterSelected(mBinding.ftDownload, filterCategory == Category.DOWNLOAD.name)
        setFilterSelected(mBinding.ftPlayer, filterCategory == Category.PLAYER.name)
        setFilterSelected(mBinding.ftSubscription, filterCategory == Category.SUBSCRIPTION.name)
        setFilterSelected(mBinding.ftSystem, filterCategory == Category.SYSTEM.name)
        setFilterSelected(mBinding.ftError, filterErrorOnly)
    }

    private fun setFilterSelected(tv: TextView, selected: Boolean) {
        // 选择型小组件按钮:只切 isSelected,底与字都由 WidgetBtn 那对选择器给
        tv.isSelected = selected
    }


    // ------------------------------------------------------------------
    // 内容（组装逻辑在 LogViewAssembler）
    // ------------------------------------------------------------------

    private fun refreshContent() {
        if (currentTab == 0) loadBizLogs() else loadAllLogs()
    }

    /** Tab1 业务日志：LogStore 查询在后台线程（Room 禁止主线程查询） */
    private fun loadBizLogs() {
        val category = filterCategory
        val errorOnly = filterErrorOnly
        mBinding.tvContent.text = "加载中..."
        Thread {
            val text = try {
                LogViewAssembler.bizText(LogStore.get(), category, errorOnly)
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                setRawText(text ?: "暂无业务日志（设置→业务日志 开启后记录）")
                if (searchQuery.isEmpty()) {
                    mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_UP) }
                }
            }
        }.start()
    }

    /** Tab2 错误日志：文件列表/读尾也走 LogStore 门面，后台线程读取,避免大文件卡主线程 */
    private fun loadAllLogs() {
        dayFiles.clear()
        dayFiles.addAll(LogViewAssembler.rawFiles(LogStore.get()))
        if (dayFiles.isEmpty()) {
            selectedFile = null
            mBinding.tvSelectedDay.text = "暂无日志"
            mBinding.tvContent.text = "暂无日志"
            return
        }
        if (selectedFile == null || !dayFiles.contains(selectedFile)) {
            selectedFile = dayFiles[0]
        }
        mBinding.tvSelectedDay.text = selectedFile?.name?.let { LogViewAssembler.dayLabel(it) } ?: "暂无日志"
        val file = selectedFile
        Thread {
            val text = try {
                LogViewAssembler.rawText(LogStore.get(), file)
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                setRawText(text ?: "暂无内容")
                if (searchQuery.isEmpty()) {
                    mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }.start()
    }

    /** 底部抽屉选择日期 */
    private fun showDatePicker() {
        if (dayFiles.isEmpty()) {
            AppBubble.toast("暂无日志")
            return
        }
        val display = Array(dayFiles.size) { i -> LogViewAssembler.dayLabel(dayFiles[i].name) }
        XPopup.Builder(this)
            .isDarkTheme(Utils.isAppDarkTheme()) // 底部抽屉跟随 App 主题(直读主题设置,避免 ROM uiMode 不同步误判浅色)
            .asBottomList("选择日期", display) { position, _ ->
                if (position in dayFiles.indices) {
                    selectedFile = dayFiles[position]
                    mBinding.tvSelectedDay.text = display[position]
                    refreshContent()
                }
            }
            .show()
    }

    private fun scrollBottom() {
        mBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
    }

    // ------------------------------------------------------------------
    // 全文搜索(类浏览器 Ctrl+F)
    // ------------------------------------------------------------------

    private fun wireSearch() {
        mBinding.btnSearch.setOnClickListener {
            if (mBinding.searchBar.visibility == View.VISIBLE) closeSearch() else openSearch()
        }
        mBinding.btnPrev.setOnClickListener { goMatch(-1) }
        mBinding.btnNext.setOnClickListener { goMatch(1) }
        mBinding.btnSearchClose.setOnClickListener { closeSearch() }

        mBinding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                matchIndex = 0
                renderSearch()
            }
        })
        // 软键盘搜索键 / 回车:优先跳下一处
        mBinding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                goMatch(1)
                true
            } else {
                false
            }
        }
    }

    /** 内容装载统一入口:记录原始文本并(若有关键词)重算高亮 */
    private fun setRawText(text: String) {
        rawText = text
        renderSearch()
    }

    /** 按当前关键词重绘全文:全部命中浅色底,当前命中高亮底,并滚动到当前命中 */
    private fun renderSearch() {
        val tv = mBinding.tvContent
        val q = searchQuery.trim()
        matches.clear()
        if (q.isEmpty() || rawText.isEmpty()) {
            tv.text = rawText
            updateMatchUi()
            return
        }
        val hay = rawText.lowercase(Locale.ROOT)
        val needle = q.lowercase(Locale.ROOT)
        var from = 0
        while (from < hay.length) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            matches.add(idx)
            from = idx + needle.length
        }
        if (matches.isEmpty()) {
            tv.text = rawText
            updateMatchUi()
            return
        }
        if (matchIndex >= matches.size) matchIndex = 0
        val sp = SpannableString(rawText)
        for (i in matches.indices) {
            // 当前命中高亮为橙色底,其余命中浅黄底
            val bg = if (i == matchIndex) 0xFFFFB300.toInt() else 0x55FFF176
            sp.setSpan(
                BackgroundColorSpan(bg), matches[i], matches[i] + q.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tv.text = sp
        updateMatchUi()
        scrollToMatch()
    }

    /** 计数标签:n/N;无匹配提示;未在搜索则清空 */
    private fun updateMatchUi() {
        mBinding.tvMatch.text = when {
            searchQuery.isEmpty() -> ""
            matches.isEmpty() -> "无匹配"
            else -> "${matchIndex + 1}/${matches.size}"
        }
    }

    /** 让当前命中行进入可视区(尽量居中) */
    private fun scrollToMatch() {
        if (matches.isEmpty() || matchIndex !in matches.indices) return
        mBinding.scrollLog.post {
            val tv = mBinding.tvContent
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(matches[matchIndex])
            // 内边距在滚动容器上(卡片内部滚动,见 activity_log.xml):行坐标要加上容器上内边距换算到滚动内容坐标系,
            // 可视区高度也要扣掉上下内边距,否则命中行会整体偏一个内边距
            val padTop = mBinding.scrollLog.paddingTop
            val visible = mBinding.scrollLog.height - padTop - mBinding.scrollLog.paddingBottom
            val top = layout.getLineTop(line) + padTop
            val bottom = layout.getLineBottom(line) + padTop
            val sy = mBinding.scrollLog.scrollY
            val target = if (top < sy + padTop || bottom > sy + padTop + visible) (top + bottom - visible) / 2 - padTop else sy
            mBinding.scrollLog.scrollTo(0, maxOf(0, target))
        }
    }

    /** 上一处/下一处(-1/+1,循环) */
    private fun goMatch(delta: Int) {
        if (matches.isEmpty()) {
            if (searchQuery.isNotEmpty()) renderSearch()
            return
        }
        matchIndex = (matchIndex + delta + matches.size) % matches.size
        renderSearch()
    }

    private fun openSearch() {
        mBinding.searchBar.visibility = View.VISIBLE
        mBinding.etSearch.requestFocus()
        ime().showSoftInput(mBinding.etSearch, 0)
    }

    private fun closeSearch() {
        mBinding.searchBar.visibility = View.GONE
        searchQuery = ""
        matches.clear()
        matchIndex = 0
        mBinding.etSearch.setText("")
        ime().hideSoftInputFromWindow(mBinding.etSearch.windowToken, 0)
        renderSearch()
    }

    private fun ime(): android.view.inputmethod.InputMethodManager =
        getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

    private fun copyContent() {
        val text = mBinding.tvContent.text?.toString() ?: ""
        if (text.isEmpty()) {
            AppBubble.toast("暂无内容可复制")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("运行日志", text))
        AppBubble.toast("已复制当前日志内容")
    }

    private fun confirmClear() {
        // 清空不可逆 → 确认键走危险色(与下载/本地视频的删除确认同一套)
        com.github.tvbox.osc.ui.dialog.ConfirmDialog.showDanger(this, "清空日志",
            "确定清空${if (currentTab == 0) "业务日志" else "错误日志"}吗？", "清空", {
                if (currentTab == 0) {
                    LogViewAssembler.clearBiz(LogStore.get())
                    mBinding.tvContent.text = "暂无日志"
                } else {
                    LogViewAssembler.clearRaw(LogStore.get())
                    selectedFile = null
                    refreshContent()
                }
                AppBubble.toast("已清空")
            })
    }

    private fun export() {
        val store = LogStore.get()
        val file = if (currentTab == 0) {
            LogViewAssembler.exportBiz(store, filterCategory, filterErrorOnly)
        } else {
            LogViewAssembler.exportRaw(store)
        }
        if (file == null) {
            AppBubble.toast("暂无日志可导出")
            return
        }
        shareFile(file)
    }

    private fun shareFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "导出运行日志"))
        } catch (th: Throwable) {
            th.printStackTrace()
            AppBubble.toast("导出失败:" + th.message)
        }
    }
}
