# 改进.txt 差距审计（gap 清单）

> 生成方式：对照 `改进.txt` 逐项做仓库审计（grep/读码），记录"文档要求 vs 当前状态"。
> 状态图例：✅ 已达标 / ⚠️ 部分 / ❌ 未做。审计日期：见最近提交记录。

> **模块现状（2026-09）**：全仓 9 个模块 `:app`/`:common`/`:core-storage`/`:player`/`:thirdparty`/`:log`/`:core-network`/`:spider`/`:download`。本文件中提到的 `:core-model`/`:core-utils`/`:state` 已合并进 `:common`，`:spider-api`→`:spider`，`:player-api`→`:player`，`:crash`/`:TabLayout`/`:ViewPager1Delegate`/`:quickjs`→`:thirdparty`，`:ui-common`→`:app`（主题 JSON 在 `app/src/main/assets/theme/`）。下文历史记录保留当年模块名。

## 1. 依赖方向（改进.txt §一/§六）— 大体达标
- ✅ app 内 `getCSP()` 清零；UI 无 `new OkHttpClient.Builder()`。
- ✅ `:common`（原 `:core-model`）无 android import；`:download` 只经 `:spider` 的公开契约（原 `:spider-api`，现契约与实现同模块）取爬虫能力，边界由源码门禁守。
- ✅ Gradle `checkModuleDependencies` 门禁 + CI（`.github/workflows/verify.yml`）。
- ⚠️ 仍存在跨层直读（见 §4 穿透点）。

## 2. 第一阶段验收对照（§七·一）
| 项 | 状态 | 现状/残留 |
|---|---|---|
| SourceViewModel 走 SpiderApi | ✅ type3 typed 优先+回退 | type0/1/4 仍在 VM 内走 `HttpClient`+`xml()/json()/sortJson()` 内联解析(EventBus 已全仓移除,VM 侧改为直调监听,见 AGENTS §五)。ApiConfig 直读已清零：源注册表/首页源/vip 旗标改经 `:spider` 契约 `SourceConfigApi`（原 `:spider-api`，`SourceConfigProviders` 注入，ApiConfig 实现契约） |
| DownloadFragment 走 DownloadFacade | ✅ | MSG_* 已并入 Facade;UI 无 `util.DownloadManager`/内部实现 import(门禁含 kt) |
| DetailActivity 不直调 DownloadManager | ✅ | 另：仍直用 `cache.RoomDataManger.getVodInfo`（DAO 泄漏点，见 §4） |
| 注册并使用 PlayerFactory | ⚠️ | 已注册 IJK(1)/Exo(2) adapter + `PlaybackSessions`/`VideoViewPlayerApi` 会话原型；PlayFragment 仍直持 `MyVideoView`/内核，session 仅日志观察 |

## 3. 模块边界（§二/§八）
| 模块 | 状态 | 残留 |
|---|---|---|
| `:common`（原 `:core-model`） | ✅ | `ParseBean` 仍在 `:spider`（本轮迁移）+ 含行为(getUrl proxy 替换 / mixUrl Base64) → 迁移时纯化 |
| `:core-network` | ⚠️ | 目录/模块名已对齐;配置(SystemConfig/HawkConfig/KeyValueStore)已迁 :core-storage,event/LogEvent 已清;内部仍是杂项袋:`util/{HttpClient,OkGoHelper,AES,MD5,AdBlocker,AppLog,LOG,urlhttp/*}`(改进.txt §2.8 待拆) |
| `:core-storage` | ✅ | data/cache/Repository + **配置归位**：`SystemConfig/HawkConfig` 迁入 `com.github.tvbox.osc.config`，新增 `KeyValueStore`(Hawk 类型安全封装,App 侧业务 Config 均走它);app 无 DAO 直读、UI 经门面读写配置 |
| `:spider`（原 `:spider-api`，现契约与实现同模块） | ✅ 试点 | 字符串通道(SpiderContentApi)仍在(过渡兼容)；`ApiConfig` 仍暴露具体 Spider(内部实现需留) |
| `:download` | ✅ | 内部实现已收 `...download.internal` 包(Manager/Scheduler/Executor/Core/Store/Config/Policy/Archive/Notifier/Log/task 全族),公开包仅 Facade+模型/接口;app 零内部实现引用(门禁 java+kt 全查) |
| `:player`（原 `:player-api`，现契约与实现同模块） | ⚠️ | 契约 + 原型已接；app 仍直用 `MyVideoView`/IJK/Exo、`PlayerTrackHelper` 按内核 instanceof 分发 |
| `:app` 的 ui-kit / ui-common（原 `:ui-common` 已并入 `:app`） | ⚠️ | ui-common=纯资源 ✅（现为 app 内资源，主题 JSON 在 `app/src/main/assets/theme/`）；app 内已建 ui-kit package（6 个纯净组件），通用 View 归拢中 |
| `:playback` / feature-* | ❌ | 未建（改进.txt 第三/四阶段，需真机回归环境） |

## 4. 穿透点（UI/上层直读下层实现，新代码应避免）
- UI 直读 `Hawk`：**UI 层已清零**。直播偏好→`LiveConfig`(含 EPG 只读、频道播放配置覆写);系统级偏好→
  `SystemConfig`;订阅/搜索域→`util.SubscriptionConfig`;用户页热播缓存→`util.HomeHotCache`。剩余裸读写仅在
  装配/封装边界:App.java 订阅默认注入与 putDefault(启动装配)、RemoteTVBox(类内方法封装)、各配置门面内部。
- UI 直触 DAO/存储实现：已清零(app `RoomDataManger` 直读已收口到 HistoryRepository)。
- UI/业务自建线程池：`PlayFragment`(PLAYED_RECORD_EXECUTOR/parseThreadPool)、`LocalVideoFrameLoader`/`LocalVideoAdapter` **已收口**到模块级执行器 `HeavyTaskUtil`；门禁 `checkModuleDependencies` 的 UI 层红线已从"只认 new*ThreadPool"收紧为拦截 `Executors.new*` 全部工厂 + `new *ThreadPoolExecutor`/`ForkJoinPool`。剩余 `Thunder`、subtitle `DefaultTaskExecutor` 未收口（随大页面拆分一并治理）。
- EventBus 已**全仓移除**（app + download，依赖已从 classpath 删除，仅剩注释里的历史说明）；跨页/模块事件一律直调、
  经 Facade 订阅接口或明确监听器，禁止再引入（AGENTS §五，门禁含 app 层 EventBus 红线）。
- ui-kit:app 内已建 `com.github.tvbox.osc.ui.kit`(§2.7 第一阶段),迁入 6 个纯净组件;
  播放器/业务耦合视图(Player*View/FrostedGlassUtil)仍留 widget 包。
- 直播偏好已收口:`util.LiveConfig` 门面(connectTimeout/showTime/showNetSpeed/channelReverse/crossGroup/
  lastChannel/liveHistory),LiveActivity/三个设置弹窗/历史源弹窗不再裸读 Hawk;EPG_URL 仍跨模块(spider 写)。
- 直播设置弹窗重复已合并:`ui/dialog/LiveSettingPanel` 共享协调器,LiveSettingDialog(底部)/
  LiveSettingRightDialog(抽屉)收敛为薄壳(-372 行);LiveActivity 内嵌面板(showSettingGroup 家族)
  已删除(死代码,无调用入口,含 activity_live.xml 布局块)。
- 弹窗展示协调器:`ui/dialog/DialogCoordinator` 统一 XPopup 组装(center/right/bottom/loading/confirm),
  页面不再散拼 `new XPopup.Builder(...)`;DetailActivity/LiveActivity/LocalPlayActivity/PlayFragment/
  DownloadFragment/MyFragment/BaseActivity/LiveApiDialog 已迁移;XPopup 内置形态(asCenterList/
  asInputConfirm/asImageViewer)与 ConfirmDialog 工厂保留直调。
- 同构弹窗内容层合并(改进.txt §三 消除重复):
  `ui/dialog/{LiveSetting,DownloadSeries,PlayingControl}Panel` 共享内容协调器,
  各自 Bottom/Right 双壳收敛为薄壳(仅保留 壳/间距/字号 差异);
  已合并:LiveSettingDialog↔Right / DownloadSeriesDialog↔Right / PlayingControlDialog↔Right。
  说明:AllVodSeriesBottom↔Right 是**刻意差异**(Bottom 本地 RoundChip 网格单选,Right 复用
  DetailActivity 的 SeriesAdapter+flags 且 onDismiss 复位 grid)——不强行合并,保持两套适配器契约。
- 事件类已全部删除（随 EventBus 退役）：`DownloadEvent`/`RefreshEvent`/`ServerEvent`/`LogEvent`/
  `HistoryStateEvent`/`TopStateEvent` 均无残留源码，跨页/模块事件改直调或 Facade 订阅接口（AGENTS §五）。
- DownloadFragment 不再直连 EventBus:下载结构变更走 DownloadFacade.DownloadStatusListener、
  任务级进度走新增 DownloadFacade.TaskProgressListener(onTaskProgress(taskId))——改进.txt §五
  "跨页状态由 Facade 提供订阅"落地;UI 零 org.greenrobot.eventbus import。

## 5. 大文件拆分（§三）— 未完成
| 文件 | 行数 | 期望 |
|---|---|---|
| PlayFragment.java | ~1610 | PlayViewModel/Coordinator/PlayerSession(SubtitleCoordinator/PlayHistoryRepository 已抽,见 §8) |
| DetailActivity.java | ~1276 | DetailViewModel/Repository/EpisodeSelectionState（已拆出少量 Helper） |
| DownloadFragment.java | ~1208 | 已大量走 Facade，可继续薄化 |
| SourceViewModel.java | ~970 | 源元信息已走 `SourceConfigApi` 契约；type0/1 内联解析仍留(进一步依赖注入化；EventBus 侧已改直调监听) |

