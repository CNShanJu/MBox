package com.github.tvbox.osc.share.domain;

import java.io.IOException;
import java.util.List;

/**
 * 导入保护：在逐域分发前后提供「快照 + 锁 + 事务 + 回滚」的一致性保护。
 *
 * <p>默认实现 {@link DefaultImportProtection} 即配置导入的 applyWithRollback 语义
 * （{@code ImportFileCheckpoint} 文件快照 + Room 事务 + Prefs 回滚 + 多把锁）。
 * 下游（如备份还原）可重写 {@link #runProtected} 换成自己的保护 —— 例如备份还原的
 * 「先做 SystemBackupStore 保护快照，失败回滚、成功清理」。
 */
public interface ImportProtection {

    /**
     * 在保护范围内执行 {@code action} 并返回导入结果。
     *
     * @param session 本次导入上下文（选定的数据域、导入语义）
     * @param action  逐域分发动作（调用方实现对每个域执行 merge/overwrite/clear）
     */
    List<String> runProtected(TransferSession session, ImportAction action) throws IOException;

    /** 在保护范围内执行的逐域分发。 */
    interface ImportAction {
        List<String> run() throws IOException;
    }
}
