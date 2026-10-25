package com.github.tvbox.osc.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.github.tvbox.osc.repo.CacheRepository;
import com.github.tvbox.osc.repo.CollectRepository;
import com.github.tvbox.osc.repo.HistoryRepository;
import com.github.tvbox.osc.repo.HistoryRepositories;

/**
 * 历史/收藏/缓存仓储的 UI 访问入口(AGENTS §五:UI 不得直调 HistoryRepositories 静态单例)。
 * <p>
 * UI 经 {@link ViewModelProvider}(Factory) 拿到本 ViewModel,再经 {@link #history()}/
 * {@link #collect()}/{@link #cache()} 访问仓储;仓储本身仍是共享单例(组合根装配),
 * 测试注入缝 {@link HistoryRepositories#setHistory}/{@link HistoryRepositories#setCollect}/
 * {@link HistoryRepositories#setCache} 不受影响。非 UI 层(字幕/传输/播放工具)继续经
 * {@link HistoryRepositories} 直取。
 */
public final class HistoryViewModel extends ViewModel {

    private final HistoryRepository history;
    private final CollectRepository collect;
    private final CacheRepository cache;

    private HistoryViewModel(HistoryRepository history, CollectRepository collect, CacheRepository cache) {
        this.history = history;
        this.collect = collect;
        this.cache = cache;
    }

    public HistoryRepository history() {
        return history;
    }

    public CollectRepository collect() {
        return collect;
    }

    public CacheRepository cache() {
        return cache;
    }

    /** 组合根装配点:从 HistoryRepositories(共享单例)取仓储注入本 ViewModel。 */
    public static final class Factory implements ViewModelProvider.Factory {
        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (!modelClass.isAssignableFrom(HistoryViewModel.class)) {
                throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
            }
            return (T) new HistoryViewModel(
                    HistoryRepositories.history(),
                    HistoryRepositories.collect(),
                    HistoryRepositories.cache());
        }
    }
}
