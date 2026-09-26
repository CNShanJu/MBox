package com.github.tvbox.osc.log;

/**
 * 日志配置的键值存储端口（依赖倒置：:log 不依赖上层存储模块，由宿主在启动时注入实现，
 * 例如基于 core-storage 配置封装/未来 DataStore 的实现，避免键读写分叉）。
 * <p>
 * 键沿用旧应用 key（"app_log"/"log_level"/"log_retention"，历史设置兼容）。
 */
public interface LogConfigStore {

    boolean getBoolean(String key, boolean defValue);

    int getInt(String key, int defValue);

    void putBoolean(String key, boolean value);

    void putInt(String key, int value);
}
