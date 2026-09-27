package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.theme.ThemeDef
import com.github.tvbox.osc.bean.theme.ThemeKey
import com.github.tvbox.osc.bean.theme.ThemePalette
import com.github.tvbox.osc.bean.theme.ThemeSpec
import com.github.tvbox.osc.bean.theme.ThemeType
import com.github.tvbox.osc.databinding.ActivityThemeEditorBinding
import com.github.tvbox.osc.storage.theme.ThemeArchive
import com.github.tvbox.osc.storage.theme.ThemeStore
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.ColorPickerDialog
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.ui.dialog.ThemeNameDialog
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.BgImageImporter
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.PicassoLoad
import com.blankj.utilcode.util.ClipboardUtils
import java.io.File
import java.util.LinkedHashMap

/**
 * 自定义主题编辑(二级页,入口:设置 - 主题颜色 - 自定义主题颜色 / 长按主题「编辑」)。
 *
 * <p>页面结构 = 主题类型 + 四个类目的可配置项 + 背景 + 底部动作:
 * <ul>
 *   <li><b>主题类型</b>:亮色 / 暗色。它决定这个主题按哪种明暗渲染(夜间模式、弹窗气泡、状态栏),
 *       也是"每个色值恢复默认"的取值来源。改了类型而颜色还没动过时会顺手按新类型的内置主题重新填充
 *       (否则会得到一个"说是暗色、其实是浅色"的主题);已经调过的颜色一律保持不动。</li>
 *   <li><b>颜色项</b>:按 {@link ThemeSpec} 的类目列出全部 25 项;每项都能<b>直接改十六进制文本</b>,
 *       也能点色块开取色板。透明度项给数字(0-100),没有色板。</li>
 *   <li><b>背景</b>:图片(走既有背景图导入:体积上限 / 纠 EXIF 方向 / 转 WebP,并按内容 hash 去重)
 *       / 纯色 / 恢复默认(默认值按主题类型取内置主题,即纯色)。</li>
 *   <li><b>底部动作</b>:新建时是「取消 + 导入主题」,编辑已有主题时换成「删除 + 导出主题」——
 *       取消/删除是<b>两个按钮</b>(样式不同:取消=次按钮,删除=危险入口红字),导入/导出互斥显示。</li>
 * </ul>
 *
 * <p>保存:标题栏右侧「保存」。没有名字时弹命名弹窗(重名不许过、可取消);保存成功即返回上级页,
 * 真正的生效仍由上级「主题颜色」弹窗关闭时的重启负责(本页不做实时预览)。
 */
class ThemeEditorActivity : BaseVbActivity<ActivityThemeEditorBinding>() {

    companion object {
        /** 传入要编辑的主题 id;不传/空串 = 新建 */
        const val EXTRA_THEME_ID = "theme_id"

        private const val REQ_PICK_BG = 0x0E01
        private const val REQ_EXPORT_FILE = 0x0E02
        private const val REQ_IMPORT_FILE = 0x0E03

        /** 页面背景色:在颜色列表里不重复列,归下面的「背景」块(纯色就是它) */
        private const val BG_BODY_KEY = "bg_body"
    }

    /** 编辑草稿(点保存前不落盘) */
    private var draft: ThemeDef? = null
    private var isNew = true
    /** 进入本页时该主题的"未改动判定"基准(类型切换时判断颜色是否动过) */
    private var typeBaseline: Map<String, String> = emptyMap()
    /** key → 该键的所有行(同一个键可能在两处出现,如 bg_body 在颜色卡与「背景」块各一行) */
    private val rows = LinkedHashMap<String, MutableList<Row>>()

    /** 导出到缓存里的文件(用户选好位置后复制过去) */
    private var exportedFile: File? = null

    private class Row(
        val key: ThemeKey,
        val label: TextView,
        val swatch: View,
        val input: EditText
    )

