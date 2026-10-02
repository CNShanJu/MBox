# MBox 项目规则(AGENTS)

> 项目名:MBox(仓库 `CNShanJu/MBox`,原名 TVBoxOS-Mobile / 工程名 TVBoxMobile,更名已落地于代码与文档)。

> 本文件将仓库根目录《改进.txt》的限制与要求固化为**项目级规则**,所有代码改动(人工与 AI)必须遵守;
> 并沉淀安全审计中的**持续约束型**红线(见「七、安全与工程红线」,一次性修复明细不入本文件)。
> 原始文档见仓库根 `改进.txt`(经确认已纳入版本库跟踪,不再豁免提交);本文件是其在版本库中的权威落地,随 git 一起跟踪与演进。两份文件需保持一致,改规则/改要求时同步更新。

---

## 一、目标架构(依赖方向,强制)

最终分四层,依赖只允许自上而下,**禁止反向**:

```
app / feature
    ↓
业务 API:PlayerApi、SpiderService、DownloadFacade
    ↓
业务实现:player、spider、download
    ↓
基础设施:network、storage、state、log
    ↓
纯模型:model
```

- 业务模块只通过公开契约协作(接口/门面),具体实现放 internal 包。
- 禁止基础模块反向依赖业务或 UI。
- 同一领域模型只定义一次;跨模块不共享可变对象。
- 所有后台任务必须由模块级执行器管理,**页面/UI 不得自行创建线程池**。
- UI 组件不得读取全局配置(Hawk/ApiConfig 等)或访问业务单例;状态与事件经属性、接口或 ViewModel 传入。

## 二、模块边界(现状模块与残留治理)

现状:**10 个模块** —— `:app`、`:common`、`:core-network`、`:core-storage`、`:log`、`:player`、`:spider`、`:download`、`:share`、`:thirdparty`。
合模块已落地(契约与实现同模块后,靠**源码层门禁**守边界,不再靠 Gradle 拆模块):`:core-model`/`:core-utils`/`:state` → `:common`;`:spider-api` → `:spider`;`:player-api` → `:player`;`:crash`/`:TabLayout`/`:ViewPager1Delegate`/`:quickjs` → `:thirdparty`;`:ui-common` → `:app`(主题 JSON 在 `app/src/main/assets/theme/`,`generateThemeColors` 任务在 `app/build.gradle`)。

| 模块 | 边界要求 |
|---|---|
| `:common` | 全局共用:纯模型(`com.github.tvbox.osc.bean.*`,不依赖 Android/Hawk/Room/ApiConfig)+ 算法工具(AES/MD5/AdBlocker)+ 系统状态(`.state.*`);由原 `:core-model`/`:core-utils`/`:state` 三模块合并,包名不变 |
| `:core-network` | 收敛网络客户端(OkGoHelper/HttpClient/各 OkHttp 创建),业务不得自行 `new OkHttpClient.Builder()` |
| `:core-storage` | Room/缓存/配置收口,对外只暴露 Repository/类型安全 Config,不暴露 DAO;storage 不得依赖 spider/download/player |
| `:spider` | 爬虫**契约 + 实现同模块**(原 `:spider-api` 已并入):契约(SourcePage/Category/Detail/Search/Play 与 SourceConfigProviders/ParseConfigProviders/LiveChannelConfigApi/SourceLoaderApi/PlayUrlResolverProviders/IjkCodecConfigProviders/SpiderFaultProviders)、QuickJS/JarLoader/ApiConfig/具体 Spider;**app/UI 一律经契约,禁止直连 `ApiConfig`/`JsLoader`/`PlayUrlResolver`**(`checkModuleDependencies` 已加该红线,白名单只剩组合根/启动注入点) |
| `:player` | 播放**契约 + 实现同模块**(原 `:player-api` 已并入):契约(PlayerSession/PlayerState/PlayerOptions/PlayerEvent/PlayerFactory)与 MyVideoView/IJK/Media3 同模块;UI 不得直接依赖具体内核 |
| `:thirdparty` | 纯第三方归堆,不依赖任何本仓模块:TabLayout(`com.angcyo.tablayout`)、CustomActivityOnCrash(`cat.ereza.customactivityoncrash`,原 `:crash`)、QuickJS(`com.whl.quickjs.*` + `src/main/jniLibs`,原 `:quickjs`) |
| `:download` | 只公开 DownloadFacade.enqueue/pause/resume/delete/observe;Manager/Scheduler/Executor/Archive 等为模块内部实现 |
| `:share` | 分享/导入导出(传输层):契约(`ShareFacade`/`ShareTransport`/`ShareCapability`/`ShareLink`/`SharePackage`/`ShareManifest` 等)+ 实现(`online`=storage.to、`transport.lan`=局域网、`local`=本地文件)同模块;平台**可插拔**(注册顺序即优先级,`availablePlatforms/firstAvailable` 自动选可用平台),在线平台不可用时换实现而不改调用方。局域网 HTTP 服务在 `:app`,经 `transport.lan.LanShareHost` 桥接注入,故本模块**不得依赖 `:app`**;`internal`/`online`/`local` 为内部实现,**app 禁止 import**(门禁已加) |
| `:log` | 日志(LogStore + 落盘/Logcat 采集),不依赖任何业务模块 |
| `:app` 的 ui-kit/ui-common | 主题资源(原 `:ui-common`)与通用组件已全部收在 app 内(app 的 `res` + `ui/kit` package);组件成熟后再考虑拆模块 |