## 6. 现代化（§七·五）— 按显式批次推进
Exo→Media3 1.4.1 已完成代码迁移(2026-10-01，待播放器人工回归)，Hawk→DataStore 代码已完成；Java→Kotlin 渐进、Hilt（按需）。（EventBus→直调/明确监听接口**已完成**，见 §4；ui-common→ui-kit 见下方现状口径）
现状口径：`:ui-common` 已并入 `:app`（主题 JSON 在 `app/src/main/assets/theme/`、`generateThemeColors` 任务在 `app/build.gradle`、公共资源在 `app/src/main/res/`），ui-kit 组件已在 app 内，只余"组件成熟后再评估拆模块"。

## 7. 近期可安全推进清单（按收益）
1. ✅ `ParseBean` 已迁 `:core-model` 并纯化：移除 Base64(mixUrl)/proxy 替换依赖；行为收敛到
   `:spider` 的 `ParseBeanUrls.url()/mixUrl()`（调用点 PlayUrlResolver/PlayFragment 已切换）。
2. ✅ download public 面收敛：`DownloadConfig/DownloadCore` 能力并入 `DownloadFacade`（config + episodeId/states），
   app UI/工具全改走 Facade；装配入口 `init/setUrlResolverApi/setUrlSniffer` 收口到 Facade 静态方法；
   app 对 `util.Download*` import 清零，`checkModuleDependencies` 新增源码级门禁防回归。
3. ✅ app 内 `RoomDataManger` 直读已清零：UI 改走 `HistoryRepositories.history().get(...)`
   （接口新增 get(sourceKey,vodId)，Fake/单测同步）。
4. 🔜 `:core-network`(原 common)`util/{HawkConfig,SystemConfig,HttpClient}` 分模块收口（网络留下，配置→core-storage/新 config）。
5. ✅ UI 摘 Hawk(UI 层清零):直播偏好 `LiveConfig`(connectTimeout/showTime/netSpeed/channelReverse/
   crossGroup/lastChannel/liveHistory + EPG 只读 + 频道播放配置覆写);系统级 `SystemConfig`;订阅/搜索域
   `util.SubscriptionConfig`;用户页热播缓存 `util.HomeHotCache`。UI/页面/Helper 对 Hawk 与 HawkConfig 键的
   裸读写全部改走门面;残留仅在装配(App 订阅注入/putDefault)与类内封装(RemoteTVBox)与门面自身。
6. ⏸ playback shell + PlayFragment/DetailActivity 大拆分（需真机回归）。
7. ⏸ feature 模块化/Hilt（长期）；Media3/DataStore 已完成代码迁移，待对应人工回归。

## 8. 边界规则抽查结果（§六逐条）
- ✅ app 无 getCSP / UI 无裸建 OkHttpClient / `:common`（原 core-model）无 android 依赖 / `:download` 只经 `:spider` 公开契约（原 `:spider-api`，现契约与实现同模块，边界由源码门禁守）。
- ⚠️ 播放器收口第一步:新增 `player/KernelTrackSupport` 能力接口,IJK/Exo 各自实现,
  `PlayerTrackHelper` 不再 instanceof 具体内核(app 内 UI 已无内核强转);轨道切换/内置字幕回调/
  进度恢复语义收敛到接口。
  剩余(需真机回归):PlayFragment 由 mVideoView 直控切到 PlayerApi/PlaybackSessions 全驱动、
  PlayViewModel 抽取、app 内 MyVideoView/IKJ/Exo 引用清零、PlayerHelper 工厂
  收口 AppCompositionRoot。
- ✅ SubtitleCoordinator 抽离(等价搬移,宿主薄委托):`util/player/SubtitleCoordinator.java` 注入
  (Activity,VodController,MyVideoView),承载字幕装载(缓存/外挂/内置自动选中文)/字幕设置弹窗
  (在线搜索/本地选择/字号延迟样式/开关)/音轨与内置字幕切换(SelectDialog+轨道切换+进度恢复);
  PlayFragment 相应方法变薄委托,refresh 字幕字号事件转调 applySubtitleSize。
- ✅ PlayHistoryRepository 抽离(等价搬移,宿主薄委托):`util/player/PlayHistoryRepository.java`
  收口 "key→MD5→CacheRepository" 读写与"跳过片头(st)叠加"读取语义(类型兼容分支/打印保留);
  PlayFragment 的 getSavedProgress/saveProgress/切集与重置 delete 六处触点全委托,HistoryRepositories/MD5
  直读清零;JVM 单测 7 例(Fake CacheRepository:roundTrip/skip 取大/String 兼容/删除)。
  配套:common `MD5.string2MD5/encrypt` 的空值判断去 Android TextUtils 依赖(行为等价,纯算法类可 JVM 测)。
- ✅ SourceViewModel 去 ApiConfig 直读:新增 `spider-api.SourceConfigApi`(getSource/getHomeSourceBean/
  getSourceBeanList/getVipParseFlags,只用 core-model 类型)+ `SourceConfigProviders` 持有者(:spider 的
  ApiConfig 实现契约,AppCompositionRoot.init 注入);SourceViewModel 改持 `SourceConfigApi` 字段,8 处
  `ApiConfig.get().*` 清零,不再 import :spider 的 ApiConfig 类(VM 侧源元信息可经接口注入 Fake;type0/1
  内联解析仍留)。
- ✅ UI/展示层源元信息读取收口(同一契约):DetailActivity/GridFragment/UserFragment/HomeFragment/
  CollectActivity/HistoryActivity/FastSearchActivity、Search/History/Collect/FastSearch/QuickSearch Adapter、
  PlayFragment(getSource/getVipParseFlags)、SearchHelper/DetailQuickSearchHelper/WebSniffResolver 等
  18 处文件改走 `SourceConfigProviders`;app 内"源元信息(源注册表/首页源/源列表/vip 旗标)"经 ApiConfig
  直读清零(HomeFragment 等保留 ApiConfig 仅做 loadConfig/loadJar/setSourceBean 等源管理调用)。
- ✅ IJK 解码配置收口到播放契约:新增 `player-api.IjkCodecConfigApi`(getIjkCodes/getCurrentIJKCode/
  getIJKCodec,返回值 core-model IJKCode)+ `IjkCodecConfigProviders` 持有者;AppCompositionRoot 以适配器
  桥接 :spider ApiConfig 现有实现(避免 spider 反向依赖播放契约)。app 内 5 个解码配置读取点
  (IjkMediaPlayer/VodController/LocalVideoController/PlayerHelper/SettingActivity)改走契约,
  :spider ApiConfig 的 import 从播放侧清零。
- ✅ 解析配置/解析执行收口:新增 `spider-api.ParseConfigApi`(getDefaultParse/setDefaultParse/
  getParseBeanList/jsonExt/jsonExtMix)+ `ParseConfigProviders` 持有者;AppCompositionRoot 桥接
  ApiConfig(jar loader)。app 内解析读取点 VodController(默认解析弹窗/列表)与 PlayFragment
  (解析流程 defaultParse/parseBeanList/jsonExt/jsonExtMix)全改走契约,两文件对 ApiConfig import 清零。
- ✅ 直播/源加载收口 + ApiConfig 全 app 直读清零:新增 `spider-api.LiveChannelConfigApi`
  (getChannelGroupList/loadLives)、`SourceLoaderApi`(loadConfig/loadJar/getSpider + Callback,
  回调语义与原 LoadConfigCallback 对齐)、`SourceConfigApi.setSourceBean`;LiveActivity/HomeFragment
  改走契约,清理 LivePlayerManager/FolderAdapter/DoubanSuggestAdapter/RemoteServer 四处死 import。
  至此 app 代码对 `:spider` ApiConfig 的引用仅剩 AppCompositionRoot 桥接点(组合根,合法),业务/UI 全经
  spider-api/player-api 契约。
- ✅ download internal 化(§六"实现放 internal 包"):19 个实现/辅助类移入 `com.github.tvbox.osc.download.internal`
  (util/{Manager,Scheduler,Executor,Core,Store,Config,Policy,FileCleaner}、task/*、根包 Archive/Log/Notifier/
  ForegroundService/Event/ProgressEvent);DownloadFacade 增补委托(queryArchiveByVod/getAllArchive/
  findArchiveByPath/removeArchiveOrphansByVod,init 内含 Notifier 初始化);app 泄漏点全改走 Facade
  (App/DetailActivity/DownloadFragment 归档调用、SettingActivity 下载设置原直读 util.DownloadConfig 改 Facade);
  checkModuleDependencies 源码门禁扩展覆盖 .kt 与 download.internal 包,防回归。
- ✅ app 内 3 处 `RoomDataManger` 死 import 已删(CollectActivity/HomeFragment/HistoryActivity,无实际调用)。
- ✅ 配置归位 core-storage(改进.txt §2.3):`SystemConfig/HawkConfig` 由 :core-network(原 common)迁入
  core-storage `com.github.tvbox.osc.config` 包;新增 `KeyValueStore`(Hawk 类型安全封装:getString/getBoolean/
  getInt/泛型 get/put/delete/contains),SystemConfig 内部改走 KeyValueStore;App 侧 LiveConfig/SubscriptionConfig/
  HomeHotCache 亦改走 KeyValueStore(仅剩 App 启动装配直触 Hawk)。依赖补齐:core-storage→:log/:core-model/hawk,
  core-network→core-storage,spider→core-storage(无环、通过 checkModuleDependencies)。
- ✅ 订阅页本地导入改系统 SAF(替代 hedzr 反射 StorageVolume 兼容性问题):`SubscriptionActivity.pickFile`
  改用 `ActivityResultContracts.OpenDocument`(*/* + 扩展名校验),仅接受 ExternalStorageProvider 主卷并转真实
  路径后仍以 clan:// 订阅源加入(保留记忆导入目录/去重/权限门禁)。
