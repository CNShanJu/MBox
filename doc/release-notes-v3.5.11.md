## v3.5.11

## 问题修复

- **反复启动不再被误判成「安全模式」**:重装应用、切换主题这类正常的快速重启不再计入「连续崩溃」,只有真实崩溃才算 —— 不会再无故以安全模式启动、少加载源。
- **退出播放、切换内核后画面残留 / 偶发卡顿**:播放器释放顺序修正(先把画面解绑再释放内核),退出更干净。
- **个别源卡死拖住整个搜索**:卡死的源会被识别并跳过,一次搜索不会再卡到没反应。
- **异常响应把内存吃满**:下载与播放遇到超大响应体时会主动截断并记入日志,不再无限往内存里堆。
- **画中画**:详情页与本地播放进入画中画时不再出错。
- **搜索页的历史搜索 / 热门搜索 / 联想标签圆角被裁**:换成自研的流式标签容器(行高把标签自己的外边距算全),内置主题下标签四角完整、边框是细线。
- 搜索页「热门搜索」拿不到词时,不再只留一个空标题。
- 搜索页两个小标题统一左对齐(原来一个靠左、一个居中)。
- 直播页频道 / 分组列表的选中底色与文字色跟随主题,不再是一块「外来色」。
- **详情页截图后的「跳转阿狸 / 优汐 / 夸父 / 关闭」改用应用统一的居中选择弹窗**:配色与圆角跟其它弹窗一致(原来是另一个风格的下拉列表)。

## 体验与性能

- **更省电、发热更低**:列表图片的闪烁动画在离屏或不可见时自动停止;每次搜索不再重复预加载;错误日志的采集做了节流;网页嗅探用完即释放;海报占位与更新气泡不再反复新建绘制对象。
- 下载进度改为节流写入,长时间下载更省电。
- 弹窗与抽屉的圆角、描边统一到一个来源,不会再出现「同一个抽屉里有些圆有些方」。
- 搜索页历史 / 热词、日志分类、播放设置这些小按键的边框线改细,不再显重。

## 兼容性与注意事项

- **自定义主题(自建主题)的样式问题这一版仍未完全解决**:在自定义主题下,个别组件的圆角 / 描边可能和内置主题不一致(搜索页「历史搜索」那几个标签最明显)。这一版已经让换肤尽量**以内置主题的圆角为准**做了统一,但**残留问题还在,下一版继续修** —— 在意观感的话,建议先用内置的「浅色 / 深色」主题。
- 包名未变,可直接覆盖安装,不需要先卸载。

<!-- dev -->
本版区间:`v3.5.10..HEAD`,其中 HEAD 之上还有一整批未提交改动 —— **本版主体就是这批**。

一、稳定性 / 资源批次(工作区未提交):
- 启动:`base/StartupGuard`(崩溃熔断 5 分钟窗口/3 次/10 分钟冷却、错误进程最小启动、CrashStateCollector、阻塞式落盘)、`base/App` 早退与 `catch (Throwable)`、`util/PipHelper` + DetailActivity/LocalPlayActivity 的 PiP 守卫。
- 播放:`player/AbstractPlayer#detachSurface/releaseAsync`、`ijk/IjkPlayer`(本地引用 + 同步 setSurface(null) + 不置空字段)、`AndroidMediaPlayer`、`VideoView`、`render/TextureRenderView` 释放后置空、`SubtitleCoordinator` 可取消恢复 + epoch、`util/player/TrackRestoreGuard`。
- 源与网络:`util/js/Global#releaseOnJsThread/safeRelease`、`JsSpider`(isIdle/markWedged/64MB setMemoryLimit)、`JsLoader` LRU(MAX_LIVE_SPIDERS=16)、`UA` 缓存、`FileUtils.getAsOpen` 关流、`core-network/HttpBodyReader`(文本 16MB / 二进制 24MB 上限)。
- 下载与日志:`DownloadManager` 进度指纹 + 2s 节流、`LogStore.insertAllBlocking/flushNowBlocking`、`LogcatCapture` 批次按成功清理 + 60s 节流、`DownloadStore` 整段 try、`download/AndroidManifest.xml` 服务全名。
- 发热:图片闪烁自停(`PicassoShimmer`,30s 上限 + 缓存渐变 + setLocalMatrix)、`FastSearchAdapter` 预取消 + 已加载判断、`PicassoLoad` LRU(1000)、`PosterPlaceholderDrawable` 覆盖条缓存、`WebSniffResolver` 60s 空闲释放、`ScrollThumbIndicator`、`PageBackgroundView` 遮罩改 dispatchDraw、`UpdateBubbleView` 缓存 density。

