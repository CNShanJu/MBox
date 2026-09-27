## 新增功能
- **自定义主题颜色**:设置 → 主题颜色 → 最下方「＋ 自定义主题颜色」,可以自建主题并命名:逐一调整各项颜色,**取色板(色相条 + 透明度)或直接填十六进制色值**都行;还能给这套主题单独配背景图(拖动位置、双指缩放、调不透明度与遮罩)。想建几套建几套,点一下即可切换。
- **主题可以发给别人、也能收别人的**:编辑页「导出主题」——不带背景图时导出一份主题文本(同时复制到剪贴板,聊天软件里粘贴就能发),带背景图时导出成一个压缩包;「导入主题」支持选文件或从剪贴板粘贴,导入的内容先进编辑页让你过一眼,确认无误再保存。
- **「局域网服务」开了就知道该访问哪个地址**:打开开关会弹出说明,直接列出同网段设备要访问的地址(每行都能复制);取不到局域网 IP 时会说明原因;下面还有「立即重启应用」,不用自己去后台结束应用。
- **源打不开时会说明原因**:详情页不再只有「暂无数据」——会写明「该源已不在当前订阅中」「插件缺少某个类,请更新订阅或换源」这类具体原因,能分清是源坏了还是这个片子没资源。

## 功能变更
- 主题颜色弹窗从三选一变成列表:跟随系统 / 浅色 / 深色 / 你自建的主题,行尾标出「亮色默认」「暗色默认」与「使用中」;长按自建主题可编辑、设为默认、删除,长按内置浅色/深色可把该类型的默认换成自己的主题。
- **主题改动统一在关闭弹窗后生效**:选完主题(或改完编辑页保存)会重启应用让新配色落地,不会再出现"弹窗还开着、背后的界面已经变色"。
- 用自建主题时,页面背景以主题自带的为准,设置里的「设置背景图」入口会隐藏;切回内置浅色/深色后,你之前设的底图和摆放(缩放/位置/不透明度/遮罩)原样恢复。
- 删除、清空这类不可逆操作的确认键统一改成红底;列表工具条上的「删除」仍是无底色红字 —— 一眼能分清哪个是危险动作。
- 下载页长按多选时,「全选 / 删除 / 取消全选」移到卡片底部,与「可用空间 · 并发」那条占同一格、一次只显示一条。

## 问题修复
- **换过订阅后,新订阅的站点集体打不开、页面一片空白**:根因是上一份订阅留下的爬虫包被当成了可用缓存,现在换订阅会自动清掉并重新下载。
- **爬虫包下载失败时,把上一份能用的包一起毁了**(所有插件源同时打不开):现在先下到临时文件、确认完整才替换,订阅声明了校验值的先校验;这次没拉下来也会退回本地那份可用缓存。
- **开过「局域网服务」再重启应用,服务起不来**(订阅刷新、本地播放、代理会一起失效):现在重启后会按当前开关把服务重新起来。
- **用 Exo 内核拖动进度条后画面卡住不动、声音还在走**:拖完立刻出画,与另一个内核的手感一致(只向前对齐到关键帧,不会越过你的落点)。
- **下载中途被别的应用或拍照吃掉空间**时,会出现分片失败、合并失败,严重时把手机存储写满:现在**可用空间低于 1GB 会自动暂停全部下载**并写明原因,清理空间后点继续即从原进度续传。
- **明明能出 MP4 的剧集常被退回 `.ts`**:现在先校验成品,可用就照旧给 MP4;确实失败也会在完成信息里写明原因,不再悄悄降级。
- 本地视频文件夹列表的缩略图不再被拉伸留白、露出背后的占位图。
- 快速搜索页的返回箭头在深色主题下几乎看不见(以前是写死的黑色),现在跟随主题。
- 二级页顶部标题栏与弹窗右上角的关闭键统一了大小和垂直对齐;**状态栏较高的手机上标题不再被压扁、文字显示不全**。

## 体验与性能
- 下载页的「约大小」和下载前的空间检查以前常偏差到成倍:现在会先抽样探测几片真实分片的大小再推算,更贴近实际。
- 导入背景图(含在主题里选背景图)前先看存储空间,不够会直接告诉你「还需清理约 X」,不会写到一半才失败。
- 弹窗里的长列表,滚动条只在滚动时出现、停手后淡出,不再常亮挡在内容旁边。
- 「检查更新」下载中,悬浮气泡直接显示百分比进度,进度环 / 图标 / 百分比同色;下载完成弹窗的进度条也不再是「蓝条配绿字」。
- 设置页、本地视频页等列表滚动时,内容不再贴到屏幕或标题栏边缘,始终留有内边距。

## 兼容性与注意事项
- 包名未变,可直接覆盖安装,无需卸载。
- 自建主题与主题背景图是这一版新增的能力;你以前设的全局背景图与摆放会被保留,切回内置浅色/深色即恢复。
- 主题编辑页**不提供实时预览**:改完要保存、等应用重启后才能看到效果(有意为之,避免边改边闪)。
- 局域网说明里提到的网页版「搜索 / 推送 / 接口」三个入口这一版还没接上,点不动属正常;文件浏览 / 上传 / 下载 / 删除可用。