- ✅ 订阅页新增 **JSON 导入**(粘贴导入,免文件):`SubsciptionDialog` 标题栏加"JSON导入"入口(图标与"本地导入"同款 16dp,
  原 24dp 偏大),弹窗 `JsonImportDialog`(多行等宽输入 + 剪贴板粘贴 + 格式校验不通过不关窗)确认后由
  `SubscriptionActivity.importJsonText` 识别:清单数组 `[{name,url}]`/`[{sourceName,sourceUrl}]`、多线路 `{"urls":[...]}`、
  多仓 `{"storeHouse":[...]}`(弹窗选仓)、单条 `{"name":..,"url":..}` 直接加订阅;识别不了(单源规则配置、整份配置)
  按内容摘要存应用专属导入目录 json,再以 clan:// 本地订阅加入。落库/去重/启用首条逻辑与本地导入共用
  `importSubscriptionEntries`/`addLocalFileSubscription`,多仓选仓抽出 `showStoreHouseChoose` 供订阅地址返回多仓时复用。
- ✅ 订阅导入拦非订阅内容(线上实例:用户把「阅读」Legado 书源粘进 JSON 导入,存成 clan:// 订阅后每次启动都
  `不是订阅配置(缺少 sites)` 报"解析配置失败",重选订阅也恢复不了):`CmsApiRules` 新增内容形态判定
  `subscriptionShape`(`SHAPE_CONFIG/SITE/ENCRYPTED/BOOK_SOURCE/LIVES/UNUSABLE`)、`looksLikeBookSource`、
  `bookSourceSiteUrl`(书源带 `sourceUrl`+`rule*` 规则;数组导出看首个对象,扫描按字符串/转义与括号深度,
  开头 BOM 与 `//` 注释与加载阶段 FindResult 同规则先剥);`SubscriptionActivity` 三处入口(JSON 粘贴 / 本地文件 /
  订阅地址响应)落盘前判定:完整配置与加密套路可用、裸站点条目/数组补 `{"sites":[…]}` 外壳后另存、不能用的按形态
  提示原因(「阅读」书源/只有直播源/缺 sites)并拒绝落盘,书源另按其中的站点地址嗅探采集接口(红牛资源书源的
  sourceUrl 即站点首页);本地文件识别抽出 `importJsonEntries` 与粘贴导入共用(清单/多线路/多仓/单条)。
  `ApiConfig.parseJson` 对缺 `sites` 的裸站点内容先补壳自愈,解析失败提示改"该订阅不是 TVBox 配置"
  (`HomeFragment.showTipDialog` 文案变化时重建弹窗,TipDialog 文案只在 onCreate 绑一次)。
- ✅ 订阅管理新增 **导出**(勾选多份订阅 → 抓取配置 → 合并成一份 txt → 分享):
  `SubscriptionAdapter` 加导出态(复选框语义由"当前订阅-单选"切到"要导出的订阅-多选",导出态隐藏删除/置顶标记,
  选择集按 URL 存 `LinkedHashSet`,列表重排不丢);`activity_subscription.xml` 标题栏加导出入口(与"使用说明"同排容器,
  整体让开原生"添加"图标)+ 底部操作条(全选/已选 N 项/取消/导出,列表区改 `0dp+weight=1` 让出这条);
  抓取与落盘在 `util/SubscriptionExporter`(clan:// 直读主存储文件、读不到回落内置本地服务 `/file/`;http(s) 走
  `HttpClient.getSync`,带 `;pk;` 的加密订阅只取文本;规范化路径拒绝 `..` 逃出主存储根;单份 8MB 上限;跑
  `HeavyTaskUtil` 共享池、epoch 自检过期作废、结果切主线程);合并策略在 `util/SubsConfigMerger`
  (纯 JSON 逻辑带 JVM 单测:以首份可解析配置为底保留其 spider/lives/wallpaper 等,sites 按 key、parses 按 url 去重,
  flags/rules 取并集,后续配置 spider 不一致只记冲突不合并,非 JSON/加密串进 skipped);落盘
  `getExternalCacheDir()/subscription_export/` 的 txt(只留最近 4 份),经 FileProvider 分享。
- ✅ 新增**抓页面接入**(站点采集接口关闭时的兜底;也用于「阅读」书源):`HtmlSiteRules`(spider-api,纯字符串
  逻辑可单测:子目录线索/分类/详情链接/播放链接(只认本片 vod id,防把"猜你喜欢"当剧集)/播放页真实地址
  (`player_aaaa`/MacPlayer/m3u8 正则/iframe)/总页数/ext 与单源配置生成) + `HtmlSiteImporter`(app,实探
  首页→分类→详情→播放→搜索,整链路探通才落盘,写 `maccms_<key>.json`) + 运行时模板
  `app/src/main/assets/js/lib/maccms.js`(type 3 源,`api=assets://js/lib/maccms.js`,`ext` 带 host/prefix/分类/
  搜索模板;纯正则解析,不依赖 cheerio,故 Node 侧可原样跑同一份代码;列表与搜索路由按候选逐个实探:
  v10 `index.php/vod/type|show/id` → 伪静态 `vodtype`/`vodshow`/`vodsearch`,探通哪个写哪个);`SubscriptionActivity`
  两处接入:「阅读」书源改用其 `sourceUrl` 接入、普通站点地址在采集接口探不到时自动继续抓页面;`CmsApiRules` 增
  `buildSubscriptionJson(带 ext)` 重载。回归:22 个 `HtmlSiteRulesTest` 单测 + `scripts/check-maccms-source.mjs`
  (Node 拿真实页面跑模板全链路,可喂 Java 生成的 ext 验证"生成→消费"契约)。
- ✅ 修 **JsSpider.init 线程错误(JS/JAR 源 ext 为 JSON 时源直接初始化失败)**:`JsSpider.init` 里
  `ctx.parse(extend)` 跑在调用线程上,而 QuickJS 上下文属于它自己的单线程执行器 → 抛
  `QuickJSException: Must be call same thread in QuickJSContext.create!` → `JsLoader.getSpider` 落空 →
  首页无分类、分类/搜索全空(线上实例:抓页面源因为 ext 带站点配置必走这行,表现为"导入了但没数据");
  修复:`ctx.parse` 与 `proxy1` 的 ctx 调用都改到 `submit(...)` 里执行。注:只有 ext 是 JSON 的源会踩到,
  所以此前 ext 为空的 JS 源没暴露。
- ✅ 新增**聚合搜索"加载更多"翻页**:`SpiderContentApi` 增 `searchContent(..., pg)` 默认方法(老实现不受影响)
  → `SpiderContentImpl` 覆写走 `Spider.searchContent(key,quick,pg)`;`SourceViewModel.getSearchPaged(key,wd,page)`
  支持 type3(type0/1/4 走 HTTP 拼参带 `pg`),结果经新增的 `SearchPageBatchListener` 单独一路投递;
  `FastSearchActivity` 上拉到底按轮次取各源下一页并追加(列表不满一屏自动续拉,一次手势最多 3 轮),
  记账抽 `util/SearchPagingState`(纯逻辑,9 个 JVM 单测:空页到底/没回包到底/乱序回包/多源独立翻页/复位)。
- ✅ 字幕本地导入同步去 hedzr:`SubtitleCoordinator.openLocalFileChooserDialog` 原用
  `com.github.hedzr:android-file-chooser` 的 ChooserDialog(反射 StorageVolume.getPath,在 Android 11+/targetSdk 34
  被 hiddenapi 拒收,见 9/5 日志 NoSuchMethodException)改为自研 `SubtitleFileChooserDialog`(标准 File API 列目录/
  按字幕后缀过滤/上级导航,配合 MANAGE_EXTERNAL_STORAGE 全文件访问授权);已移除 hedzr 依赖与其
  FileChooser 主题。同步修复本地视频字幕:`LocalPlayActivity` 接通 SubtitleCoordinator(经 `SubtitleController` 契约复用,
  在线/本地控制器共同实现),"字幕"按钮不再无响应。修复后真机回归:在线/本地"字幕-本地"选 .srt 是否正常装载。
- ✅ 字幕设置弹窗开关不关弹窗:`SubtitleDialog` 的"打开/关闭字幕"不再 `dismiss()`(原来一点开关就把整个字幕设置弹窗
  关了,得重新进设置),改为原地切换状态并仅显隐选项区(字号/延迟/样式/本地/内置/搜索在开启时展示),可在同一弹窗内继续调整。
- ✅ 数据备份/还原改为 DataStore + Room DB(修复"备份不生效"):`BackupDialog` 原只读写已退役的 `Hawk2` SharedPreferences
  + `sqlite`(Room DB),配置已迁 DataStore 后它只存到空壳 hawk、恢复也写回无人读的 Hawk2,故"备份/导入完全不生效"。
  改为:`backup` 写 `config.json`(`PrefsDataStore.exportJson` 全量导出 System/播放/订阅/直播/主页热播/下载等配置域)+
  `sqlite`(Room DB 历史/收藏/缓存);`restore` 经 `PrefsDataStore.importJson`(数值整/浮点归一)写回配置域 + 恢复 Room DB 后重启。
  另修 `allBackup()` 对 `listFiles()`=null 的潜在 NPE。真机回归:设置→数据备份还原→立即备份,改配置后还原是否恢复。
- ✅ 更新下载优化与全局悬浮圈:下载逻辑从 `Updater` 实现抽出为 `update/UpdateManager`(应用级单例 +
  HeavyTaskUtil 线程,断点续传/暂停/继续/取消、复用已下载同名完整 APK、安装完成后启动清理本地 APK),
  任何页面不因关闭下载弹窗而中断;新增 `update/UpdateFloatIndicator` 全局悬浮圆圈(仿首页直播钮,
  附着当前 Activity 窗口,经 `BaseActivity` onResume/onPause 挂载/卸载),点击弹出 `UpdateIndicatorDialog`
  查看进度并手动 暂停/继续/不再更新/安装。`GithubReleaseUpdater.downloadAndInstall` 改为委托 UpdateManager,
  后续切换其他更新源只实现各自 checkUpdate。真机回归:检查更新→立即下载→关掉弹窗仍继续→悬浮圈控制→安装后首启清 APK。