现状残留(持续治理,新代码勿新增同类):
- app 仍直用 `MyVideoView`/IJK/Media3、`PlayerTrackHelper` 经内核能力接口分发(播放器收口长线)。
- **旧 ExoPlayer 已迁至 Media3 1.4.1**(匹配 compileSdk 34):类型 2 沿用旧配置,`EXOmPlayer`/`xyz.doikki.videoplayer.exo` 为兼容类名,实现只用 `androidx.media3`。禁止重新引入 `com.google.android.exoplayer` 依赖或旧包 import;Media3 各组件必须同版本,UI 不得 import `androidx.media3`。取流复用组合根注入的客户端,每个媒体源独立持有请求头快照;音轨/字幕选择用 `Tracks`/`TrackSelectionOverride`,字幕回调用 `CueGroup`。真机行为待用户人工回归。
- `:core-network` 已只剩网络职责(OkGoHelper/HttpClient/HttpUrls/FCallBack/HCallBack/SSLCompat/urlhttp 的 brotli 拦截器);AES/MD5/AdBlocker/AppLog/LOG 已迁出。残留:网络客户端装配与通用工具仍同包,后续可按职责再分目录。
- **日志只有一条写通道**:业务日志一律 `LogStore`(`:log` 模块,结构化落 Room),错误流由 `LogcatCapture` 落 `filesDir/app_logs/logcat-*.log`;旧 `AppLog` 按天文件通道**已删除**(它与 LogStore 共用 `"app_log"` 开关,一开日志就双写且 `app-*.log` 永不清理、界面不可见;调用点已全部迁移)。新代码**不得再新增文件级日志通道**。
- `:spider` 字符串通道(SpiderContentApi)为过渡兼容层,新功能不得新增字符串协议依赖。
- app 已无直连 `:spider` 实现的代码:批量下载解析经 `PlayUrlResolverProviders`(spiderapi 契约,组合根注入 `SpiderUrlResolverImpl`),JS 源运行态(取消在跑任务/清空源实例)经 `SourceLoaderProviders.stopAllSourceTasks/resetSources`。门禁 `spiderImplAllow` 白名单已收净,只剩组合根 `di/AppCompositionRoot`、启动 `base/App`、`server/ControlManager` 三个装配/注入点。
- 未建 `:playback` / feature-* 模块(第三/四阶段,需真机回归环境再动)。

## 三、大页面拆分目标(§三)

- **PlayFragment**:应拆为 PlayFragment(只绑 View/生命周期)+ PlayViewModel(页面状态)+ PlayCoordinator(换源/解析/重试)+ PlayerSession(播放控制)+ SubtitleCoordinator + PlayHistoryRepository。
  - Fragment 不应直接持有 Spider、ExoPlayer、IJK 或 DAO;解析/嗅探引擎已抽 `util/player/PlayParseCoordinator`。
  - 已抽:`PlayRequest`/`PlaySessionKeys`/`PlayedVodKey`/`SubtitleCoordinator`/`PlayHistoryRepository`/`PlayParseCoordinator`;剩余大块(startPlayUrl 等)仍与控制器耦合,拆分需真机回归。
- **DetailActivity**:应拆 DetailViewModel/DetailRepository/EpisodeSelectionState/DetailDownloadCoordinator/QuickSearchCoordinator;Activity 只处理导航、弹窗、视图事件。
- **SourceViewModel**:只依赖 SpiderService 等契约(可 FakeSpiderService 做 JVM 单测),不得直调 `ApiConfig.get().getCSP()` 之类具体爬虫。

## 四、依赖注入(§四)

- 不强制全面引入 Hilt;先建 app 层组合根 `AppCompositionRoot`(network/storage/spider/download/players 等)。
- 静态 get() 保留为兼容入口;新代码一律构造注入接口;ViewModel 经 Factory 取依赖;旧调用逐个迁移后静态入口清零。
- 依赖注入的目的是可替换实现与便于测试,不是为框架而框架。

## 五、状态与事件(§五)

- 页面状态:ViewModel + LiveData(逐步 Kotlin 化后 StateFlow);一次性页面事件用明确 Event 类型。
- 跨页状态(下载/播放):由 Facade 提供订阅接口(如 `DownloadFacade.DownloadStatusListener/TaskProgressListener`、`PlaybackSessions`)。
- 系统状态(电量/网络):统一 SystemStateMonitor。
- **EventBus 已全仓移除(app + download,依赖已从 classpath 删除),禁止再引入**;跨页/模块事件一律用直调、Facade 订阅接口或明确监听器(如 `DownloadFacade` 内部经 `DownloadManager.EventSink` 直调扇出、播放通知经 `PlayService.onPlaybackNotify` 直调)。`checkModuleDependencies` 已加 app 层 EventBus 红线;移除依赖后任何模块新增 EventBus 均编译不过。

## 六、验收红线(新代码不得触碰)

1. app 内基本搜索不到 `getCSP()`、`DownloadManager.get()` 与具体播放器内核直调(见 `checkModuleDependencies` 门禁)。
2. UI/页面层禁止裸读 Hawk 或具体配置单例(一律经业务 Config 门面读写,如 SystemConfig/LiveConfig/PlayConfig/SubscriptionConfig;底层为 core-storage `PrefsDataStore`/DataStore)。
3. UI 禁止自建线程池;禁止 `(Activity) context` 强转具体 Activity 依赖弹窗/组件(依赖经构造注入窄宿主接口)。
4. 禁止引入/使用 EventBus(已全仓移除,依赖不在 classpath);跨页/模块事件一律直调或经 Facade 订阅接口。
5. 模块依赖只增公开契约接口;Gradle 依赖默认 `implementation`,谨慎 `api`。
6. 业务/后台任务必须用模块级共享执行器(如 `HeavyTaskUtil`),且带取消/过期自检语义(epoch),不得每轮 new 线程池后 shutdown 了事。
7. 所有可滚动组件(RecyclerView/ScrollView/GridView/横向列表等)必须保留**内边距**并配合
   `clipToPadding=false` 让首/末内容不贴到屏幕或容器边缘滚动;禁止内容贴边滚动影响视觉效果。
