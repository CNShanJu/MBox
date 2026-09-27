package com.github.tvbox.osc.share.transport;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.share.ShareAvailability;
import com.github.tvbox.osc.share.ShareCallback;
import com.github.tvbox.osc.share.ShareCapability;
import com.github.tvbox.osc.share.ShareImportListener;
import com.github.tvbox.osc.share.ShareLimits;
import com.github.tvbox.osc.share.ShareLink;
import com.github.tvbox.osc.share.SharePackage;
import com.github.tvbox.osc.share.SharePlatform;
import com.github.tvbox.osc.share.ShareRequest;

import java.util.Set;

/**
 * 分享传输契约 —— 本模块的核心扩展点。
 *
 * <p>设计目标就是"在线平台将来不可用能提前换掉":上层(界面/门面)只认这个接口,
 * 换平台 = 新增一个实现 + 在 {@link #platform()} 上挂一枚
 * {@link SharePlatform} 值,调用方一行不改。因此本接口<b>不得</b>出现任何平台专有概念
 * (不能有 r2_key / upload_id / NanoHTTPD 这类词),平台细节一律关在实现里。
 *
 * <p>能力不对称,调用前先看 {@link #capabilities()}:
 * <ul>
 *   <li>{@link ShareCapability#EXPORT}:{@link #export} 可用;</li>
 *   <li>{@link ShareCapability#IMPORT_PULL}:{@link #pull} 可用(在线链接);</li>
 *   <li>{@link ShareCapability#IMPORT_PUSH}:{@link #listen} 可用(局域网对端上传)。</li>
 * </ul>
 * 不支持的方法<b>不要调用</b>;真被调到了,实现必须抛
 * {@code UnsupportedOperationException}(这是编程错误,不是用户可恢复的失败,
 * 所以不进 {@link ShareCallback#onError})。用户可恢复的不可用一律表达为
 * {@link #availability()} 给出的 {@code false} + 原因。
 *
 * <p>线程:所有回调一律在主线程({@link ShareCallback} 有约定);实现内部自己切线程,
 * <b>禁止在调用方线程上做网络/大文件 IO</b>(调用点常在 UI 线程)。
 *
 * <p>取消:同一 transport 同一时刻只认最后一次操作。{@link #cancel()} 之后,
 * 在跑的旧操作必须在回调上以 {@code CANCELLED} 收尾,并丢弃随后迟到的结果
 * (不能出现"取消了又报成功")。
 */
public interface ShareTransport {

    /** 本实现对应的平台标识(注册表按它索引,必须与实现一一对应) */
    @NonNull
    SharePlatform platform();

    /** 支持的能力集合(非空;只含真实实现的) */
    @NonNull
    Set<ShareCapability> capabilities();

    /** 是否支持某能力(便捷方法,默认按 {@link #capabilities()} 判断) */
    default boolean supports(@NonNull ShareCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * 当前可用性。可能随设置/网络/服务实例变化,<b>每次操作前重新取</b>,不要缓存。
     * 不可用时 {@link ShareAvailability#reason()} 必须是一句能给用户看的话。
     */
    @NonNull
    ShareAvailability availability();

    /** 平台限额(用于导出前本地预检与界面展示),不知道就给 {@code ShareLimits.UNKNOWN} */
    @NonNull
    ShareLimits limits();

    /**
     * 导出:把归档包发出去,成功后给出 {@link ShareLink}。
     *
     * <p>要求 {@link ShareCapability#EXPORT}。不可用时直接以
     * {@code UNAVAILABLE}(带 availability 的 reason)失败,不要先传一半再报错。
     */
    void export(@NonNull ShareRequest request, @NonNull ShareCallback<ShareLink> callback);

    /**
     * 导入·拉取:按用户给的引用(在线分享链接/清单地址)取回归档包。
     *
     * <p>要求 {@link ShareCapability#IMPORT_PULL}。注意在线平台可能<b>没有直链</b>
     * (storage.to 已下线 {@code /r/}),实现需要先解析再下载;解析失败要报
     * {@code PARSE}(附上"请改用分享页/换平台"的提示),而不是 {@code NETWORK}。
     *
     * <p>返回的 {@link SharePackage#file()} 指向本模块落好的临时文件,调用方负责用后清理。
     */
    void pull(@NonNull String shareRef, @NonNull ShareCallback<SharePackage> callback);

    /**
     * 注册导入监听(推送式导入:本机挂接收口,等对端上传)。
     *
     * <p>要求 {@link ShareCapability#IMPORT_PUSH}。传 {@code null} 表示注销。
     * 幂等:重复注册以最后一次为准(旧监听不再收到回调)。
     */
    void listen(@NonNull ShareImportListener listener);

    /** 取消当前在跑的操作(导出/拉取/接收),并让其在回调上以 CANCELLED 收尾 */
    void cancel();

    /**
     * 释放资源并摘掉内部监听。由 {@code ShareFacade} 在注销/应用退出时调用。
     * 与 {@link #cancel()} 的区别:后者只是停一次操作,本方法让实例回到"未使用"状态
     * (如局域网会话撤销、临时文件清理)。释放后仍可再次使用。
     */
    void release();
}
