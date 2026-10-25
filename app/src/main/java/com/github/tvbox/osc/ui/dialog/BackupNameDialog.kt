package com.github.tvbox.osc.ui.dialog

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.transfer.BackupNaming
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup

/**
 * 备份命名弹窗，两个场景共用一套输入体验：
 * <ul>
 *   <li><b>新建备份</b>：可以手输名字，也可以勾「使用系统时间」；</li>
 *   <li><b>修改名称</b>：只存展示名，勾选项隐藏（改名跟时间没关系）。</li>
 * </ul>
 *
 * <p>名字给不出来（没输又没勾）时<b>不关闭弹窗</b>，就地提示 —— 备份是低频操作，
 * 在这里一次定好名字，比事后发现列表里一堆时间串再回头改要省事。
 */
class BackupNameDialog @JvmOverloads constructor(
    context: Context,
    private val title: String = "新建备份",
    private val allowTimestamp: Boolean = true,
    private val initialName: String = "",
    private val confirmText: String = "开始备份"
) : AppCenterPopupView(context) {

    fun interface Listener {
        /**
         * @param name 已 trim 且非空的名字
         * @param useTimestamp 是否勾选了「使用系统时间」（此时名字就是那个时间串）
         */
        fun onConfirm(name: String, useTimestamp: Boolean)
    }

    private var listener: Listener? = null
    private lateinit var input: EditText
    private lateinit var timestamp: CheckBox
    private lateinit var error: TextView
    /** 勾选时间时暂存手输的名字，取消勾选时还原，别让用户白打一遍 */
    private var typed = ""

    override fun getImplLayoutId(): Int = R.layout.dialog_backup_name

    override fun onCreate() {
        super.onCreate()
        input = findViewById(R.id.et_name)
        timestamp = findViewById(R.id.cb_timestamp)
        error = findViewById(R.id.tv_error)
        findViewById<TextView>(R.id.tv_title).text = title
        findViewById<TextView>(R.id.btn_ok).text = confirmText
        timestamp.visibility = if (allowTimestamp) View.VISIBLE else View.GONE
        if (initialName.isNotEmpty()) {
            input.setText(initialName)
            input.setSelection(initialName.length)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // 用户已经在改了，红字留着只会让人以为还没改对
                if (error.visibility == View.VISIBLE) error.visibility = View.GONE
            }
        })

        timestamp.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                typed = input.text.toString()
                input.setText(BackupNaming.timeName(System.currentTimeMillis()))
                input.isEnabled = false
            } else {
                input.isEnabled = true
                input.setText(typed)
                input.setSelection(input.text.length)
            }
            if (error.visibility == View.VISIBLE) error.visibility = View.GONE
        }

        findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_ok).setOnClickListener { submit() }
    }

    private fun submit() {
        val useTimestamp = allowTimestamp && timestamp.isChecked
        // 勾选时按"点下去的那一刻"取时间，而不是勾选那一刻，避免停留几分钟后名字偏旧
        val name = if (useTimestamp) BackupNaming.timeName(System.currentTimeMillis())
        else BackupNaming.sanitize(input.text.toString())
        if (name.isEmpty()) {
            error.setText("请输入备份名称，或勾选使用系统时间")
            error.visibility = View.VISIBLE
            input.requestFocus()
            return
        }
        listener?.onConfirm(name, useTimestamp)
        dismiss()
    }

    companion object {
        /** 新建备份：可输入名字，也可勾选系统时间。 */
        @JvmStatic
        fun showCreate(context: Context, listener: Listener) {
            attach(BackupNameDialog(context), context, listener)
        }

        /** 修改备份名称：只改展示名，不动文件与内容。 */
        @JvmStatic
        fun showRename(context: Context, currentName: String, listener: Listener) {
            attach(BackupNameDialog(context, title = "修改备份名称",
                allowTimestamp = false, initialName = currentName, confirmText = "保存"),
                context, listener)
        }

        private fun attach(dialog: BackupNameDialog, context: Context, listener: Listener) {
            dialog.listener = listener
            XPopup.Builder(context)
                .isDarkTheme(Utils.isAppDarkTheme())
                .asCustom(dialog)
                .show()
        }
    }
}