8. 用户主动触发的删除、清除、清空操作必须先弹确认框;取消后不得改动数据。批量操作应说明影响范围,
   删除正在使用的直播源等会改变当前选择的操作应说明后续行为;输入框清字和页面临时状态重置不属于此类。

## 七、安全与工程红线(持续约束)

**网络与 TLS**
- 禁止全局关闭 HTTPS/TLS 校验(如 WebView `onReceivedSslError` 一律 proceed、HttpClient 全信任);确需忽略证书只允许"按域 + 用户显式开关"且默认拒绝(cancel)。
- 局域网/回环服务(RemoteServer 等)按需启动、用毕即停;对外暴露最小化,敏感操作必须带鉴权/令牌,禁止无鉴权读写。

**输入与文件安全**
- 本地路径一律防目录穿越:拼接前校验并规范化,拒绝 `..`/绝对路径逃逸出目标目录。
- 解压(压缩包/归档)防 Zip Slip:每个条目的最终落盘路径必须位于目标目录内,解压前校验。
- HLS/代理分片等高频落盘必须节流/限频,禁止无界写盘。

**数据、并发与性能**
- Room/DB 查询禁止主线程执行;需即异步(协程/执行器)或缓存预热。
- Gson 等解析器复用实例并建索引/缓存,禁止热路径重复构建;UA 等常量数组化,避免每请求新建数组。
- 启动阶段不做阻塞式删缓存/重 IO;大资源懒加载,页面销毁即释放。
- 列表更新用 DiffUtil 时保证差量正确(LocalVideoAdapter/下载列表等禁止 O(n²)/整表 notify);页面/Adapter 不得把视图/Activity 持有到生命周期外(视图泄漏)。

**Android 暴露面**
- Manifest 权限最小化:只声明实际使用权限;组件(Activity/Service/Receiver/Provider)按需 `exported`,不对外暴露者一律 `android:exported="false"`,敏感暴露组件配权限保护。

**依赖、构建与资源**
- 依赖升级与版本对齐走显式批次(Room/Media3 等跨模块依赖版本一致),禁止引入冗余/重复依赖;同步清理死源码与无效 import。
- Gradle 开启/维护缓存与并行构建,避免本地与 CI 行为漂移。
- 图片加载统一单一图片库,不复用多套;网络状态/电量等系统状态统一经 SystemStateMonitor 单点订阅。
- **全局复用同一个图片占位符**:全 App 图片(海报/封面/缩略图等)占位与加载失败统一用
  `placeholder_poster`(灰底+居中图标,主题感知)。**占位图标当前用 `ic_placeholder_cat`**
  (由 `D:\Code\Video\icon\drawing (1).svg` 导出;位图按 96dp 各密度置于 drawable-<dpi>,灰底+透明黑猫撕纸);
  原矢量 `ic_image_placeholder.xml`(`D:\Code\Video\icon\图片加载失败.svg` 转)保留为回退,不再被引用。
  禁止各页面/适配器自行引入第二套占位图
  (如 iv_load_fail / img_loading_placeholder / ic_img_placeholder / ic_poster_placeholder 等),发现即收敛到统一占位。
  **占位只有一套实现**:`util/PosterPlaceholderDrawable`(加载态 `loading` / 失败态 `failed`,色值圆角图标与
  XML `placeholder_poster` 同源)+ 统一加载入口 `util/PicassoLoad`(URL 走 `into`、本地文件走 `intoFile`);
  XML `placeholder_poster` 只作**布局里的静态形态**(首帧),运行时占位一律经 `PicassoLoad` 铺。
  **占位必须放 ImageView 的背景层,禁止塞进 src / `.placeholder()`**:占位是"灰底+居中图标",
  塞 src 会被 scaleType(centerCrop)当普通图片等比放大再裁剪,小卡片上会**超出显示范围**
  (下载页 56×74dp 封面曾经的故障)。**图标尺寸必须随宿主自适应**:位图各密度都是 96dp 且几乎撑满画布,
  宿主小于 96dp 时按比例缩(只缩不放),否则一样会溢出。
  **失败态文案位置有硬约束**:海报卡底部有一条渐变黑底信息条(`view_search_poster_card.xml` 的
  `llNoteBar`、`item_grid.xml` 的底部覆盖条),它绘制在 ImageView 背景之上,故"图片加载失败"文字与图标
  必须排在**该条上方**的可用区内。实现见 `util/PosterPlaceholderDrawable#failed`:**整组在卡片里垂直居中**,
  覆盖条高度由宿主视图自动量(排在图片之后、可见、贴住图片底边、只占下半部分、非 TextView/ImageView 的兄弟),
  居中放不下就等比缩小图标(下限 40dp)、再放不下才整体上移、最后只留文字;宿主没有覆盖条时纯居中,
  **不要再固定预留一段高度**(固定预留会让整组贴到卡片顶部,观感上"整体往上飘")。
  新增海报卡布局时按上述形态摆覆盖条即可被自动识别,无需改绘制代码。
  不要再把失败文字贴底绘制,否则通栏/宫格等带信息条的形态会被整条盖住。
