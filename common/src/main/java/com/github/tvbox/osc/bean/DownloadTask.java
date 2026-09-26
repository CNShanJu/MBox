package com.github.tvbox.osc.bean;

import java.util.Map;

/**
 * 下载任务(支持直链与 HLS m3u8,断点续传)
 */
public class DownloadTask {

    public static final int STATE_WAITING = 0;
    public static final int STATE_DOWNLOADING = 1;
    /** 用户手动暂停:不参与调度,需用户手动继续 */
    public static final int STATE_PAUSED = 2;
    public static final int STATE_COMPLETED = 3;
    public static final int STATE_FAILED = 4;
    /** 调度暂停(并发满被挤下):等待调度,有空位自动恢复 */
    public static final int STATE_SYSTEM_PAUSED = 5;
    /** 网络暂停(仅WiFi开启时切蜂窝/断WiFi):条件恢复后自动恢复,区别于用户手动暂停 */
    public static final int STATE_NETWORK_PAUSED = 6;
    /** 已取消(删除记录):下载线程立即中止,不落最终文件,碎片保留 */
    public static final int STATE_CANCELLED = 7;

    public String id;
    public String url;
    /** 来源名称(取自订阅里配置的源名),作为一级目录 */
    public String sourceName;
    /** 剧名,作为二级目录与显示分组 */
    public String vodName;
    /** 封面图 URL(来自详情页 vodInfo.pic,列表项展示用) */
    public String pic;
    /** 名称分组(兼容旧字段,现等同剧名) */
    public String groupName;
    /** 保存文件名(剧名_第几集.mp4) */
    public String fileName;
    /** 最终文件路径 */
    public String savePath;
    /** 临时文件(.part) */
    public String partPath;
    /** m3u8 临时分片目录(下载完成后删除) */
    public String tmpDir;

    // 直链下载进度(字节)
    public long totalBytes;
    public long downloadedBytes;

    // HLS 下载进度(分段)
    public int totalSegments;
    public int doneSegments;
    public long segmentBytes; // 当前分段已下载字节(用于段内断点续传)

    public int state = STATE_WAITING;
    public String message = "";
    public long createTime = System.currentTimeMillis();

    /** 实时下载速度(字节/秒),仅内存使用不持久化(transient) */
    public transient long speed = 0;

    /** 每任务限速(字节/秒),0=不限速;仅内存使用(transient,5.4 增强) */
    public transient long speedLimit = 0;

    /**
     * 限速窗口状态(transient,仅内存):窗口起点与窗口内已写字节。
     * 必须跨调用保存 —— 原实现把它们写成方法内局部变量、每次重置,导致限速永不生效(见 util/ThrottlePolicy)。
     */
    public transient long throttleWindowStart = 0;
    public transient long throttleWindowBytes = 0;

    /**
     * 大小预检结果(随任务持久化):直链探测到的是精确 Content-Length,写入 {@link #totalBytes};
     * 本字段仅记 m3u8 的估算大小(码率×时长)。0=尚未探测/无法探测。
     * 仅用于磁盘空间预检与"约大小"展示,不参与精确进度分母。
     */
    public long estimatedBytes = 0;

    /** 大小探测进行中标记(transient):探测单飞去重,避免入队异步探测与启动前阻塞探测重复请求 */
    public transient boolean probing = false;

    /** 本进程会话内是否已尝试过大小探测(transient):探测成功或失败都置真,
     * 防止每次重启任务/重试都重复探测(失败多为服务器不返回大小,重复探测无意义) */
    public transient boolean probeDone = false;

    /** 来源 key(重启后重新解析地址用) */
    public String sourceKey;
    /** 线路名(重启后重新解析地址用) */
    public String playFlag;
    /** 源站原始集地址(重启后重新解析地址用,非空时启动前自动重新解析) */
    public String episodeRawUrl;
    /** 集数名(如 第1集_720P),记录到分段信息txt */
    public String episodeName;
    /** 统一剧集标识:sourceKey|vodId|playFlag|playIndex(见 DownloadCore.buildEpisodeId) */
    public String episodeId;
    /** 播放解析返回的请求头(UA/Referer 等,防盗链源的分片/文件校验,下载必须携带),随任务持久化 */
    public Map<String, String> headers;
    /** 进程重启后首次启动前是否需要重新解析地址(transient) */
    public transient boolean needReResolve = false;
    /** 本次任务已重新解析地址的次数(transient,限制次数避免无限重解析) */
    public transient int reResolveCount = 0;
    /**
     * 备用线路候选(换线路重下用,随任务持久化):入队时按"同集"从其它 playFlag 收集
     * (见 {@code util/DownloadRoutePlan#alternatives});命中"本线路整体失效"时按顺序切换,
     * 切一条消费一条(见 DownloadScheduler#switchRoute)。
     */
    public java.util.List<com.github.tvbox.osc.bean.DownloadRoute> altRoutes;
    /** 本次任务已换过的线路条数(transient,失败提示里用来说明"换过几条线路仍失败") */
    public transient int routeSwitchCount = 0;
    /** 是否因网络错误失败(网络恢复后自动续传,transient) */
    public transient boolean networkFailed = false;

    // ------------------------------------------------------------------
    // 排队/插队(4.4)
    // ------------------------------------------------------------------

    public static final int PRIORITY_LOW = 0;
    public static final int PRIORITY_NORMAL = 1;
    public static final int PRIORITY_HIGH = 2;

    /** 优先级(随任务持久化): HIGH=2 / NORMAL=1 / LOW=0, 调度按此降序 */
    public int priority = PRIORITY_NORMAL;

    /** 队列序(随任务持久化): 同优先级内 FIFO; moveToFront 时置最前 */
    public long queueOrder = 0;

    /** 被抢占时间(transient): 置 SYSTEM_PAUSED 时记录, 同级内"后抢占先恢复" */
    public transient long preemptTime = 0;

    /** 合并尝试次数(transient): 合并失败重试计数, 日志"合并/第k次" */
    public transient int mergeCount = 0;

    /** 上次合并失败原因(transient): 供"合并/重试"日志还原上下文 */
    public transient String mergeFailReason = "";

    public boolean isHls() {
        return totalSegments > 0;
    }

    public int getProgressPercent() {
        if (isHls()) {
            if (totalSegments <= 0) return 0;
            return (int) (100L * doneSegments / totalSegments);
        }
        if (totalBytes <= 0) return 0;
        return (int) (100L * downloadedBytes / totalBytes);
    }
}
