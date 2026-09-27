package com.github.tvbox.osc.share;

/**
 * 传输能力位:各平台能做到哪些事<b>并不对称</b>,调用前必须先看能力,不要假设。
 *
 * <p>为什么必须显式声明而不是"接口方法都在、调不通就抛异常":
 * <ul>
 *   <li>在线平台(storage.to):导出是官方 API,导入要靠分享页/清单地址解析 —— 能导出不代表能导入;</li>
 *   <li>局域网:导出=本机挂出下载地址(对端拉),导入=对端把文件<b>推</b>上来({@link #IMPORT_PUSH});
 *       它没有"主动去对端取"的入口,{@link #IMPORT_PULL} 对它是无意义的;</li>
 *   <li>本地文件:只有导出,导入走系统文件选择器,不经过本模块。</li>
 * </ul>
 * 界面据此决定"这个平台能不能显示导入入口",门面据此拒绝不支持的调用并给出明确错误码
 * ({@link ShareErrorCode#UNSUPPORTED}),而不是让用户点了才发现没反应。
 */
public enum ShareCapability {

    /** 导出:把归档包发出去,换回一个可分享的引用({@link ShareLink})。 */
    EXPORT("导出"),

    /** 导入·拉取:本机<b>主动</b>按用户给的引用去远端取回归档包(在线分享链接走这条)。 */
    IMPORT_PULL("导入(拉取)"),

    /** 导入·推送:由对端把归档包<b>交给</b>本机(局域网设备上传走这条)。 */
    IMPORT_PUSH("导入(推送)");

    private final String label;

    ShareCapability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