二、主题 / 界面批次:
- 抽屉与弹窗统一:`AppBottomPopupView` 的 `show()` 兜底(直呼 show 不再抛 popupInfo is null)、`BottomListDialog`(日志日期 + API 历史)、MyFragment 关于、LiveApiDialog、VideoDetailDialog 全走 `DialogCoordinator.bottom`。
- 圆角与描边收口:`common_corners`(原写死在 dimens.xml)与新增 `stroke_widget_btn` 都进 `theme_radii.json`;清掉两处 2mm 描边(button_detail_quick_search / shape_setting_sort_focus)、删掉死常量 `DialogStyle.CORNER_RADIUS_DP=25`、`input_dialog_api_input` 换成 0.5dp 底线后删除。
- 搜索页:**自研 `ui/kit/FlowTagLayout` + `ui/kit/FlowLineBreaker` 取代 `com.hyman:flowlayout-lib`**(该库行高漏算子视图上下外边距 → 标签最后一行被裁掉下半截,即"上圆下方"的根因),依赖已删;API 同名同形;`FlowLineBreakerTest` 7 条断言钉住;3 个布局 + 2 处调用点迁移。
- 详情页截图弹窗:`asCenterList` → `SelectDialog`(并给 SelectDialog 增加 `select=-1` 的"动作列表"语义)。
- 新绊线:`ThemePillShapeContractTest#widgetButtonRadiusAndStrokeFitTheKeyHeight`(已反证:16dp/34dp=47% 会红)、`RadiusCheckTest`、`DialogPanelRadiusContractTest`、`ThemeStyleCoverageTest` 等。
- 自检:`theme/RadiusCheck`(启动打一行"包内 dimen vs 主题文件 vs 换肤重建后的四角圆角 + app/系统密度")。

三、构建侧(AS 部署优化陷阱):
- 现场证据:AS 的部署链路会删掉 `intermediates/processed_res/<变体>/*.ap_`,并在 `outputs/apk/debug` 留一个 149 条目、没有 `res/`、没有 `resources.arsc`、没有 AndroidManifest 的包 → 资源类改动永远不上机(用户口径"改圆角没反应"的一半)。
- 兜底:`app/build.gradle` 的 `package*` doFirst 删不完整 APK、`preBuild` 自愈缺失的 `processed_res/<变体>`;`scripts/check-apk.mjs`(零依赖 APK 校验,坏包 exit 1)。
- 用户可见验证口径:外观改动必须**完整安装**(`gradlew :app:installDebug` 或装 release 包);AS 点运行不带资源。

四、已知未完成(下一版):
- 自定义主题下圆角仍有残留问题(用户口径:内置主题四角正常、切自定义主题不正常)。已做 `ThemeDrawables.keepCompiledCorners`(重建后把编译期那份的圆角逐状态抄回,只抄圆角、颜色与描边仍走主题),**待真机复验**;复验用启动日志里的 `圆角自检 [自定义主题] …;编译期 chip=… ;换肤重建 chip=…` 两段对比。
- 审计清单剩余项:onTrimMemory/onLowMemory(14)、外部可控项(15–18)、Room fallbackToDestructiveMigration(22,需用户定数据取舍)。
<!-- /dev -->
