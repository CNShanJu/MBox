package com.github.tvbox.osc.ui.adapter;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.transfer.LocalBackupEntry;

import java.util.ArrayList;
import java.util.List;

/** Dedicated typed rows for user backups and protected system recovery backups. */
public final class LocalBackupAdapter extends BaseQuickAdapter<Object, BaseViewHolder> {
    /**
     * 「使用外部备份数据」动作行的标记:它必须排在备份结果最后面,所以是一个真正的列表项
     * (跟着列表滚动、空列表时就是唯一一行),只是不承载备份数据 —— 点击交给宿主页选文件。
     */
    private static final Object EXTERNAL_ROW = new Object();

    public LocalBackupAdapter() {
        super(R.layout.item_local_backup, new ArrayList<>());
    }

    public static boolean isExternalRow(Object item) {
        return item == EXTERNAL_ROW;
    }

    /** 备份行在前,动作行固定排最后;没有系统文件选择入口时不追加。 */
    public void submit(List<LocalBackupEntry> entries, boolean withExternalRow) {
        List<Object> rows = new ArrayList<>();
        if (entries != null) rows.addAll(entries);
        if (withExternalRow) rows.add(EXTERNAL_ROW);
        setNewData(rows);
    }

    @Override
    protected void convert(BaseViewHolder helper, Object item) {
        // 显式用 setVisibility,别碰库的 setGone/setVisible —— 后者的布尔语义是反的
        // (setGone(id, true) 实为 VISIBLE),视图复用时会让动作行/备份行的图标张冠李戴。
        TextView name = helper.getView(R.id.tvName);
        ImageView action = helper.getView(R.id.ivAction);
        if (isExternalRow(item)) {
            // 动作行:无任何图标,纯文字且水平居中
            action.setVisibility(View.GONE);
            name.setText("使用外部备份数据");
            name.setGravity(Gravity.CENTER);
            helper.itemView.setContentDescription("使用外部备份数据：从手机文件管理中选择 ZIP 备份并还原");
            return;
        }
        LocalBackupEntry entry = (LocalBackupEntry) item;
        boolean system = entry.isSystem();
        action.setVisibility(View.VISIBLE);
        // 视图复用:上一次可能是动作行,重心和整行描述都要复位
        name.setGravity(Gravity.CENTER_VERTICAL);
        helper.itemView.setContentDescription(null);
        name.setText(system ? "系统临时备份 · " + entry.name() : entry.name());
        action.setImageResource(system ? R.drawable.ic_backup_info : R.drawable.ic_delete);
        ImageViewCompat.setImageTintList(action, ColorStateList.valueOf(ContextCompat.getColor(
                action.getContext(), system ? R.color.text_highlight : R.color.text_danger)));
        action.setContentDescription(system ? "查看系统临时备份说明" : "删除备份");
        // 删除/说明键走子项点击(addOnClickListener 只 setClickable、不设 focusable,
        // 不会抢焦点、也不影响整行长按);文字与空白处还原走整行点击。
        helper.addOnClickListener(R.id.ivAction);
    }
}
