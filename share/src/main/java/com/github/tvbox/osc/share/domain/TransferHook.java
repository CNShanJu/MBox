package com.github.tvbox.osc.share.domain;

import java.io.IOException;

/**
 * 导入/导出的定制化钩子：在通用调度流程（归档打包/解压、逐域分发）前后插入下游自定义逻辑。
 *
 * <p>典型用法（本地备份还原）：覆写当前数据前先做一次保护快照，成功则清理、失败则回滚 ——
 * 由实现方以有状态匿名类承载快照句柄：
 * <pre>{@code
 * TransferHook protect = new TransferHook() {
 *     private File snapshot;
 *     public void before(TransferSession s) throws IOException { snapshot = preserveCurrent(); }
 *     public void after(TransferSession s, boolean ok) throws IOException {
 *         if (ok) cleanup(snapshot); else rollback(snapshot);
 *     }
 * };
 * }</pre>
 */
public interface TransferHook {

    /** 通用流程执行前调用。 */
    default void before(TransferSession session) throws IOException {
    }

    /** 通用流程执行后调用；{@code success} 表示域分发是否全部成功。 */
    default void after(TransferSession session, boolean success) throws IOException {
    }
}
