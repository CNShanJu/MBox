package com.github.tvbox.osc.share.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次导入/导出调度的只读上下文，供 {@link TransferHook} 读取（选定的数据域、导入语义等）。
 */
public final class TransferSession {

    private final Set<String> domains;
    private final ImportMode mode;
    private final boolean export;

    public TransferSession(Set<String> domains, ImportMode mode, boolean export) {
        this.domains = Collections.unmodifiableSet(new LinkedHashSet<>(domains));
        this.mode = mode;
        this.export = export;
    }

    /** 本次涉及的数据域 id。 */
    public Set<String> domains() {
        return domains;
    }

    /** 导入语义（导出时为 {@code null}）。 */
    public ImportMode mode() {
        return mode;
    }

    /** 是否为导出调度。 */
    public boolean isExport() {
        return export;
    }
}
