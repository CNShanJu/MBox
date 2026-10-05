package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;

import com.blankj.utilcode.util.KeyboardUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.SubtitleData;
import com.github.tvbox.osc.ui.adapter.SearchSubtitleAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SubtitleViewModel;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线字幕搜索弹窗（统一走 XPopup 居中弹窗 AppCenterPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new SearchSubtitleDialog(activity)} + {@code setSubtitleLoader} +
 * {@code setSearchWord} + {@code show()} / {@code dismiss()}。
 * 分页/zip 回退逻辑保留:返回键在 zip 预览态先回列表,否则关闭。
 */
public class SearchSubtitleDialog extends AppCenterPopupView {

    private Context mContext;
    private TvRecyclerView mGridView;
    private SearchSubtitleAdapter searchAdapter;

    private EditText subtitleSearchEt;
    private SubtitleLoader mSubtitleLoader;
    private ProgressBar loadingBar;
    private TextView emptyState;
    private SubtitleViewModel subtitleViewModel;
    private Observer<SubtitleData> searchObserver;
    private boolean requestedResults;
    private boolean dismissed;
    private int subtitleRequestEpoch;
    private int page = 1;
    private int maxPage = 5;
    private String searchWord = "";

    private List<Subtitle> zipSubtitles = new ArrayList<>();
    private boolean isSearchPag = true;

    private String pendingSearchWord; // setSearchWord 在 show 前调用时的暂存

    public SearchSubtitleDialog(@NonNull @NotNull Context context) {
        super(context);
        mContext = context;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_search_subtitle;
    }

