package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Selection
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLogBinding
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.LogViewAssembler
import com.github.tvbox.osc.ui.kit.TabPageAnimator
import com.github.tvbox.osc.ui.kit.TabSwipeHelper
import com.github.tvbox.osc.ui.kit.WidgetPressEffect
import java.io.File
import java.lang.ref.WeakReference
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
    private var downloadTaskKey: String? = null
    private val bizEpoch = java.util.concurrent.atomic.AtomicInteger()
    private val errorEpoch = java.util.concurrent.atomic.AtomicInteger()
    private var clearing = false
    private val mainPageAnimator = TabPageAnimator()
    private val bizPageAnimator = TabPageAnimator()
    private var activeBizPage = 0
    private var businessText = ""
    private var errorText = ""
    private var swipeEligible = false
    private val swipeTracker by lazy { TabSwipeHelper.tracker(this) { navigateSwipe(it) } }
    private val categories = arrayOf<String?>(
        null, Category.DOWNLOAD.name, Category.PLAYER.name,
        Category.SUBSCRIPTION.name, Category.SYSTEM.name
    )

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
        downloadTaskKey = intent.getStringExtra("download_task_key")
        if (!downloadTaskKey.isNullOrEmpty()) filterCategory = Category.DOWNLOAD.name
        mBinding.btnClear.setOnClickListener { confirmClear() }
        mBinding.btnExport.setOnClickListener { export() }
        mBinding.btnScrollBottom.setOnClickListener { scrollBottom() }
        WidgetPressEffect.attach(mBinding.btnScrollBottom)
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

        styleMainTabs()
        updateFilterButtons()
        mBinding.llFilter.visibility = View.VISIBLE
        mBinding.llDatePicker.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        refreshContent()
    }

    /** 仅观察日志内容区的单指滑动；输入、日期、筛选条与操作按钮保留自己的手势。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeEligible = hit(mBinding.tabPageContainer, ev) &&
                    !hit(mBinding.btnScrollBottom, ev) && !hasTextSelection()
                if (swipeEligible) {
                    swipeTracker.onTouch(ev)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swipeEligible = false
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            if (swipeEligible && !hasTextSelection()) swipeTracker.onTouch(ev)
            swipeEligible = false
        }
        return handled
    }

    private fun hasTextSelection(): Boolean {
        val text = activeTextView().text
        if (text !is Spanned) return false
        val start = Selection.getSelectionStart(text)
        val end = Selection.getSelectionEnd(text)
        return start >= 0 && end > start
    }

    private fun hit(view: View, event: MotionEvent): Boolean {
        if (!view.isShown) return false
        val xy = IntArray(2)
        view.getLocationOnScreen(xy)
        return event.rawX >= xy[0] && event.rawX < xy[0] + view.width &&
            event.rawY >= xy[1] && event.rawY < xy[1] + view.height
    }

    private fun navigateSwipe(dir: Int) {
        when {
            currentTab == 0 && dir < 0 -> switchTab(1)
            currentTab == 1 && dir > 0 -> switchTab(0)
        }
    }

    // ------------------------------------------------------------------
    // Tab 切换
    // ------------------------------------------------------------------

    private fun switchTab(tab: Int) {
        if (tab == currentTab) return
        bizPageAnimator.finish()
        val outgoing = if (currentTab == 0) mBinding.pageBiz else mBinding.pageError
        val incoming = if (tab == 0) mBinding.pageBiz else mBinding.pageError
        if (currentTab == 0) bizEpoch.incrementAndGet() else errorEpoch.incrementAndGet()
        currentTab = tab
        styleMainTabs()
        val isBiz = tab == 0
        mBinding.llFilter.visibility = if (isBiz) View.VISIBLE else View.GONE
        mBinding.llDatePicker.visibility = if (isBiz) View.GONE else View.VISIBLE
        rawText = if (isBiz) businessText else errorText
        renderSearch()
        mainPageAnimator.slide(mBinding.tabPageContainer, outgoing, incoming, if (isBiz) 1 else -1)
        refreshContent()
    }

    private fun styleMainTabs() {
        mBinding.tvTabBiz.isSelected = currentTab == 0
        mBinding.tvTabAll.isSelected = currentTab == 1
    }

    private fun setBizFilter(category: String?, errorToggle: Boolean) {
        val previousCategory = filterCategory
        val previousErrorOnly = filterErrorOnly
        if (errorToggle) {
            filterErrorOnly = !filterErrorOnly
        } else {
            filterCategory = category
        }
        if (previousCategory == filterCategory && previousErrorOnly == filterErrorOnly) return
        val previousIndex = categories.indexOf(previousCategory).coerceAtLeast(0)
        val nextIndex = categories.indexOf(filterCategory).coerceAtLeast(0)
        val direction = if (errorToggle) {
            if (filterErrorOnly) -1 else 1
        } else if (nextIndex > previousIndex) -1 else 1
        updateFilterButtons()
        revealFilterButton()
        bizPageAnimator.finish()
        val outgoing = activeBizScroll()
        activeBizPage = 1 - activeBizPage
        val incoming = activeBizScroll()
        businessText = "加载中..."
        rawText = businessText
        renderSearch()
        incoming.scrollTo(0, 0)
        bizPageAnimator.slide(mBinding.bizPageContainer, outgoing, incoming, direction)
        loadBizLogs()
    }

    private fun updateFilterButtons() {
        setFilterSelected(mBinding.ftAll, filterCategory == null)
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

    private fun revealFilterButton() {
        val selected = when (filterCategory) {
            Category.DOWNLOAD.name -> mBinding.ftDownload
            Category.PLAYER.name -> mBinding.ftPlayer
            Category.SUBSCRIPTION.name -> mBinding.ftSubscription
            Category.SYSTEM.name -> mBinding.ftSystem
            else -> mBinding.ftAll
        }
        mBinding.llFilter.post {
            val scroll = mBinding.llFilter
            val left = selected.left
            val right = selected.right
            when {
                left < scroll.scrollX -> scroll.smoothScrollTo(left, 0)
                right > scroll.scrollX + scroll.width -> scroll.smoothScrollTo(right - scroll.width, 0)
            }
        }
    }

    private fun activeBizScroll(): ScrollView =
        if (activeBizPage == 0) mBinding.scrollLog else mBinding.scrollBizNext

    private fun activeScroll(): ScrollView =
        if (currentTab == 0) activeBizScroll() else mBinding.scrollError

    private fun activeTextView(): TextView = when {
        currentTab == 1 -> mBinding.tvError
        activeBizPage == 0 -> mBinding.tvContent
        else -> mBinding.tvBizNext
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
        val taskKey = downloadTaskKey
        val epoch = bizEpoch.incrementAndGet()
        if (businessText.isEmpty()) setRawText("加载中...")
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val text = try {
                LogViewAssembler.bizText(LogStore.get(), category, errorOnly, taskKey)
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                if (bizEpoch.get() != epoch || currentTab != 0 || isFinishing || isDestroyed) return@runOnUiThread
                setRawText(text ?: "暂无业务日志（设置→业务日志 开启后记录）")
                if (searchQuery.isEmpty()) {
                    val scroll = activeScroll()
                    scroll.post {
                        if (bizEpoch.get() == epoch && currentTab == 0) scroll.fullScroll(View.FOCUS_UP)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        bizEpoch.incrementAndGet()
        errorEpoch.incrementAndGet()
        mainPageAnimator.finish()
        bizPageAnimator.finish()
        super.onDestroy()
    }

    /** Tab2 错误日志：文件列表/读尾也走 LogStore 门面，后台线程读取,避免大文件卡主线程 */
    private fun loadAllLogs() {
        val epoch = errorEpoch.incrementAndGet()
        val preferredFile = selectedFile
        if (errorText.isEmpty()) setRawText("加载中...")
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val result = try {
                val files = LogViewAssembler.rawFiles(LogStore.get())
                val file = preferredFile?.takeIf { files.contains(it) } ?: files.firstOrNull()
                Triple(files, file, LogViewAssembler.rawText(LogStore.get(), file))
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                if (errorEpoch.get() != epoch || currentTab != 1 || isFinishing || isDestroyed) return@runOnUiThread
                dayFiles.clear()
                if (result != null) dayFiles.addAll(result.first)
                selectedFile = result?.second
                mBinding.tvSelectedDay.text = selectedFile?.name?.let { LogViewAssembler.dayLabel(it) } ?: "暂无日志"
                setRawText(result?.third ?: if (dayFiles.isEmpty()) "暂无日志" else "暂无内容")
                if (searchQuery.isEmpty()) {
                    val scroll = activeScroll()
                    scroll.post {
                        if (errorEpoch.get() == epoch && currentTab == 1) scroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    /** 底部抽屉选择日期:走公共抽屉组件(见 ui/dialog/BottomListDialog 的类注释) */
    private fun showDatePicker() {
        if (dayFiles.isEmpty()) {
            AppBubble.toast("暂无日志")
            return
        }
        val display = dayFiles.map { LogViewAssembler.dayLabel(it.name) }
        val selected = dayFiles.indexOf(selectedFile).coerceAtLeast(0)
        com.github.tvbox.osc.ui.dialog.BottomListDialog(this, "选择日期", display, selected) { position, _ ->
            if (position in dayFiles.indices) {
                selectedFile = dayFiles[position]
                mBinding.tvSelectedDay.text = display[position]
                refreshContent()
            }
        }.show()
    }

    private fun scrollBottom() {
        activeScroll().fullScroll(View.FOCUS_DOWN)
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
        if (currentTab == 0) businessText = text else errorText = text
        rawText = text
        renderSearch()
    }

    /** 按当前关键词重绘全文:全部命中浅色底,当前命中高亮底,并滚动到当前命中 */
    private fun renderSearch() {
        val tv = activeTextView()
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
        val scroll = activeScroll()
        val tv = activeTextView()
        val tab = currentTab
        scroll.post {
            if (tab != currentTab || tv !== activeTextView() || matchIndex !in matches.indices) return@post
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(matches[matchIndex])
            // 内边距在滚动容器上(卡片内部滚动,见 activity_log.xml):行坐标要加上容器上内边距换算到滚动内容坐标系,
            // 可视区高度也要扣掉上下内边距,否则命中行会整体偏一个内边距
            val padTop = scroll.paddingTop
            val visible = scroll.height - padTop - scroll.paddingBottom
            val top = layout.getLineTop(line) + padTop
            val bottom = layout.getLineBottom(line) + padTop
            val sy = scroll.scrollY
            val target = if (top < sy + padTop || bottom > sy + padTop + visible) (top + bottom - visible) / 2 - padTop else sy
            scroll.scrollTo(0, maxOf(0, target))
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
        val text = activeTextView().text?.toString() ?: ""
        if (text.isEmpty()) {
            AppBubble.toast("暂无内容可复制")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("运行日志", text))
        AppBubble.toast("已复制当前日志内容")
    }

    private fun confirmClear() {
        if (clearing) return
        val tabToClear = currentTab
        // 清空不可逆 → 确认键走红边红字的危险空心样式
        com.github.tvbox.osc.ui.dialog.ConfirmDialog.showDanger(this, "清空日志",
            "确定清空${if (tabToClear == 0) "业务日志" else "错误日志"}吗？", "清空", {
                if (clearing) return@showDanger
                clearing = true
                mBinding.btnClear.isEnabled = false
                if (tabToClear == 0) bizEpoch.incrementAndGet() else errorEpoch.incrementAndGet()
                val pageRef = WeakReference(this)
                HeavyTaskUtil.getBigTaskExecutorService().execute {
                    val cleared = runCatching {
                        if (tabToClear == 0) LogViewAssembler.clearBiz(LogStore.get())
                        else LogViewAssembler.clearRaw(LogStore.get())
                    }.getOrDefault(false)
                    Handler(Looper.getMainLooper()).post {
                        val page = pageRef.get() ?: return@post
                        if (page.isFinishing || page.isDestroyed) return@post
                        page.clearing = false
                        page.mBinding.btnClear.isEnabled = true
                        if (tabToClear == 0) {
                            page.bizEpoch.incrementAndGet()
                            page.businessText = ""
                        } else {
                            page.errorEpoch.incrementAndGet()
                            page.errorText = ""
                            page.dayFiles.clear()
                            page.selectedFile = null
                        }
                        if (page.currentTab == tabToClear) page.refreshContent()
                        AppBubble.toast(if (cleared) "已清空" else "清空未完成，请重试")
                    }
                }
            })
    }

    private fun export() {
        val store = LogStore.get()
        val tab = currentTab
        val category = filterCategory
        val errorOnly = filterErrorOnly
        val taskKey = downloadTaskKey
        HeavyTaskUtil.getSerialExecutorService().execute {
            val file = if (tab == 0) LogViewAssembler.exportBiz(store, category, errorOnly, taskKey)
                else LogViewAssembler.exportRaw(store)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) AppBubble.toast("暂无日志可导出") else shareFile(file)
            }
        }
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
            AppBubble.toast("导出失败")
        }
    }
}
