package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;

@UnstableApi
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
     * 每个媒体源使用独立工厂，下次起播会从提供方取新 client。
     */
    public synchronized void setOkClient(OkHttpClient client) {
        mOkClient = client;
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
     * 作废当前 client(安全 DNS 变更后调用)，下一媒体源的工厂会使用新 client。
     * 不关闭旧 client:正在播的流仍持有它,关闭会直接断流。
     */
    public synchronized void dropOkClient() {
        mOkClient = null;
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
            factory = getCacheDataSourceFactory(headers);
        } else {
            factory = getDataSourceFactory(headers);
        }
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            return new DefaultMediaSourceFactory(factory, getExtractorsFactory()).createMediaSource(getMediaItem(uri, errorCode));
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
        String lower = fileName.toLowerCase(Locale.ROOT);
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

    private DataSource.Factory getCacheDataSourceFactory(Map<String, String> headers) {
        if (mCache == null) {
            mCache = newCache();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory(headers))
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
    private DataSource.Factory getDataSourceFactory(Map<String, String> headers) {
        return new DefaultDataSource.Factory(mAppContext, getHttpDataSourceFactory(headers));
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    private DataSource.Factory getHttpDataSourceFactory(Map<String, String> headers) {
        OkHttpClient client = resolveOkClient();
        if (client == null) {
            Log.e(TAG, "取流客户端未注入:app 组合根未调用 setOkClientSupplier");
            throw new IllegalStateException("Media3 播放客户端未注入(见 AppCompositionRoot)");
        }
        // client 复用，工厂按媒体源隔离：HLS 旧分片不能被下一次播放的 UA/鉴权头污染。
        return new OkHttpDataSource.Factory(client)
                .setUserAgent(mUserAgent)
                .setDefaultRequestProperties(copyHeaders(headers));
    }

    /** 保留源站 UA（任意大小写），不修改调用方的头；数据源负责避免再追加默认 UA。 */
    static Map<String, String> copyHeaders(Map<String, String> headers) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (headers == null) return copy;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() == null || header.getValue() == null) continue;
            String value = header.getValue().trim();
            if ("User-Agent".equalsIgnoreCase(header.getKey()) && value.isEmpty()) continue;
            copy.put(header.getKey(), value);
        }
        return copy;
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }

}
