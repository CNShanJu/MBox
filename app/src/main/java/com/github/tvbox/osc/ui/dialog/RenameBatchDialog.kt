package com.github.tvbox.osc.ui.dialog

import android.content.Context
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogInputBatchNameBinding
import com.github.tvbox.osc.util.AppBubble

/**
 * 批次重命名弹窗(订阅管理页批次组头 → 重命名):
 * 预填当前批次名,确认后由页面把组内所有成员的 batchName 一并改写。
 *
 * 纯 UI:不落库、不碰订阅列表,只把最终名称回传给调用方。
 * 名称是组头的唯一展示字段,空名会让组头变成一行空白,所以这里直接拦掉(不交给调用方判)。
 */
class RenameBatchDialog(
    context: Context,
    private val defaultName: String = "",
    private val onConfirm: (name: String) -> Unit
) : AppCenterPopupView(context) {

    private lateinit var binding: DialogInputBatchNameBinding

    override fun getImplLayoutId(): Int = R.layout.dialog_input_batch_name

    override fun onCreate() {
        super.onCreate()
        binding = DialogInputBatchNameBinding.bind(popupImplView)
        // 光标落在末尾:改名场景通常是"在原名基础上修几个字",不是整体重输
        binding.etName.setText(defaultName)
        binding.etName.setSelection(defaultName.length)
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                AppBubble.toast("请输入批次名称")
                return@setOnClickListener
            }
            onConfirm(name)
            dismiss()
        }
    }
}