- ✅ 错误日志日期抽屉跟随主题:`LogActivity` 错误日志 Tab 的点"选择日期"底部抽屉(asBottomList)未传
  `isDarkTheme`,暗色主题下仍是浅色底;补 `.isDarkTheme(Utils.isAppDarkTheme())`(直读 App 主题设置,避免 ROM uiMode
  不同步误判浅色→白底)。同步给本次新增的 `UpdateIndicatorDialog`/`SubtitleFileChooserDialog` 的 `show()` 补了
  `.isDarkTheme(Utils.isAppDarkTheme())`。
- ✅ 爬虫 JS 异步桥空对象 NPE 修复:`JsSpider.call` 在 `jsObject==null`(JS 模块内容缺失/未导出 __JS_SPIDER__)
  时直接返回 null;`Async.call` 对 null object 防御,不再抛 `getJSFunction on null` 的 NPE(避免一条源挂掉刷爆错误日志)。
- ✅ 详情折叠/展开文案修正:`InlineExpandableText.buildCollapsed` 不再把前导省略号“… ”上链接色(只“展开”蓝),
  “收回”改为内联在展开文本行末(新增 `buildExpanded`),不再单独换行;`VideoDetailDialog` 同步改用内联“收回”。
- ✅ 本地字幕显隐加固:`SubtitleCoordinator.setSubtitlePath` 在用户显式选字幕时,先用<b>当前</b>播放内核重新绑定
  字幕引擎(prepared 时绑定的 player 实例可能被重建),并强制字幕可见+开启(此前依赖 PlayConfig 状态,若开关未开则 GONE 不显示)。
- ✅ :download 持久化收口:`internal/{DownloadManager,DownloadStore,DownloadArchive,DownloadPolicy}` 的 Hawk 直存
  (任务列表/档案/并发/WiFi 策略)改走 `config.KeyValueStore`,:download 新增依赖 :core-storage、移除 hawk 库依赖。
- ✅ js 运行时缓存文件化:spider `util/js/local`(JS localStorage 桥)改存 `filesDir/js_runtime/*.txt`(ApiConfig
  setAppContext 注入 context),旧 Hawk 键按访问惰性迁移删除。
  **hawk 移除决策**:core-storage 的 hawk 保留为"一次性 legacy 迁移通道"(下载任务/档案回退、旧键导入——
  下载任务恢复数据安全优先),KeyValueStore 收敛为迁移辅助、DataStore 为运行权威;各模块(除 core-storage)已零 hawk 依赖。
- ✅ Hawk→DataStore 分叉修复与收边:`:spider ApiConfig` 共享键(EPG_URL/LIVE_HISTORY/api_url/ijk_codec/自用
  HOME_API/DEFAULT_PARSE)改走 PrefsDataStore(与订阅/直播/播放域同底层);App.putDefault/DEBUG_OPEN 不再回写
  Hawk;RemoteTVBox 遥控记忆、HomeHotCache 热播缓存亦切 DataStore(含存量迁移)。js 运行时缓存(local.java)
  仍留 Hawk,待文件化后评估移除 core-storage 的 hawk 依赖。
- ✅ DataStore typed 对象支持(PrefsDataStore `putJson/getJson`,gson+TypeToken):`SubscriptionConfig`(订阅列表/
  默认订阅/搜索历史/源勾选 HashMap)与 `LiveConfig`(偏好标量+历史源列表 json+频道播放配置改存 JSON 文本,
  频道旧键按访问惰性迁移)均切 DataStore,旧 Hawk 存量导入删键。
- ✅ DataStore 扩域(SystemConfig):13 键全部迁移(主题/DNS/首页/历史数/直播源/无痕/预览/快搜/调试/SSL/局域网/
  loading_anim),加载动画旧"数字型"历史值跳过避免类型冲突;旧 Hawk 存量类加载时一次性导入并删旧键。
  已覆盖域:下载策略、播放设置、日志、系统级偏好。
- ✅ DataStore 扩域(现代化):`PlayConfig`(播放 12 键,含 float)+ `PlayerFactory`(play_type 同键)+ `LogConfig`
  (经注入实现 `DefaultLogConfigStore` 切 PrefsDataStore,app_log 全链路 AppLog/设置页同底层)均完成迁移,
  旧 Hawk 存量各自一次性导入并删旧键。DataStore 化已覆盖:下载并发/WiFi、播放设置、日志开关三域。
- ✅ DataStore 化试点(改进.txt §7.5):core-storage 引入 `datastore-preferences-rxjava3`,新增 `config.PrefsDataStore`
  (Preferences DataStore 同步门面:启动读盘入内存,get 内存直读,put 串行同步落盘;标量 int/boolean/string/float/long);
  `DownloadPolicy`(下载并发/仅WiFi 两纯标量键)完成迁移 + 旧 Hawk 存量一次性搬入并删旧键(App.initParams 先初始化
  PrefsDataStore)。后续纯标量域(SystemConfig 等)可同模式扩展;对象键域需先定 typed 序列化/文件化方案。
- ✅ :download 大值键脱离配置存储:任务列表(download_tasks_v1)/档案(download_archive_v1)改存应用私有文件
  `filesDir/*.json`(gson+原子 tmp rename),启动读文件、旧 Hawk 存量一次性迁移并删旧键;load 移到
  `DownloadManager.init(context)` 后 boot()(解决"构造早于 appContext"时序);并发/WiFi 等小偏好仍在
  KeyValueStore。配置存储(DataStore 化候选)不再承载大对象。
- ✅ EventBus 收敛试点:电量改为 SystemStateMonitor 事件(`TYPE_BATTERY_LEVEL` 百分比 + SystemState.batteryPercent,
  主线程回调),PlayFragment/LocalPlayActivity 电池图标改订阅 monitor(注册即取当前值);DetailActivity/LocalPlay
  的 BatteryReceiver→EventBus 转发删除,BatteryReceiver.kt 孤儿文件移除。EventBus 仅剩跨页 refresh 事件(长线)。
- ✅ :log 日志配置依赖倒置(DataStore 化前置):新增 `log.LogConfigStore` 端口,`LogConfig` 不再直 Hawk,由
  宿主注入 core-storage `DefaultLogConfigStore`(基于 KeyValueStore)或未来 DataStore 实现;:log 移除 hawk 依赖。
  → 第三方 hawk 现仅 core-storage `KeyValueStore` 单一持有,DataStore 化(第五阶段)前置已清。
- ✅ 剩余 hawk 直用收口:core-network `AppLog`(运行日志开关)、player-api `PlayConfig/PlayerFactory`(播放偏好
  /默认播放器)、spider `ApiConfig` 与 `util/js/local`(订阅配置缓存/js 运行时缓存)全部改走 `KeyValueStore`;
  相应移除 core-network/player-api/spider/app 的 hawk 库依赖。第三方 hawk 仅剩持有者:core-storage(KeyValueStore
  封装)与 :log(LogConfig 开关/级别——依赖方向 log 不可反向依赖 core-storage,由 :log 自持,属底层边界)。
- ✅ app 对第三方 Hawk 依赖清零:App 启动装配(Hawk.init→`KeyValueStore.init`、默认值 putDefault/订阅文件同步→`SubscriptionConfig`+
  KeyValueStore)与 RemoteTVBox(遥控主机记忆)亦改走 core-storage config 封装;app 源码零
  `com.orhanobut.hawk` import/调用(仅 core-storage `KeyValueStore` 持有 Hawk)。
- ✅ LogEvent 空投清理:LOG 曾向 EventBus 空投 LogEvent(全仓零订阅者),现已移除投递与 `event/LogEvent`,
  LOG 保持纯 Logcat 输出;core-network 移除 eventbus 依赖与空 event 目录(运行时日志文件由 :log 模块承担)。
- ✅ common(现 :core-network)event 收口完成:已删 HistoryStateEvent/TopStateEvent(零引用孤儿)、
  DownloadEvent/RefreshEvent/ServerEvent 各自归位业务/app 模块,LogEvent 空投随 LOG 改造移除——core-network
  已无 EventBus 事件/依赖,event 目录删除。
- ⚠️ DownloadFragment 已完成 Facade 订阅去 EventBus;app 其余 EventBus 点(搜索/快速搜索/历史/直播等
  refresh 事件)仍为跨 Fragment 通信,逐步收口属"状态/事件管理"长线项。
  **当前面貌(截至 2026 快搜/字幕/死事件批次后)**:EventBus 订阅方仅剩
  BaseActivity(空壳载体)/DetailActivity(`TYPE_REFRESH`、`TYPE_QUICK_SEARCH_RESULT`)/FastSearchActivity
  (`TYPE_SEARCH_RESULT`、`ServerEvent`)/PlayService(`TYPE_REFRESH_NOTIFY`)/DownloadFacade(模块内桥);
  PlayFragment/QuickSearchDialog/LocalPlayActivity/UserFragment/GridFragment 均已零订阅。
  剩余有意保留:①播放器主线 `TYPE_REFRESH`/`TYPE_REFRESH_NOTIFY`(后台播放宿主销毁时 EventBus
  静默丢弃是保护语义,直调化并入 PlayFragment 全驱动改造);②VM 多源结果流 `TYPE_SEARCH_RESULT`/
  `TYPE_QUICK_SEARCH_RESULT`(每源一批、宿主累加,LiveData 单值会丢中间批次,归 SourceViewModel
  注入化);③`ServerEvent` 遥控域(SearchReceiver 空壳/16-17 常量由并行侧接线);④download 模块内
  Manager→Facade 桥(模块内实现,跨线程切主线程职责,非跨模块)。

## 10. 处理记录(按轮追加)
- ✅ SourceViewModel type0/1 解析纯函数化:`spider-api/AbsXmlParser.parseXml/parseJson/normalize`(XStream 白名单加固、
  xml 清洗、线路串→beanList、sourceKey 回填),VM `xml()/json()` 改为解析+`publishDetailPayload` 副作用分离;
  建议真机回归 type0/1 列表/详情/搜索与 type3 typed 路径。并行侧同文件改动(absXml 简化)已合流,评审一次。
- ✅ 真机 NPE 修复(typed 通道):absXml(typed) 改走公开的 `AbsXmlParser.normalize`,统一回填 sourceKey 并
  拆分线路串→beanList(此前并行简化把 typed 端拆分丢掉,checkThunder 遍历 null beanList 崩溃);另加
  beanList 空防御。gate 绿。真机回归点:type3 typed 详情/播放/雷资源判定。(commit 348fca79)