    @Override
    protected boolean contentSelfScrollable() {
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        initView();
        initViewModel();
        if (pendingSearchWord != null) {
            applySearchWord(pendingSearchWord);
            pendingSearchWord = null;
        }
        fitResultsToDialogHeight();
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
                    .asCustom(this).show();
        }
        return super.show();
    }

    private void initView() {
        loadingBar = findViewById(R.id.loadingBar);
        emptyState = findViewById(R.id.emptyState);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setVisibility(View.GONE);
        findViewById(R.id.iv_close_search_subtitle).setOnClickListener(v -> dismiss());
        subtitleSearchEt = findViewById(R.id.input);
        findViewById(R.id.inputSubmit).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            String wd = subtitleSearchEt.getText().toString().trim();
            search(wd);
        });
        searchAdapter = new SearchSubtitleAdapter();
        mGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        mGridView.setAdapter(searchAdapter);
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Subtitle subtitle = searchAdapter.getData().get(position);
                //加载字幕
                if (mSubtitleLoader != null) {
                    if (subtitle.getIsZip()) {
                        isSearchPag = false;
                        loadingBar.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.GONE);
                        emptyState.setVisibility(View.GONE);
                        requestedResults = true;
                        if (subtitleViewModel != null) {
                            subtitleViewModel.getSearchResultSubtitleUrls(subtitle);
                        } else {
                            loadingBar.setVisibility(View.GONE);
                            showList();
                            AppBubble.toast("字幕搜索暂不可用");
                        }
                    } else {
                        if (TextUtils.isEmpty(subtitle.getUrl())) {
                            AppBubble.toast("url加载失败,请重新搜索");
                            return;
                        }
                        loadSubtitle(subtitle);
                    }
                }
            }
        });

        subtitleSearchEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String wd = subtitleSearchEt.getText().toString().trim();
                search(wd);
                return true;
            }
            return false;
        });
        searchAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                if (searchAdapter.getData().size() > 0 && searchAdapter.getData().get(0).getIsZip()) {
                    subtitleViewModel.searchResult(searchWord, page);
                }
            }
        }, mGridView);
        searchAdapter.setNewData(new ArrayList<>());
    }

    private void initViewModel() {
        if (!(mContext instanceof ViewModelStoreOwner) || !(mContext instanceof LifecycleOwner)) {
            return; // context 不是 Activity/LifecycleOwner 时降级(正常调用方都是 Activity)
        }
        subtitleViewModel = new ViewModelProvider((ViewModelStoreOwner) mContext).get(SubtitleViewModel.class);
        searchObserver = new Observer<SubtitleData>() {
            @Override
            public void onChanged(SubtitleData subtitleData) {
                if (dismissed || !isShow() || !requestedResults) return;
                if (subtitleData == null) {
                    loadingBar.setVisibility(View.GONE);
                    showEmpty("字幕搜索失败，请重试");
                    AppBubble.toast("字幕搜索失败，请重试");
                    return;
                }
                List<Subtitle> data = subtitleData.getSubtitleList();
                loadingBar.setVisibility(View.GONE);
                if (data == null) {
                    showEmpty("字幕搜索失败，请重试");
                    AppBubble.toast("字幕搜索失败，请重试");
                    return;
                }

                if (data.size() > 0) {
                    showList();
                    if (subtitleData.getIsZip()) {
                        if (subtitleData.getIsNew()) {
                            searchAdapter.setNewData(data);
                            zipSubtitles = new ArrayList<>(data);
                        } else {
                            searchAdapter.addData(data);
                            zipSubtitles.addAll(data);
                        }
                        page++;
                        if (page > maxPage) {
                            searchAdapter.loadMoreEnd();
                            searchAdapter.setEnableLoadMore(false);
                        } else {
                            searchAdapter.loadMoreComplete();
                            searchAdapter.setEnableLoadMore(true);
                        }
                    } else {
                        searchAdapter.loadMoreComplete();
                        searchAdapter.setNewData(data);
                        searchAdapter.setEnableLoadMore(false);
                    }
                } else {
                    if (subtitleData.getIsNew()) {
                        showEmpty("没有找到匹配的字幕，试试更短的片名");
                    } else {
                        showList();
                    }
                    if (page > maxPage) {
                        searchAdapter.loadMoreEnd();
                    } else {
                        searchAdapter.loadMoreComplete();
                    }
                    searchAdapter.setEnableLoadMore(false);
                }
            }
        };
        subtitleViewModel.searchResult.observe((LifecycleOwner) mContext, searchObserver);
    }

    private void loadSubtitle(Subtitle subtitle) {
        if (subtitleViewModel == null) {
            AppBubble.toast("字幕加载暂不可用");
            return;
        }
        final int requestEpoch = ++subtitleRequestEpoch;
        loadingBar.setVisibility(View.VISIBLE);
        mGridView.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        subtitleViewModel.getSubtitleUrl(subtitle, new SubtitleViewModel.SubtitleUrlCallback() {
            @Override
            public void onSuccess(String url) {
                if (dismissed || !isShow() || requestEpoch != subtitleRequestEpoch) return;
                Subtitle resolved = new Subtitle();
                resolved.setName(subtitle.getName());
                resolved.setUrl(url);
                resolved.setIsZip(false);
                mSubtitleLoader.loadSubtitle(resolved, new LoadResult() {
                    @Override
                    public void onLoaded() {
                        if (dismissed || !isShow() || requestEpoch != subtitleRequestEpoch) return;
                        AppBubble.toast("字幕已加载");
                        dismiss();
                    }

                    @Override
                    public void onFailed(String message) {
                        if (dismissed || !isShow() || requestEpoch != subtitleRequestEpoch) return;
                        loadingBar.setVisibility(View.GONE);
                        showList();
                        AppBubble.toast(message);
                    }
                });
            }

            @Override
            public void onError() {
                if (dismissed || !isShow() || requestEpoch != subtitleRequestEpoch) return;
                loadingBar.setVisibility(View.GONE);
                showList();
                AppBubble.toast("字幕地址获取失败，请重试或换一个字幕");
            }
        });
    }

    public void setSubtitleLoader(SubtitleLoader subtitleLoader) {
        mSubtitleLoader = subtitleLoader;
    }

    public interface SubtitleLoader {
        void loadSubtitle(Subtitle subtitle, LoadResult result);
    }

    public interface LoadResult {
        void onLoaded();

        void onFailed(String message);
    }

    private void showEmpty(String message) {
        emptyState.setText(message);
        emptyState.setVisibility(View.VISIBLE);
        mGridView.setVisibility(View.GONE);
    }

    private void showList() {
        emptyState.setVisibility(View.GONE);
        mGridView.setVisibility(View.VISIBLE);
    }

    /** 横屏短窗口优先保留标题和搜索框，让结果区在剩余高度内自己滚动。 */
    private void fitResultsToDialogHeight() {
        View results = (View) mGridView.getParent();
        results.post(() -> {
            if (dismissed || !(results.getParent() instanceof ViewGroup)) return;
            ViewGroup panel = (ViewGroup) results.getParent();
            int occupied = panel.getPaddingTop() + panel.getPaddingBottom();
            for (int i = 0; i < panel.getChildCount(); i++) {
                View child = panel.getChildAt(i);
                if (child == results || child.getVisibility() == View.GONE) continue;
                occupied += child.getMeasuredHeight();
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    occupied += margins.topMargin + margins.bottomMargin;
                }
            }
            int minResultsHeight = (int) (80 * getResources().getDisplayMetrics().density);
            int maxResultsHeight = Math.max(minResultsHeight, getMaxHeight() - occupied);
            ViewGroup.LayoutParams params = results.getLayoutParams();
            if (params.height > maxResultsHeight) {
                params.height = maxResultsHeight;
                results.setLayoutParams(params);
            }
        });
    }

    /** 兼容旧 API：show 前调用先暂存,onCreate 后再应用（含聚焦输入框） */
    public void setSearchWord(String wd) {
        pendingSearchWord = wd;
        if (subtitleSearchEt != null) {
            applySearchWord(wd);
        }
    }

    private void applySearchWord(String wd) {
        if (TextUtils.isEmpty(wd)) {
            wd = "";
        }
        wd = wd.replaceAll("(?:（|\\(|\\[|【|\\.mp4|\\.mkv|\\.avi|\\.MP4|\\.MKV|\\.AVI)", "");
        wd = wd.replaceAll("(?:：|\\:|）|\\)|\\]|】|\\.)", " ");
        int len = wd.length();
        int finalLen = len >= 36 ? 36 : len;
        wd = wd.substring(0, finalLen).trim();
        subtitleSearchEt.setText(wd);
        subtitleSearchEt.setSelection(wd.length());
        subtitleSearchEt.requestFocus();
    }

    public void search(String wd) {
        KeyboardUtils.hideSoftInput(subtitleSearchEt);
        if (TextUtils.isEmpty(wd)) {
            AppBubble.toast("输入内容不能为空");
            return;
        }
        subtitleRequestEpoch++;
        isSearchPag = true;
        searchAdapter.setNewData(new ArrayList<>());
        loadingBar.setVisibility(View.VISIBLE);
        mGridView.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        searchWord = wd;
        if (subtitleViewModel != null) {
            requestedResults = true;
            subtitleViewModel.searchResult(wd, page = 1);
        } else {
            loadingBar.setVisibility(View.GONE);
            showEmpty("字幕搜索暂不可用");
            AppBubble.toast("字幕搜索暂不可用");
        }
    }

    /** zip 预览分页回退语义:返回键先回列表再关闭 */
    @Override
    protected boolean onBackPressed() {
        subtitleRequestEpoch++;
        if (!isSearchPag) {
            if (subtitleViewModel != null) subtitleViewModel.cancelPendingSearch();
            isSearchPag = true;
            loadingBar.setVisibility(View.GONE);
            showList();
            searchAdapter.setNewData(zipSubtitles);
            searchAdapter.setEnableLoadMore(page < maxPage);
            return true;
        }
        dismiss();
        return true;
    }

    @Override
    protected void onDismiss() {
        dismissed = true;
        subtitleRequestEpoch++;
        if (subtitleViewModel != null && searchObserver != null) {
            subtitleViewModel.searchResult.removeObserver(searchObserver);
            searchObserver = null;
        }
        super.onDismiss();
    }
}
