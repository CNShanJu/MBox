package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.ui.adapter.QuickSearchAdapter;
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 其它数据源相关搜索弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new QuickSearchDialog(ctx)} + {@code setOnDismissListener} + {@code show()};
 * 数据不再经 EventBus,由宿主在弹窗生命周期内直喂:
 * {@link #appendResults(List)} 追加累计结果、{@link #updateWords(List)} 刷新词表;
 * 点击行为经 {@link #setHost(Host)} 回调宿主(选中影片/切换搜索词),宿主负责后续编排。
 */
public class QuickSearchDialog extends AppBottomPopupView {

    /** 宿主回调:弹窗点击行为(原 EventBus TYPE_QUICK_SEARCH_SELECT / WORD_CHANGE 直调化) */
    public interface Host {
        /** 选中某条结果:宿主加载该影片详情 */
        void onVideoSelected(Movie.Video video);

        /** 点击某搜索词:宿主清空旧结果并按新词重新编排 */
        void onWordChange(String word);
    }

    private SearchWordAdapter searchWordAdapter;
    private QuickSearchAdapter searchAdapter;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewWord;
    List<Movie.Video> results = new ArrayList<>();

    private Host host;
    private DialogInterface.OnDismissListener externalDismissListener;

    public QuickSearchDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    /** 绑定宿主回调(点击行为;每次 show 前设置) */
    public void setHost(Host host) {
        this.host = host;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_quick_search;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        initViews();
    }

    /** 宿主追加一批累计结果(原 EventBus TYPE_QUICK_SEARCH 消费) */
    public void appendResults(List<Movie.Video> data) {
        if (data == null) {
            return;
        }
        results.addAll(data);
        if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
    }

    /** 宿主刷新词表(原 EventBus TYPE_QUICK_SEARCH_WORD 消费) */
    public void updateWords(List<String> words) {
        if (words == null) {
            return;
        }
        if (searchWordAdapter != null) searchWordAdapter.setNewData(words);
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
                    .setPopupCallback(new XPopupCallback() {
                        @Override public void onCreated(BasePopupView v) { }
                        @Override public void beforeShow(BasePopupView v) { }
                        @Override public void onShow(BasePopupView v) { }
                        @Override public void onDismiss(BasePopupView v) {
                            DialogInterface.OnDismissListener l = externalDismissListener;
                            externalDismissListener = null;
                            if (l != null) l.onDismiss(null);
                        }
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

    /** 兼容旧 Dialog API：dismiss 后回调（同一次展示只触发一次） */
    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.externalDismissListener = listener;
    }

    private void initViews() {
        mGridView = findViewById(R.id.mGridView);
        searchAdapter = new QuickSearchAdapter();
        mGridView.setHasFixedSize(true);
        // lite
        mGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        // with preview
        // mGridView.setLayoutManager(new V7GridLayoutManager(getContext(), 3));
        mGridView.setAdapter(searchAdapter);
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Movie.Video video = searchAdapter.getData().get(position);
                if (host != null) host.onVideoSelected(video);
                dismiss();
            }
        });

        searchAdapter.setNewData(results);
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewWord.setAdapter(searchWordAdapter);
        mGridViewWord.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
        searchWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                searchAdapter.getData().clear();
                searchAdapter.notifyDataSetChanged();
                if (host != null) host.onWordChange(searchWordAdapter.getData().get(position));
            }
        });
        searchWordAdapter.setNewData(new ArrayList<>());
    }
}
