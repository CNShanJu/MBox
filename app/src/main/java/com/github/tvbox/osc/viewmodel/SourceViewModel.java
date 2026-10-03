package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.spiderapi.SourceConfigApi;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.security.NoTypePermission;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        // 源配置元信息契约(AppCompositionRoot 已注入 :spider 的 ApiConfig 实现;可注入 Fake)
        sourceConfig = SourceConfigProviders.get();
    }

    /** 源配置元信息契约(源注册表/首页源/vip 解析旗标;不再直读 :spider 的 ApiConfig) */
    private final SourceConfigApi sourceConfig;

    /**
     * 按源分道提交爬虫任务:同一 sourceKey 恒定同一单线程道(串行,保护 quickjs 上下文/jar 实例),
     * 不同源并行,消除"一个慢详情/播放解析 head-of-line 阻塞其余接口"。
     * (取代旧的 spThreadPool 全局单线程提交——该池现仅剩无 key 兜底用途,新代码一律走本方法)
     */
    private static void spExecute(String sourceKey, Runnable task) {
        com.github.catvod.crawler.SpiderApi.executeSerial(sourceKey, task);
    }

    // homeContent
    public void getSort(String sourceKey) {
        if (sourceKey == null) {
            sortResult.postValue(null);
            return;
        }
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        if (sourceBean == null) {
            // 源不存在(订阅刚被换掉/删除):原来直接 sourceBean.getType() 抛 NPE →
            // 首页永远收不到"本轮结束" → loading 一直转(与 getDetail 同一防御)
            sortResult.postValue(null);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            // 调试辅助:jar 源首页加载 trace(定位 jar 内 toast 触发源;tag=SpiderTrace)
            android.util.Log.i("SpiderTrace", "[首页] " + sourceBean.getName() + " type=3 homeContent 开始");
            long traceStart = System.currentTimeMillis();
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    // 线程池治理(改进.txt §六):不再每轮 newSingleThreadExecutor 后 shutdown,
                    // 直接提交应用级共享大池(HeavyTaskUtil),15s 超时 + cancel 语义不变
                    ExecutorService executor = HeavyTaskUtil.getBigTaskExecutorService();
                    boolean typedHit = false;
                    try {
                        AbsSortXml sortXml = null;
                        List<Movie.Video> embedded = null; // 同响应内嵌首页视频(若有)
                        // 强类型试点:homeContent 解析下沉 :spider(15s 超时保护,与旧字符串链路一致)
                        Future<AbsSortXml> typedFuture = executor.submit(new Callable<AbsSortXml>() {
                            @Override
                            public AbsSortXml call() {
                                return com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get()
                                        .homeContent(sourceBean.getKey(), true);
                            }
                        });
                        try {
                            sortXml = typedFuture.get(15, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            e.printStackTrace();
                            typedFuture.cancel(true);
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                        }
                        typedHit = sortXml != null;
                        if (typedHit) {
                            // impl 已把响应内嵌 list(首页视频)解析到 sortXml.videoList
                            embedded = sortXml.videoList;
                        } else {
                            android.util.Log.w("SpiderBridge", "home(typed) 无结果/超时,回退字符串通道: key="
                                    + sourceBean.getKey());
                            // 字符串通道回退:旧链路语义(拉串后按 sortJson 解析)
                            Future<String> stringFuture = executor.submit(new Callable<String>() {
                                @Override
                                public String call() throws Exception {
                                    return com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                            .homeContent(sourceBean.getKey(), true);
                                }
                            });
                            String sortJson = null;
                            try {
                                sortJson = stringFuture.get(15, TimeUnit.SECONDS);
                            } catch (TimeoutException e) {
                                e.printStackTrace();
                                stringFuture.cancel(true);
                            } catch (InterruptedException | ExecutionException e) {
                                e.printStackTrace();
                            }
                            if (sortJson != null) {
                                sortXml = sortJson(sortJson);
                                // 旧 json() 语义:同响应可能内嵌首页视频(list)
                                if (sortXml != null && sortXml.list != null && sortXml.list.videoList != null
                                        && !sortXml.list.videoList.isEmpty()) {
                                    embedded = sortXml.list.videoList;
                                }
                            }
                        }
                        publishHomeSort(sourceBean, sortXml, embedded);
                    } catch (Throwable th) {
                        // 每条路径都必须让首页收到"本轮结束"(与 getList 同一教训):
                        // 原来只 printStackTrace,源抛异常/网络被快速失败时 sortResult 永不发射 →
                        // 首屏 loading 一直转(离线冷启动 + 从"网络不可用"页返回就是这个现象)
                        th.printStackTrace();
                        sortResult.postValue(null);
                    } finally {
                        android.util.Log.i("SpiderTrace", "[首页] " + sourceBean.getName()
                                + " homeContent 结束 耗时=" + (System.currentTimeMillis() - traceStart)
                                + "ms typed=" + typedHit);
                    }
                }
            };
            spExecute(sourceBean.getKey(), waitResponse);
        } else if (type == 0 || type == 1) {
            HttpClient.get(sourceBean.getApi(), null, sourceBean.getKey() + "_sort", new HCallBack() {
                        @Override
                        public void onSuccess(String content) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = content;
                                sortXml = sortXml(xml);
                            } else if (type == 1) {
                                String json = content;
                                sortXml = sortJson(json);
                            }
                            if (sortXml != null && SystemConfig.getHomeRec() == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        sortResult.postValue(finalSortXml);
                                    }
                                });
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            sortResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            Map<String, String> sortParams = new HashMap<>();
            sortParams.put("filter", "true");
            HttpClient.get(sourceBean.getApi(), sortParams, null, sourceBean.getKey() + "_sort", new HCallBack() {
                    @Override
                    public void onSuccess(String sortJson) {
                        if (sortJson != null) {
                            AbsSortXml sortXml = sortJson(sortJson);
                            if (sortXml != null && SystemConfig.getHomeRec() == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    sortResult.postValue(sortXml);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            sortResult.postValue(sortXml);
                                        }
                                    });
                                }
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        } else {
                            sortResult.postValue(null);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        sortResult.postValue(null);
                    }
                });
        } else {
            sortResult.postValue(null);
        }
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        SourceBean homeSourceBean = sourceConfig.getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            spExecute(homeSourceBean.getKey(), new Runnable() {
                @Override
                public void run() {
                    try {
                        // type3 的强类型实现和旧字符串通道最终都调用同一个爬虫、同一个解析器。
                        // 失败后再次回退只会重复请求该源（尤其会让异常的分类接口再抛一次）。
                        com.github.tvbox.osc.bean.AbsXml typed =
                                com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get().category(
                                        homeSourceBean.getKey(), sortData.id, page + "", true, sortData.filterSelect);
                        if (typed != null && typed.movie != null) {
                            absXml(typed, homeSourceBean.getKey());
                            listResult.postValue(typed);
                        } else {
                            listResult.postValue(null);
                        }
                    } catch (Throwable th) {
                        // 异常也必须收尾，避免分类页的加载动画一直盖住列表。
                        LogStore.fail(Category.SUBSCRIPTION, "分类加载异常: " + homeSourceBean.getName()
                                + " 第" + page + "页, " + th);
                        listResult.postValue(null);
                    }
                }
            });
        } else if (type == 0 || type == 1 || type == 4) {
            // HTTP 源分类列表契约化:typed 优先;失败/空结果回退旧 HttpClient 直连(行为兜底)
            final MovieSort.SortData finalSortData = sortData;
            final int finalPage = page;
            spExecute(homeSourceBean.getKey(), new Runnable() {
                @Override
                public void run() {
                    com.github.tvbox.osc.bean.AbsXml typed = null;
                    try {
                        typed = com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get().category(
                                homeSourceBean.getKey(), finalSortData.id, finalPage + "", true,
                                finalSortData.filterSelect);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    if (typed != null && typed.movie != null) {
                        absXml(typed, homeSourceBean.getKey());
                        listResult.postValue(typed);
                        return;
                    }
                    android.util.Log.i("SpiderBridge", "category(typed/http) 不可用,回退旧路径: key="
                            + homeSourceBean.getKey() + " tid=" + finalSortData.id + " pg=" + finalPage);
                    try {
                        fetchListHttpLegacy(homeSourceBean, type, finalSortData, finalPage);
                    } catch (Throwable th) {
                        // 兜底:回退路径自身也炸时同样要收尾,别让调用方一直等
                        th.printStackTrace();
                        listResult.postValue(null);
                    }
                }
            });
        } else {
            listResult.postValue(null);
        }
    }

    /** type0/1/4 分类列表旧路径:HttpClient 直连拼参(typed 失败时的行为兜底,与原实现逐字一致) */
    private void fetchListHttpLegacy(final SourceBean homeSourceBean, final int type,
                                     final MovieSort.SortData sortData, final int page) {
        if (type == 0 || type == 1) {
            Map<String, String> listParams = new HashMap<>();
            listParams.put("ac", type == 0 ? "videolist" : "detail");
            listParams.put("t", sortData.id);
            listParams.put("pg", String.valueOf(page));
            if (sortData.filterSelect != null) {
                for (Map.Entry<String, String> entry : sortData.filterSelect.entrySet()) {
                    listParams.put(entry.getKey(), entry.getValue());
                }
            }
            listParams.put("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString());
            HttpClient.get(homeSourceBean.getApi(), listParams, null, homeSourceBean.getApi(), new HCallBack() {

                        @Override
                        public void onSuccess(String content) {
                            if (type == 0) {
                                String xml = content;
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = content;
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            listResult.postValue(null);
                        }
                    });
        } else if (type == 4) {
            String ext = "";
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    String selectExt = new JSONObject(sortData.filterSelect).toString();
                    ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT | Base64.NO_WRAP);
                    LOG.i(ext);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            } else {
                ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT | Base64.NO_WRAP);
            }
            Map<String, String> listExtParams = new HashMap<>();
            listExtParams.put("ac", "detail");
            listExtParams.put("filter", "true");
            listExtParams.put("t", sortData.id);
            listExtParams.put("pg", String.valueOf(page));
            listExtParams.put("ext", ext);
            HttpClient.get(homeSourceBean.getApi(), listExtParams, null, homeSourceBean.getApi(), new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        LOG.i(json);
                        json(listResult, json, homeSourceBean.getKey());
                    }

                    @Override
                    public void onError(Throwable e) {
                        listResult.postValue(null);
                    }
                });
        } else {
            listResult.postValue(null);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }

    /**
     * 发布 type=3 首页结果(typed 命中或字符串回退共用):
     * homeRec=1 且有内嵌首页视频 → 富化后直接发布;无内嵌 → 走 homeVideoContent(typed)补推荐;
     * homeRec=0 → 仅发布分类。
     */
    private void publishHomeSort(final SourceBean sourceBean, AbsSortXml sortXml, List<Movie.Video> embedded) {
        if (sortXml == null) {
            sortResult.postValue(null);
            return;
        }
        if (SystemConfig.getHomeRec() == 1) {
            if (embedded != null && !embedded.isEmpty()) {
                // 同响应内嵌首页视频:与旧 json() 语义一致做富化(sourceKey 归属/urlBean 拆分)
                AbsXml wrap = new AbsXml();
                Movie movie = new Movie();
                movie.videoList = embedded;
                wrap.movie = movie;
                absXml(wrap, sourceBean.getKey());
                sortXml.videoList = embedded;
                sortResult.postValue(sortXml);
            } else {
                getHomeRecList(sourceBean, null, new HomeRecCallback() {
                    @Override
                    public void done(List<Movie.Video> videos) {
                        sortXml.videoList = videos;
                        sortResult.postValue(sortXml);
                    }
                });
            }
        } else {
            sortResult.postValue(sortXml);
        }
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    // 线程池治理(改进.txt §六):不再每轮 newSingleThreadExecutor 后 shutdown,
                    ExecutorService executor = HeavyTaskUtil.getBigTaskExecutorService();
                    Future<com.github.tvbox.osc.bean.AbsXml> future = executor.submit(new Callable<com.github.tvbox.osc.bean.AbsXml>() {
                        @Override
                        public com.github.tvbox.osc.bean.AbsXml call() throws Exception {
                            // 强类型试点:解析下沉 :spider(带 15s 超时保护,与旧字符串链路一致)
                            return com.github.tvbox.osc.spiderapi.SpiderHomeProviders.get()
                                    .homeVideoContent(sourceBean.getKey());
                        }
                    });
                    com.github.tvbox.osc.bean.AbsXml result = null;
                    try {
                        result = future.get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (result != null && result.movie != null && result.movie.videoList != null) {
                            absXml(result, sourceBean.getKey());
                            android.util.Log.d("SpiderBridge", "homeVideo(typed) 命中: key=" + sourceBean.getKey()
                                    + " size=" + result.movie.videoList.size());
                            callback.done(result.movie.videoList);
                        } else {
                            android.util.Log.w("SpiderBridge", "homeVideo(typed) 无结果,回退空列表: key=" + sourceBean.getKey());
                            callback.done(null);
                        }
                    }
                }
            };
            spExecute(sourceBean.getKey(), waitResponse);
        } else if (type == 0 || type == 1) {
            Map<String, String> homeRecParams = new HashMap<>();
            homeRecParams.put("ac", sourceBean.getType() == 0 ? "videolist" : "detail");
            homeRecParams.put("ids", TextUtils.join(",", ids));
            HttpClient.get(sourceBean.getApi(), homeRecParams, null, "detail", new HCallBack() {

                        @Override
                        public void onSuccess(String content) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = content;
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = content;
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
    // detailContent
    public void getDetail(String sourceKey, String id) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        if (sourceBean == null) {
            // 源不存在(订阅变更/失效等),通知空结果,避免崩溃
            detailResult.postValue(null);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spExecute(sourceKey, new Runnable() {
                @Override
                public void run() {
                    try {
                        // 强类型试点:解析下沉 :spider(SpiderDetailImpl);失败回退字符串通道(行为不变)
                        com.github.tvbox.osc.bean.AbsXml typed =
                                com.github.tvbox.osc.spiderapi.SpiderDetailProviders.get().detail(sourceKey, id);
                        if (typed != null && typed.movie != null) {
                            absXml(typed, sourceBean.getKey());
                            checkThunder(typed, 0); // 内部按需 postValue(detailResult)
                            return;
                        }
                        android.util.Log.i("SpiderBridge", "detail(typed) 不可用,回退字符串通道: key=" + sourceKey + " id=" + id);
                        List<String> ids = new ArrayList<>();
                        ids.add(id);
                        json(detailResult, com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                .detailContent(sourceBean.getKey(), ids), sourceBean.getKey());
                    } catch (Throwable th) {
                        th.printStackTrace();
                        // 详情取回空/抛异常也要投递结果,否则详情页永远停在 loading(无失败提示)
                        android.util.Log.e("SpiderBridge", "detail 失败,投递空结果: key=" + sourceKey + " id=" + id + " " + th);
                        detailResult.postValue(null);
                    }
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            spExecute(sourceBean.getKey(), new Runnable() {
                @Override
                public void run() {
                    try {
                        // type0/1/4 HTTP 源契约化:typed detail(SpiderDetailImpl 按类型拼参拉取+解析)
                        // 成功即发布;失败回退旧 HttpClient 直连(行为兜底)
                        com.github.tvbox.osc.bean.AbsXml typed =
                                com.github.tvbox.osc.spiderapi.SpiderDetailProviders.get().detail(sourceKey, id);
                        if (typed != null && typed.movie != null) {
                            absXml(typed, sourceBean.getKey());
                            checkThunder(typed, 0);
                            return;
                        }
                        android.util.Log.i("SpiderBridge", "detail(typed/http) 不可用,回退旧路径: key=" + sourceKey + " id=" + id);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    fetchDetailHttpLegacy(sourceBean, type, id);
                }
            });
        } else {
            detailResult.postValue(null);
        }
    }

    /** type0/1/4 详情旧路径:HttpClient 直连拼参(typed 失败时的行为兜底,与原实现逐字一致) */
    private void fetchDetailHttpLegacy(final SourceBean sourceBean, final int type, final String id) {
        Map<String, String> detailParams =
                com.github.tvbox.osc.spiderapi.HttpSourceParams.detail(type, id);
        if (detailParams == null) {
            detailResult.postValue(null);
            return;
        }
        HttpClient.get(sourceBean.getApi(), detailParams, null, "detail", new HCallBack() {

                    @Override
                    public void onSuccess(String content) {
                        if (type == 0) {
                            String xml = content;
                            xml(detailResult, xml, sourceBean.getKey());
                        } else {
                            String json = content;
                            LOG.i(json);
                            json(detailResult, json, sourceBean.getKey());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        detailResult.postValue(null);
                    }
                });
    }
    /**
     * 主搜索"每源一批"结果直调监听(改进.txt §五 收口):替代 FastSearch 场景的
     * TYPE_SEARCH_RESULT EventBus 投递。回调线程不保证主线程,宿主自行切主线程。
     */
    public interface SearchBatchListener {
        void onSearchBatch(String sourceKey, AbsXml data, long epoch);
    }

    private volatile SearchBatchListener searchBatchListener;

    /** 注入/清除搜索批次监听(宿主销毁前必须置 null 防悬垂) */
    public void setSearchBatchListener(SearchBatchListener listener) {
        this.searchBatchListener = listener;
    }

    private void deliverSearchBatch(String sourceKey, AbsXml data, long epoch) {
        SearchBatchListener listener = searchBatchListener;
        if (listener != null) listener.onSearchBatch(sourceKey, data, epoch);
    }

    /**
     * 聚合搜索翻页"每源一批"结果直调监听(与首屏 [SearchBatchListener] 分开,宿主单独记账):
     * 翻页批次不该影响首屏"全部来源已返回"的完成态,也不该被首屏批次统计口径干扰。
     * 回调线程不保证主线程,宿主自行切主线程。
     */
    public interface SearchPageBatchListener {
        /** @param sourceKey 来源 key;@param data 该页解析结果(空页/失败为 null);@param page 该页页码(>=2) */
        void onSearchPageBatch(String sourceKey, AbsXml data, int page, long epoch);
    }

    private volatile SearchPageBatchListener searchPageBatchListener;

    /** 注入/清除翻页批次监听(宿主销毁前必须置 null 防悬垂) */
    public void setSearchPageBatchListener(SearchPageBatchListener listener) {
        searchPageBatchListener = listener;
    }

    private void deliverSearchPageBatch(String sourceKey, AbsXml data, int page, long epoch) {
        SearchPageBatchListener listener = searchPageBatchListener;
        if (listener != null) listener.onSearchPageBatch(sourceKey, data, page, epoch);
    }

    // searchContent
    public void getSearch(String sourceKey, String wd, long epoch) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        if (sourceBean == null) {
            deliverSearchBatch(sourceKey, null, epoch);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                // 强类型试点:解析下沉 :spider;失败回退字符串通道(行为不变)
                com.github.tvbox.osc.bean.AbsXml typed =
                        com.github.tvbox.osc.spiderapi.SpiderSearchProviders.get().search(sourceBean.getKey(), wd, false);
                if (typed != null && typed.movie != null) {
                    absXml(typed, sourceBean.getKey());
                    deliverSearchBatch(sourceKey, typed, epoch);
                    return;
                }
                // 正式 typed 实现已经调用同一 SpiderContentImpl；空结果或失败再走字符串
                // 通道只会对同一来源、同一关键词重发请求，慢源因此占池位两轮。
                if (com.github.tvbox.osc.spiderapi.SpiderSearchProviders.isInstalled()) {
                    deliverSearchBatch(sourceKey, null, epoch);
                    return;
                }
                android.util.Log.i("SpiderBridge", "search(typed) 不可用,回退字符串通道: key=" + sourceBean.getKey()
                        + " word=" + wd);
                String search = com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                        .searchContent(sourceBean.getKey(), wd, false);
                deliverSearchBatch(sourceKey, parseSearchPayload(search, sourceKey, 1), epoch);
            } catch (Throwable th) {
                th.printStackTrace();
                deliverSearchBatch(sourceKey, null, epoch);
            }
        } else if (type == 0 || type == 1 || type == 4) {
            // HTTP 源搜索契约化:typed 优先;失败/空结果回退旧 HttpClient 直连(行为兜底)
            com.github.tvbox.osc.bean.AbsXml typed = null;
            try {
                typed = com.github.tvbox.osc.spiderapi.SpiderSearchProviders.get()
                        .search(sourceBean.getKey(), wd, false);
            } catch (Throwable th) {
                th.printStackTrace();
            }
            if (typed != null && typed.movie != null) {
                absXml(typed, sourceBean.getKey());
                deliverSearchBatch(sourceKey, typed, epoch);
                return;
            }
            // typed HTTP 已用同一 URL/参数和解析器；空批次不应再发一次异步 GET。
            if (com.github.tvbox.osc.spiderapi.SpiderSearchProviders.isInstalled()) {
                deliverSearchBatch(sourceKey, null, epoch);
                return;
            }
            android.util.Log.i("SpiderBridge", "search(typed/http) 不可用,回退旧路径: key=" + sourceBean.getKey()
                    + " word=" + wd);
            fetchSearchHttpLegacy(sourceBean, type, wd, epoch);
        } else {
            // 未支持的源类型:也要投一批"空结果"通知宿主,否则宿主的"全部来源已返回/第一波收尾"
            // 记账永远等不到这一路(原实现只 searchResult.postValue(null),而该 LiveData 已无人观察),
            // 第二波只能干等兜底超时,完成态也一直不落。
            deliverSearchBatch(sourceKey, null, epoch);
        }
    }

    /**
     * 聚合搜索"加载更多":取某源的第 page 页(page 从 2 起;首页走 [getSearch])。
     * <p>
     * 结果经 [SearchPageBatchListener] 投递(与首屏 [SearchBatchListener] 分开,宿主单独记账,
     * 翻页批次不会干扰首屏"全部来源已返回"的完成态);本页没有更多时投递 null 数据。
     * <p>
     * type3(JS/JAR)走 [SpiderContentApi#searchContent(String,String,boolean,String)] 重载;
     * type0/1/4 走 HTTP 拼参(与首屏同一套 HttpSourceParams,额外带 pg)。
     */
    public void getSearchPaged(final String sourceKey, final String wd, final int page, final long epoch) {
        final SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        if (sourceBean == null || page <= 1 || TextUtils.isEmpty(wd)) {
            deliverSearchPageBatch(sourceKey, null, page, epoch);
            return;
        }
        final int type = sourceBean.getType();
        if (type == 3) {
            try {
                String search = com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                        .searchContent(sourceBean.getKey(), wd, false, String.valueOf(page));
                deliverSearchPageBatch(sourceKey, parseSearchPayload(search, sourceBean.getKey(), 1), page, epoch);
            } catch (Throwable th) {
                th.printStackTrace();
                deliverSearchPageBatch(sourceKey, null, page, epoch);
            }
            return;
        }
        if (type == 0 || type == 1 || type == 4) {
            Map<String, String> searchParams = com.github.tvbox.osc.spiderapi.HttpSourceParams.search(type, wd, false);
            if (searchParams == null) {
                deliverSearchPageBatch(sourceKey, null, page, epoch);
                return;
            }
            searchParams.put("pg", String.valueOf(page));
            HttpClient.get(sourceBean.getApi(), searchParams, null, "search", new HCallBack() {
                @Override
                public void onSuccess(String content) {
                    deliverSearchPageBatch(sourceKey, parseSearchPayload(content, sourceBean.getKey(), type), page, epoch);
                }

                @Override
                public void onError(Throwable e) {
                    deliverSearchPageBatch(sourceKey, null, page, epoch);
                }
            });
            return;
        }
        deliverSearchPageBatch(sourceKey, null, page, epoch);
    }

    /** 搜索响应串 → AbsXml(type0 走 XML 解析,其余走 JSON);空内容/解析失败返回 null */
    private AbsXml parseSearchPayload(String content, String sourceKey, int type) {
        if (TextUtils.isEmpty(content)) return null;
        try {
            return type == 0
                    ? com.github.tvbox.osc.spiderapi.AbsXmlParser.parseXml(content, sourceKey)
                    : com.github.tvbox.osc.spiderapi.AbsXmlParser.parseJson(content, sourceKey);
        } catch (Throwable th) {
            return null;
        }
    }

    /** type0/1/4 聚合搜索旧路径:HttpClient 直连拼参(typed 失败时的行为兜底,与原实现逐字一致) */
    private void fetchSearchHttpLegacy(final SourceBean sourceBean, final int type, final String wd, final long epoch) {
        Map<String, String> searchParams = com.github.tvbox.osc.spiderapi.HttpSourceParams.search(type, wd, false);
        if (searchParams == null) {
            deliverSearchBatch(sourceBean.getKey(), null, epoch);
            return;
        }
        HttpClient.get(sourceBean.getApi(), searchParams, null, "search", new HCallBack() {
                    @Override
                    public void onSuccess(String content) {
                        deliverSearchBatch(sourceBean.getKey(),
                                parseSearchPayload(content, sourceBean.getKey(), type), epoch);
                    }

                    @Override
                    public void onError(Throwable e) {
                        deliverSearchBatch(sourceBean.getKey(), null, epoch);
                    }
                });
    }
    // searchContent
    /**
     * 快速搜索(详情页"来源"快搜弹窗)"每源一批"结果直调监听:替代历史 EventBus 快搜批次投递。
     * 回调线程不保证主线程,宿主自行切主线程。
     */
    public interface QuickSearchBatchListener {
        void onQuickSearchBatch(AbsXml data);
    }

    private volatile QuickSearchBatchListener quickSearchBatchListener;

    /** 注入/清除快速搜索批次监听(宿主销毁前必须置 null 防悬垂) */
    public void setQuickSearchBatchListener(QuickSearchBatchListener listener) {
        this.quickSearchBatchListener = listener;
    }

    private void deliverQuickSearchBatch(AbsXml data) {
        QuickSearchBatchListener listener = quickSearchBatchListener;
        if (listener != null) listener.onQuickSearchBatch(data);
    }

    public void getQuickSearch(String sourceKey, String wd) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                // 强类型试点:quick 搜索解析下沉 :spider;失败回退字符串通道
                com.github.tvbox.osc.bean.AbsXml typed =
                        com.github.tvbox.osc.spiderapi.SpiderSearchProviders.get().search(sourceBean.getKey(), wd, true);
                if (typed != null && typed.movie != null) {
                    absXml(typed, sourceBean.getKey());
                    deliverQuickSearchBatch(typed);
                    return;
                }
                android.util.Log.i("SpiderBridge", "quickSearch(typed) 不可用,回退字符串通道: key=" + sourceBean.getKey()
                        + " word=" + wd);
                json(quickSearchResult, com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                        .searchContent(sourceBean.getKey(), wd, true), sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (type == 0 || type == 1 || type == 4) {
            // HTTP 源快速搜索契约化:typed 优先;失败/空结果回退旧 HttpClient 直连(行为兜底)
            com.github.tvbox.osc.bean.AbsXml typed = null;
            try {
                typed = com.github.tvbox.osc.spiderapi.SpiderSearchProviders.get()
                        .search(sourceBean.getKey(), wd, true);
            } catch (Throwable th) {
                th.printStackTrace();
            }
            if (typed != null && typed.movie != null) {
                absXml(typed, sourceBean.getKey());
                deliverQuickSearchBatch(typed);
                return;
            }
            android.util.Log.i("SpiderBridge", "quickSearch(typed/http) 不可用,回退旧路径: key=" + sourceBean.getKey()
                    + " word=" + wd);
            fetchQuickSearchHttpLegacy(sourceBean, type, wd);
        } else {
            quickSearchResult.postValue(null);
        }
    }

    /** type0/1/4 快速搜索旧路径:HttpClient 直连拼参(typed 失败时的行为兜底,与原实现逐字一致;
     *  tag 与原实现一致——type4 用 "search",type0/1 用 "quick_search",保取消语义) */
    private void fetchQuickSearchHttpLegacy(final SourceBean sourceBean, final int type, final String wd) {
        Map<String, String> quickParams = com.github.tvbox.osc.spiderapi.HttpSourceParams.search(type, wd, true);
        if (quickParams == null) {
            quickSearchResult.postValue(null);
            return;
        }
        HttpClient.get(sourceBean.getApi(), quickParams, null,
                type == 4 ? "search" : "quick_search", new HCallBack() {
                    @Override
                    public void onSuccess(String content) {
                        if (type == 0) {
                            String xml = content;
                            xml(quickSearchResult, xml, sourceBean.getKey());
                        } else {
                            String json = content;
                            json(quickSearchResult, json, sourceBean.getKey());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        // quickSearchResult.postValue(null);
                        deliverQuickSearchBatch(null);
                    }
                });
    }
    // playerContent
    private void postPlayFailure(String progressKey, String url) {
        JSONObject failure = new JSONObject();
        try {
            failure.put("proKey", progressKey);
            failure.put("key", url);
            failure.put("playError", true);
        } catch (Throwable ignored) { }
        playResult.postValue(failure);
    }

    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        SourceBean sourceBean = sourceConfig.getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            spExecute(sourceBean.getKey(), new Runnable() {
                @Override
                public void run() {
                    try {
                        String json = com.github.tvbox.osc.spiderapi.SpiderContentProviders.get()
                                .playerContent(sourceBean.getKey(), playFlag, url, sourceConfig.getVipParseFlags());
                        JSONObject result = new JSONObject(json);
                        result.put("key", url);
                        result.put("proKey", progressKey);
                        result.put("subtKey", subtitleKey);
                        if (!result.has("flag"))
                            result.put("flag", playFlag);
                        playResult.postValue(result);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        postPlayFailure(progressKey, url);
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(url) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", url);
                } else {
                    result.put("parse", 1);
                    result.put("url", url);
                }
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                playResult.postValue(result);
            } catch (Throwable th) {
                th.printStackTrace();
                postPlayFailure(progressKey, url);
            }
        } else if (type == 4) {
            Map<String, String> playParams = com.github.tvbox.osc.spiderapi.HttpSourceParams.play(type, url, playFlag);
            if (playParams == null) {
                postPlayFailure(progressKey, url);
                return;
            }
            HttpClient.get(sourceBean.getApi(), playParams, null, "play", new HCallBack() {
                    @Override
                    public void onSuccess(String json) {
                        LOG.i(json);
                        try {
                            JSONObject result = new JSONObject(json);
                            result.put("key", url);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            playResult.postValue(result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            postPlayFailure(progressKey, url);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        postPlayFailure(progressKey, url);
                    }
                });
        }else {
            postPlayFailure(progressKey, url);
        }
    }

    private AbsSortXml sortJson(String json) {
        // 解析已抽到 SortParser(纯静态、可单测);发布由调用方完成
        return com.github.tvbox.osc.spiderapi.SortParser.parseSortJson(json);
    }

    /**
     * XStream 反序列化安全白名单:关闭默认"任意类型许可",仅放行业务 bean 包(及其嵌套类)
     * 与基础 JDK 类型。订阅/详情 XML 来自第三方数据源,若无白名单,恶意 XML 可让 XStream
     * 实例化任意类触发 gadget 链(潜在 RCE)。调用时机:processAnnotations 之后、fromXML 之前。
     */
    private AbsSortXml sortXml(String xml) {
        // 解析已抽到 SortParser(纯静态、可单测)
        return com.github.tvbox.osc.spiderapi.SortParser.parseSortXml(xml);
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    if (urlInfo.beanList == null) continue; // 防御:无线路列表不判雷
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$");

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }


    /**
     * typed/字符串通道统一归一:回填 sourceKey + 把线路串拆成 beanList
     * (typed 端即使只给 urls 文本,也能保证 checkThunder/选集可用)。
     * 发布副作用(postValue/直调监听)仍由调用方各自完成。
     */
    private void absXml(com.github.tvbox.osc.bean.AbsXml typed, String sourceKey) {
        if (typed != null) {
            com.github.tvbox.osc.spiderapi.AbsXmlParser.normalize(typed, sourceKey);
        }
    }

    /** 解析结果发布(原 xml/json 尾部副作用统一;data=null 表示解析失败,按原语义发布 null) */
    private void publishDetailPayload(MutableLiveData<AbsXml> result, AbsXml data) {
        if (quickSearchResult == result) {
            deliverQuickSearchBatch(data);
        } else if (result != null) {
            if (result == detailResult) {
                if (data != null) {
                    checkThunder(data, 0);
                } else {
                    result.postValue(null);
                }
            } else {
                result.postValue(data);
            }
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        AbsXml data;
        try {
            data = com.github.tvbox.osc.spiderapi.AbsXmlParser.parseXml(xml, sourceKey);
        } catch (Exception e) {
            data = null;
        }
        publishDetailPayload(result, data);
        return data;
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        AbsXml data;
        try {
            data = com.github.tvbox.osc.spiderapi.AbsXmlParser.parseJson(json, sourceKey);
        } catch (Exception e) {
            data = null;
        }
        publishDetailPayload(result, data);
        return data;
    }
    @Override
    protected void onCleared() {
        super.onCleared();
    }
}