- 多语言资源按需裁剪(`resConfigs`),禁止无界塞入语言包。
- **主题文字色只配主色与高亮色**:`brand` 管正文、标题与普通选中文字;
  `text_accent` 保留为同值派生资源,不再是主题文件/编辑器的独立配置项。
  `text_highlight` 单独控制链接与搜索来源;
  旧主题文件里的 `text_accent` 值读取与导入时静默忽略,旧版对调过的高亮值仍需正确迁移;
  自动升级键名本身不提示,确实丢失其他配置时仍需提示。
- **内置亮/暗主题也必须由运行时调色板供色,禁止让 `-night` 决定界面颜色**(强制;2026-10-02 定):
  编译期颜色资源带 `-night` 限定符,系统/OEM 可以把进程 `uiMode` 翻成夜间(魅族 Flyme 会强翻)。
  取色/取底通道的门槛必须是"运行时调色板装配好没有"(`ThemeRuntime.runtimePalette()`),
  **不能是"是不是自定义主题"**(`ThemeRuntime.active()` / `palette()` 只留给兼容扫描与诊断):
  - `ThemeResources`(代码取色/状态色/图标着色)、`ThemeContextWrapper`(换肤 Context/Resources 包装,
    Activity 侧与 **Application 侧都要包**:气泡/Toast/通知/应用上下文 inflate 的弹窗走应用上下文)、
    `ThemeDrawables`(旧资源兼容重建、状态色 selector、图标 tint)三处都不得再用 `active()` 当门槛;
  - `ThemeResources(base, normalizeNight)`:Activity/弹窗一侧 `true`(把包装内 `uiMode` 归一到我们自己的
    明暗类型,连 native 解析的 `@color`/`?attr` 都不被强翻),**Application 一侧必须 `false`** ——
    "跟随系统"是读应用级 `Configuration.uiMode` 判系统明暗的(`ThemeStore.systemNight()` /
    `BaseActivity.systemNightNow()`),归一了它会自锁,再也翻不动;
  - 内置主题也走重建后,**圆角一律以编译期那份 drawable 为准**(`ThemeDrawables#keepCompiledCorners`),
    这条从"自定义主题的修补"升级为内置主题的保命线,别删;
  - 排障:启动/系统明暗变化/主题生效各留一行 `明暗(...)` 日志(系统配置 + 系统偏好 + 生效主题 +
    运行时供色 + 跟随系统),被强翻的机型先看这一行。
  - 反例(2026-10-02 用户口径):内置浅色主题 + Flyme 深色模式 → **"只有弹窗/浮层/气泡/通知这类新窗口变深,
    页面主体还是浅色"**,就是这条通道只在自定义主题下被接管造成的。
- **播放器画面上的样式不随主题**(强制;2026-10-01 定):播放器底恒为黑,压在**视频画面**上的元素
  (控制条/OSD、暂停与滑动进度浮层、字幕与 seek 进度图标、播放器菜单浮层)一律用**固定配色**
  (白 / 半透明黑 / 固定字面量;进度与暂停提示底固定为 15% 白色半透明),**禁止**把 `text_foreground`/`text_sub`/`btn_select_*`/`bg_float` 等主题色接进去 ——
  浅色主题下主题色压黑画面必然看不清。涉及 `player_vod_control_view.xml`、`dkplayer_layout_live_side.xml`、
  `dkplayer_layout_live_normal.xml`、`player_live_control_view.xml`、`dkplayer_layout_menu_view.xml` 等。
  反之,**带主题面板底的浮层与页面元素照旧走主题**:直播页 EPG 信息条(`bg_large_round_float`)与频道/分组卡片、
  直播设置与线路抽屉(`bg_float`)、播放设置面板里的小组件按钮(`selector_widget_btn`/`WidgetBtn`)等。
  判断口径一句话:**它画在黑画面/视频上 → 固定配色;它有自己的(主题)面板底 → 跟主题。**
  新增播放器控件时按此写死配色,不要"顺手接主题";也不要把这条当成"整页不跟主题"的理由 —— 页面卡片照旧走主题。
- **空心组件的边框线 = 它自己的文字颜色**(强制;2026-10-02 更新):凡"无填充、只有描边"的组件
  —— 空心按钮 `BtnSecondary`、小组件键 `WidgetBtn`/`PageBgChip`(搜索页历史/热词/联想、日志分类、背景预设、
  播放器设置键)、危险入口红字键 `BtnDangerGhost`、`bg_r_*_stroke_primary` 描边小件 ——
  描边色必须与该组件**自己的文字色同源**:普通键 = 文字主色(`btn_plain_text` / `text_foreground`),
  危险入口 = 危险红 `#E5484D`,禁用态 = `text_disable`。
  **禁止**再让它们各自去挂"空心按钮边框线颜色"`btn_cancel_bg`:描边和文字各配一个键,自定义主题下必然出现
  "文字跟了主题、边框没跟"(用户口径"小组件的边框线还是写死的")。`btn_cancel_bg` 此后只服务
  **没有自带文字**的纯描边容器与输入框(`bg_theme_field`/`bg_theme_input_underline` 未聚焦态/
  `bg_r_*_stroke_primary` 之外的描边容器)。
  主按钮与实心选中态的填充、描边同取不透明的 `brand`(派生为 `btn_select_bg` / `btn_select_stroke`),
  底上的文字取 `btn_confirm_text`(派生为 `btn_select_text`);`btn_confirm_bg` 已移出主题配置,
  真正的空心操作统一用 `BtnSecondary`,描边与文字同色。日志筛选、背景预设与直播选中条共用实心选中规则;输入框底线的聚焦态仍换 `text_foreground`
  做反馈(未聚焦用 `btn_cancel_bg`,故意与文字区分)。
