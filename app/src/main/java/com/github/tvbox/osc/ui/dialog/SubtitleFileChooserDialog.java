package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 本地字幕文件选择弹窗(自研文件浏览器)。
 * <p>替代已不可用的 {@code com.github.hedzr:android-file-chooser}(ChooserDialog):
 * 该库经反射访问隐藏 API {@code StorageVolume.getPath()},在 Android 11+/targetSdk 34 被拒
 * (见日志 NoSuchMethodException),导致本地字幕选择失效。
 * 本实现只用标准 {@link File} API 列出目录/字幕文件并回传绝对路径,不依赖隐藏 API;
 * 分类(目录优先 + 名称排序)、后缀过滤、上级导航、越界保护均在应用内完成。
 * <p>外部用法:{@code new SubtitleFileChooserDialog(ctx, startPath, listener)} + {@code show()};
 * 目录点击进入,字母字幕文件点击即回传路径并关闭;BACK 键先返上级、到存储根后关闭。
 */
public class SubtitleFileChooserDialog extends AppCenterPopupView {

    /** 支持的本地字幕后缀(与原 ChooserDialog 的 withFilter(...,"srt","ass","scc","stl","ttml") 一致) */
    public static final List<String> SUBTITLE_EXTS =
            Arrays.asList("srt", "ass", "scc", "stl", "ttml");

    private final String mStartPath;
    private final OnFileChosenListener mListener;

    private File mRootDir;          // 不允许越过的顶层目录(缺省为外部存储根)
    private File mCurrentDir;       // 当前浏览目录
    private final List<File> mEntries = new ArrayList<>();
    private EmitterAdapter mAdapter;
    private TextView mPathView;
    private View mUpView;

    /** 选中字幕文件回调(携绝对路径) */
    public interface OnFileChosenListener {
        void onChosen(String path);
    }

    public SubtitleFileChooserDialog(@NonNull @NotNull Context context,
                                     String startPath,
                                     OnFileChosenListener listener) {
        super(context);
        mStartPath = startPath;
        mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_subtitle_file_chooser;
    }

    /** 列表自带滚动:超高时由列表自滚,不整卡包裹 */
    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mPathView = findViewById(R.id.path);
        mUpView = findViewById(R.id.tv_up);

        findViewById(R.id.iv_close).setOnClickListener(v -> dismiss());
        mUpView.setOnClickListener(v -> goParent());

        TvRecyclerView list = findViewById(R.id.list);
        mAdapter = new EmitterAdapter();
        list.setAdapter(mAdapter);
        list.setSelectedPosition(-1);

        // 起始目录(缺省外部存储根),并设定不可越过的顶层
        File start = TextUtils.isEmpty(mStartPath) ? null : new File(mStartPath);
        if (start == null || !start.isDirectory()) {
            start = Environment.getExternalStorageDirectory();
        }
        if (start == null || !start.isDirectory()) {
            AppBubble.toast("未找到可访问的存储目录");
            dismiss();
            return;
        }
        mRootDir = Environment.getExternalStorageDirectory();
        if (mRootDir == null || !mRootDir.isDirectory()) {
            mRootDir = start;
        }
        navigateTo(start);
    }

    /** BACK 键:先返上级,到存储根则交回关闭 */
    @Override
    protected boolean onBackPressed() {
        if (goParent()) {
            return true;
        }
        return false;
    }

    /** 返回上级;已在顶层或上级不可读时返回 false(表示应关闭) */
    private boolean goParent() {
        if (mCurrentDir == null) {
            return false;
        }
        File parent = mCurrentDir.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canRead()) {
            return false;
        }
        // 不允许越过存储根(避免展示 /storage 之类系统目录,误导用户)
        String root = mRootDir == null ? null : mRootDir.getAbsolutePath();
        if (TextUtils.isEmpty(root) || !parent.getAbsolutePath().startsWith(root)) {
            return false;
        }
        navigateTo(parent);
        return true;
    }

    private void navigateTo(final File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            AppBubble.toast("无法访问该目录");
            return;
        }
        mCurrentDir = dir;
        mPathView.setText(dir.getAbsolutePath());
        mUpView.setVisibility(canGoParent() ? View.VISIBLE : View.GONE);

        mEntries.clear();
        File[] children = dir.listFiles();
        if (children != null) {
            List<File> dirs = new ArrayList<>();
            List<File> files = new ArrayList<>();
            for (File f : children) {
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (isSubtitleFile(f)) {
                    files.add(f);
                }
            }
            sort(dirs);
            sort(files);
            mEntries.addAll(dirs);
            mEntries.addAll(files);
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        if (mEntries.isEmpty()) {
            // 空目录给个提示(不影响继续返回上级)
            AppBubble.toast("无字幕文件");
        }
        TvRecyclerView list = findViewById(R.id.list);
        if (list != null) {
            list.post(() -> list.smoothScrollToPosition(0));
        }
    }

    private boolean canGoParent() {
        if (mCurrentDir == null || mRootDir == null) {
            return false;
        }
        File parent = mCurrentDir.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canRead()) {
            return false;
        }
        return parent.getAbsolutePath().startsWith(mRootDir.getAbsolutePath());
    }

    private static boolean isSubtitleFile(File f) {
        String name = f.getName();
        int idx = name.lastIndexOf('.');
        if (idx <= 0 || idx == name.length() - 1) {
            return false;
        }
        return SUBTITLE_EXTS.contains(name.substring(idx + 1).toLowerCase(Locale.ROOT));
    }

    private static void sort(List<File> list) {
        list.sort((a, b) -> {
            String na = a.getName().toLowerCase(Locale.ROOT);
            String nb = b.getName().toLowerCase(Locale.ROOT);
            return na.compareTo(nb);
        });
    }

    /** 兼容旧调用点:popupInfo 未绑定时经 Builder 绑定后展示(与 SubtitleDialog 一致,跟随主题) */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isAppDarkTheme())
                    .asCustom(this).show();
        }
        return super.show();
    }

    // ── 列表适配器:目录优先,文件按后缀过滤,点击目录进入 / 点击文件回传 ──

    private class EmitterAdapter extends RecyclerView.Adapter<EmitterAdapter.EmitterViewHolder> {

        @NonNull
        @NotNull
        @Override
        public EmitterViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dialog_subtitle_file_chooser, parent, false);
            return new EmitterViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull @NotNull EmitterViewHolder holder, int position) {
            final File f = mEntries.get(position);
            boolean isDir = f.isDirectory();
            String name = f.getName();
            holder.name.setText(isDir ? name + " /" : name);
            holder.itemView.setOnClickListener(v -> {
                if (!isSubtitleFile(f) && !f.isDirectory()) {
                    return;
                }
                if (f.isDirectory()) {
                    navigateTo(f);
                } else {
                    String path = f.getAbsolutePath();
                    dismiss();
                    if (mListener != null) {
                        mListener.onChosen(path);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        class EmitterViewHolder extends RecyclerView.ViewHolder {
            final TextView name;

            EmitterViewHolder(@NonNull @NotNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tv_name);
            }
        }
    }
}
