package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.CheckboxSearchAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

/**
 * 指定搜索源(过滤)多选弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new SearchCheckboxDialog(ctx, sources, checked)} + {@code show()};
 * 关闭时保存勾选(经 XPopupCallback.onDismiss,等价原 dismiss 覆写语义)。
 */
public class SearchCheckboxDialog extends AppBottomPopupView {

    private RecyclerView mGridView;
    private CheckboxSearchAdapter checkboxSearchAdapter;
    private final List<SourceBean> mSourceList;
    TextView checkAll;
    TextView clearAll;

    public HashMap<String, String> mCheckSourcees;

    public SearchCheckboxDialog(@NonNull @NotNull Context context, List<SourceBean> sourceList, HashMap<String, String> checkedSources) {
        super(context);
        mSourceList = sourceList;
        mCheckSourcees = checkedSources;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_checkbox_search;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        initView();
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
                    .setPopupCallback(new com.lxj.xpopup.interfaces.XPopupCallback() {
                        @Override public void onCreated(BasePopupView v) { }
                        @Override public void beforeShow(BasePopupView v) { }
                        @Override public void onShow(BasePopupView v) { }
                        @Override public void onDismiss(BasePopupView v) { saveChecked(); }
                        @Override public void beforeDismiss(BasePopupView v) { }
                        @Override public boolean onBackPressed(BasePopupView v) { return false; }
                        @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                        @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                        @Override public void onClickOutside(BasePopupView v) { }
                    })
                    .asCustom(this).show();
        }
        return super.show();
    }

    /** 关闭时保存勾选源(等价原 dismiss() 覆写语义) */
    private void saveChecked() {
        if (checkboxSearchAdapter != null) {
            checkboxSearchAdapter.setMCheckedSources();
        }
    }

    protected void initView() {
        mGridView = findViewById(R.id.mGridView);
        checkAll = findViewById(R.id.checkAll);
        clearAll = findViewById(R.id.clearAll);
        checkboxSearchAdapter = new CheckboxSearchAdapter(new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }

            @Override
            public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getName().equals(newItem.getName());
            }
        });
        mGridView.setHasFixedSize(true);

        mGridView.setLayoutManager(new V7GridLayoutManager(getContext(), 2));
        View root = findViewById(R.id.root);
        ViewGroup.LayoutParams clp = root.getLayoutParams();
        // 底部弹窗内容宽度撑满弹窗容器(XPopup 已按底部浮层管理宽度),这里按内容排版即可

        mGridView.setAdapter(checkboxSearchAdapter);
        checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
        int pos = 0;
        if (mSourceList != null && mCheckSourcees != null) {
            for (int i = 0; i < mSourceList.size(); i++) {
                String key = mSourceList.get(i).getKey();
                if (mCheckSourcees.containsKey(key)) {
                    pos = i;
                    break;
                }
            }
        }
        final int scrollPosition = pos;
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                mGridView.smoothScrollToPosition(scrollPosition);
            }
        });
        checkAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                mCheckSourcees = new HashMap<>();
                assert mSourceList != null;
                for (SourceBean sourceBean : mSourceList) {
                    mCheckSourcees.put(sourceBean.getKey(), "1");
                }
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
            }
        });
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                mCheckSourcees = new HashMap<>();
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees);
            }
        });
    }
}
