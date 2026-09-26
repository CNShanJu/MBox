package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSource;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSourceFactory;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public final class ExoMediaSourceHelper {

    private static final String TAG = "ExoMediaSourceHelper";

    private static volatile ExoMediaSourceHelper sInstance;

    /**
     * 取流客户端提供方(app 组合根注入一次)。
     * <p>
     * 为什么必须有:client 会被"安全 DNS 变更"作废({@link #dropOkClient()}),作废之后必须有人重新给 ——
     * 原实现只指望调用方再调一次 {@link #setOkClient},而全仓零调用点,于是 mOkClient 恒为 null,
     * 取流时 OkHttpDataSource 内部 checkNotNull(callFactory) 抛 NPE(真机回归:Exo 每次起播即失败)。
     * 有了提供方,作废后由本类在下次取用时自取,不必依赖谁记得多调一次。
     */
    private static volatile java.util.function.Supplier<OkHttpClient> sClientSupplier;

    private final String mUserAgent;
    private final Context mAppContext;
    /** 由 client 派生的 DataSource 工厂:client 被替换(安全 DNS 变更)时必须一起失效 */
    private volatile OkHttpDataSource.Factory mHttpDataSourceFactory;
    private volatile OkHttpClient mOkClient = null;
    private Cache mCache;

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
        mUserAgent = Util.getUserAgent(mAppContext, mAppContext.getApplicationInfo().name);
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 设置播放用 OkHttpClient。
     * <p>
     * 传 null 表示"作废"({@code getOkClient()} 会回退到当前实例):安全 DNS 变更后
     * 旧工厂里裹的是旧 client,必须一并丢掉,否则下次起播仍用旧 DNS。
     */
    public synchronized void setOkClient(OkHttpClient client) {
        mOkClient = client;
        mHttpDataSourceFactory = null;
    }

    /** 当前播放客户端(已缓存的实例);需"没有就自取"时用 {@link #resolveOkClient()} */
    public OkHttpClient getOkClient() {
        return mOkClient;
    }

    /**
     * 注入取流客户端提供方(app 组合根启动时调一次)。
     * 提供方内部负责懒建与"安全 DNS 变更后重建"(见 App.playbackHttpClient)。
     */
    public static void setOkClientSupplier(java.util.function.Supplier<OkHttpClient> supplier) {
        sClientSupplier = supplier;
    }

    /**
     * 取当前播放客户端:mOkClient 优先;被作废({@link #dropOkClient()})后经提供方重新取用并缓存 —— 自愈,
     * 不再依赖"作废之后必须有人记得再 setOkClient 一次"(那正是 Exo 取流客户端恒为 null 的成因)。
     * 拿不到时返回 null,由取流处给出明确报错。
     */
    public OkHttpClient resolveOkClient() {
        OkHttpClient client = mOkClient;
        if (client != null) return client;
        java.util.function.Supplier<OkHttpClient> supplier = sClientSupplier;
        if (supplier == null) return null;
        synchronized (this) {
            if (mOkClient == null) {
                try {
                    mOkClient = supplier.get();
                } catch (Throwable th) {
                    Log.e(TAG, "播放客户端提供方取用失败", th);
                }
            }
            return mOkClient;
        }
    }

    /**
     * 作废当前 client 与由它派生的 DataSource 工厂(安全 DNS 变更后调用)。
     * 不关闭旧 client:正在播的流仍持有它,关闭会直接断流。
     */
    public synchronized void dropOkClient() {
        mOkClient = null;
        mHttpDataSourceFactory = null;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, -1);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int errorCode) {
        Uri contentUri = Uri.parse(uri);
        if ("rtmp".equals(contentUri.getScheme())) {
            return new ProgressiveMediaSource.Factory(new RtmpDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(contentUri));
        } else if ("rtsp".equals(contentUri.getScheme())) {
            return new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri));
        }
        int contentType = inferContentType(uri);
        DataSource.Factory factory;
        if (isCache) {
            factory = getCacheDataSourceFactory();
        } else {
            factory = getDataSourceFactory();
        }
        if (mHttpDataSourceFactory != null) {
            setHeaders(headers);
        }
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
            return new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory()).createMediaSource(getMediaItem(uri, errorCode));
        }
        switch (contentType) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            default:
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
        }
    }

    private static MediaItem getMediaItem(String uri, int errorCode) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(Uri.parse(uri.trim().replace("\\", "")));
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        return builder.build();
    }

    private static synchronized ExtractorsFactory getExtractorsFactory() {
        return new DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);

    }

    /**
     * 已知的普通媒体后缀:路径已明确指向这类文件时,不再拿查询串里的目标地址兜底
     * (避免 xxx.mp4?sign=yyy.m3u8 之类被误判成 HLS)。
     */
    private static final java.util.Set<String> PLAIN_MEDIA_EXTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "mp4", "mkv", "avi", "ts", "flv", "mov", "wmv", "webm", "m4v", "mpg", "mpeg",
            "rmvb", "rm", "3gp", "vob", "mp3", "m4a", "flac", "aac", "wav", "ogg"));

    private int inferContentType(String fileName) {
        String lower = fileName.toLowerCase();
        String path = lower;
        String query = "";
        int queryIdx = lower.indexOf('?');
        if (queryIdx >= 0) {
            path = lower.substring(0, queryIdx);
            query = lower.substring(queryIdx + 1);
        }
        // 先看路径的最后扩展名,避免下载文件名含 ".m3u8"(如 xxx.m3u8_sign=yyy_播放.mp4) 被误判为 HLS
        int dotIdx = path.lastIndexOf('.');
        String ext = dotIdx >= 0 ? path.substring(dotIdx + 1) : "";
        if ("mpd".equals(ext)) {
            return C.TYPE_DASH;
        }
        if ("m3u8".equals(ext)) {
            return C.TYPE_HLS;
        }
        // 路径没有明确媒体后缀时,真实目标可能在查询串里:代理地址形如
        // http://127.0.0.1:9978/proxy?do=js&...&url=<源地址 m3u8>,只按路径判定会退化成 Progressive
        // (首播失败后靠 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED 重试兜底),这里保留查询串兜底识别
        if (queryIdx >= 0 && !PLAIN_MEDIA_EXTS.contains(ext)) {
            if (query.contains(".m3u8")) {
                return C.TYPE_HLS;
            }
            if (query.contains(".mpd")) {
                return C.TYPE_DASH;
            }
        }
        return C.TYPE_OTHER;
    }

    private DataSource.Factory getCacheDataSourceFactory() {
        if (mCache == null) {
            mCache = newCache();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private Cache newCache() {
        return new SimpleCache(
                new File(mAppContext.getExternalCacheDir(), "exo-video-cache"),//缓存目录
                new LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024),//缓存大小，默认512M，使用LRU算法实现
                new StandaloneDatabaseProvider(mAppContext));
    }

    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory() {
        return new DefaultDataSource.Factory(mAppContext, getHttpDataSourceFactory());
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    private DataSource.Factory getHttpDataSourceFactory() {
        OkHttpDataSource.Factory factory = mHttpDataSourceFactory;
        if (factory == null) {
            synchronized (this) {
                factory = mHttpDataSourceFactory;
                if (factory == null) {
                    OkHttpClient client = resolveOkClient();
                    if (client == null) {
                        // 明确报错并留日志:否则要到取流时 OkHttpDataSource 内部 checkNotNull(callFactory)
                        // 才崩,现场只剩一句看不出所以然的 NPE(真机上就是这个症状)
                        Log.e(TAG, "取流客户端未注入:app 组合根未调用 setOkClientSupplier");
                        throw new IllegalStateException("Exo 播放客户端未注入(见 AppCompositionRoot)");
                    }
                    factory = new OkHttpDataSource.Factory(client)
                            .setUserAgent(mUserAgent)/*
                            .setAllowCrossProtocolRedirects(true)*/;
                    mHttpDataSourceFactory = factory;
                }
            }
        }
        return factory;
    }

    private void setHeaders(Map<String, String> headers) {
        if (headers != null && headers.size() > 0) {
            // 复制一份再动:传进来的是 VideoView 持有的那份 headers 引用(直接传引用),就地
            // remove("User-Agent") 会把调用方的 UA 永久吃掉 —— 第一次起播正常,replay()/错误重试/
            // 切线路再次 setDataSource 时只剩裸 UA,需要 UA 的源站直接 403
            Map<String, String> copy = new LinkedHashMap<>(headers);
            //如果发现用户通过header传递了UA，则强行将HttpDataSourceFactory里面的userAgent字段替换成用户的
            if (copy.containsKey("User-Agent")) {
                String value = copy.remove("User-Agent");
                if (!TextUtils.isEmpty(value)) {
                    try {
                        Field userAgentField = mHttpDataSourceFactory.getClass().getDeclaredField("userAgent");
                        userAgentField.setAccessible(true);
                        userAgentField.set(mHttpDataSourceFactory, value.trim());
                    } catch (Exception e) {
                        //ignore
                    }
                }
            }
            for (String k : copy.keySet()) {
                String v = copy.get(k);
                if (v != null)
                    copy.put(k, v.trim());
            }
            mHttpDataSourceFactory.setDefaultRequestProperties(copy);
        }
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }

}