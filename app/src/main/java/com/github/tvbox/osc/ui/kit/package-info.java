/**
 * ui-kit(改进.txt §2.7 第一阶段:app 内独立 package,确认复用后再拆 Gradle 模块)。
 * <p>
 * 收录"通用可复用 UI 组件":AppSwitch / AppTitleBar / RatioShadowLayout / ClearEditText /
 * RecyclerView ItemDecoration 等。
 * <p>
 * 边界铁律(新迁入组件必须满足):
 * <ul>
 *   <li>不依赖具体 Activity 类型;</li>
 *   <li>不读取 Hawk、ApiConfig、业务单例;</li>
 *   <li>不发送 EventBus 业务事件;</li>
 *   <li>状态与事件通过属性/接口/ViewModel 传入;</li>
 *   <li>文案/颜色/尺寸可配置(优先走主题资源 R)。</li>
 * </ul>
 * 播放器/业务强耦合视图(PlayerMenuView/PlayerTitleView/FrostedGlassUtil 等)留在原 widget 包,
 * 归属 playback/业务侧,不入本包。
 */
package com.github.tvbox.osc.ui.kit;