- ✅ AbsXmlParser XML 样例 + 防御回归:parseXml type0 多线路 dd/空 year/state 样例;normalize typed 路径
  (仅 urls 文本补 beanList,对应真机 NPE);normalize 自身加固 null data/空白 urls。(6c07f31f)
- ✅ RefreshEvent 零订阅死类型/死投递清理:全仓 @Subscribe 审计后删 TYPE_HISTORY_REFRESH、
  TYPE_API_URL_CHANGE/TYPE_PUSH_URL(仅 ControlManager 投,零订阅;push 本就"暂未实现")、
  LocalPlayActivity finish() 投 TYPE_REFRESH ""(处理器仅认 Integer/JSONObject)。遥控推送常量 16/17
  由并行侧按新编号保留声明,未动。(86ea62be)
- ✅ 字幕字号变更同屏直调化:DetailActivity→PlayFragment 的 TYPE_SUBTITLE_SIZE_CHANGE 广播改为
  playFragment.applySubtitleTextSize 直调(预览播放器与详情页同屏、两端唯一);PlayFragment 移除
  唯一 @Subscribe 及 register/unregister;常量 12 删除。(d9f754eb)
- ✅ 快速搜索弹窗簇收口 EventBus(DetailActivity 屏内闭环直调):QuickSearchDialog 删注册/@Subscribe/投递,
  改 Host(onVideoSelected/onWordChange)+appendResults/updateWords 宿主直喂;DetailQuickSearchHelper 注入
  QuickSearchOutput 输出回调(仅弹窗展示期有效,等价原订阅窗口);DetailActivity tvSite 打开时 setHost+初始
  直喂累计结果/词表(修复原 show 前广播在弹窗注册前丢失的时序);RefreshEvent 删零引用常量
  QUICK_SEARCH/SELECT/WORD/WORD_CHANGE(2-5)。保留 TYPE_QUICK_SEARCH_RESULT:SourceViewModel→DetailActivity
  的多源异步结果流(每源一批、宿主累加),LiveData 单值会丢中间批次,归"SourceViewModel 双轨/注入化"长线。
  (98a253a3)真机回归点:详情页"来源"快搜弹窗数据流/点词切换/点结果跳详情。
- ✅ type4 快搜 onError 路由修复:SourceViewModel.getQuickSearch type4 分支 HTTP 失败误投 TYPE_SEARCH_RESULT
  (FastSearch 主搜索通道),改投 TYPE_QUICK_SEARCH_RESULT null(与 type0/1 快搜 onError 对齐),避免错误清空
  无关搜索页结果。(db13be31)
- ✅ UserFragment/GridFragment 死 EventBus import 清理(无 @Subscribe/register/post;GridFragment 的
  mGridView.post 为 View.post 非 EventBus)。(e2bfc2b7/90299dd4/ca63859e)
- ✅ DetailActivity onDestroy 断开快搜输出桥(setQuickSearchOutput null)并清弹窗引用,防匿名回调
  悬空引用已销毁弹窗/Activity。(d2580119)
- ✅ SortParser type0 XML 分类样例单测(parseSortXml 已测:rss/class/ty 解析 + filters 空补 +
  畸形输入返回 null;JSON 分支原有覆盖)。双通道均 JVM 可测。(9077b3fd)
- ✅ DownloadFragment 纯展示逻辑抽离为 `util.DownloadDisplay`(纯静态,无 Android 依赖):
  剧集名/清晰度/索引段解析、尺寸/速度格式化、列表指纹、递归删除;DownloadFragment 净 -78 行、
  TextUtils import 清除;新增 JVM 单测 7 例(格式化边界/命名解析/指纹/递归删)。
  配套:`checkModuleDependencies` 门禁收窄为仅拦截 download.internal(app util 的 Download* 旧实现
  已迁入 internal 并删除,宽拦 util.Download* 已无对象)。(8de5b5ee)
- ✅ buildPercentText(任务行"大小·进度"文本)并入 DownloadDisplay,补 HLS 混排/纯字节/失败附因用例。
- ✅ LiveActivity 移除死代码:EPG `getTime(String,String)`/`durationToString(int)` 全仓零调用,删除并清 import。(57203751)
- ✅ DownloadFragment 聚合分组抽离 `util/DownloadGrouping`(纯数据):DownloadGroup 模型 + 分组/归属/组序/
  存在性判定迁出,宿主仅留 Facade 取数与委托;JVM 单测 6 例。DownloadFragment 1306→1079 行。(30a792be)
- ✅ 任务行状态展示下沉 DownloadDisplay:statusTextOf/statusToneOf(ERROR/MUTED/ACTIVE,颜色表留 UI)、
  stageMessageOwnsProgress(合并/补片自带进度)、shouldShowSpeed(逐字等价原 startsWith 判定);单测 3 组。
  DownloadDisplayTest 累计 12 例。(aef13cfd)
- ✅ 聚合卡副标题 aggregateNote(任务数+已完成且文件存在集数)下沉 DownloadGrouping;单测 7 例。
  DownloadFragment 1306→1038 行。(1f876086)
- ✅ 任务行文本拼装下沉 DownloadDisplay:titleText(剧名·集名判重)/statusLine(状态+百分比+网速,
  收尾省略整体百分比)/sourceText(未知来源)/swipeActionText(继续/重试/暂停);convert 仅剩 setText
  与色调取色。DownloadDisplayTest 累计 15 例。DownloadFragment →1016 行。(0d929aeb)
- ✅ FormatSRT 解析样例单测(字幕装载纯解析链路首测):双字幕顺序+毫秒精确、多行 <br /> 连接、
  空输入零字幕。(ffbd4988)
- ✅ LiveActivity 死代码收尾:showBottomEpg 无用空 Handler、一批零使用字段(playUrl/timeFormat/
  countDownTimer3/videoWidth/videoHeight/show)+ 从未赋值的 countDownTimer(恒空 cancel 块)删除,
  清 CountDownTimer/SimpleDateFormat import。LiveActivity 815→731 行。(679881bb/b8e0b7ce)
- ✅ SourceViewModel 死代码/冗余清理:删除零调用 getSortFilter(及 gson JsonArray/JsonElement/
  JsonObject/LinkedHashMap import);sortJson/sortXml 未用的 MutableLiveData result 参数去除
  (解析后由调用方发布),4 处调用点同步。(1ddead9b)
- ✅ DetailActivity 下载弹窗数据模型抽离 `util/DownloadSeriesModel`(纯):勾选集名收集、
  按全集正表重建选集副本并写 episodeId、批量状态查询数组拆分;宿主仅做弹窗类型分派与
  Facade episodeId 工厂注入。单测 4 例。(308572ba)
- ✅ 批量下载结果文案下沉 EpisodeDownloadBatch.toastMessage(Outcome)(六路文案纯映射),
  宿主只弹 toast + null 兜底;单测覆盖各分支。DetailActivity 下载弹窗状态层数据/文案面
  已纯化;剩余为 UI 弹窗实例/生命周期编排(全屏退出、抽屉/底部弹窗分派、Facade 订阅),
  与宿主强耦合,深拆需真机背书。(2f7149d1)
- ✅ DetailActivity 下载弹窗协调器等值搬移 `ui/dialog/DownloadDialogCoordinator`:
  弹窗实例/防重入、DownloadFacade 状态订阅注销、数据后台准备、Wi-Fi 确认+批量入队+文案
  全部下沉;DetailActivity 只实现 Host(数据/全屏退出时序/postDelayed/跳转/toast/runOnUi),
  public 入口转发协调器;同步清理 30+ 失效 import。DetailActivity 1244→993 行。
  (013f9c15)真机回归点:详情"下载"底部弹窗、全屏控制栏右侧抽屉、选集勾选/排序保留、
  批量下载文案、下载状态实时刷新。
- ✅ DetailActivity 再清理零调用死方法 getHtml;同时并行侧提交搜索卡片/全屏控制样式批
  (9bb20a51),整仓门禁复绿。DetailActivity →987 行。(254bb13d)
- ✅ 选集弹窗右侧抽屉去 Activity 强依赖:AllVodSeriesRightDialog 不再把 Context 强转
  DetailActivity,改为构造注入 seriesFlagAdapter/seriesAdapter + sortAction/isSeriesReversed
  (与底部弹窗一致的回调风格,行为不变)。DetailActivity 调用点同步。(bc9c2d97)
- ✅ PlayFragment 前置步骤:会话键(progressKey/subtitleCacheKey)纯构造抽
  util/player/PlaySessionKeys(与历史拼接逐字一致),作为后续 PlayViewModel 化的数据键来源;
  单测 3 例(格式/确定性/索引敏感)。(fe9be382)
- ✅ PlaySessionKeys 补 playbackSessionKey(vod|来源|剧id|线路|索引,PlaybackSessions 登记语义),
  PlayFragment.bindPlaybackSession 复用;单测补 2 例。(4d7c0d93)
- ✅ "已播放剧集"键抽 util/player/PlayedVodKey(sourceKey|vodId):PlayFragment 写入与
  DownloadFragment 读取(episodeId 前两段)共用同一语义,消除两处重复拼接/截取;
  单测 3 例(null 归一/截取/畸形)。播放身份键域(进度/字幕/会话/播放记录)全部收敛。(047f2e0f)
- ✅ 播放请求上下文抽 util/player/PlayRequest(of(vodInfo, series)):PlayFragment.play() 散参
  (来源/线路/索引/集名/地址 + 进度/字幕键)聚合为不可变对象,reset 清历史与 VM.getPlay
  调用经上下文取值;键与历史拼接一致。后续 PlayViewModel 化的请求载体。单测 1 例。(4b2a5df8)
