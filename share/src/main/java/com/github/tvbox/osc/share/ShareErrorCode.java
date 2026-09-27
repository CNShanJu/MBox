package com.github.tvbox.osc.share;

/**
 * 分享/导入导出错误码。
 *
 * <p>为什么要码 + 文案两套:界面要按码决定动作(如 {@link #UNAVAILABLE} 引导去设置页开局域网开关、
 * {@link #RATE_LIMITED} 提示明天再来并用服务端给的等待秒数倒计时),而文案只负责说人话;
 * 只揣一句 message 的话,界面只能靠字符串匹配,改一个字就断。
 */
public enum ShareErrorCode {

    /** 未初始化:{@code ShareFacade.init(AppContext)} 还没调用(启动早期就发起分享)。 */
    NOT_INITIALIZED("分享模块尚未初始化"),

    /** 平台不支持该能力(先看 {@link ShareCapability})。 */
    UNSUPPORTED("该平台不支持此操作"),

    /**
     * 权限/密码问题(在线平台 401=资源需要密码或密码错误;403=当前客户端不是该资源的所有者)。
     * <p>与 {@link #UNAVAILABLE} 的区别:那个是"平台现在用不了",这个是"这次请求不被允许",
     * 换平台或重试都没用,得拿对凭据(owner token / 密码)才行。
     */
    AUTH("没有权限访问该分享(可能需要密码或所有权凭据)"),

    /** 平台当前不可用(局域网开关没开、桥接未注入等),{@code reason} 里有具体原因。 */
    UNAVAILABLE("该平台当前不可用"),

    /** 入参不合法(空包、文件不存在、引用为空、域名不在白名单等)。 */
    INVALID_INPUT("参数不合法"),

    /** 网络层失败(连不上/超时/被中断)。 */
    NETWORK("网络请求失败"),

    /** 服务端返回非 2xx 且没给出可读原因。 */
    HTTP("服务端返回异常"),

    /** 触发平台限额(匿名上传每日次数、并发分片会话上限等)。 */
    RATE_LIMITED("触发平台限额,请稍后再试"),

    /** 包体超过平台单文件上限。 */
    TOO_LARGE("文件超过平台上限"),

    /** 响应/归档包结构解析失败(JSON 非法、清单缺失、非本应用导出等)。 */
    PARSE("数据解析失败"),

    /** 校验和不匹配(传输途中损坏或被改动)。 */
    CHECKSUM("文件校验不一致"),

    /** 已取消。 */
    CANCELLED("操作已取消"),

    /** 读写本地文件失败(缓存目录不可用、空间不足等)。 */
    IO("本地文件读写失败"),

    /** 其它未归类。 */
    UNKNOWN("分享失败");

    private final String defaultMessage;

    ShareErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** 该码的兜底文案(实现没给 message 时用它) */
    public String defaultMessage() {
        return defaultMessage;
    }
}