- **空心按钮按下仍为空心**(强制;2026-10-01 定):`BtnSecondary`、`BtnDangerGhost` 与未选中的 `WidgetBtn` 在
  `state_pressed` 下保持透明填充,描边与文字继续同色;点击反馈不能把空心键临时刷成实心。
  `BtnPrimary` 按下/短时禁用时保留原来的填充与文字对比,不得切到浅色半透明底导致白字难辨;
  只有真正的选中件才允许持续显示实心选中态。
- **点击型组件不得带"选中外观"**(强制;2026-10-01 定):`FlowTagLayout` 的 `app:max_select` 只给
  **真·可选件**(确实要在多个选项里选一个的标签);只做"点一下就触发动作"的 chip
  (搜索页历史/热词/相关搜索)一律**不写** `max_select` —— 写了以后点击会 `setSelected(true)`,
  而 chip 的底 `selector_widget_btn` 恰好带"选中 = 实心填充"状态,点过的词就变成一块选中色
  (用户口径"点击之后会给选中色背景……它只是点击组件,而不是当可选组件")。
  同理:新增点击型 chip 时用无状态底(只有描边/无底),别挂带 `state_selected` 的选择器。

## 八、推荐推进顺序(供排期参考)

1. **第一阶段(补边界)**:SourceViewModel→SpiderApi;DownloadFragment→DownloadFacade;DetailActivity 不直调 DownloadManager;注册并使用 PlayerFactory;禁止新增 Hawk/EventBus/具体 Manager 直调。
2. **第二阶段(抽基础)**:common(模型/工具/状态)→ spider 契约 → core-network → core-storage;已完成,且契约模块已回并业务模块(见 §二)。
3. **第三阶段(拆播放器与大页)**:playback shell;拆 PlayFragment/DetailActivity;字幕迁 playback;ViewModel 只调接口。
4. **第四阶段**:feature-* 按需模块化(不要在依赖未稳时先搬目录)。
5. **第五阶段(现代化)**:Exo→Media3(代码已迁移,待人工回归)、EventBus→Flow/接口、Hawk→DataStore、Java→Kotlin、Hilt(按需)、依赖检查与测试门禁。

## 九、开发与提交纪律

- 全量门禁:改动后必须跑 `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest checkModuleDependencies` 全绿再提交。
- 提交信息遵循 `doc/Git提交规范.md`(type[scope]: 描述 ≤50 字;一次提交一件事;提交前 git status 确认无多余文件)。
- 文件级提交:按范围 `git add`,不混入无关改动;本规则文件(AGENTS.md)与各 doc/ 状态文档随对应批次同步更新并提交。
- 改进.txt(已纳入版本库跟踪)与 AGENTS.md 保持一致,改动同步更新;release-notes-v3.0.1.md 不入库,其要求以上方章节为准。

### 代码托管与镜像同步(强制)

1. **GitHub 是唯一权威,Gitee 只是镜像**:`origin` = `https://github.com/CNShanJu/MBox.git`(权威:CI、Release 都在这边);
   `gitee` = `https://gitee.com/CnAyo/MBox.git`(国内镜像:代码 + 发行版附件)。
   另一远端 `legacy` 指向更名前的旧仓库 `CNShanJu/TVBoxOS-Mobile`,只作历史留档,**不要往它推新提交**。