    override fun init() {
        val id = intent.getStringExtra(EXTRA_THEME_ID) ?: ""
        isNew = id.isEmpty()
        val def = if (isNew) {
            // 新建:以"当前生效的类型"内置配色为起点(用户大概率就是要照着当前观感改)
            ThemeDef.blank(ThemeStore.activeType(), ThemeStore.builtinInput(ThemeStore.activeType()))
        } else {
            ThemeStore.find(id)?.copy()
                ?: ThemeDef.blank(ThemeType.BRIGHT, ThemeStore.builtinInput(ThemeType.BRIGHT))
        }
        def.materialize(ThemeStore.builtinInput(def.type))
        draft = def
        typeBaseline = LinkedHashMap(def.colors())

        buildColorRows()
        bindType()
        bindBackground()
        bindActions()
        refreshHeader()
        refreshAllValues()
    }

    // ------------------------------------------------------------------
    // 颜色项:仍按类目分成几张卡(与原来一致),只是不再打类目标题
    // ------------------------------------------------------------------

    private fun buildColorRows() {
        val inflater = LayoutInflater.from(this)
        for (group in ThemeSpec.groups()) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = ContextCompat.getDrawable(this, R.drawable.bg_large_round_float)
            val pad = dp(20)
            card.setPadding(pad, dp(16), pad, dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            card.layoutParams = lp
            // 类目标题(「底色与面」这类一级标题)按要求去掉,卡片与卡片内的行保持原样
            for (key in ThemeSpec.of(group)) {
                // 页面背景不在这张卡里重复列:它归下面「背景」块那一个取色行(纯色就是它)。
                // 两处都列 = 同一项在页面上出现两次(上面一次、下面一次),用户看到的是"重合了"。
                if (key.key == BG_BODY_KEY) continue
                val rowView = inflater.inflate(R.layout.item_theme_color, card, false)
                card.addView(rowView)
                bindRow(key, rowView)
            }
            mBinding.llGroups.addView(card)
        }
    }

    private fun bindRow(key: ThemeKey, rowView: View) {
        val label = rowView.findViewById<TextView>(R.id.tv_label)
        val swatch = rowView.findViewById<View>(R.id.v_swatch)
        val input = rowView.findViewById<EditText>(R.id.et_value)
        label.text = key.label
        // 说明文字(item_theme_color 的 tv_desc)已从行布局里去掉:每一项只留名称,说明改由主题文件 /
        // ThemeSpec 承载(编辑页不再逐项解释"这颜色用在哪")。要恢复就把布局里的 tv_desc 解注释并把
        // 这里的 desc 接线一起加回来。

        if (key.isAlpha) {
            // 透明度项:没有色板,输入 0-100 的整数
            swatch.visibility = View.GONE
            input.hint = "0-100"
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.maxLengthCompat(3)
        } else {
            swatch.isClickable = true
            swatch.setOnClickListener {
                FastClickCheckUtil.check(it)
                // 注意:这里不能捕获 draft —— 导入主题包会整份换掉草稿,捕获的旧对象会把编辑写丢
                // key.opaqueOnly(目前是「文字主色」):取色板不给透明度那一行,只收纯色
                ColorPickerDialog.show(this, key.label, draft?.color(key.key) ?: "", key.opaqueOnly) { hex ->
                    setValue(key, hex)
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (isValid(key, text)) {
                    input.setTextColor(ContextCompat.getColor(this@ThemeEditorActivity, R.color.text_foreground))
                    draft?.setColor(key.key, normalize(key, text))
                    // 同一个键可能有两行(bg_body 在颜色卡与「背景」块各有一行):一起刷新,免得两处值看着不一样
                    syncRows(key.key, text, input)
                    updateSwatch(key)
                } else {
                    input.setTextColor(ContextCompat.getColor(this@ThemeEditorActivity, R.color.text_danger))
                }
            }
        })
        rows.getOrPut(key.key) { ArrayList() }.add(Row(key, label, swatch, input))
    }

    /** 把同一个键的其它输入框同步成刚改的值(改了哪个框就跳过哪个) */
    private fun syncRows(key: String, text: String, source: EditText) {
        for (row in rows[key].orEmpty()) {
            if (row.input === source) continue
            if (row.input.text.toString() == text) continue
            row.input.setText(text)
        }
    }