<!-- dev -->
本版区间:`v3.5.9..HEAD`(HEAD = 35336f3b,其上还有一批未提交改动,即本版主体)。

主要批次:
1. **自定义主题体系(最大一块)**:`:common` 主题模型(`ThemeType/ThemeKey/ThemeSpec/ThemeDef/ThemeJson/ThemePalette/ThemePaletteFactory`)、
   `:core-storage` 的 `ThemeStore/ThemeFiles/ThemeArchive/ThemeBackgroundLibrary`、app 侧运行时换肤
   (`ThemeRuntime/ThemeContextWrapper/ThemeResources/ThemeInflaterFactory/ThemeDrawables/ThemeColorAliases`)、
   编辑页 `ThemeEditorActivity` + `ThemePickerDialog/ThemeNameDialog/ColorPickerDialog/AttachActionDialog`,
   `build.gradle` 的 `generateThemeColors` 资源生成(20 个可配置键 / 26 个资源名 / 两层透明度:页面卡片 `bg_card_alpha`、悬浮层 `bg_float_alpha`),
   键名迁移(`ThemeDef.SCHEMA = 2`,旧文件里的 `bg_float`/`bg_component_alpha`/`switch_track_on`/`download_done`/`accent_on_dark` 等按迁移表处理),
   离线自检 `scripts/check-theme-res-coverage.mjs`,设计文档 `doc/自定义主题设计.md`;单测 `ThemeJsonTest/ThemePaletteTest/ThemeDerivationParityTest/ThemeColorAliasesCoverageTest/ThemeCommitContractTest`。
2. **分享 / 导入导出骨架(`:share`,新模块,未接界面)**:`ShareFacade` 契约 + 在线(storage.to)/局域网(LAN)/本地三套传输、`ShareArchive`、平台注册按优先级选可用。
   **本版无任何界面入口**(设计文档自述"尚未接入任何界面入口"),在线直链靠解析对端下载页,未联网实测 —— 已在正文里如实写成"这一版没接上"。
3. **存储守护与体积预估**:`StorageGuardPolicy`(1GB 底线)+ `StorageSpace`(唯一 statfs 处)+ `download/internal/StorageWatchdog`(热路径/巡检/调度闸门三入口),
   `HlsSizeEstimator`(分层抽样 + 去极值平均推算 m3u8 体积),契约测试 `StorageSpaceContractTest/StorageWatchdogContractTest`。
4. **源可用性**:`SpiderFaults/SpiderFaultApi/SpiderFaultProviders`(详情页给原因)、`JarCachePolicy` + `HawkConfig.SPIDER_JAR_URL`(换订阅后旧 csp.jar 不再冒充缓存)、
   jar 下载改"临时文件 + 校验 + 完整才替换"。
5. **界面统一**:`AppTitleBar` 收成自包含组件(9 个二级页;高度 = 内容区 `CONTENT_HEIGHT_DP` 44dp + 状态栏 padding,布局写 `wrap_content` —— 之前写死 45dp 含状态栏,标题被压),
   返回图标改 `ic_seek_left`、弹窗关闭键统一 `DialogCloseButton`、`SelectActionBar` 公共多选操作栏、滚动条 thumb 只在滚动时显示、
   卡片/列表 `clipToPadding` 与内边距校回、更新气泡进度与完成态配色。
6. **其它**:`ExoMediaPlayer` 设 `SeekParameters.PREVIOUS_SYNC`(拖进度条后画面卡死)、局域网服务生命周期(HomeFragment 起停 + 重启恢复)、
   `SystemStateMonitor` 有网/没网单一口径、`LanAddressRules`(局域网地址修正)、`BgImageImporter`/`ThemeArchive` 导入前存储预检。
7. 文档:AGENTS/README/`doc/项目状态速查.md`/`doc/接口解析.md`/`doc/audit-fixes-and-refactor-status.md`/`doc/自定义主题设计.md`/`doc/分享导入导出设计.md`。

已知未完成 / 风险:
- `share/` 无界面入口;在线平台的直链解析无官方接口、平台改版即失效(`ResolvedDownload` 自标实验性);在线开关与基址没有任何界面能改。
- 主题换肤覆盖不到的少数第三方属性(如 `hl_textColor`)与复杂矢量图标仍是内置配色,`scripts/check-theme-res-coverage.mjs` 会列出剩余项;
  换肤在视图创建时应用,极端情况下首帧可能闪一下内置配色。
- `ThemeEditorActivity` 的 `EXTRA_THEME_DARK` 声明后全仓无读写(按主题类型取默认纯色的路径实际未接线,目前由编辑页兜底)。
- `activity_movie_folders.xml` 里的公共操作栏一直隐藏(合集页没有多选删除),接线处于悬空状态。
- 主题编辑页文案硬编码中文,未资源化。
- `drawable/button_detail_quick_search.xml` 的描边 `2mm` 是历史遗留(不是本版引入),直播页聚焦态边框会偏粗。
- `item_theme_color.xml` 的颜色行说明文字 `tv_desc` 被注释掉(与 `ThemeEditorActivity` 的"要恢复就解注释"对应),主题编辑页每项只剩名称。
<!-- /dev -->
