package com.github.tvbox.osc.share.transport.lan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * 局域网<b>接收口</b>:对端把导入包推上来时,服务端(:app)回调这里。
 *
 * <p>分工为什么要这么切:服务端只懂 HTTP —— 收字节、限量、写临时文件、回一句话给浏览器,
 * 它<b>不该</b>懂"这个 zip 里是不是本应用的归档、清单 schema 能不能读、落地后要不要重启应用"。
 * 那些是分享模块的知识。所以约定:
 * <ul>
 *   <li>服务端负责:校验令牌、按 {@link LanShareOptions#maxUploadBytes()} 限量、
 *       把流写到<b>会话专属的临时文件</b>,然后调 {@link #onUploadReceived};</li>
 *   <li>本模块负责:校验文件名/大小/清单可读性,必要时核对校验和,
 *       再交给业务({@link com.github.tvbox.osc.share.ShareImportListener});</li>
 *   <li>临时文件的清理:本模块接管后自己删(见 {@link #onUploadReceived} 的注释)。</li>
 * </ul>
 *
 * <p>线程:回调发生在<b>HTTP 服务线程</b>上,不是主线程。实现方(本模块)负责切主线程,
 * 服务端实现方不得在这里做重活(它会阻塞对端的 HTTP 响应)。
 */
public interface LanImportReceiver {

    /**
     * 会话撤销/接收口关闭时的清理通知(可能因过期、用户退出页面、传输完成而触发)。
     * 服务端应删除会话临时目录里残留的分片/半成品文件。
     */
    void onSessionClosed(@Nullable String sessionId);

    /**
     * 收到一个完整上传文件。
     *
     * <p>调用方(服务端)已经把它完整写到 {@code tempFile} 并关闭了流。
     * <b>所有权转移给本方法</b>:实现可以读、可以移动,处理完负责删除
     * (避免会话目录越积越多);实现不得把 {@code tempFile} 当成长期路径存下来。
     *
     * @param fileName 对端提交的原始文件名(仅用于展示/兜底命名,<b>不可信</b>,
     *                 服务端与本模块都不得直接用它拼路径)
     * @param tempFile 已落盘的临时文件(位于会话专属目录内)
     * @return 给对端浏览器显示的一句话(如"导入包已接收,请在电视上确认")
     */
    @NonNull
    String onUploadReceived(@Nullable String fileName, @NonNull File tempFile);

    /**
     * 上传被拒(超限/令牌失效/写盘失败)。
     *
     * @param reason 给对端浏览器显示的简短原因(不要泄露主机路径等内部信息)
     */
    void onUploadRejected(@Nullable String reason);
}