- ✅ playResult 回填消费 PlayRequest:progressKey/subtitleCacheKey 改由 play() 经 PlayRequest
  落字段(单一来源),mObserverPlayResult 删除 proKey/subtKey 回写(仅消费真实结果
  subt/parse/jx/url/header);VM 透传保留。快速切集时序更稳。真机回归点:断点/切集清进度、
  字幕缓存配对。(d2467f25)
- ✅ 直播符合性审计(改进.txt 对照,非并行面):core-model 直播 bean 纯净、直播 adapter/
  controller 无 Hawk/EventBus/DAO/线程池/Activity 强依赖、LiveConfig 仅走 DataStore 门面、
  直播偏好/弹窗/密码/导航此前均已收口。调整:播放内核类型映射抽 `util/LivePlayerTypes`
  (索引↔pl/软硬解码双向,round-trip 一致),单测 3 组。(db53ea93)
  ⚠️ 并行直播重构(LiveActivity 控制栏)新增 LiveLineSelect*/AllChannels 等弹窗仍以
  `(LiveActivity) context` 强转宿主(同 AllVodSeriesRightDialog 已修反模式),待并行批
  提交后再统一构造注入化。
- ✅ 直播分组密码门禁抽离 `util/LiveChannelAuth`:isPasswordConfirmed/needInputPassword/
  visibleChannels 纯逻辑(组数据+确认集合传参),LiveActivity 三方法改委托;单测 4 例。
  (9bdcc169)
- ✅ 频道组/频道导航纯化 `util/LiveChannelNav`:getNextChannel 跨组/加密跳过/回卷决策抽出,
  行为等价 + 全锁定/单组防死循环兜底;firstOpenGroup 并入 LiveChannelAuth。
  LiveActivity 815→705 行;导航/门禁单测累计 9 例。(afccb330)
- ✅ LiveActivity 死字段清理:hsEpg(Hashtable)/imgLiveIcon 零使用、isSHIYI 恒假开关
  (if 恒不触发/赋值恒 false)连用法删除,清 Hashtable import。LiveActivity 815→746 行。(ede84aea)
- ✅ 直播弹窗 Activity 强转清零(改进.txt §7 收尾,接管并行批):LiveLineSelect*/LiveSetting*/
  AllChannels 弹窗不再 `(LiveActivity) context` 强转;新增宿主窄接口 `LiveLineSelectHost`/
  `LiveSettingHost`(LiveActivity implements 后以 this 注入),AllChannels 构造注入两个列表适配器,
  线路弹窗共用 `bind` 装载逻辑。gate 绿后随并行控制条批次提交。(73060c3a)
- ✅ PlayFragment 线程池收口(改进.txt §六"页面不得自建线程池"):记录已播放剧集的
  `PLAYED_RECORD_EXECUTOR` → `HeavyTaskUtil.getSerialExecutorService()`(新增应用级共享串行执行器,
  保同 key SP 读改写不交错);doParse type2/3 每轮 `parseThreadPool` → 共享大池 + epoch 自检
  (stopParse/新一轮解析递增,排队/在途任务过期即丢弃,同 DetailQuickSearchHelper 先例)。(97aadf32)
- ✅ 解析/嗅探引擎抽 `util/player/PlayParseCoordinator`(改进.txt §三 PlayCoordinator 方向;行为等价搬移):
  initParse(默认/内联 json/parse: 解析源选择)+ doParse(嗅探/json/json 扩展/json 聚合)+ 无头 WebView
  引擎(SysWebClient 拦截/广告过滤/SSL 拒绝默认/cookie/已发现队列)+ 20s 嗅探超时 + 解析上下文
  (parseFlag/webUrl/UA/headers)全部收口,PlayFragment 只实现宿主回调(提示/播放/错误重试/解析源展示/
  主线程投递)与 setSourceBean;PlayFragment 1607→997 行。startPlayUrl 仍留宿主(播放器会话接线,属
  PlayerApi 全驱动长线)。(待提交)
- ✅ TLS 安全红线收口(OkHttp 网络栈默认校验证书):此前 OkGoHelper/spider OkHttp 无条件挂
   `SSLCompat+TM`(信任任意证书)仅撤了恒真 HostnameVerifier,证书链校验仍全局关闭;本次改为
   仅当 `SystemConfig.isIgnoreSslError()`(默认关,与 WebView 同一开关)时才挂载,默认走 OkHttp
   系统证书链+主机名校验;`SSLCompat` 构造函数不再 `setDefaultSSLSocketFactory` 改写
   HttpsURLConnection 全局默认(去除 trust-all 全局副作用)。行为:开"忽略证书错误"后 OkHttp
   请求需重启应用生效(与既有提示一致)。(cae55368)
- ✅ Zip Slip 收口:spider `Path.unzip`(爬虫 jar 可调用的解压点)逐条目做 canonical 包含性
   校验,拒绝 NUL/../绝对路径/符号链接逃逸,整体失败不落盘;仓库三处解压点(RemoteServer/
   Path.unzip/crash 读自身 dex)全部防护或只读。(0f5d7d35)
- ✅ 搜索线程池收口(改进.txt §六"页面不得自建线程池",第二轮):FastSearchActivity 页面自建
   `FixedThreadPool(10)` + shutdownNow/pauseRunnable 机制移除,每源搜索提交应用级共享大池
   (HeavyTaskUtil) + epoch 自检(新一轮发起即自弃过期任务)+ 暂停 pending 续跑(onResume 重派),
   行为与旧等价(点结果跳详情暂停/回前台续跑);SourceViewModel type3 首页加载两处每轮
   `newSingleThreadExecutor` + shutdown 改提交共享大池,15s 超时/cancel 语义不变。
   (e5935879/8d944d96)
- ✅ Manifest 暴露面精简:删零使用权限 REORDER_TASKS/CHANGE_WIFI_MULTICAST_STATE(app+player)、
   app 重复 WAKE_LOCK(player 内核自持)与未用 `<queries idm>`;无 intent-filter 的内部 Activity
   (Main/Live/Detail/FastSearch/Setting/History/Collect/Download/Log/VideoList)补显式
   android:exported=false(行为不变,默认即 false)。复核达标项:resConfigs 'zh-rCN','zh'、
   org.gradle.parallel/caching 开启、Room 2.5.2/lifecycle 2.6.2 显式对齐、下载进度 600ms 节流
   落盘(flushProgress/DownloadProgressEvent)、启动缓存清理移出主线程(阈值 100MB 延迟后台)、
   图片库唯一 Picasso、UA 常量表去重——均已在位;Gson 各 `new Gson()` 点复核均为冷路径
   (懒加载/一次性),热路径早已静态复用。(d4699d7c)
- ⚠️ 大文件物理拆分(PlayFragment 997/DetailActivity 1085/DownloadFragment 1082/
   SourceViewModel 858)与播放器主线尾段属阶段三,需真机回归背书,本轮不做搬移。
- ✅ 弹窗 Activity 强转清零收尾(改进.txt §七 红线):VideoDetailDialog 不再
   `(DetailActivity) context` 强转,改构造注入窄宿主 `VideoDetailDialog.Host`
   (仅 getCurrentVodUrl),DetailActivity implements Host。复核 grep:全仓 UI 组件
   已无 `(Activity) context` 具体 Activity 强转(含 kotlin as 强转)。(cbd70b33)
   注:§9 "SourceViewModel xml/json 纯函数提取" 主体已完成——type0/1 解析已下沉
   :spider-api(AbsXmlParser/SortParser,带 JVM 单测),VM 仅剩薄委托与多源发布编排,
   收口依赖 Device 回归轮。
- ✅ Gson 热路径静态复用复核+收口:app 内 `new Gson()` 逐点复核——热路径仅剩两处:
   Picasso 下载器每张带 `@Headers=` 图片的 header JSON 解析、局域网 `/proxy` 每次请求的
   请求头序列化,均改类级静态 GSON(Gson 线程安全,AGENTS:解析器复用实例禁热路径重复构建);
   其余点均为冷路径(懒加载/一次性)。(1b121034)
- ✅ EventBus 收窄为按需注册(BaseActivity 空壳订阅下线,改进.txt §五 兼容层收口):
   BaseActivity 移除全 Activity 自动 register 与空 `@Subscribe refresh` 壳——此前每条事件
   投递会扇出到所有存活 Activity 的空实现(无谓分发);真实订阅方各自补 @Subscribe 并自管
   register/unregister:DetailActivity(TYPE_REFRESH/TYPE_QUICK_SEARCH_RESULT)、
   FastSearchActivity(TYPE_SEARCH_RESULT/ServerEvent)、VideoListActivity(本地列表重扫)。
   全仓 EventBus 订阅方收敛为:DetailActivity/FastSearchActivity/PlayService/
   DownloadFacade(模块内桥),零空壳订阅。(27315f4f)
- ✅ UnicodeReader/CharsetUtils JVM 单测补充(字幕文件装载链路,不需真机):
   UnicodeReader BOM 识别与解码(UTF-8/UTF-16LE/UTF-16BE)、无 BOM 回退默认编码、空输入安全;
   CharsetUtils.detect 以"检测编码可无损还原原文"为准覆盖 UTF-8(含/不含 BOM)、ASCII、
   GBK 中文回退路径。(2aaaa8af)
- ✅ 字幕字符集探测单一权威化(改进.txt:同一能力一个权威实现):SubtitleLoader 本地/远程
   字幕加载各自内联的 UniversalDetector 探测改为统一 `CharsetUtils.detect`(同探测器 +
   中文常用字回退,上一批已带单测);同时修复探测结果为空时 `new String(bytes, null)`
   的潜在 NPE。CharsetUtils 由零引用变为实际消费方,不再有重复的探测实现。(e314cb5e)
- ✅ 搜索词表纯函数修正+单测:SearchHelper.splitWords 此前会向快速搜索词表注入空候选词
   (Java \W 为 ASCII 语义,中文标题如"你好 world"拆分出空串);现过滤空串,原文恒在首位、
   仅补 ASCII 词。补 SearchHelperTest(纯中文/中英混排/纯 ASCII/空串)与 HistoryHelper
   历史条数档位单测(越界回退首档)。(d5a046af/c0bed35a)
