package com.github.tvbox.osc.log;

/**
 * 日志小类型契约：各业务模块自持的小类型枚举必须实现本接口，
 * code 为落库/筛选稳定值（永久不变，历史日志兼容），label 为日志页展示名。
 */
public interface SubType {
    String code();

    String label();
}
