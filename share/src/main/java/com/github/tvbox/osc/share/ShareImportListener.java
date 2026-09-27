package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;

/**
 * 导入监听:面向"对端把包推给本机"的平台(局域网),以及"本机挂出地址等对端来取"的等待期通知。
 *
 * <p>为什么导入不像导出那样是个一次性回调:{@code IMPORT_PUSH} 的语义是
 * <b>本机先挂出一个接收口,然后等一个不一定什么时候来的对端</b>。这段时间是有状态的 ——
 * 要告诉用户"让另一台设备访问这个地址",对端真来了要推进度,对端取消/超时要能回到等待态。
 * 用一次性 {@link ShareCallback} 表达不了"等多久"和"来了又走了"。
 *
 * <p>线程约定与 {@link ShareCallback} 相同:<b>所有回调都在主线程</b>。
 * 注册后要显式 {@code ShareFacade.listenImports(platform, null)} 或
 * {@code cancel(platform)} 注销,别把界面对象长期挂在这里(本模块按引用强弱不负责生命周期)。
 */
public interface ShareImportListener {

    /**
     * 接收口状态变化。
     *
     * @param waiting true=已挂出/重新回到等待对端;false=已停止接收
     * @param hint    给用户看的一行字(通常是让对端访问的地址,如
     *                {@code http://192.168.1.23:9978/share/ab12cd});停止接收时可为空
     */
    void onWaiting(boolean waiting, @NonNull String hint);

    /**
     * 收到一个完整可用的归档包。
     *
     * <p>{@link SharePackage#file()} 指向本模块落好的<b>临时文件</b>:监听方负责读它、
     * 校验清单、落地到应用数据,然后<b>自己决定何时删除</b>。不要把它当成长期有效路径。
     */
    void onPackage(@NonNull SharePackage pkg);

    /** 出错(状态非法、读流失败、校验不过等);不影响继续等待下一个对端,除非实现方选择停止 */
    void onError(@NonNull ShareException error);
}