- ✅ 搜索结果命中判定抽纯函数:FastSearchActivity.matchSearchResult 逐字等价抽取为
   `util/SearchFilter.matches`(消除 Activity 内嵌判定与 searchTitle 参数遮蔽),补
   SearchFilterTest 锁定语义(空名/空词不命中、多词 AND、中文部分命中、首尾空白、
   纯空白查询词命中全部)。(e0787051)
- ✅ FormatASS 解析样例单测(与 FormatSRT 并列覆盖字幕纯解析链路):两条 Dialogue 的
   起始/结束毫秒与内联覆盖标签({\\i1}...)剥离;样例保持 ASCII 以规避 JVM 测试环境
   平台字符集不可移植。(2ad86b20)
- ✅ FastSearch 非空断言警告清理(searchAdapter!! 等多余 !!)。Kotlin 编译告警清点:
   本轮触及文件内告警已清零(HomeFragment/SettingActivity/SubscriptionActivity 等告警属并行批改动范围)。
   (307073a1)
- ✅ 新增《后续改造评估》(hawk 退役 / 播放器主线收口 / 杂项袋与死代码):代码级证据评估产出
   `doc/后续改造评估.md`——hawk 现仅 core-storage KeyValueStore 持有且已退化为"读一次旧键即删"的
   一次性迁移通道,退役须与旧版升级数据回归同批(建议先无 hawk 化灰度再删);播放器收口按
   P1 原型回归→P2 会话唯一指令→P3 默认开启→P4 基建收口 四阶段推进;杂项袋/死代码/EventBus 直调
   列小步清单。待并行批合流与真机回归后按序执行。
- ✅ hawk 退役版 N(无 hawk 化灰度)已实施:KeyValueStore 摘除 Hawk 后端(init 占位、legacy 读取默认值、
   put/delete 空操作),core-storage 移除 hawk 依赖,App 装配去掉 KeyValueStore.init,proguard keep 删除。
   各域 legacy 分支保留但自然失效;版 N+1(旧版升级数据回归通过后)再删本类与分支。详见
   `doc/后续改造评估.md` A.3。(4b775b18/3d9d9dcc)
- ✅ 死代码清理:删除零引用 `util/AppBizLog`(业务日志门面,已被 :log LogStore/CategoryLogger 取代;
   目录文档未收录,无需同步)。TLSSocketFactory 仍待并行目录文档合流后删除。(6dad955a)
- ✅ 疑似缺陷记录(不贸然改):FormatASS 样式段解析缺陷复现并登记(评估文档 §E)——外层循环
   段内读取会吞掉下一个段头,Styles 段不进入(.ass 样式丢失)。修复需真实 .ass 语料+真机
   渲染比对,暂缓。(b6c94a9b)
- ✅ Time 时间模型单测(SRT/ASS/帧率三格式共用时间解析):解析毫秒、SRT 往返格式化、
   ASS 补零行为锁定。(8eaed57e)
- ✅ 依赖/注释同步:player-api 移除 hawk 传递残留的 gson 依赖(core-storage 注释更正为
   PrefsDataStore);评估文档补记 Style 颜色十六进制分支错位缺陷(与样式段缺陷同批待修)。
   (fe6a80d8/3fb724b4)
- ✅ hawk 退役版 N+1(代码侧)已实施:KeyValueStore 类删除,各域 legacy 迁移分支全部移除
   (core-storage SystemConfig/DefaultLogConfigStore、player-api PlayConfig、download
   Store/Policy/Archive、app 订阅-直播-热播-遥控、spider ApiConfig/local),运行权威统一
   PrefsDataStore/文件;AGENTS/评估文档同步。发布仍需 H 组旧版升级回归。
   (cea1160b/95efedf7/10e0ddbe/f6ff846a/910b02a0/03c6a0b6)
- ✅ :core-network 杂项袋拆分 slice1:新增 :core-utils 承载纯算法 AES/MD5(纯搬移,包名不变;app/spider 接入,根门禁注册)。余 urlhttp/AESUtil、AdBlocker、LOG/AppLog 归位见评估 §G。(e3f997df/e3fd24a2)
- ✅ 字幕时间模型 Time 由 Java 转 Kotlin(Java→Kotlin 现代化批次):保持 `@JvmField mseconds` 公开字段
  (SRT/ASS/STL/SCC/TTML 等格式读写同址)与 Java 构造/`getTime(format)` 语义逐字等价;原有 TimeTest
  (解析/往返格式化/补零)原地锁语义,门禁绿后提交。
- ✅ **真机回归发现并修复 IJK 内核缺陷**(K4 首轮,MEIZU 21/Android 16):模块化批次 76158888 误删
  `player/.../tv/danmaku/ijk/media/player/ffmpeg/FFmpegApi.java`(仅按"移除未注册 Exo/FFmpeg 扩展"清理,
  但该类是 IJK `libplayer.so` JNI FindClass 必需;Java 侧无静态引用故编译/门禁不报)。真机选 IJK 播放器 →
  `ClassNotFoundException: ...FFmpegApi` → 内核静默回退 Exo。已从 v3.2.0 恢复该 5 行文件(f9bd27d6),
  门禁全绿,重装后 IJK `onNativeInvoke` 正常、音视频出流。
- ✅ 设备回归结论登记(见 device-regression-checklist §G/§H 注):mbox 专属包名(9fe89c10)使 hawk 升级回归
  对当前 HEAD 不适用(osc→mbox 包名隔离,无老用户/无 Hawk 存量);G 组双内核起播验证通过,余 play/pause/
  seek/切集/通知/断点等观感项待人工逐项比对;PlaybackSession 的 D 级日志在本机型被全局过滤,
  核对须走业务日志。
- ✅ **后台播放补系统媒体控制中心组件**(用户需求):PlayService 接入平台 MediaSession(API 21+,
  零新依赖,avoided androidx.media:media 1.0.0 旧 support 命名空间问题)——前台通知
  category=transport/vis=PUBLIC,会话 metadata=片名/集数、actions 含 PLAY/PAUSE/SKIP/SEEK,
  状态轮询(800ms)同步播放/暂停/进度;MediaSession 回调直接驱动共享 videoView,skip/stop 复用
  VOD_CONTROL 广播通道。真机验证:后台播放下会话 MBoxPlayback 注册,
  `cmd media_session dispatch play/pause` 双向驱动——play→PLAYING+进度推进,pause→PAUSED+
  position 冻结。控制中心/锁屏媒体卡由此可暂停/继续/切集。(ba7e685b,门禁全绿)
- ✅ K5/§J 子步:type3 typed 解析权威单一化——SpiderHomeImpl.parse/SpiderDetailImpl.detail/
  SpiderSearchImpl.search 删除各自内联的"Gson→AbsJson→toAbsXml"双实现,统一复用与 VM 同源的
  AbsXmlParser.parseJson(含 normalize);VM 消费点二次归一(parseJson 内部 + absXml())由新增
  幂等单测 normalize_isIdempotent 锁定无害。VM type0/1/4 六入口 typed-first 收敛为行为面,
  按评估 §J 需逐源真机回归,留真机批。(0cce8442,门禁全绿)
- ✅ K5/§J 基建子步:URL 拼装/归一抽 :core-network util/HttpUrls 纯类(无 android 依赖,
  HttpClient 委托、行为零变化;JVM 等值单测锁定),并新增带参同步
  `HttpClient.getSync(url, params, headers)`——为 HTTP 型源(type0/1/4)typed 取数
  复用同一拼装语义铺路。评估 §J 修订方向①/② 均依赖此底座。(fe58a119,门禁全绿)
- ✅ K5/§J 收敛闭环(detail/search/quickSearch/play/category 拼参 typed-first):搜索/快搜
  857db402、type4 play 拼参 8c7b5b9b、category(list) 724f7514——filterSelect 序列化在 :spider
  android 环境内用与 VM legacy **同 API 的 org.json+Base64**(同实例→键序/结果一致),typed 优先 →
  fetch*HttpLegacy 逐字兜底;真机冒烟主页/热播分类正常、无崩溃,逐源分页回归待设备批;Home sort
  (getSort)受 homeRec 富化耦合仍待设备批。(登记见 doc/后续改造评估.md §J/K,门禁全绿)
- ✅ 工作区卫生(无提交):删除 194 个运行残留——`app/hs_err_pid*.log`×187 + `app/replay_pid*.log`×2
  (此前 Gradle 测试执行器 OOM 崩溃转储)+ 根 `hs_err_pid*.log`×2 + 根 `build-release.log`/
  `build_gates_last.log`/`build_gates_appbubble.log`×3。均未跟踪且早被 .gitignore `*.log` 忽略,
  git 树零变化;按文件名精确删除(未用 git clean),并行 wave 文件原样保留。

## 9. 待真机回归后继续(播放器主线尾段,当前挂起)
> 集中回归清单见 `doc/device-regression-checklist.md`(按功能域分组,门禁绿后逐项过)。

- PlayerApi 会话内核**实验选项**:设置页加"播放器内核:PlayerApi 会话(实验)"开关(默认关);
  开启时点播走 PlayerFactory 创建的内核并驱动基础播放 + PlaybackSessions 会话观察,与 doikki 路径并行对比。
  需控制器/字幕/进度接线与真机调,决定先不做,待播放器相关批次(会话/内核统一/字幕装载/电量/网速/DataStore/
  SAF)真机回归通过后,再按完整方案实现。
- 回归通过后可继续:SourceViewModel `xml()/json()` 纯函数提取(type0/1 下沉前置,等值可单测);
  EventBus 跨页 refresh 逐类收口;core-storage hawk 依赖下线(一次性迁移通道退役)。

## 11. 剩余项清单(对照 改进.txt,截至 2026-09-06 代码级审计)

> 代码级可安全批次已基本收口(hawk 退役 N/N+1、EventBus 按需注册、死代码清理、Gson 复用、
> 解析/词表/字幕纯函数化与单测等)。以下为仍未完成的项及前置条件,均可在本文件 §9/§10、
> doc/后续改造评估.md F 表与 doc/device-regression-checklist.md 找到登记。