2. **一次 push 必须同时覆盖两端**,统一走脚本,别只推 `origin` 就算完事:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-release.ps1 -DryRun   # 先看会推什么
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-release.ps1            # 推 main + 当前版本 tag
   ```

   脚本职责与边界:推分支与 tag 到两端;**不代为升版本**(版本提升在调用前完成,脚本只在推 tag 时校验
   「要推的 tag == `app/app_config.properties` 的 versionName」);**GitHub 失败即整体失败,Gitee 失败只告警**
   (镜像掉队不该阻断发版),掉队后补推用 `-GiteeOnly`。
   脚本默认**只推当前版本 tag**:Gitee 缺的历史 tag 推上去补不出旧发行版,反而会让 CI 对每个 `v*` tag 各跑一次构建;
   确需全量对齐历史才加 `-AllTags`。
   `scripts/sync-release.ps1` **必须存为 UTF-8 with BOM**(PS 5.1 按系统代码页读无 BOM 的 .ps1,中文会全乱并报语法错);
   `scripts/verify-gitee-mirror.ps1` 同理。
3. **顺序有硬依赖:代码与 tag 必须先到 Gitee,再等 CI 建 Gitee 发行版**。
   `.github/workflows/build-apk.yml` 的 `Sync release APK to Gitee mirror` 步要先按 tag 在镜像仓库找到发行版,
   镜像没有该 tag 时它只告警跳过 —— APK 附件就同步不过去。发版后到 Actions 确认该步没有 `::warning` 跳过。
4. **镜像掉队要能发现**:任一端的 `main` 或 `v*` tag 明显落后时用 `-GiteeOnly` 补推,不要长期放任手工推。
5. **CI 同步 APK 到 Gitee 依赖 Secrets,缺令牌会静默跳过**(强制检查):
   `GITEE_TOKEN`(必填),可选 `GITEE_OWNER`/`GITEE_REPO` 覆盖默认坐标 `CnAyo/MBox`。
   - 取值:登录 https://gitee.com → 头像「设置」→「私人令牌」→ 新建,勾选 **projects**(仓库读写)权限;
   - 写入:https://github.com/CNShanJu/MBox/settings/secrets/actions → New repository secret,名 `GITEE_TOKEN`;
   - **未配置时的表现(历史上就是这样长期漏掉的)**:该步会打印 `::error` 但**仍以 success 结束**,镜像页一直
     没有该版本的附件,App 国内下载只能回落到加速代理或直连。v3.6.1 的 Gitee 发行版是**手工上传**的
     (带 Gitee 自动生成的 `v3.6.1.zip`/`v3.6.1.tar.gz` 源码包;API 上传的发行版不会有这两项 —— 可据此判断
     某版本到底是 CI 同步的还是手工传的)。
   - **每次发版后的验收动作(强制,一条命令)**:不要只看 GitHub Actions 是绿的 —— 缺令牌时那步照样 success。
     用脚本直接问 Gitee 要答案:

     ```powershell
     powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1
     ```

     它逐条验「发行版在不在 → 有没有 .apk 附件 → 体积对不对(取 Content-Length)→ 与 GitHub 正式包是否同体积 →
     匿名能不能下(顺带实测 Gitee 是否忽略 Range)」,任一不过即非零退出,可接进发版流程。批量排查用
     `-Last 5`(本地最近 5 个 tag,直接看出哪几个版本漏了);只想快速核对附件在不在、不想发下载请求就加 `-NoDownload`
     (此时体积与对账会标"未测")。脚本只读,不写仓库、不改远端。
     **注意两个 Gitee 接口坑(脚本里已规避,别再踩)**:`/releases/tags/<tag>` 返回空壳对象(HTTP 200 但字段全空),
     必须用 `/releases` 列表再按 `tag_name` 过滤;附件对象没有 `size` 字段,体积只能从 HTTP `Content-Length` 拿。
   - 补同步:配置令牌后重新运行该 tag 的 Build APK 工作流即可(该步按 tag 找发行版,重跑不重复建),
     再用上面的验收脚本确认 `全部 1 个 tag 的镜像验收通过`。
     不想等 CI、或想立刻知道令牌配得对不对,用 `scripts/sync-gitee-release.ps1`:
     `-VerifyOnly` 只验令牌与作用域(不写任何东西),或 `-Tag vX.Y.Z -ApkPath <apk>` 直接把附件补传到
     Gitee 发行版。**令牌不要写在命令行参数里**(会进 PowerShell 历史),用 `-TokenFile` 或交互式输入,用完删掉;
     CI 侧一律走 GitHub Secrets。

### 构建内存纪律(强制)

1. **Gradle 堆固定,不因单次构建临时改动**:`gradle.properties` 的 `org.gradle.jvmargs` 固定为
   `-Xmx2g -XX:MaxMetaspaceSize=512m -Djdk.tls.client.protocols=TLSv1.2`。禁止为了单次打包临时调大/调小 `-Xmx`
   构建后还原——不同 `-Xmx` 会让 Gradle 各起一个常驻 Daemon(4g/2g/1g daemon 并存)互抢内存,
   这正是"每次打包都内存不足/`mmap failed`/`hs_err_pid*.log`"的根因。
2. **打包内存不足时的标准处理**:先 `gradlew --stop` 停 Daemon,仍不足再结束残留 jdk17 java 进程
   (`Get-Process java` 排查含 `GradleDaemon`/`KotlinCompileDaemon` 的进程),然后以固定配置重新构建。
   不要靠改堆绕行。
3. 若确需为 CI/极端机器调堆,走显式批次讨论,完成后回写固定值,不得留下多个历史堆配置常驻进程。

### 验证纪律(强制)

1. **未经用户明确要求,禁止用机器在真机上验证结果**(adb/自动化脚本一律算):不装包、不启动/点击/滑动界面、
   不抓 logcat、不读设备文件、不改设备数据;UI 树抓取、截图识读等同样属于机器调试。
   真机结果一律**由用户人工验证**。
2. 允许(且应当)做的机器验证只限**本机开发侧**:JVM 单测、纯逻辑/解析校验、`scripts/` 下的离线校验脚本、
   编译与门禁全绿。这些不属于"真机验证"。
3. 交付说明必须把「已机器验证(单测/构建/离线脚本)」与「待人工验证(真机行为)」分开写,
   不得把未经人工验证的真机行为说成"已修复/已可用"。
4. 确需真机验证时(例如"只有真机才能暴露的问题"),先向用户提出请求并等其同意;用户在对话中明确
   "可以验证/去验证"即视为当次同意,不得默认继承到下一位需求。

### 发布与版本纪律(强制)

1. **Release 文案一律归纳,禁止照搬 commit**(强制):发布 Release/发版说明(以及任何给用户看的版本更新说明)时,
   **禁止直接把 git commit 列表原文照搬贴出**,必须**基于 commit 记录自行总结**为**用户可读的更新说明**。做法:
   - 先取本次发版的 commit 区间(上一发布 tag.. 当前 tag,如 `git log <prev>..<curr> --no-merges`),
     逐个读懂每条 commit 的**意图与影响**,不能只扫标题;
   - 按**用户视角**归并分类(通常「新增功能 / 修复 / 改进 / 其他」),每类下用口语化、面向用户的要点概括
     「改了什么 + 对用户有何影响」,而非罗列 commit 标题;
   - 剔除对用户无意义的内部细节(依赖重构、代码结构调整、占位符实现、安全审计等),避免照抄;
   - 按「大.中.小」版本号写出与版本定位相符的总结,**重要变更置顶**,开头标注版本号;措辞克制准确,不夸大新增/修复;
   - 同一条 commit 跨多类时归入最相关的一类,避免重复。
   - **给开发者看的内容用注释围栏标出**(强制):App「检查更新」弹窗只展示用户可读内容,约定 Release 正文里
     内部信息(commit 号、依赖重构、构建/脚本细节)包在 `<!-- dev --> … <!-- /dev -->` 之间
     (`dev-only`/`internal`/`内部`/`开发者` 同义,大小写不敏感),客户端会自动剔除(实现见
     `update/github/ReleaseNotes`);GitHub 网页本身不渲染 HTML 注释,网页正文依旧完整。
     只写了起始围栏视为"以下全是内部内容",一并隐藏。
     **围栏必须独占一行**(允许行首空白):只在正文里"提到"围栏写法(如说明这个约定本身)不会被当成围栏,
     否则会把它之后所有用户可见内容一起吞掉;同一行内成对写出(`<!-- dev -->x<!-- /dev -->`)仍整段剔除。
   - **不必为跨版本升级补历史**:App 取 releases 列表,把"比当前新、且不高于可下载最新版"的各版本说明
     按新→旧拼成一个弹窗(最多 5 个版本),每条 Release 只写自己的改动即可;
     正文顶部的版本标题(`## v3.5.5`)与「相比 vX 的更新:」引言会被去重,不重复出现。
   - **发版流水线不得自动搬运 commit**(强制):`.github/workflows/build-apk.yml` 打 tag 时的 Release 正文,
     只取仓库里维护者手写的 `doc/release-notes-<tag>.md`(UTF-8);该文件缺失时正文只留 `<!-- dev -->` 围栏
     里的提交记录并在 Actions 输出警告,**绝不把 commit 标题当作给用户的更新说明**。发版前用
     `node scripts/check-release-notes.js doc/release-notes-<tag>.md` 自检。
