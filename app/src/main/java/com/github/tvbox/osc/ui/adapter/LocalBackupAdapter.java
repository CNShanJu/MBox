package com.github.tvbox.osc.ui.adapter;

import android.content.res.ColorStateList;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.transfer.LocalBackupEntry;

import java.util.ArrayList;

/** Dedicated typed rows for user backups and protected system recovery backups. */
public final class LocalBackupAdapter extends BaseQuickAdapter<LocalBackupEntry, BaseViewHolder> {
    public LocalBackupAdapter() {
        super(R.layout.item_local_backup, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, LocalBackupEntry entry) {
        boolean system = entry.isSystem();
        helper.setText(R.id.tvName, system ? "系统临时备份 · " + entry.name() : entry.name());
        ImageView action = helper.getView(R.id.ivAction);
        action.setImageResource(system ? R.drawable.ic_backup_info : R.drawable.ic_delete);
        ImageViewCompat.setImageTintList(action, ColorStateList.valueOf(ContextCompat.getColor(
                action.getContext(), system ? R.color.text_highlight : R.color.text_danger)));
        action.setContentDescription(system ? "查看系统临时备份说明" : "删除备份");
        helper.addOnClickListener(R.id.tvName, R.id.ivAction);
    }
}
