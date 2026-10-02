package com.github.tvbox.osc.transfer;

import com.google.gson.JsonObject;
import java.util.Set;

/** 已解析的数据来源；界面只展示目录和用户选择，具体传输由实现负责。 */
public interface ConfigImportSource {
    JsonObject catalog();
    String importSelected(Set<String> categories) throws Exception;

    /** 返回 false 表示服务端已撤销当前会话；网络故障应抛异常以便保留会话重试。 */
    default boolean isConnected() throws Exception { return true; }
}