2. **只写用户关注的内容,开发者事项一律不进用户可见正文**(强制;已发生过"把 AGENTS 规则文件改动写进 Release"的
   事故——某版本用户更新说明首条是 `docs(AGENTS): 发布含中文必须显式UTF-8防乱码`,用户看不懂也不需要知道):
   - **属于用户的内容**(这些才写,每类挑重要的 1~5 条):
     1) **新增功能**:用户能用到的新能力(如"设置里新增「自动检查更新」开关");
     2) **功能变更**:原有行为怎么变了、入口挪到哪了(如"订阅支持长按编辑地址,不必删了重加");
     3) **移除/停用**:砍掉或下线的功能、不再支持的用法(如"移除毛玻璃效果,底栏改纯色遮罩");
     4) **问题修复**:用户会遇到的故障修好了(如"修复导入的订阅在升级后消失");
     5) **体验与性能**:更快、更省电、更少打扰、界面更顺手;
     6) **兼容性与注意事项**:系统要求变化、数据迁移、需要用户手动做一步的事(如"需重新授权存储权限")。
   - **不属于用户的内容**(绝不写进用户可见部分;确要留档就包进 `<!-- dev -->` 围栏):
     - 规则/流程/文档类改动:`AGENTS.md`、`改进.txt`、`doc/**`、README、`doc/Git提交规范.md` 等纯文档提交;
     - 构建与发布体系:Gradle/AGP/依赖版本升级、依赖重构、CI/工作流、签名与打包脚本、版本号提升(`chore(版本)`);
     - 代码结构类:模块拆分与分层、接口抽取、命名与目录调整、死代码清理、EventBus→直调这类内部机制替换
       (除非用户能感知到差异,如"更省内存/启动更快",那按第 5 类写"效果"而不是写"机制");
     - 测试与门禁:单测、离线校验脚本、依赖门禁任务;
     - 纯内部实现细节(占位符实现方式、日志埋点、注释、性能埋点等);
     - **判断标准**:这条改动,普通用户看完能不能一句话说出"对我有什么用"。说不出 → 不写。
   - **阈值豁免无效**:哪怕某次发版里"内部改动"占了绝大多数(例如 6 条提交里 5 条是规则/依赖/构建),
     也只写那 1 条用户可感知的;Release 说明少而准,好过多而杂。
   - **措辞**:不要写 commit 前缀、文件名、类名、issue 号、构建/脚本细节;用"用户做了什么 → 会有什么变化"的句式;
     重要变更置顶;不夸大(不把"内部调整"包装成"重大升级")。
   - **发布前最后一道自检(强制,逐条过)**:把要发的正文从头到尾读一遍,回答"用户看得懂吗、这跟他有关吗",
     并确认正文里**不含**下列字样:`AGENTS`、`改进.txt`、`doc/`、`chore(`、`refactor(`、`docs(`、`ci(`、
     `test(`、`依赖`、`模块`、`重构`、`门禁`、`单测`、`Gradle`、`versionCode`、commit 短号。
     命中任意一个 → 挪进 `<!-- dev -->` 围栏或直接删掉。
3. **debug 包严禁进 git**:永远不要提交/推送 debug 构建产物(如 *_debug.apk、build 产物);
   除非用户**明确要求**,且即便如此也须先与用户**二次确认**后方可提交。
