package com.github.tvbox.osc.share.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareException;

/**
 * "分享引用 → 可下载文件"的解析策略(可替换)。
 *
 * <p>为什么要抽出成接口,而不是把解析逻辑写死在 transport 里 —— 这是本模块最容易烂掉的一环:
 * 解析依赖<b>对方的页面/接口结构</b>,而 storage.to 已经改过一次(下线 {@code /r/} 直链)。
 * 把它关在一个小接口后面,改版时:
 * <ul>
 *   <li>只需换一个实现(或在设置里换基址/换平台),{@link StorageToTransport} 与界面都不用动;</li>
 *   <li>可以按引用形态分派(集合清单走 JSON、单文件页走 HTML),每个形态一个实现,
 *       互不影响 —— 某一种失效不会连累另一种;</li>
 *   <li>能单测:喂进去一段 HTML/JSON 文本,断言解析结果,不需要真的联网。</li>
 * </ul>
 *
 * <p>实现约定:无法解析时抛 {@code PARSE} 且文案要<b>给出下一步</b>
 * (如"请用集合链接,或自行下载后从本地导入"),不要只说"解析失败"。
 * 网络层失败抛 {@code NETWORK},不要把两种情况混在一起。
 */
public interface StorageToLinkResolver {

    /**
     * 解析用户给的引用。
     *
     * @param shareRef 用户输入(分享页地址 / 集合清单地址 / 纯 id 均可,由实现决定容错到什么程度)
     * @return 可下载目标(非 null)
     * @throws ShareException 无法解析({@code PARSE})或取不到内容({@code NETWORK}/{@code HTTP})
     */
    @NonNull
    ResolvedDownload resolve(@Nullable String shareRef) throws ShareException;
}
