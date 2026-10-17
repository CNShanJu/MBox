package com.github.tvbox.osc.share.domain;

import java.io.File;
import java.io.IOException;

/**
 * 可导入导出的数据域（订阅源 / 直播源 / 主题 / 设置 / 历史 / prefs / room / subs 等）。
 *
 * <p>这是"统一数据导入导出"的唯一抽象：每类数据实现本接口，向 {@link DataTransferHub}
 * 注册后，局域网配置、本地备份、在线分享等所有链路就都自动获得该数据的导入导出能力，
 * 无需再为某条链路单独改调度代码。
 *
 * <p>约定：
 * <ul>
 *   <li>一个数据域在归档里只产出自己那一个条目文件（{@link #entryName()}）；</li>
 *   <li>{@link #exportTo} —— 导出到文件；</li>
 *   <li>{@link #mergeFrom} —— 合并导入（保留现有数据，按域内规则去重）；</li>
 *   <li>{@link #overwriteFrom} —— 覆盖导入（默认 = {@link #clearData} + {@link #mergeFrom}，
 *       域可按需覆写，例如整库替换型数据）；</li>
 *   <li>{@link #clearData} —— 清空本机该域数据。</li>
 * </ul>
 *
 * <p>领域逻辑（序列化格式、去重规则、存储位置）完全由实现方管理，本模块只负责调度与归档。
 */
public interface DataDomain {

    /** 唯一标识，如 {@code "subscriptions"} / {@code "prefs"}。 */
    String id();

    /** 归档内条目文件名，如 {@code "subscriptions.json"} / {@code "prefs.json"}。 */
    String entryName();

    /** 单条目大小上限（字节）。 */
    long maxBytes();

    /** 是否有数据（用于"空机判断"等场景），可默认返回 {@code true}。 */
    default boolean hasData() {
        return true;
    }

    /** 展示名（结果文案用），默认返回 {@link #id()}。 */
    default String label() {
        return id();
    }

    /** 导出本域数据到 {@code target}（调用方保证该文件可写、随后打包进归档）。 */
    void exportTo(File target) throws IOException;

    /** 合并导入：保留本机现有数据，按域内规则去重/合并；返回本次实际落地的条目数（用于结果文案）。 */
    int mergeFrom(File source) throws IOException;

    /** 覆盖导入：默认先清空再合并；域可覆写以实现更精细的覆盖语义；返回实际落地的条目数。 */
    default int overwriteFrom(File source) throws IOException {
        clearData();
        return mergeFrom(source);
    }

    /** 清空本机该域数据。 */
    void clearData() throws IOException;
}
