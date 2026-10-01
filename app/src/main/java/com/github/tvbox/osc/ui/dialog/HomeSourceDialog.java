package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 首页选择源面板，宿主注入源快照、选择动作和订阅管理导航。 */
public final class HomeSourceDialog extends SelectDialog<HomeSourceChoices.Row> {
    public interface OnSourceSelected {
        void onSelected(String key);
    }

    private final List<HomeSourceChoices.Row> rows;
    private final String selectedKey;
    private final OnSourceSelected onSourceSelected;
    private final Runnable openSubscriptions;
    private int filterGeneration;

    public HomeSourceDialog(Context context, List<HomeSourceChoices.Row> rows, String selectedKey,
                            OnSourceSelected onSourceSelected, Runnable openSubscriptions) {
        super(context, R.layout.dialog_home_source);
        this.rows = new ArrayList<>(rows);
        this.selectedKey = selectedKey;
        this.onSourceSelected = onSourceSelected;
        this.openSubscriptions = openSubscriptions;
        setTip(context.getString(R.string.home_source_title));
        setListLayoutManager(new V7GridLayoutManager(context, 2));
        setDynamicHeightByScreen(true);
        setAutoFocusEditText(false);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 用户手动点搜索框时，也不让 XPopup 将整张面板向上平移。
        if (popupInfo != null) popupInfo.isMoveUpToKeyboard = false;
        applyRowBackground(R.id.source_search_box);
        applyRowBackground(R.id.btn_switch_subscription);
        // 保留面板与筛选状态，订阅未变化时从管理页返回仍可继续选择。
        findViewById(R.id.btn_switch_subscription).setOnClickListener(
                view -> openSubscriptions.run());
        TvRecyclerView list = findViewById(R.id.list);
        SourceAdapter adapter = new SourceAdapter();
        list.setAdapter(adapter);
        EditText search = findViewById(R.id.et_source_filter);
        TextView empty = findViewById(R.id.tv_source_empty);
        showMatches(adapter, list, empty, "", true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                showMatches(adapter, list, empty, s.toString(), false);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        clampListHeightToFit();
    }

    private void applyRowBackground(int viewId) {
        View view = findViewById(viewId);
        view.setBackground(SelectDialogAdapter.createRowBackground(
                view.getContext(), view.getBackground()));
    }

    private void showMatches(SourceAdapter adapter, TvRecyclerView list, TextView empty,
                             String query, boolean initial) {
        List<HomeSourceChoices.Row> matches = HomeSourceChoices.filter(rows, query);
        empty.setText(rows.isEmpty() ? R.string.home_source_none : R.string.home_source_no_match);
        empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
        int generation = ++filterGeneration;
        adapter.submitList(matches, () -> {
            clampListHeightToFit();
            if (initial && generation == filterGeneration) {
                int position = HomeSourceChoices.selectedIndex(matches, selectedKey);
                if (position >= 0) list.scrollToPosition(position);
            }
        });
    }

    private final class SourceAdapter extends ListAdapter<HomeSourceChoices.Row, SourceHolder> {
        SourceAdapter() {
            super(new DiffUtil.ItemCallback<HomeSourceChoices.Row>() {
                @Override public boolean areItemsTheSame(@NonNull HomeSourceChoices.Row oldItem,
                                                         @NonNull HomeSourceChoices.Row newItem) {
                    return Objects.equals(oldItem.key, newItem.key);
                }
                @Override public boolean areContentsTheSame(@NonNull HomeSourceChoices.Row oldItem,
                                                            @NonNull HomeSourceChoices.Row newItem) {
                    return oldItem.name.equals(newItem.name);
                }
            });
        }

        @NonNull @Override
        public SourceHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.item_dialog_select, parent, false);
            view.setBackground(SelectDialogAdapter.createRowBackground(
                    view.getContext(), view.getBackground()));
            SourceHolder holder = new SourceHolder(view);
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            view.setMinimumHeight(Math.round(44 * parent.getResources().getDisplayMetrics().density));
            view.setOnClickListener(clicked -> {
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                HomeSourceChoices.Row row = getItem(position);
                if (!Objects.equals(row.key, selectedKey)) {
                    dismissWith(() -> onSourceSelected.onSelected(row.key));
                }
            });
            return holder;
        }

        @Override public void onBindViewHolder(@NonNull SourceHolder holder, int position) {
            HomeSourceChoices.Row row = getItem(position);
            holder.name.setText(row.name);
            holder.name.setChecked(Objects.equals(row.key, selectedKey));
            holder.itemView.findViewById(R.id.tv_markers).setVisibility(View.GONE);
        }
    }

    private static final class SourceHolder extends RecyclerView.ViewHolder {
        final MaterialCheckBox name;
        SourceHolder(View view) {
            super(view);
            name = view.findViewById(R.id.tvName);
        }
    }
}
