package com.github.tvbox.osc.share.transport.lan;

import androidx.annotation.NonNull;

/**
 * 局域网分享的 HTTP 路径与鉴权约定 —— <b>本模块与 :app 服务端之间的协议契约</b>。
 *
 * <p>为什么把路径常量放在模块里而不是让 :app 自己定:服务端的路由(RemoteServer)在 :app,
 * 但"对端该访问哪个地址"这句话是<b>给用户看的</b>,由本模块生成。两边各写一份字符串,
 * 改一处忘另一处就会变成"链接点了 404"这种很难查的故障。常量放这里,服务端实现
 * ({@code LanShareHost} 的实现方)引用同一份。
 *
 * <p>安全约束(AGENTS §七:敏感操作必须带鉴权/令牌,禁止无鉴权读写)——
 * 局域网侧不能像 {@code /file/<rel>} 那样无鉴权读:
 * <ul>
 *   <li>每条路径都带 {@code <token>}(会话级随机令牌),令牌错 → 403,且<b>不区分</b>
 *       "路径不存在"与"令牌错误"(避免探测);</li>
 *   <li>会话有效期内可多次下载(默认上限见 {@link LanShareOptions}),过期或撤销后立即 403/404;</li>
 *   <li>上传只写进<b>本模块指定的会话临时目录</b>,绝不落到外部存储根或应用私有目录
 *       (与备份/配置的真实数据隔离,校验通过后才由业务落地);</li>
 *   <li>上传大小上限由服务端按 {@link LanShareOptions} 的限额卡住,防磁盘写满。</li>
 * </ul>
 */
public final class LanShareEndpoints {

    /** 分享命名空间:与服务端既有的 {@code /file}、{@code /upload} 等管理接口分开 */
    public static final String PREFIX = "/share";

    /**
     * 下载(对端来取导出包):{@code GET /share/d/<token>}
     * <p>不带文件名:名字从会话里取,避免路径拼接引入目录穿越风险。
     */
    public static final String DOWNLOAD_PREFIX = PREFIX + "/d/";

    /**
     * 上传(对端把导入包推上来):{@code GET /share/u/<token>} 返回一个极简上传页,
     * {@code POST /share/u/<token>}(multipart/form-data,字段名 {@code file})才是真正收字节。
     */
    public static final String IMPORT_PREFIX = PREFIX + "/u/";

    /** 浏览器上传页里的文件字段名 */
    public static final String UPLOAD_FIELD = "file";

    /** 令牌也可经请求头携带(给脚本/命令行用):{@code X-MBox-Share-Token: <token>} */
    public static final String HEADER_TOKEN = "X-MBox-Share-Token";

    /** 令牌查询参数名(浏览器直接访问链接时走它) */
    public static final String PARAM_TOKEN = "token";

    private LanShareEndpoints() {
    }

    /** 下载地址 */
    @NonNull
    public static String downloadUrl(@NonNull String baseUrl, @NonNull String token) {
        return trimBase(baseUrl) + DOWNLOAD_PREFIX + token;
    }

    /** 上传页地址(给对端用户手动打开用) */
    @NonNull
    public static String importUrl(@NonNull String baseUrl, @NonNull String token) {
        return trimBase(baseUrl) + IMPORT_PREFIX + token;
    }

    /** {@code http://192.168.1.23:9978/} → {@code http://192.168.1.23:9978}(去掉尾斜杠) */
    @NonNull
    private static String trimBase(@NonNull String baseUrl) {
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