    /** 值合法吗(颜色:6/8 位十六进制;透明度:0-100 整数) */
    private fun isValid(key: ThemeKey, text: String): Boolean {
        if (text.isEmpty()) return false
        return if (key.isAlpha) {
            val v = text.toIntOrNull() ?: return false
            v in 0..100
        } else {
            val hex = text.replace("#", "").uppercase()
            when {
                !hex.matches(Regex("(?i)[0-9a-f]{6}|[0-9a-f]{8}")) -> false
                // 只允许纯色的键(「文字主色」):写成 8 位时必须是不透明(FF 开头),否则就是"设了透明度",不接受
                key.opaqueOnly && hex.length == 8 -> hex.startsWith("FF")
                else -> true
            }
        }
    }

    /** 规范化成主题文件里的写法(颜色统一带 # 并大写) */
    private fun normalize(key: ThemeKey, text: String): String {
        if (key.isAlpha) return (text.toIntOrNull() ?: 0).coerceIn(0, 100).toString()
        val hex = text.replace("#", "").uppercase()
        // 只允许纯色的键:即便用户写了 FF 开头的 8 位,也归一成 6 位纯色
        if (key.opaqueOnly && hex.length == 8) return "#" + hex.substring(2)
        return "#$hex"
    }

    private fun setValue(key: ThemeKey, value: String) {
        for (row in rows[key.key].orEmpty()) {
            row.input.setText(value)
            row.input.setSelection(row.input.text.length)
        }
    }

    /** 色块填充:颜色项直接用该色;透明度项用它的底色叠上该透明度(直观看出"透多少") */
    private fun updateSwatch(key: ThemeKey) {
        if (draft == null || key.isAlpha) return
        for (row in rows[key.key].orEmpty()) {
            val v = row.input.text.toString().trim()
            val color = if (isValid(key, v)) ThemePalette.parseColor(normalize(key, v), 0xFF1F2937.toInt())
            else 0x00000000
            row.swatch.background = swatchDrawable(color)
        }
    }

    private fun swatchDrawable(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(6).toFloat()
        g.setColor(color)
        g.setStroke(dp(1), ContextCompat.getColor(this, R.color.btn_stroke))
        return g
    }

    // ------------------------------------------------------------------
    // 主题类型
    // ------------------------------------------------------------------