4. **push 前自动升版本(用户未另行说明时)**:
   - 每次 push 若用户没有指定版本,先按 **大版本号·中版本号·小版本号** 提升**小版本号**一次
     (改 `app/app_config.properties` 的 `versionName` 末段 +1,并同步 `versionCode` 单调递增),再提交代码。
   - 同时把 `debugVersionName` 设为**上一版实际发布的版本号**;不能由新版末段减 1 推算,因为发布版本可能跳号。
   - 用户让**打 tag** 时:一律基于**最新的 versionName** 打(如 `v3.4.5`)。
   - 用户让**发布 app(出正式包)**时:一律基于**最新 tag 对应的版本**构建。
   - 用户有主动说明(指定版本号/tag/发布方式)时,以用户说明为准。
5. **发布/脚本写入含中文(非 ASCII)内容必须显式 UTF-8,发布后抽查乱码**(强制;血泪教训:曾两次发版正文中文全部变成
   `?`,起因是脚本上传编码被破坏):
   - 向 GitHub Release 等网页/接口上传含中文的文本时,**禁止**依赖 Windows PowerShell 5.1
     `Invoke-WebRequest`/`Invoke-RestMethod` 字符串 body 的默认编码——其按 ASCII 发送,会把每个中文字符替换成 `?`;
   - 正确做法:显式带 charset 并以 UTF-8 字节发送(body 先 `[Text.Encoding]::UTF8.GetBytes(...)`,
     `ContentType 'application/json; charset=utf-8'`),或直接用 `curl.exe --data-binary @<utf8文件>`;
   - 含中文的脚本/数据文件(如 .ps1/.md)一律以 **UTF-8** 落盘,避免 GBK/ANSI 读取错乱;
   - **发布后必须抽查** GitHub 上正文/标题/资产名是否乱码(连续 `?` 即编码被破坏),发现后立即以 UTF-8 方式修正
     正文再让用户/客户端验证,不得把乱码留到用户测试环节。

## 十、常用基础设施速查

- 配置:core-storage `config.PrefsDataStore`(DataStore,运行权威;历史 Hawk 一次性迁移通道 `KeyValueStore` 已随 hawk 退役下线);各业务 Config 门面见 `com.github.tvbox.osc.config`(SystemConfig)与各模块 config 包。
- 契约 Providers(在 `:spider` 模块的契约包内,原 `:spider-api`):`SourceConfigProviders`/`ParseConfigProviders`/`LiveChannelConfigApi`(含 `getSubscribeLiveSources()`:订阅自带的直播源清单(名字+地址),订阅管理「直播源」页据此列出「来自:&lt;订阅名&gt;」条目;与 `getFallbackChannelGroupList()` 的区别是**始终保留**解析结果,不受"未配用户直播源时兜底列表被清空"影响)/`SourceLoaderProviders`(含 `stopAllSourceTasks`/`resetSources` 源运行态)/`PlayUrlResolverProviders`(批量下载解析)/`IjkCodecConfigProviders`/`SpiderFaultProviders`(某源因插件缺类或初始化失败而不可用时的原因;页面据此给出说法,而不是只显示"暂无数据") 等,业务/UI 一律经它们取源元信息,禁止直触 ApiConfig。`checkModuleDependencies` 已把"app 直连 `ApiConfig`/`JsLoader`/`PlayUrlResolver`"设为红线,白名单只剩组合根 `di/AppCompositionRoot`、启动 `base/App`、`server/ControlManager` 三个装配/注入点。
- 弹窗:统一 `ui/dialog/DialogCoordinator`(center/right/bottom/loading/confirm);同构内容层合并用共享 Panel(如 LiveSettingPanel/DownloadSeriesPanel/PlayingControlPanel),Bottom/Right 收敛为薄壳。
- 流式标签(搜索页历史/热词 chip、联想弹窗 chip):统一 `ui/kit/FlowTagLayout` + `ui/kit/FlowLineBreaker`(**自研**,2026-09-27 起替代第三方 `com.hyman:flowlayout-lib` —— 那条库行高漏算子视图外边距,标签最后一行会被裁掉下半截;排版算术是纯函数并有 `FlowLineBreakerTest` 钉住)。间距只认条目自己的 `layout_margin`,别再引入第二套 spacing 属性,也别再引第三方流式布局库。
- 共享执行器:`util/HeavyTaskUtil`(getBigTaskExecutorService 并行 / getSerialExecutorService 串行);配合 epoch/过期自检做取消语义。
- 播放上下文:`util/player/{PlayRequest,PlaySessionKeys,PlayedVodKey,PlayHistoryRepository,SubtitleCoordinator,PlayParseCoordinator,PlaybackSessions}`。

## 下载鉴权与存储的持续约束（2026-10-01）

- 下载权限必须按实际保存路径判断；Android 11+ 的应用私有目录不得要求“所有文件访问”权限。仅 Wi-Fi 等待属于排队状态。
- URL、Cookie、Referer、UA 与自定义请求头通过公开契约刷新并原子替换；下载模块不得直接访问 WebView、ApiConfig 或具体登录实现，暂停/过期任务不得回写结果。
- HLS 续传必须核对媒体序列、分片路径与顺序、字节范围、密钥/IV、init 布局；不能仅凭数组下标复用，也不能仅因短时鉴权参数变化清空进度。无法安全确认时暂停或清理后重下。
- 401/403 先有限续期，404/410 先有限短重试并刷新清单，再确认永久缺片；登录失效必须可恢复暂停，不得高频循环请求旧地址。分片 URL 不整表持久化。