### A. 模块与依赖边界(改进.txt §一/§二)
| 项 | 状态 | 前置/说明 |
|---|---|---|
| :playback / feature-* 模块 | ❌ 未建 | 阶段三/四;需真机回归环境 |
| :core-network 杂项袋拆分 | ✅ 完成 | AES/MD5→:core-utils、urlhttp 旧栈删除、AdBlocker→:core-utils、SubUrlResolver(s)→:spider、LOG/AppLog→:core-storage(5bf1d01d);util 余纯网络职责（现状：`:core-utils` 已并入 `:common`，包名不变） |
| 字符串通道 SpiderContentApi | ⚠️ 澄清:typed 底座,非删除项 | typed 实现(SpiderHome/Detail/SearchImpl)内部经 SpiderContentImpl 拉取后再解析,string 通道是运行底座;可治理点=收窄 app 直用面(SourceViewModel typed-first+fallback),需真机背书(见评估 §H) |
| SourceViewModel type0/1/4 契约化 | ⏳ 解析层已收口 | typed 解析已与 VM 同源(AbsXmlParser,0cce8442);VM type0/1/4 六入口 typed-first 收敛为行为面,需逐源真机回归(见评估 §J/K5) |
| 播放器内核收口 | ⚠️ | UI 层内核直用清零(SettingActivity DoT 收口 PlayerTrackHelper,45ea6745);MyVideoView 仍 7 文件 import(播放器全驱动 P1-P4 待真机) |
| Exo→Media3 | ⏳ 代码已迁移,待人工回归 | Media3 1.4.1(匹配 compileSdk 34),类型 2 兼容旧设置；DASH/HLS/RTSP/RTMP/缓存、音轨选择与 CueGroup 字幕回调已迁移；关键帧 seek/同步 MediaCodec/单次重试保留 |
| :app 的 ui-kit / ui-common（原 `:ui-common` 已并入 `:app`） | ✅ 达标 | ui-common 纯资源(0 java,107 res，现为 app 内资源，主题 JSON 在 `app/src/main/assets/theme/`);ui-kit 15 组件无 Hawk/EventBus/ApiConfig/Activity 强转/自建线程池违例;拆独立模块待复用稳定(见评估 §I) |

### B. 大页面物理拆分(改进.txt §三,阶段三)
> 行数实测于 HEAD 701bccb8(2026-09-06,Get-Content 计行,与 git HEAD 一致):
> PlayFragment 1010 / DetailActivity 1063 / DownloadFragment 1082 / SourceViewModel 949 / LiveActivity 883。
> 注:SourceViewModel 因 K5 typed-first 收敛(+fetch*HttpLegacy 逐字兜底)较旧记录(851)净增;
> 物理拆分未动,仍属阶段三。
协调器与纯化已大量下沉;剩余宿主编排需真机回归背书后继续搬移。

### C. 状态与事件(改进.txt §五)
EventBus 订阅方已收敛 4 个真实方;仍剩多源结果流(TYPE_SEARCH_RESULT/TYPE_QUICK_SEARCH_RESULT)
直调/注入化未做(行为敏感,建议随播放器收口批、真机回归)。

### D. 现代化(改进.txt §八·第五阶段)
✅ Hawk→DataStore(代码完成,发布前需 H 组旧版升级回归);⏳ Media3(代码完成,待 §B 播放人工回归);⚠️ Java→Kotlin 部分;
❌ Hilt(组合根复杂化后再评估);✅ 依赖方向门禁 + JVM 测试。

### E. 待修缺陷 / 待放行(已登记)
- FormatASS 样式段解析缺陷 + Style 颜色十六进制错位(doc/后续改造评估.md §E):需真实 .ass 语料+真机渲染比对;
- 播放器收口 P1–P4(清单 G 组)、hawk 版 N+1 发布放行(清单 H 组):均需设备回归。
  - 2026-09-06 **K4 设备回归已完成**(MEIZU 21/Android 16):G 组双内核(IJK/Exo)起播+
    观感项(播放控制/切集/完成/出错/断点/后台通知)人工实测全部通过;会话复用无泄漏;
    IJK FFmpegApi 误删缺陷已修(f9bd27d6);后台播放补系统 MediaSession 媒体卡(ba7e685b,
    控制中心可暂停/继续,d adb dispatch 双向验证)。H 组因 mbox 包名隔离判定"对当前 HEAD 不适用"
    (osc→mbox 无 Hawk 存量场景,详见 checklist §H;未来 osc 发布线验证路径已记录)。
    播放器收口 P2–P4 与 type0/1/4 契约化(K5)属后续批次,待排期。

- ✅ 主搜索批次流直调化(改进.txt §五 试点):TYPE_SEARCH_RESULT 下线——SourceViewModel 增 SearchBatchListener,FastSearchActivity 注入/置空并主线程投递;refresh 订阅与常量删除。quick 结果流亦已直调化(TYPE_QUICK_SEARCH_RESULT 下线,8e1e8fa6)。详情选集/播放配置同步亦已直调化(PlayFragment.PlaySyncHost,TYPE_REFRESH 下线,702f24db)。EventBus 现仅剩:后台通知 TYPE_REFRESH_NOTIFY(PlayFragment/VodController→PlayService)、遥控 ServerEvent、DownloadFacade 模块内桥;DetailActivity/SourceViewModel 内 EventBus 已归零。
- ✅ 死代码清扫:app 模块删除 14 个零引用类(旧 EPG/直播控制器/列表适配/旧控件/工具,-1099 行,06adec9c/504c77a6);其余模块全仓扫描仅 4 候选均判定保留(CaocInitProvider=crash Manifest auto-init Provider;SpiderDebug/SpiderJS/UTF8BOMFighter=QuickJS JS 桥按名反射,需 jar/真机确认后才可删)。
- ✅ 静态审计追加:共享 DTO(Movie/VodInfo/Abs*/SourceBean/Subscription 等)确认单源在 :core-model,app/spider 仅存 UI 本地模型(Epginfo/VideoFolder/VideoInfo/Doh),无重复定义;app 移除重复 kotlin-stdlib 声明(依赖面未见其它零引用项)。
- ✅ 门禁硬化(改进.txt §六/AGENTS §六 落地):checkModuleDependencies 现自动扫描 app(含测试)的 getCSP 直调/Hawk import/DownloadManager.get() 直调/业务自建 OkHttpClient,违规即抛错;此前仅靠人工 grep。
- ✅ EventBus 全仓下线(承接上条"仅剩三处"):①后台通知 TYPE_REFRESH_NOTIFY 改 `PlayService.onPlaybackNotify(String)` 静态直调(内部 post 主线程,复刻 ThreadMode.MAIN;服务未启动静默跳过),VideoListActivity 冗余订阅删除(onResume 已重扫),RefreshEvent 类删除;②遥控 ServerEvent 死链整条删除(SearchReceiver.onReceive 空实现、SERVER_SEARCH 无发送方、SUCCESS/CONNECTION 无订阅者),连带删 SearchReceiver 类+Manifest 注册+ControlManager 广播+ServerEvent 类,收敛 exported 暴露面;③download 模块内桥改 `DownloadManager.EventSink` 回调(Facade 构造注册,runOnMain 复刻 ThreadMode.MAIN),删 DownloadEvent/DownloadProgressEvent 类。app 与 download 的 eventbus 依赖均移除(全仓最后一处),classpath 无 eventbus=编译期硬门禁;checkModuleDependencies 增 app 层 EventBus 红线。改进.txt §五"EventBus→Flow/接口"(第五阶段)提前完成。
- ✅ 门禁再硬化:UI 层红线自动化(app/ui 禁 IJK/Exo 内核 import 与自建线程池),与验收红线扫描一并入 checkModuleDependencies。
- ✅ 门禁再硬化:app 层「具体 Activity 强转」静态检查入 checkModuleDependencies(命中按文件行号报错)。
- ✅ CI 门禁:build-apk workflow 在 assembleRelease 前新增 Verify 步骤(unit tests + checkModuleDependencies),验收红线随 CI 强制。
- ✅ Java→Kotlin 试点(§八 五阶段):HistoryHelper 转 Kotlin(object + @JvmStatic,旧 Java 静态调用与 HistoryHelperTest 不受影响)。
- ✅ Java→Kotlin 试点续:SearchFilter 转 Kotlin(object + @JvmStatic,测试与 FastSearch 调用不变)。
- ✅ Java→Kotlin 试点续:PlayedVodKey 转 Kotlin(object + @JvmStatic,播放/下载调用与测试不变)。
- ✅ Java→Kotlin 试点续:PlaySessionKeys 转 Kotlin(注意:@JvmStatic 方法若被 Java 传 null,参数须声明可空并复刻拼接语义,测试先行发现该点)。
- ✅ Java→Kotlin 试点续:PlayRequest 转 Kotlin(class + private ctor + companion @JvmStatic of,保留 vodInfo()/seriesName() 等 Java 风格访问器以兼容 Java 调用)。
- ✅ Java→Kotlin 试点续:LiveChannelAuth 转 Kotlin(object + @JvmStatic;visibleChannels 保持可空返回语义)。
- ✅ Java→Kotlin 试点续:LivePlayerTypes 转 Kotlin(object + const + @JvmStatic,双向映射与解码语义等价)。
- ✅ Java→Kotlin 试点续:DownloadSeriesModel 转 Kotlin(object + @JvmStatic;IntFunction 工厂参数与数组拆分语义等价)。
- ✅ Java→Kotlin 试点续:LiveChannelNav 转 Kotlin(object + @JvmStatic;IntUnaryOperator/IntPredicate 参数与回卷/跳过锁定语义等价)。
- ✅ Java→Kotlin 试点续:DownloadGrouping、EpisodeDownloadBatch 转 Kotlin(@JvmStatic/@JvmField 保 Java 字段;分组/入队文案/解析逻辑测试托底)。
- ✅ Java→Kotlin 试点续:DownloadDisplay、M3u8Cleaner 转 Kotlin(格式/文案/指纹/HLS 净化全部等值,各自测试托底)。