    private fun bindType() {
        mBinding.llType.setOnClickListener {
            FastClickCheckUtil.check(it)
            val d = draft ?: return@setOnClickListener
            val types = arrayListOf(ThemeType.BRIGHT, ThemeType.DARK)
            val labels = arrayListOf(ThemeType.BRIGHT.label, ThemeType.DARK.label)
            val dialog = SelectDialog<String>(this)
            dialog.setTip("主题类型")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) = applyType(types[pos])
                override fun getDisplay(value: String?): String = value ?: ""
            }, SelectDialogAdapter.stringDiff, labels, if (d.type == ThemeType.DARK) 1 else 0)
            dialog.show()
        }
    }

    /**
     * 切换类型:颜色<b>没动过</b>就按新类型的内置主题重新填充(否则会得到一个"声称暗色、实则浅色"的主题);
     * 已经调过的颜色保持不动(用户明确改过的东西不替他做主)。
     */
    private fun applyType(type: ThemeType) {
        val d = draft ?: return
        if (d.type == type) return
        val untouched = typeBaseline.isNotEmpty() &&
                ThemeSpec.all().all { typeBaseline[it.key] == d.color(it.key) }
        d.type = type
        if (untouched) {
            ThemeStore.resetToBuiltin(d)
            AppBubble.toast("已按「${type.label}」内置主题填充颜色")
        } else {
            AppBubble.toast("只改了类型,颜色保持不变")
        }
        typeBaseline = LinkedHashMap(d.colors())
        refreshHeader()
        refreshAllValues()
    }

    // ------------------------------------------------------------------
    // 背景:一个开关切"纯色 / 背景图"
    // ------------------------------------------------------------------

    /**
     * 背景只有两种形态,**用一个开关切**:关=纯色(用本主题的页面背景色 bg_body,取色方式与上面的颜色项完全一样),
     * 开=背景图(预览居中 / 描述居左 / 按钮居中)。
     * <p>模型里仍保留 MODE_DEFAULT(「跟随该类型内置默认背景」)以兼容导入的主题包,
     * 界面上它等价于纯色 —— 「恢复默认」按钮会把颜色与模式一起退回该类型的内置值。
     */
    private fun bindBackground() {
        // 纯色形态:一行"取色"(与颜色项同一个控件、同一个取色板);「恢复默认」按钮在布局里
        // (用共享的 BtnSecondary 样式,与其它按钮同款 —— 之前是代码 new 出来的,样式跟其它按钮不一致)
        val solidRow = LayoutInflater.from(this)
            .inflate(R.layout.item_theme_color, mBinding.llBgSolid, false)
        mBinding.llBgSolid.addView(solidRow)
        bindRow(bgBodyKey(), solidRow)

        mBinding.btnBgReset.setOnClickListener {
            FastClickCheckUtil.check(it)
            resetBackgroundToDefault()
        }

        mBinding.btnBgPick.setOnClickListener {
            FastClickCheckUtil.check(it)
            pickBackground()
        }
        mBinding.switchBgImage.setChecked(draft?.hasBackgroundImage() == true)
        mBinding.switchBgImage.setOnClickListener(null)
        mBinding.llBgImageRow.setOnClickListener {
            FastClickCheckUtil.check(it)
            setImageBackground(!(draft?.hasBackgroundImage() ?: false))
        }
        mBinding.switchBgImage.isClickable = false
        mBinding.llBgImageRow.isClickable = true
    }

    private fun bgBodyKey(): ThemeKey = ThemeSpec.byKey(BG_BODY_KEY) ?: ThemeSpec.all()[0]

    /** 开关:切"背景图"(开)与"纯色"(关);从图片切走时只改模式,旧图交给保存时的回收统一清理 */
    private fun setImageBackground(on: Boolean) {
        val d = draft ?: return
        if (on) {
            if (d.hasBackgroundImage()) return
            // 打开开关但还没有图:直接进选图流程(选完才算真的切过去)
            mBinding.switchBgImage.setChecked(false)
            pickBackground()
            return
        }
        d.background.mode = ThemeDef.Background.MODE_SOLID
        d.background.ref = ""
        refreshBackground()
    }

    /** 恢复默认:颜色取"该类型内置主题"的页面背景色,模式退回跟随默认(纯色) */
    private fun resetBackgroundToDefault() {
        val d = draft ?: return
        val builtin = ThemeStore.defaultValueOf(d.type, bgBodyKey())
        if (builtin != null) {
            d.setColor(BG_BODY_KEY, builtin)
            setValue(bgBodyKey(), builtin)
        }
        d.background.mode = ThemeDef.Background.MODE_DEFAULT
        d.background.ref = ""
        refreshBackground()
        AppBubble.toast("背景已恢复默认(按「${d.type.label}」内置主题)")
    }

    /**
     * 选图 / 调整图片:交给既有的「设置背景图」页({@link BackgroundSettingActivity})来做 ——
     * 那一页本身就是预览(单指拖动 / 双指缩放 / 位置预设 / 不透明度 / 遮罩),不另写一套选图与摆放逻辑。
     * 走"主题模式":改的是这个主题自己的背景(含摆放),确认后把结果经 Intent 交回本页草稿。
     */
    private fun pickBackground() {
        val d = draft ?: return
        val intent = Intent(this, BackgroundSettingActivity::class.java)
        intent.putExtra(BackgroundSettingActivity.EXTRA_THEME_MODE, true)
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_PATH, currentBackgroundPath())
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_REF, d.background.ref)
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_ZOOM, d.background.zoom)
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_ANCHOR_X, d.background.anchorX)
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_ANCHOR_Y, d.background.anchorY)
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_ALPHA, d.background.alpha)
        intent.putExtra(BackgroundSettingActivity.EXTRA_IN_SCRIM, d.background.isScrim)
        startActivityForResult(intent, REQ_PICK_BG)
    }

    /** 当前草稿的背景图绝对路径(没有图时为空串;文件丢了也当没有,免得把空路径带过去) */
    private fun currentBackgroundPath(): String {
        val d = draft ?: return ""
        if (!d.hasBackgroundImage()) return ""
        return ThemeStore.resolveBackgroundPath(d.background.ref)
    }

    /** 「设置背景图」页确认回来的背景草稿:写进主题草稿(还没落盘,保存时才写文件) */
    private fun applyBackgroundResult(data: Intent?) {
        val d = draft ?: return
        val isImage = data?.getBooleanExtra(BackgroundSettingActivity.EXTRA_OUT_IS_IMAGE, false) == true
        if (isImage) {
            val ref = data?.getStringExtra(BackgroundSettingActivity.EXTRA_OUT_REF).orEmpty()
            if (ref.isEmpty()) {
                AppBubble.toast("背景图没拿到,请重试")
                return
            }
            d.background.mode = ThemeDef.Background.MODE_IMAGE
            d.background.ref = ref
            d.background.zoom = data?.getFloatExtra(BackgroundSettingActivity.EXTRA_OUT_ZOOM, 0f) ?: 0f
            d.background.anchorX = data?.getFloatExtra(
                BackgroundSettingActivity.EXTRA_OUT_ANCHOR_X, ThemeDef.Background.DEFAULT_ANCHOR)
                ?: ThemeDef.Background.DEFAULT_ANCHOR
            d.background.anchorY = data?.getFloatExtra(
                BackgroundSettingActivity.EXTRA_OUT_ANCHOR_Y, ThemeDef.Background.DEFAULT_ANCHOR)
                ?: ThemeDef.Background.DEFAULT_ANCHOR
            d.background.alpha = data?.getIntExtra(
                BackgroundSettingActivity.EXTRA_OUT_ALPHA, ThemeDef.Background.DEFAULT_ALPHA)
                ?: ThemeDef.Background.DEFAULT_ALPHA
            d.background.isScrim = data?.getBooleanExtra(BackgroundSettingActivity.EXTRA_OUT_SCRIM, true) ?: true
        } else {
            // 在那一页点了"恢复默认":回该类型的默认背景(=纯色)
            d.background.mode = ThemeDef.Background.MODE_DEFAULT
            d.background.ref = ""
        }
        refreshBackground()
    }

    /** 按当前草稿渲染:开关位置、纯色/图片两块谁显示、图片预览 */
    private fun refreshBackground() {
        val d = draft ?: return
        val image = d.hasBackgroundImage()
        mBinding.switchBgImage.setChecked(image)
        mBinding.llBgImage.visibility = if (image) View.VISIBLE else View.GONE
        mBinding.llBgSolid.visibility = if (image) View.GONE else View.VISIBLE
        mBinding.btnBgReset.visibility = if (image) View.GONE else View.VISIBLE
        mBinding.btnBgPick.text = if (image) "调整图片" else "选择图片"
        // 取色行的色块跟着 bg_body 走(与上面颜色项同一份值、同一个控件)
        updateSwatch(bgBodyKey())
        if (!image) {
            mBinding.ivBg.setImageDrawable(null)
            return
        }
        val path = ThemeStore.resolveBackgroundPath(d.background.ref)
        if (path.isEmpty()) {
            // 图没了(文件被清理/手改过主题包):清掉旧图,别留一张过期的预览
            mBinding.ivBg.setImageDrawable(null)
            mBinding.ivBg.setTag(null)
            return
        }
        val ref = d.background.ref
        // post 到下一次布局后再交给 PicassoLoad:它用 fit()+centerCrop 按控件尺寸解码,
        // 视图还没量过就发起会一直停在加载态(预览看着就像"只显示了占位图")
        mBinding.ivBg.post {
            val cur = draft ?: return@post
            if (cur.background.ref != ref) return@post // 期间又换了图:这次加载作废
            PicassoLoad.intoFile(mBinding.ivBg, File(path))
        }
    }

    // ------------------------------------------------------------------
    // 动作:保存 / 删除 / 导入 / 导出
    // ------------------------------------------------------------------

    private fun bindActions() {
        mBinding.tvSave.setOnClickListener {
            FastClickCheckUtil.check(it)
            onSaveClicked()
        }
        // 取消(新建)与删除(已有)是两个按钮:样式不同 —— 取消=次按钮,删除=危险入口(无底色 + 红字),
        // 共用一个按钮就没法各自套样式(用户口径:"删除按钮样式和主题都对不上")
        mBinding.btnCancel.visibility = if (isNew) View.VISIBLE else View.GONE
        mBinding.btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE
        mBinding.btnCancel.setOnClickListener {
            FastClickCheckUtil.check(it)
            finish()
        }
        mBinding.btnDelete.setOnClickListener {
            FastClickCheckUtil.check(it)
            onDeleteClicked()
        }
        mBinding.btnImport.visibility = if (isNew) View.VISIBLE else View.GONE
        mBinding.btnExport.visibility = if (isNew) View.GONE else View.VISIBLE
        mBinding.btnImport.setOnClickListener {
            FastClickCheckUtil.check(it)
            showImportChooser()
        }
        mBinding.btnExport.setOnClickListener {
            FastClickCheckUtil.check(it)
            doExport()
        }
        // 点"主题名称"那一行可以改名/起名
        mBinding.llName.setOnClickListener {
            FastClickCheckUtil.check(it)
            askName()
        }
    }

    private fun refreshHeader() {
        val d = draft ?: return
        // 标题栏区分"新建"与"编辑":同一个页面两种用途,标题得说清这次到底在干什么
        // (名字仍然不进标题栏 —— 太长会把右侧「保存」挤走,名字在下面「主题名称」那一行)
        mBinding.titleBar.setTitle(if (isNew) "新建主题" else "编辑主题")
        mBinding.tvType.text = d.type.label
        mBinding.tvName.text = if (d.name.isEmpty()) "未命名" else d.name
    }

    /** 把 25 个键的所有输入框刷成草稿里的值(同一个键可能有两行,一起刷) */
    private fun refreshAllValues() {
        val d = draft ?: return
        for (key in ThemeSpec.all()) {
            val v = d.color(key.key)
            for (row in rows[key.key].orEmpty()) {
                if (row.input.text.toString() != v) {
                    row.input.setText(v)
                }
            }
            updateSwatch(key)
        }
        refreshBackground()
    }

    /** 收集输入框 → 草稿,同时校验;返回第一个不合法项的提示文案 */
    private fun collectValues(): String? {
        val d = draft ?: return "主题数据为空"
        var firstBad: ThemeKey? = null
        var badText = ""
        for (key in ThemeSpec.all()) {
            val row = rows[key.key]?.firstOrNull() ?: continue
            val text = row.input.text.toString().trim()
            if (!isValid(key, text)) {
                if (firstBad == null) {
                    firstBad = key
                    badText = when {
                        key.isAlpha -> "「${key.label}」请填 0-100 的整数"
                        // 只允许纯色的键(「文字主色」)不给透明度写法
                        key.opaqueOnly -> "「${key.label}」只收纯色,请填 #RRGGBB(不带透明度)"
                        else -> "「${key.label}」请填 #RRGGBB 或 #AARRGGBB"
                    }
                }
                continue
            }
            d.setColor(key.key, normalize(key, text))
        }
        val bad = firstBad ?: return null
        rows[bad.key]?.firstOrNull()?.input?.requestFocus()
        return badText
    }

    private fun onSaveClicked() {
        val err = collectValues()
        if (err != null) {
            AppBubble.toast(err)
            return
        }
        val d = draft ?: return
        if (d.name.isEmpty()) {
            askName()
        } else {
            saveThenFinish()
        }
    }

    /** 命名(保存时还没名字 / 点标题改名都走这里);重名不放过、可取消 */
    private fun askName() {
        val d = draft ?: return
        ThemeNameDialog.show(this, d.name, if (isNew) "" else d.id, object : ThemeNameDialog.Listener {
            override fun onConfirm(name: String): Boolean {
                d.name = name
                refreshHeader()
                return true
            }

            override fun onCancel() {
                // 取消命名 = 不保存(新建流程整个放弃);已有主题则保持原名不动
            }
        })
    }

    private fun saveThenFinish() {
        val d = draft ?: return
        showLoadingDialog("正在保存…")
        HeavyTaskUtil.getSerialExecutorService().execute {
            val result = ThemeStore.save(d)
            mBinding.root.post {
                dismissLoadingDialog()
                if (!result.ok()) {
                    AppBubble.toast(result.error ?: "保存失败")
                    return@post
                }
                // **保存即选中**:用户进这一页就是在调"我这一套",保存完当然要用它。
                // 只落盘不选中,关闭主题弹窗后只会白重启一次、界面一点不变 ——
                // 这正是一开始"我自定义的主题没效果"的来源(用户口径)。
                // 选择关系直接落库(草稿在弹窗那边,编辑页够不着);弹窗关闭时按新选择重启。
                runCatching {
                    val s = ThemeStore.selection()
                    if (s.customId != result.id) {
                        s.customId = result.id
                        s.commit()
                    }
                }
                AppBubble.toast("已保存并使用,关闭主题弹窗后重启生效")
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun onDeleteClicked() {
        val d = draft ?: return
        ConfirmDialog.showDanger(this, "删除主题",
            "确定删除「${d.name}」吗?它带的背景图(若没有别的主题在用)也会一起清理。", "删除") {
            showLoadingDialog("正在删除…")
            HeavyTaskUtil.getSerialExecutorService().execute {
                val ok = ThemeStore.delete(d.id)
                mBinding.root.post {
                    dismissLoadingDialog()
                    if (!ok) {
                        AppBubble.toast("删除失败,请稍后再试")
                        return@post
                    }
                    AppBubble.toast("已删除")
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 导出 / 导入
    // ------------------------------------------------------------------

    private fun doExport() {
        val d = draft ?: return
        showLoadingDialog("正在导出…")
        HeavyTaskUtil.getSerialExecutorService().execute {
            val result = ThemeArchive.exportTo(d, cacheDir)
            mBinding.root.post {
                dismissLoadingDialog()
                if (!result.ok()) {
                    AppBubble.toast(result.error ?: "导出失败")
                    return@post
                }
                exportedFile = result.file
                if (!result.withImage) {
                    // 没有背景图 = 一个纯文本 JSON:顺手复制到剪贴板,微信里直接粘贴就能发出去
                    ClipboardUtils.copyText(readText(result.file!!))
                    AppBubble.toast("主题 JSON 已复制到剪贴板")
                }
                startExportPicker(result.file!!, result.withImage)
            }
        }
    }

    private fun startExportPicker(file: File, withImage: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = if (withImage) "application/zip" else "application/json"
            intent.putExtra(Intent.EXTRA_TITLE, file.name)
            startActivityForResult(intent, REQ_EXPORT_FILE)
        } catch (t: Throwable) {
            AppBubble.toast("没有可用的文件保存入口")
        }
    }

    private fun copyExportTo(uri: Uri) {
        val src = exportedFile ?: return
        HeavyTaskUtil.getSerialExecutorService().execute {
            var ok = false
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                    ok = true
                }
            } catch (t: Throwable) {
                ok = false
            }
            mBinding.root.post {
                AppBubble.toast(if (ok) "已导出:${src.name}" else "写入失败,换个位置再试")
            }
        }
    }

    private fun showImportChooser() {
        val dialog = SelectDialog<String>(this)
        dialog.setTip("导入主题")
        dialog.setAdapter(object : SelectDialogInterface<String?> {
            override fun click(value: String?, pos: Int) {
                if (pos == 0) pickImportFile() else importFromClipboard()
            }

            override fun getDisplay(value: String?): String = value ?: ""
        }, SelectDialogAdapter.stringDiff, arrayListOf("从文件导入(.json / .zip)", "从剪贴板导入(JSON 文本)"), 0)
        dialog.show()
    }

    private fun pickImportFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(intent, REQ_IMPORT_FILE)
        } catch (t: Throwable) {
            AppBubble.toast("没有可用的文件选择器")
        }
    }

    private fun importFromFile(uri: Uri) {
        showLoadingDialog("正在导入主题…")
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            // 先落到缓存临时文件再解析(SAF 的 Uri 不适合直接当文件用)
            val tmp = File(cacheDir, "theme_import_${System.currentTimeMillis()}.bin")
            var error: String? = null
            var def: ThemeDef? = null
            var warnings: List<String> = emptyList()
            try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    error = "文件打不开"
                } else {
                    input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
                    val r = ThemeArchive.importFrom(tmp)
                    if (!r.ok()) {
                        error = r.error
                    } else {
                        def = r.def
                        warnings = r.warnings
                    }
                }
            } catch (t: Throwable) {
                error = "文件读不出来"
            } finally {
                tmp.delete()
            }
            val finalError = error
            val finalDef = def
            val finalWarnings = warnings
            mBinding.root.post {
                dismissLoadingDialog()
                if (finalDef == null) {
                    AppBubble.toast(finalError ?: "导入失败")
                    return@post
                }
                applyImported(finalDef, finalWarnings)
            }
        }
    }

    private fun importFromClipboard() {
        val text = ClipboardUtils.getText()?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            AppBubble.toast("剪贴板为空")
            return
        }
        val r = ThemeArchive.importText(text)
        if (!r.ok()) {
            AppBubble.toast(r.error ?: "导入失败")
            return
        }
        applyImported(r.def, r.warnings)
    }

    /**
     * 导入结果灌进当前草稿(而不是直接落库):用户还能就地检查/改一改再保存,
     * 也避免"选错了包直接把已有主题覆盖掉"。名字重复时让用户另起一个。
     */
    private fun applyImported(def: ThemeDef, warnings: List<String>) {
        def.materialize(ThemeStore.builtinInput(def.type))
        def.id = "" // 导入一律新建
        def.createdAt = 0L
        draft = def
        isNew = true // 之后按"新建"处理:底部是 取消 + 导入,保存时若重名会拦
        typeBaseline = LinkedHashMap(def.colors())
        bindActions()
        refreshHeader()
        refreshAllValues()
        if (warnings.isNotEmpty()) {
            AppBubble.toastLong("已导入:" + warnings.first())
        } else {
            AppBubble.toast("已导入,检查后点保存")
        }
        // 名字重复(或为空)时立刻让用户定名,免得保存时才发现
        if (def.name.isEmpty() || ThemeStore.checkName(def.name, "") != null) {
            ThemeNameDialog.show(this, def.name, "", object : ThemeNameDialog.Listener {
                override fun onConfirm(name: String): Boolean {
                    def.name = name
                    refreshHeader()
                    return true
                }

                override fun onCancel() {
                    // 不定名也可以:保存时会再问一次
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // 选图 / 选文件回调
    // ------------------------------------------------------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        // 背景是走「设置背景图」页的主题模式:结果**只带 extras、没有 data Uri**,
        // 所以不能统一先判 uri(之前就是这里提前 return,导致选完图片没生效)
        if (requestCode == REQ_PICK_BG) {
            applyBackgroundResult(data)
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_FILE -> copyExportTo(uri)
            REQ_IMPORT_FILE -> importFromFile(uri)
        }
    }

    private fun readText(f: File): String = try {
        f.readText()
    } catch (t: Throwable) {
        ""
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    /** EditText 没有公开的 maxLength 设置(setFilters 更啰嗦,这里给透明度输入用) */
    private fun EditText.maxLengthCompat(max: Int) {
        filters = arrayOf(android.text.InputFilter.LengthFilter(max))
    }

    override fun onDestroy() {
        super.onDestroy()
        rows.clear()
    }
}
