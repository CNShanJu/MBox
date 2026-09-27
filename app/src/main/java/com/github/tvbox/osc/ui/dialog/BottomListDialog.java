package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogTitleListBinding;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration;

import java.util.List;

/**
 * 通用「底部列表选择」抽屉:标题 + 单选列表。
 *
 * <p><b>为什么要有它</b>(2026-09-27,用户口径"错误日志里的时间选择弹窗没走公共抽屉组件"):
 * 这类"从一列里选一个"的底部抽屉此前是裸用 {@code XPopup.asBottomList(...)} ——
 * 那是 XPopup 自带的 Material 列表:面板底、圆角、文字色自成一套,既不跟换肤,也不带全站抽屉
 * 那块面板底,于是同一个 App 里"抽屉"出现两种长相、圆角也对不上。本类把它收口成共用件:
 * <ul>
 *   <li>壳 = {@link AppBottomPopupView}(换肤后的 {@code bg_bottom_dialog} 面板底 + 主题圆角 +
 *       统一的最大高度分档 + 横屏限宽 + 毛玻璃),与其它底部抽屉完全同款;</li>
 *   <li>排版 = {@code dialog_title_list}(与接口历史抽屉 {@link ApiHistoryDialog} 同一份布局);</li>
 *   <li>行 = {@link SelectDialogAdapter}(与居中版选择弹窗 {@link SelectDialog} 同一套行样式)。</li>
 * </ul>
 *
 * <p>用法:{@code new BottomListDialog(ctx, "选择日期", labels, selectedIndex, (pos, text) -> {...}).show();}
 * 需要限高时用 {@code DialogCoordinator.bottom(ctx, dialog, heightPx)} 弹。
 */
public class BottomListDialog extends AppBottomPopupView {

    /** 选中一项后的回调(在弹窗关闭后触发,position 是原列表下标) */
    public interface OnPickListener {
        void onPick(int position, String text);
    }

    private final String mTitle;
    private final List<String> mItems;
    private final int mSelected;
    private final OnPickListener mListener;

    public BottomListDialog(@NonNull Context context, String title, List<String> items,
                            int selectedIndex, OnPickListener listener) {
        super(context);
        mTitle = title;
        mItems = items;
        mSelected = selectedIndex;
        mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_title_list;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogTitleListBinding binding = DialogTitleListBinding.bind(getPopupImplView());
        binding.title.setText(mTitle == null ? "" : mTitle);
        // "使用帮助"入口是接口历史抽屉专有的,通用列表不显示(布局默认可见,这里显式关掉)
        binding.ivUseTip.setVisibility(View.GONE);

        binding.rv.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.rv.addItemDecoration(new LinearSpacingItemDecoration(20, true));

        final List<String> items = mItems;
        SelectDialogAdapter<String> adapter = new SelectDialogAdapter<>(
                new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        dismissWith(() -> {
                            if (mListener != null) mListener.onPick(pos, value);
                        });
                    }

                    @Override
                    public CharSequence getDisplay(String val) {
                        return val == null ? "" : val;
                    }
                }, SelectDialogAdapter.stringDiff);
        binding.rv.setAdapter(adapter);
        if (items != null) {
            adapter.setData(items, Math.max(0, Math.min(mSelected, items.size() - 1)));
        }
    }
}
