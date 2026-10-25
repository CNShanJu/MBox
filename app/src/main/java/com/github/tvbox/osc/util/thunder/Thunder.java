package com.github.tvbox.osc.util.thunder;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.FileUtils;
import com.xunlei.downloadlib.XLDownloadManager;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xunlei.downloadlib.android.XLUtil;
import com.xunlei.downloadlib.parameter.TorrentFileInfo;
import com.xunlei.downloadlib.parameter.TorrentInfo;
import com.xunlei.downloadlib.parameter.XLTaskInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class Thunder {

    /** 磁力元数据解析的墙钟超时(毫秒)。超过即视为无种/失效,快速失败而非无限卡死。 */
    private static final long MAGNET_PARSE_TIMEOUT_MS = 8_000L;
    /** 种子下载等待的墙钟超时(毫秒)。无种时迅雷库长期停在"下载中",超时即提示失败。 */
    private static final long TORRENT_DOWNLOAD_TIMEOUT_MS = 30_000L;

    private static String cacheRoot = "";
    private static String localPath = "";
    private static String name = "";
    private static String task_url = "";
    private static long currentTask = 0L;
    private static ArrayList<TorrentFileInfo> torrentFileInfoArrayList = null;
    private static ExecutorService threadPool = null;

    /**
     * 迅雷 native 库(XLTaskHelper)的 init 是重量级同步调用,且可能长时间阻塞;
     * 同一进程内只需初始化一次,重复 init 既浪费又增加卡死概率。
     */
    private static volatile boolean inited = false;
    private static final Object INIT_LOCK = new Object();

    private static void init(Context context) {
        if (inited) return;
        synchronized (INIT_LOCK) {
            if (inited) return;
            Log.i("Thunder", "init 开始, 线程=" + Thread.currentThread().getName());
            // fake deviceId and Mac
            SharedPreferences sharedPreferences = context.getSharedPreferences("rand_thunder_id", Context.MODE_PRIVATE);
            String imei = sharedPreferences.getString("imei", null);
            String mac = sharedPreferences.getString("mac", null);
            if (imei == null) {
                imei = randomImei();
                sharedPreferences.edit().putString("imei", imei).commit();
            }
            if (mac == null) {
                mac = randomMac();
                sharedPreferences.edit().putString("mac", mac).commit();
            }

            XLUtil.mIMEI = imei;
            XLUtil.isGetIMEI = true;
            XLUtil.mMAC = mac;
            XLUtil.isGetMAC = true;
            String cd3 = "cee25055f125a2fde0";
            String base64Decode = "axzNjAwMQ^^yb==0^852^083dbcff^";
            String substring = base64Decode.substring(1);
            String substring2 = cd3.substring(0, cd3.length() - 1);
            final String cd = substring + substring2;
            Log.i("Thunder", "init: 即将调用 XLTaskHelper.init (native), 线程=" + Thread.currentThread().getName());
            // native init 可能永久阻塞(死锁/网络),用 FutureTask 在独立线程执行 + 超时放弃,
            // 避免持有 INIT_LOCK 期间把整个迅雷通道和后续调用者全部卡死。
            FutureTask<Boolean> initTask = new FutureTask<>(new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    long t0 = System.currentTimeMillis();
                    XLTaskHelper.init(context, cd, "21.01.07.800002");
                    Log.i("Thunder", "init: XLTaskHelper.init 返回, 耗时=" + (System.currentTimeMillis() - t0) + "ms, 线程=" + Thread.currentThread().getName());
                    return true;
                }
            });
            new Thread(initTask, "thunder-native-init").start();
            boolean initOk = false;
            try {
                initOk = initTask.get(10, TimeUnit.SECONDS);
            } catch (Throwable th) {
                Log.w("Thunder", "init: XLTaskHelper.init 超时/异常, 放弃迅雷通道: " + th);
            }
            cacheRoot = context.getCacheDir().getAbsolutePath() + File.separator + "thunder";
            // 仅当 native init 真正成功才标记 inited;失败/超时保持 false,后续调用会重试,
            // 但不会再次阻塞(每次都有 10s 上限)。
            inited = initOk;
        }
    }

    /** 后台执行器懒加载:play 可能在未调 parse 时被直接调用,必须保证 native 调用不落主线程 */
    private static ExecutorService pool() {
        if (threadPool == null || threadPool.isShutdown()) {
            synchronized (Thunder.class) {
                if (threadPool == null || threadPool.isShutdown()) {
                    threadPool = Executors.newSingleThreadExecutor();
                }
            }
        }
        return threadPool;
    }

    public static void stop(Boolean bool) {
        if (currentTask > 0) {
            Log.i("Thunder", "stop: 调用 stopTask, 线程=" + Thread.currentThread().getName());
            long t0 = System.currentTimeMillis();
            XLTaskHelper.instance().stopTask(currentTask);
            Log.i("Thunder", "stop: stopTask 返回, 耗时=" + (System.currentTimeMillis() - t0) + "ms");
            currentTask = 0L;
        }
        if(bool){
            torrentFileInfoArrayList = null;
            // del cache file
            File cache = new File(task_url.isEmpty()?cacheRoot:localPath);
            Log.i("Thunder", "stop: 删除缓存 " + cache.getAbsolutePath() + ", 线程=" + Thread.currentThread().getName());
            long t0 = System.currentTimeMillis();
            recursiveDelete(cache);
            Log.i("Thunder", "stop: 删除缓存完成, 耗时=" + (System.currentTimeMillis() - t0) + "ms");
            if (!cache.exists())
                cache.mkdirs();
            if (threadPool != null) {
                try {
                    threadPool.shutdownNow();
                    threadPool = null;
                } catch (Throwable th) {

                }
            }
        }
    }

    public interface ThunderCallback {

        void status(int code, String info);

        void list(Map<Integer, String> urlMap);

        void play(String url);
    }

    private static ArrayList<String> playList = null;
    private static ArrayList<String> ed2kList = null;
    public static void parse(Context context, Movie.Video.UrlBean urlBean, ThunderCallback callback) {
        Log.i("Thunder", "parse 被调用, 当前线程=" + Thread.currentThread().getName());
        // init(XLTaskHelper.init 重量级 native)与 stop(true)(stopTask native + 删除缓存文件 IO)
        // 都可能长时间阻塞,必须在后台线程执行,否则详情页回调线程(可能主线程)直接 ANR。
        pool().execute(new Runnable() {
            @Override
            public void run() {
                Log.i("Thunder", "parse 后台线程开始, 线程=" + Thread.currentThread().getName());
                init(context);
                if (!inited) {
                    Log.w("Thunder", "parse: 迅雷 native 库初始化失败/超时, 放弃解析, 直接回调失败");
                    callback.status(-1, "迅雷库初始化失败");
                    return;
                }
                stop(true);
                torrentFileInfoArrayList=new ArrayList<>();
                playList=new ArrayList<>();
                ed2kList=new ArrayList<>();
                Map<Integer, String> urlMap = new HashMap<>();
                for (int idx=0;idx<urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = urlBean.infoList.get(idx);
                    if (urlInfo != null) {
                        String url="";
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                            boolean isParse=false;
                            url=infoBean.url;
                            if (isMagnet(url) || isThunder(url) || isTorrent(url)) {
                                if(isThunder(url) )url=XLDownloadManager.getInstance().parserThunderUrl(url);
                                String link = isThunder(url) ? XLDownloadManager.getInstance().parserThunderUrl(url) : url;
                                Uri p = Uri.parse(link);
                                if (p == null) {
                                    continue;
                                }
                                String fileName = XLTaskHelper.instance().getFileName(link);
                                File cache = new File(cacheRoot + File.separator + fileName);
                                try {
                                    if (currentTask > 0) {
                                        XLTaskHelper.instance().stopTask(currentTask);
                                        currentTask = 0L;
                                    }
                                    currentTask = isMagnet(url) ?
                                            XLTaskHelper.instance().addMagentTask(url, cacheRoot, fileName) :
                                            XLTaskHelper.instance().addThunderTask(url, cacheRoot, fileName);
                                } catch (Exception exception) {
                                    exception.printStackTrace();
                                    currentTask = 0;
                                }
                                if (currentTask <= 0) {
                                    continue;
                                }
                                // 磁力元数据解析:墙钟超时熔断。无种/无 tracker 时迅雷库会长期卡在
                                // getTaskInfo/getTorrentInfo 上,不能用固定轮询次数兜底(单次 native 调用就可能
                                // 阻塞远超 100ms)。给 8 秒窗口,超时即放弃该磁力,避免拖死解析。
                                long magnetDeadline = System.currentTimeMillis() + MAGNET_PARSE_TIMEOUT_MS;
                                outerLoop:
                                while (true) {
                                    if (System.currentTimeMillis() > magnetDeadline) {
                                        break;
                                    }
                                    XLTaskInfo taskInfo = XLTaskHelper.instance().getTaskInfo(currentTask);
                                    if(taskInfo!=null){
                                        switch (taskInfo.mTaskStatus) {
                                            case 2: {
                                                try {
                                                    TorrentInfo torrentInfo = XLTaskHelper.instance().getTorrentInfo(cache.getAbsolutePath());
                                                    if (torrentInfo == null || TextUtils.isEmpty(torrentInfo.mInfoHash)) {

                                                    } else {
                                                        TorrentFileInfo[] mSubFileInfo = torrentInfo.mSubFileInfo;
                                                        if (mSubFileInfo != null) {
                                                            for (TorrentFileInfo sub : mSubFileInfo) {
                                                                if (isMedia(sub.mFileName) && sub.mFileSize > 1048576L * 30) {
                                                                    sub.torrentPath = cache.getAbsolutePath();
                                                                    playList.add(sub.mFileName + "$tvbox-torrent:" + torrentFileInfoArrayList.size());
                                                                    torrentFileInfoArrayList.add(sub);
                                                                }
                                                            }
                                                            isParse=true;
                                                            break outerLoop;
                                                        }
                                                    }
                                                } catch (Throwable throwable) {
                                                    throwable.printStackTrace();
                                                }
                                            }
                                            case 3: {
                                                break outerLoop;
                                            }
                                            default: {
                                            }
                                        }
                                    }
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }else {
                                url=infoBean.url;
                                if(isThunder(url))url=XLDownloadManager.getInstance().parserThunderUrl(url);
                                if(isNetworkDownloadTask(url)){
                                    task_url=url;
                                    if(TextUtils.isEmpty(task_url)){
                                        continue;
                                    }
                                    name = XLTaskHelper.instance().getFileName(task_url);
                                    playList.add(name + "$tvbox-oth:" + ed2kList.size());
                                    ed2kList.add(task_url);
                                    isParse=true;
                                }
                            }
                            if (!isParse)playList.add(infoBean.name + "$" + infoBean.url);
                        }
                        if (playList.size() > 0) {
                            urlMap.put(idx,TextUtils.join("#", playList));
                            playList.clear();
                        }
                    }
                }

                if (urlMap.size() > 0) {
                    callback.list(urlMap);
                } else {
                    callback.status(-1, "解析异常");
                }
            }
        });
    }


    public static boolean play(String url, ThunderCallback callback) {
        if (url.startsWith("tvbox-torrent:")) {
            int idx = Integer.parseInt(url.substring(14));
            final TorrentFileInfo info = torrentFileInfoArrayList.get(idx);
            // 所有 XLTaskHelper native 调用(可能长时间阻塞)一律丢后台线程,主线程只派发 + 收回调。
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    if (currentTask > 0) {
                        XLTaskHelper.instance().stopTask(currentTask);
                        currentTask = 0L;
                    }
                    String torrentName = new File(info.torrentPath).getName();
                    String cache = cacheRoot + File.separator + torrentName.substring(0, torrentName.lastIndexOf("."));
                    currentTask = XLTaskHelper.instance().addTorrentTask(info.torrentPath, cache, info.mFileIndex);
                    if (currentTask < 0) {
                        callback.status(-1, "下载出错");
                        return;
                    }
                    // 下载等待:墙钟超时熔断。无种时 getBtSubTaskInfo 可能长期卡在"下载中",给 30 秒
                    // 窗口,超时即提示失败,避免无限等待把线程占死。
                    long deadline = System.currentTimeMillis() + TORRENT_DOWNLOAD_TIMEOUT_MS;
                    while (true) {
                        if (System.currentTimeMillis() > deadline) {
                            callback.status(-1, "下载超时(可能无种)");
                            break;
                        }
                        XLTaskInfo taskInfo = XLTaskHelper.instance().getBtSubTaskInfo(currentTask, info.mFileIndex).mTaskInfo;
                        switch (taskInfo.mTaskStatus) {
                            case 3: {
                                callback.status(-1, errorInfo(taskInfo.mErrorCode));
                                return;
                            }
                            case 1:
                            case 4: // 下载中
                            case 2: { // 下载完成
                                String pUrl = XLTaskHelper.instance().getLoclUrl(cache + File.separator + info.mFileName);
                                callback.play(pUrl);
                                return;
                            }
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            });
            return true;
        }
        if (url.startsWith("tvbox-oth:")) {
            final int idx = Integer.parseInt(url.substring(10));
            final String taskUrl = ed2kList.get(idx);
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    stop(false); // stopTask 是 native 同步调用,必须在后台线程
                    // getFileName/addThunderTask 是 native 同步调用,必须在后台线程
                    task_url = taskUrl;
                    name = XLTaskHelper.instance().getFileName(task_url);
                    localPath = (new File(cacheRoot + File.separator + "temp", FileUtils.getFileNameWithoutExt(name))) + "/";
                    currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null);

                    long deadline = System.currentTimeMillis() + TORRENT_DOWNLOAD_TIMEOUT_MS;
                    while (true) {
                        if (System.currentTimeMillis() > deadline) {
                            callback.status(-1, "解析下载超时");
                            break;
                        }
                        String playUrl = getPlayUrl();
                        if (playUrl != null && !playUrl.isEmpty()) {
                            callback.play(playUrl);
                            return;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            });
            return true;
        }
        if (isEd2k(url) || isFtp(url)) {
            final String taskUrl = url;
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    init(App.getInstance());
                    if (!inited) {
                        callback.status(-1, "迅雷库初始化失败");
                        return;
                    }
                    if (currentTask > 0) {
                        XLTaskHelper.instance().stopTask(currentTask);
                        currentTask = 0L;
                    }
                    task_url = taskUrl;
                    name = XLTaskHelper.instance().getFileName(task_url);
                    localPath = (new File(cacheRoot + File.separator + "temp", FileUtils.getFileNameWithoutExt(name))) + "/";
                    currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null);

                    long deadline = System.currentTimeMillis() + TORRENT_DOWNLOAD_TIMEOUT_MS;
                    while (true) {
                        if (System.currentTimeMillis() > deadline) {
                            callback.status(-1, "解析下载超时");
                            break;
                        }
                        String playUrl = getPlayUrl();
                        if (!TextUtils.isEmpty(playUrl)) {
                            callback.play(playUrl);
                            return;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            });
            return true;
        }
        return false;
    }


    private static String errorInfo(int code) {
        switch (code) {
            case 9125:
                return "文件名太长";
            case 111120:
                return "文件路径太长";
            case 111142:
                return "文件太小";
            case 111085:
                return "磁盘空间不足";
            case 111171:
                return "拒绝的网络连接";
            case 9301:
                return "缓冲区不足";
            case 114001:
            case 114004:
            case 114005:
            case 114006:
            case 114007:
            case 114011:
            case 9304:
            case 111154:
                return "版权限制：无权下载";
            case 114101:
                return "无效链接";
            default:
                return "ErrorCode=" + code;
        }
    }


    public static boolean isSupportUrl(String url) {
//        return isMagnet(url) || isThunder(url) || isEd2k(url) || isFtp(url);
        return isMagnet(url) || isThunder(url) || isTorrent(url);
    }

    private static boolean isMagnet(String url) {
        return url.toLowerCase().startsWith("magnet:");
    }

    private static boolean isThunder(String url) {
        return url.toLowerCase().startsWith("thunder");
    }

    private static boolean isTorrent(String url) {
        return url.toLowerCase().split(";")[0].endsWith(".torrent");
    }

    private static boolean isEd2k(String url) {
        return url.toLowerCase().startsWith("ed2k:");
    }

    public static boolean isFtp(String url) {
        return url.toLowerCase().startsWith("ftp://");
    }

    static void recursiveDelete(File file) {
        if (!file.exists())
            return;
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                recursiveDelete(f);
            }
        }
        file.delete();
    }

    static ArrayList<String> formats = new ArrayList<>();

    static boolean isMedia(String name) {
        if (formats.size() == 0) {
            formats.add(".rmvb");
            formats.add(".avi");
            formats.add(".mkv");
            formats.add(".flv");
            formats.add(".mp4");
            formats.add(".rm");
            formats.add(".vob");
            formats.add(".wmv");
            formats.add(".mov");
            formats.add(".3gp");
            formats.add(".asf");
            formats.add("mpg");
            formats.add("mpeg");
            formats.add("mpe");
        }
        for (String f : formats) {
            if (name.toLowerCase().endsWith(f))
                return true;

        }
        return false;
    }

    static String randomImei() {
        return randomString("0123456", 15);
    }

    static String randomMac() {
        return randomString("ABCDEF0123456", 12).toUpperCase();
    }

    static String randomString(String base, int length) {
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }


    public static boolean isNetworkDownloadTask(String url){
        if(TextUtils.isEmpty(url)) return false;
        if(isFtp(url) || isEd2k(url)){
            return true;
        }else{
            return false;
        }
    }

    public static void stopTask(){
        if(currentTask != 0L){
            XLTaskHelper.instance().deleteTask(currentTask, task_url.isEmpty()?cacheRoot:localPath);
            currentTask = 0L;
        }
    }
    public static XLTaskInfo getTaskInfo(){
        return XLTaskHelper.instance().getTaskInfo(currentTask);
    }

    public static String getPlayUrl(){
        if(currentTask != 0L){
            if(isNetworkDownloadTask(task_url)){
                return XLTaskHelper.instance().getLoclUrl(localPath + name);
            }
        }
        return null;
    }



}
