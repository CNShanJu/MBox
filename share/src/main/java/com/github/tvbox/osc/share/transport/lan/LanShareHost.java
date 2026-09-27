package com.github.tvbox.osc.share.transport.lan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.SharePackage;

import java.util.List;

/**
 * 局域网分享的<b>宿主桥接</b>:实现放在 :app(它持有 HTTP 服务与网络状态),
 * 契约放在本模块(分享逻辑在这里)。这是本模块唯一需要"反向"拿 :app 能力的地方。
 *
 * <p>为什么要桥接(而不是本模块自己拿 IP、自己起服务):
 * <ul>
 *   <li><b>服务实例必须唯一</b>:局域网分享要复用已有的 {@code server/RemoteServer}
 *       (端口 9978、进程令牌、绑定方式都是一份)。再起一个会有端口冲突,而且多开一个
 *       监听端口本身就是多一份对外暴露面(AGENTS §七);</li>
 *   <li><b>"能不能用"只有 :app 知道</b>:开关是"重启生效"的 —— 用户刚打开开关时,
 *       当前进程里的服务其实还是只绑 127.0.0.1。这个区别记在 :app 的
 *       {@code ControlManager}(LAN_ACTIVE vs LAN_PENDING_RESTART),本模块看不到,
 *       所以由 {@link #isLanServiceEnabled()} 与 {@link #isLanBound()} 两个事实回答,
 *       文案由本模块拼({@link LanShareTransport#availability()});</li>
 *   <li><b>取本机可达 IP 是平台知识</b>:多网卡/热点/Wi-Fi Direct 的筛选规则在 :app
 *       ({@code RemoteServer.getLanIpv4Addresses} + {@code LanAddressRules}),本模块不重复实现。</li>
 * </ul>
 *
 * <p>:app 实现方必须保证:
 * <ol>
 *   <li><b>令牌鉴权</b>:所有 {@link LanShareEndpoints} 路径都校验会话令牌,
 *       错误令牌一律 403 且不区分"路径不存在"(防探测);</li>
 *   <li><b>只暴露会话内容</b>:下载只吐当前会话那一个文件,<b>绝不</b>复用
 *       {@code /file/<rel>} 那套外部存储根目录访问(那会把整个存储卡开放出去);</li>
 *   <li><b>写入隔离</b>:上传只写进会话专属临时目录,文件名一律由服务端重新生成
 *       (不采信对端文件名),并做 Zip Slip 无关的路径规范化;</li>
 *   <li><b>到点自撤</b>:{@link LanShareSession#expiresAtMillis()} 到点后服务端主动失效
 *       会话(不依赖界面来调 {@link #retract});</li>
 *   <li><b>用毕即停</b>:会话结束/页面退出后不留可访问路径(AGENTS §七)。</li>
 * </ol>
 *
 * <p>线程:{@link #publish} / {@link #retract} / {@link #setImportReceiver} 可能被主线程
 * 或分享模块的 IO 线程调用,实现必须线程安全;{@link LanImportReceiver} 的回调由服务端
 * 在自己线程上发起(见其注释)。
 */
public interface LanShareHost {

    /**
     * 设置里的「局域网服务」开关是否打开。
     * <p>注意:这只是<b>用户意愿</b>,不代表当前进程的服务真的对外可达 —— 见 {@link #isLanBound()}。
     */
    boolean isLanServiceEnabled();

    /**
     * 当前运行的 HTTP 服务实例是否真的绑定了局域网网卡(而非仅 127.0.0.1)。
     * <p>与 {@link #isLanServiceEnabled()} 的差集就是"开关已开、待重启生效"这一档,
     * 是 {@link LanShareTransport#availability()} 能给出准确提示的关键。
     */
    boolean isLanBound();

    /**
     * 本机所有"局域网里别的设备能访问到"的地址(形如 {@code http://192.168.1.23:9978/})。
     * 取不到(没连 Wi-Fi/网线)时返回空表 —— 此时分享不可用,而不是拿 {@code 0.0.0.0} 糊弄用户。
     */
    @NonNull
    List<String> accessUrls();

    /**
     * 挂出一个分享会话:把 {@code pkg} 暴露成"按令牌可下载(可选可上传)"的地址。
     *
     * <p>实现要点:{@code pkg.file()} 由调用方(:app 的归档器)持有的临时文件,
     * 会话期间<b>不得被调用方提前删除</b>;本方法应当只登记会话(+ 生成随机令牌、
     * 按 {@link LanShareOptions#sessionTtlMillis()} 设定到期),<b>不要</b>把整包读进内存。
     *
     * @return 已挂出的会话(含对端要访问的地址与令牌)
     * @throws ShareException 服务未起/开关未开/文件不可读/会话数超限等
     */
    @NonNull
    LanShareSession publish(@NonNull SharePackage pkg, @NonNull LanShareOptions opts) throws ShareException;

    /** 撤销会话并释放其资源(临时目录清理);会话不存在时是空操作(幂等) */
    void retract(@Nullable String sessionId);

    /**
     * 当前上传接收口的地址(形如 {@code http://192.168.1.23:9978/share/u/ab12cd});
     * 没设接收口、或取不到局域网地址时返回空串。
     *
     * <p>为什么单独一个方法而不是让 {@code setImportReceiver} 返回它:接收口是<b>长期存在</b>的
     * (对端可能几分钟后才来),而界面随时可能重建(旋屏/回前台)并重新问一次"现在该让对端访问什么";
     * 有 this 方法就能随时问到当前值,不必为了拿地址而重复设置 receiver。
     */
    @NonNull
    String importUrl();

    /**
     * 设置(或清除)上传接收口。传 {@code null} 表示不再接收对端上传:
     * 服务端应立刻让 {@code /share/u/<token>} 返回 403(不再像"还没上传"那样继续等)。
     *
     * <p>幂等:重复设置以最后一次为准(旧 receiver 收到 {@link LanImportReceiver#onSessionClosed})。
     */
    void setImportReceiver(@Nullable LanImportReceiver receiver);
}
