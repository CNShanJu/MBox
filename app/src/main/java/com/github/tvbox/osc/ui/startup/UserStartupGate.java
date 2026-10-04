package com.github.tvbox.osc.ui.startup;

/** 启动专属动作从宿主读取已确定的来源，不自行推断 Activity 或进程状态。 */
public interface UserStartupGate {
    boolean isUserInitiatedLaunch();
}
