# 安全审计整改与架构迁移状态（跟踪文档）

> 本文件汇总 MBox（原名 TVBoxOS-Mobile）多轮整改（安全/性能审计 + 模块边界路线图 改进.txt）
> 的落地状态、关键改动点与真机回归矩阵。代码级验证：Debug/Release 双变体 BUILD SUCCESSFUL。

> **模块现状（2026-09）**：全仓 9 个模块 `:app`/`:common`/`:core-storage`/`:player`/`:thirdparty`/`:log`/`:core-network`/`:spider`/`:download`。本文件中提到的 `:core-model`/`:core-utils`/`:state` 已合并进 `:common`，`:spider-api`→`:spider`，`:player-api`→`:player`，`:crash`/`:TabLayout`/`:ViewPager1Delegate`/`:quickjs`→`:thirdparty`，`:ui-common`→`:app`（主题 JSON 在 `app/src/main/assets/theme/`）。下文历史记录保留当年模块名。

## 0. 近期进展补充（2026-09-25）

- **安全 DNS(DoH)改为即时生效 + 爬虫侧接上（2026-09-27）**：此前 `SettingActivity` 改"安全 DNS"只换静态字段 `OkGoHelper.dnsOverHttps`，而 OkHttp 的 DNS 是 **build 时写进 client** 的 —— 已建好的 `defaultClient`/`noRedirectClient`/Exo `playbackHttpClient`/Picasso 图片客户端/下载客户端全都继续用旧 DNS，**必须重启**；`spider` 的 `catvod.net.OkHttp.setDoh` 更是**全仓零调用点**，爬虫请求从不走 DoH。本轮改为：
  - 选项表/夹取/下标→url 收敛为纯逻辑 `util/DohOptions`（+`DohOptionsTest` 6 例）；放在 **`:core-network`** 而非 `:common`——门禁里 `:core-network` 禁止依赖 `:common`，而这份表正是网络侧自用，跟随使用方落位。`OkGoHelper.dnsHttpsList/dohLabel/dohCount/getDohUrl` 全部委托它（语义不变）。
  - `OkGoHelper.refreshDnsOverHttps()` 改为**按 url 早退 + 重建**：换掉 `defaultClient`/`noRedirectClient`（统一 `rebuildDohClients()`，与首次构建同一段代码），图片客户端置空下次懒建；旧 client **一律不 shutdown**（在跑的请求仍持有它）。
  - 新增变更广播 `OkGoHelper.addDohChangeListener`（方向仍是业务模块→`:core-network`，基础模块不认识业务模块）：`:download` 静态订阅置空下载客户端；`:spider` 静态订阅重建自己的 client（`initDoh` 把 `OkGoHelper.currentDohUrl()` 映射成 `Doh`，首次触碰本类即自动跟上）；app 侧订阅把 Exo 播放客户端**置脏**、作废 Exo 的 DataSource 工厂并更换 Picasso 的 downloader。
  - app 侧还订阅了 `SystemConfig.subscribe` 复核 DoH：设置页、备份恢复（`importConfig`）等**任何**写入方都自动触达，不依赖调用方记得多调一次。
  - **未单测（需真机确认）**：client 重建与 Exo/Picasso 换 client 属纯 Android，JVM 测不了；设置页文案已改"（即时生效）"。
  - 文件：`core-network/.../util/{DohOptions,OkGoHelper}.java`、`download/.../internal/{DownloadManager,DownloadExecutor,DownloadStore}.java`、`spider/.../catvod/net/OkHttp.java`、`spider/.../catvod/crawler/Spider.java`、`player/.../exo/ExoMediaSourceHelper.java`、`app/.../base/App.java`、`app/.../server/RemoteServer.java`、`app/src/test/.../util/DohOptionsTest.java`。

- **未推送改动全量复查 + 缺陷修复（一轮 review→fix 批次）**：对当时全部未 push 内容（6 个本地 commit + 工作区改动）做了四个域（下载/播放UI/检查更新/网络爬虫）并行审查 + 逐条读码复核，确认并修掉以下问题（均已过 `assembleDebug/assembleRelease/testDebugUnitTest/checkModuleDependencies`，单测 176 例 0 失败）：
  - **默认订阅被清空（高，已被并行会话先修）**：`App.putDefaultApi()` 原逻辑把"上次注入记录"里的订阅无条件删除且拒绝补回 → 第 2 次启动清空内置默认订阅 + 置空 apiUrl。现改为 `injectedTags` 差集（只删"注入过且文件已移除"的项），**需真机回归：装包→启动→杀进程→再启动，订阅仍在**。
  - **发版说明版本号重复（中）**：`ReleaseNotes.aggregate` 单版本分支绕过标题去重 → 弹窗标题"发现新版本 vX"下再来一行 `## vX`；改为单版本同样走 `dropVersionHeader`，引言去重也从"标题命中"子分支里独立出来。
  - **dev 围栏误吞正文（中）**：围栏改为**独占一行**才生效（同一行成对写出仍整段剔除），避免正文里"提到"围栏写法时把它之后的用户可见内容一起删掉；AGENTS 围栏约定同步补充说明，测试补 4 例。
  - **代理地址不再判 HLS（中）**：`ExoMediaSourceHelper.inferContentType` 先按路径末段扩展名判定后，对"路径无明确媒体后缀"的地址回退看查询串（`/proxy?...&url=xxx.m3u8`），恢复旧 `contains(".m3u8")` 兜底，避免退化成 Progressive 首播失败。
  - **失败图记忆永不自愈（中）**：`PicassoLoad`/`FastSearchAdapter` 的失败集合改为"URL→失败时间 + 60s 窗口 + 上限 500"（成功即清），瞬时失败（开局无网/CDN 抖动）不再需要杀进程才恢复。
  - **下载细节（低）**：AES-128 密钥请求登记进 `activeResponses`（暂停/删除可中断在途密钥请求，登记采用顶替-还原避免摘掉分段响应的登记）；"仅Wi-Fi"守卫下沉到 `doStartDownloads()` 单入口（授权成功回调不再绕过）；右侧下载抽屉补注册状态监听、注销与注册成对（`statusListenerRegistered`）。
  - **其它（低）**：`PlayService.sInstance` 加 `volatile` + 只清自己；`CmsApiRules` 协议相对链接 `//host/...` 按当前页协议绝对化、`siteKey` 加主机名短哈希（消除不同站点生成同名 `cms_<key>.json` 互相覆盖）、候选顺序改为站点根默认路径优先（不再被 10 条上限截掉）；`player_vod_control_view.xml` marginStart/marginLeft 统一 dp_10（原 start=30 覆盖 left=10 使改动无效）；`box_vod_control_view.xml` 两处 `textSize` 由 dp 回 sp；`DetailActivity` 无剧集时连 260dp 预览占位区一起收起；订阅地址响应"配置 vs 资源站采集接口"判定收紧（`CmsApiRules.detectKind`，避免只有 flags/ads 的最小配置被误送去嗅探）。
  - 未改（评估后风险更高，留待排期）：`Utils.getVideoList()` 的 MediaStore 主线程查询 + 新增 `File.length()` 兜底（需两页异步化重构）；跨版本续传复用旧密文分片（触发前提苛刻，强制校验有引发补片死循环风险）。

- **广告过滤（净化视频）开关专项复查 + 修复（2026-09-26）**：开关链路＝`SettingActivity`→`PlayConfig.isVideoPurify()`→`PlayFragment.playUrl`（m3u8 少数派分片剔除，净化后走 `RemoteServer` 回环给播放器）。查出并修掉 6 处缺陷（门禁全绿，单测 300 例 0 失败）：
  - **广告名单串源（中）**：`AdBlocker` 只有一份名单且以 `AdBlocker.isEmpty()` 当"只初始化一次"的开关 → 第一个源的 `ads` 永久生效、切源后新源的 `ads` 永远加不进来（`clear()` 全仓无调用点，名单无法刷新）。改为**默认名单（`ensureDefaultHosts`，幂等）+ 当前源名单（`setSourceHosts`，每次解析配置整体替换）**两层，域名统一小写归一（原来 `isAd` 把 URL 转小写却不归一 host，大写域名永不命中），默认名单用写时复制列表保证 WebView 拦截线程读写安全；`ApiConfig` 里 `getAsJsonArray("ads")` 补 null 防御（缺 `ads` 字段时原来直接 NPE 断掉整个 `parseJson`）。
  - **带 BOM 的清单静默放弃过滤（中）**：`content.startsWith("#EXTM3U")` 对 BOM 判否 → 回退直接播原地址，过滤看不见地失效（只能靠第三方 `unBom.php` 代理兜底）。新增 `M3u8Cleaner.stripBom`，在取到清单处与净化入口统一剥 BOM/前导空白。
  - **`#EXT-X-MAP` 未绝对化 → fMP4 放不出来（中）**：净化后的清单由回环提供，清单内相对地址会被播放器按 `127.0.0.1` 解析。原来只补 `#EXT-X-KEY`（此前修过"只补第一条"），`#EXT-X-MAP`（fMP4 init 段）、`#EXT-X-MEDIA`/`#EXT-X-I-FRAME-STREAM-INF` 的 `URI=` 仍漏；现改为**所有 `#EXT-X-*` 标签的 `URI="..."` 统一绝对化**。
  - **净化地址被 Exo 判成 Progressive（中）**：回环路径 `/m3u8` 没有 `.m3u8` 后缀，`ExoMediaSourceHelper.inferContentType` 取的"扩展名"是 `127.0.0.1` 里最后一个点之后的内容（`1:9978/m3u8`）→ 退化成 Progressive，首播必然失败再靠 `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` 重试（用户看到一次黑屏）。新增规范路径 `/purify.m3u8`（`/m3u8` 保留兼容），响应改 `application/vnd.apple.mpegurl` + `Cache-Control: no-store`（名单是全局单槽，禁止播放器缓存到上一条清单）。
  - **同 FQN 双份单测互相遮蔽（低）**：`M3u8CleanerTest` 同时存在 `.java` 与 `.kt`（同包同名），测试运行时只加载一个 → **Java 那份的 6 个用例从未执行**（跑分只有 Kotlin 的 3 例）。两份合并为一个 Kotlin 类（补 `#EXT-X-MAP`/`#EXT-X-MEDIA`/BOM 用例），删除 `.java`；顺带清掉死常量 `HawkConfig.VIDEO_PURIFY`（实际键在 `PlayConfig`）。
  - **已补（下载与播放对齐，2026-09-26）**：③ 下载链路（`M3u8DownloadTask`/`DownloadExecutor`）**已改为用与播放同一份净化清单** —— 净化逻辑从 `app/util/player/M3u8Cleaner.kt` 下沉为 `common/util/M3u8Purifier.java`（模块边界：`:download` 不能依赖 `:app`），`PlayFragment` 与下载侧都调它，少数派（广告/占位）分片在下载时同样剔除。这同时修掉了**"播放不缺、下载缺"**：那些被判为少数派的片在源站往往已被删（404），播放器不看它们所以一路顺，下载器照单全下就会报缺片（实测 940 片里 8 片 404 全是少数派前缀）。净化后清单的分片序号会变，靠既有的 `segments.sig` 指纹保护丢弃旧碎片重下（升级前正在下的任务会重下一次，不会拼错）。
  - **未改（需真机确认后再定）**：① `PlayFragment.startPlayUrl` 内 `autoRetryCount>0` 时把 m3u8 交给第三方 `http://home.jundie.top:666/unBom.php?m3u8=<未编码URL>` 去 BOM（隐私外泄 + URL 带 `&` 时被截断），本地已有剥 BOM 能力，可改本地回环兜底；② 开关**不控制** WebView 嗅探里的 `AdBlocker.isAd` 拦截（嗅探页广告/统计资源仍被拦，属另一语义）。

- **fMP4（`#EXT-X-MAP`）与字节范围分片（`#EXT-X-BYTERANGE`）支持（2026-09-26）**：新增纯逻辑解析器 `common/util/HlsMediaPlaylist`（21 例单测）；`DownloadExecutor` 去掉"遇这两条直接抛"，改为 init 段单独落盘并在**合并时先写 init 再拼分片**、带范围分片用**固定区间 Range**（回 200 即判失败、校验 `Content-Range` 起点终点、拒 `multipart/byteranges`）、重封装失败时 fMP4 **保留 `.mp4`**、指纹带上范围（整表无范围时与旧格式逐字节一致，升级不会误清在下的任务）。**限制**：加密 init 直接失败（不产出"分片都对、整集打不开"的成品）；跨 `#EXT-X-DISCONTINUITY` 仍不做时间轴重排；**fMP4 不参与跨线路补片**（替补片段与本线路 init 段"同源"无法证明，PTS 接缝校验只认 188 包 TS），切片型 TS 线路的补片范围已带上。真机回归见 §3 第 10 条。

- **设置项有效性核查（2026-09-26）**：四条"改了就生效"里 **3 真 1 误报**：IJK 解码 `click` 只改内存字段未写 `PlayConfig` → 已补；安全 DNS 下标越界（老备份 `doh_url` 4~6 vs 列表 4 项）→ 加 `OkGoHelper.dohCount/dohLabel` 夹取；清缓存只删内部 cacheDir、裸线程 + 提前 toast → 改走 `HeavyTaskUtil` + `FileUtils.clearAllCache()` + 删完再 toast；**运行日志开关"要重启"是误报**（`LogConfig.setEnabled` 内部早已调 `LogStore.get().setEnabled`，写日志门控读的就是那个实例字段）。以下三条**口径已定**（刻意选择，不要再当缺陷修）：
  - **局域网服务令牌**：保持现状 —— 令牌仍由本机（含回环）下发、`/token.js` 对局域网可见；开关默认关（显式 opt-in），描述写明"局域网可访问(同网设备可管理)"。要收紧需先定配对/一次性授权方案。
  - **无痕浏览**：只覆盖"历史 + 搜索历史"，**收藏照常记录**（设置页写明"不记历史与搜索(收藏照常)"）。
  - **老剧的播放设置**：保持现状 —— 历史里的 per-vod `playerCfg` 优先于主设置（老剧沿用首次播放时的解码/渲染/缩放），要改就在播放面板改（面板改的也是这份 per-vod 配置）；这是"手动微调优先"的刻意语义。

- **下载模块专项核查（执行器 / 调度与管理器 / 与 UI 交界，2026-09-26）**：逐条核实报告后修复如下（每条都先在代码里验真再改）：
  - **P0 重封装样本缓冲用了堆缓冲 → HLS 成品从未变成 MP4**：`ByteBuffer.allocate(8MB)` 喂给 native 的 `readSampleData`/`writeSampleData`（经 `GetDirectBufferAddress` 取地址），非 direct 抛 `IllegalArgumentException` → 被 `catch (Throwable)` 吞掉 → 回退 `.ts`，于是 3.5.7 起的"标准 MP4 成品"实际从未生效。改起步与扩容都用 `allocateDirect`（8MB→64MB 封顶）+ 每样本 `clear()`；新增源码级绊线单测 `RemuxBufferContractTest`（本机跑不了 MediaExtractor，就守住最容易回退的一行）。真机核验：成品后缀应变 `.mp4`、logcat 不再有"重封装失败"。
  - **P1 限速功能从未生效**：`throttle()` 把窗口起点/窗口字节写成方法内局部变量、每次调用都重置 → `elapsed` 恒为 0 → 每次直接 return、从不 sleep。改为窗口状态挂在任务上（`DownloadTask.throttleWindowStart/throttleWindowBytes`），结算规则抽成纯逻辑 `util/ThrottlePolicy`（7 例单测）。**并补上缺失的 UI 入口**：`util/ThrottlePolicy.PRESET_BYTES_PER_SEC`（不限速/512KB/s/1MB/s/2MB/s/5MB/s）+ `label`/`presetIndex` 一份事实源；设置项持久化在 `PrefsDataStore`（`download_speed_limit_kbps`，KB/s 存 Int），入口两处（下载页标题栏齿轮弹窗、设置页下载分组），**改完对运行中的任务立即生效**（`DownloadPolicy.setSpeedLimitBytesPerSec` 直接刷所有任务的 `speedLimit`），任务启动时统一套用（`DownloadScheduler` 起任务处）。
  - **P1 读流终止条件写成 `> 0`**：五处（直链/分片/重封装转 188/`FileCleaner` 两处/`DownloadStore`）改为 `!= -1`——0 不是 EOF，按 `>0` 退出会把"没读完"当"读完"，随后 `.part` 照样 rename 成 `%05d.ts` 当成功。并给**分片**补上缺失的完整性校验：服务器声明长度且本次是"整段原样落盘"（未续传/未剥壳/未解密）时，实收字节必须等于声明值，否则删残片并抛错（原来只判 `exists() && length()>0`）。更正报告一处：直链路径本来就有 Content-Length 比对，缺的是分片路径。
  - **P1 中断时把未完成分片记成已完成**：`downloadSegment` 遇中断是"正常 return"（不抛异常），调用方紧接着 `doneSegments = i + 1` → 进度与磁盘不一致；调用方补中断自检后收尾返回。
  - **P2 磁盘峰值只按 1× 预检**：核实为真（分片 + `merged.tmp` + `remux_*.mp4` 会同时存在，峰值≈3×），但**按用户判断保留 1×** —— m3u8 的大小本身是估算（不准），乘倍数会误拒本来够用的机器；空间真不够时是优雅退化（合并失败保留碎片可重试、重封装失败只回退 `.ts`，成品仍可播）。想更保守只需改 `DownloadPolicy.SPACE_PEAK_FACTOR`。
  - **仍待处理（报告已列，本轮未动）**：退避期间独占并发额度（`Thread.sleep` 时状态仍是 DOWNLOADING）；结构事件去抖无最大等待上限；`DownloadFragment.refresh()` 主线程全量 stat + `purgeOrphans` 顺带写盘；"已播放"标记被 `catch (Throwable ignored)` 吞掉；P3 若干（`DownloadStore` 锁顺序、`getPosterDir` 空 context、海报直写非原子、`break` 跳过的尾部未记死片）；以及优化项（`:download` 无自身单测目录、分片无断点续传、磁盘被扫 3 遍、`gapSegments` 线性查、跨 DISCONTINUITY 的 PTS 钳制、前台服务异常被吞）。
  - **已支持（本次补齐）：fMP4（`#EXT-X-MAP` init 段）与字节范围分片（`#EXT-X-BYTERANGE`）不再"直接抛"** ——
    这两类正是"整集一个大文件 + 固定区间取片"与 CMAF 源的常见形态，此前清单里出现就直接失败、整集下不了。
    解析下沉到 `:common` 的纯逻辑类 `util/HlsMediaPlaylist`（**21 例 JVM 单测**：显式 offset、隐式 offset 接上一条
    同资源分片末尾、按资源分别记 offset、MAP 带/不带 BYTERANGE、无 MAP 的纯 BYTERANGE 单文件 TS 切片、CRLF/BOM/
    空行/注释/HTML 包裹行、加密属性透传、异常输入给明确原因；`SegmentListSignature` 另补 2 例"范围入指纹但
    整表无范围时指纹与旧格式一致"），下载侧只按解析结果取片：
    init 段落 `init.mp4`（**不占 `%05d.ts` 序号**）、**合并时先写 init 段再按序号拼分片**（产物即完整 fMP4），
    下载后自检含 `moov`；范围分片用**固定区间** `Range: bytes=<off>-<off+len-1>`，**服务器忽略 Range 回 200（整文件）
    判失败**（否则整个大文件会被当成"这一片"存下来），并与 `segDone` 的**续传 open-ended Range 严格区分**
    （带区间一律整段重下，偏移坐标系不同）；**fMP4 重封装失败保留 `.mp4`**（不改名 `.ts` —— 改名是 TS 字节流才需要的
    伪装）；跨线路补片把范围一并传下去。
    **限制**：加密的 fMP4（SAMPLE-AES / init 段被加密）不支持（init 段自检即判失败）、`#EXT-X-MAP` 中途更换判失败、
    fMP4 不参与跨线路补片（缺口走缺片完成）、跨 DISCONTINUITY 仍不做时间轴重排。
    **需真机确认**：这类源下载出的成片能否被 ExoPlayer/MediaExtractor 正常识别（见 §3 第 10 条）。

- **hawk 全量退役完成**：`KeyValueStore` 类及全部 legacy 迁移分支已删除，运行权威统一 `PrefsDataStore`/文件；全仓零 `com.orhanobut.hawk` 依赖（mbox 包名隔离，无 Hawk 存量升级场景）。
- **订阅本地导入改系统 SAF**：`SubscriptionActivity` 用 `ActivityResultContracts.OpenDocument` 替代 hedzr 反射，支持 `content://` 流、`primary:`/`home:` 文档卷，复制到应用专属目录 + canonical 防穿越，按 URL 去重；移除 `MANAGE_EXTERNAL_STORAGE` 前置检查。
- **下载存储权限引导**：`DownloadDialogCoordinator` 无存储权限时弹 `ConfirmDialog` + `XXPermissions` 拉起系统授权（与「我的-本地视频」入口一致），不再仅 toast 提示。
- **播放器收口 P1 真机通过**（MEIZU 21/Android 16）：IJK/Exo 双内核起播、后台播放系统 MediaSession 媒体卡、会话 bind/release 无泄漏。

## 1. 已落地改动总览

### 1.1 局域网 HTTP 服务（RemoteServer / ControlManager）
- 默认仅绑定 `127.0.0.1`：订阅/本地播放/代理等回环功能不受影响；局域网可达需
  `HawkConfig.LAN_SERVER_ENABLE = true`（设置页新增“局域网服务”开关，重启应用生效）。
- 管理令牌：每次进程启动随机生成（`accessToken`），web 控制台经 `/token.js` 注入
  `window.TVBOX_TOKEN`，所有 AJAX 自动携带 `X-TVBox-Token`；`/token.js` 禁止缓存。
- 鉴权范围：`/upload`、`/newFolder`、`/delFolder`、`/delFile`、`/action`、目录列表须令牌（回环放行）；
  `/proxy`、`/purify.m3u8`（旧路径 `/m3u8` 保留兼容）、`/dns-query` 仅本机回环。
- 路径安全：`resolveUnderRoot()` 拒绝 `..`/绝对路径/NUL/反斜杠分隔并做 canonical 根目录包含性校验；
  拒绝删除外部存储根；ZIP 解压逐条目 canonical 包含性校验（Zip Slip），`ZipFile`/流全部 try-with-resources。
- 越界修复：`/proxy` 返回数组按 `length>=3` 且 `rs[2] instanceof InputStream` 校验后再读。
- 文件：`app/.../server/RemoteServer.java`、`ControlManager.java`、`InputRequestProcess.java`、
  `res/raw/{index.html,script.js}`。

### 1.2 TLS 与证书策略
- 移除所有“恒真 HostnameVerifier”（OkGoHelper / App / spider OkHttp / SSLCompat.VERIFIER）。
- WebView `onReceivedSslError` 默认 `cancel()`，仅当 `HawkConfig.IGNORE_SSL_ERROR=true`（默认 false）放行。
  覆盖点：PlayFragment、PlayParseHelper、WebSniffResolver。
- 设置页新增“忽略证书错误”开关（默认 OFF；WebView 即时生效，OkHttp 网络请求在下次换 DoH/重启时随客户端重建生效）。
- 文件：`common/.../util/OkGoHelper.java`、`common/.../net/SSLCompat.java`、
  `spider/.../net/OkHttp.java`、`app/.../base/App.java`、`util/WebSniffResolver.java`、
  `util/player/PlayParseHelper.java`、`ui/fragment/PlayFragment.java`。

### 1.3 明确崩溃点修复（判空/越界）
- ApiConfig `getIJKCodec`：离线/空列表不再 `ijkCodes.get(0)` NPE（改从非空列表取，空列表返回 null 由调用方防御）。
- IjkMediaPlayer.setOptions：codec 判空后再取 option。
- InputRequestProcess：word/url 参数缺失判空。
- CustomWebReceiver：intent/action/extras 判空。
- PlayService：`videoInfo.split("&&")` 越界回退、静态 videoView 判空。
- PlayFragment/PlayParseHelper/DetailActivity：sourceBean/集合/playIndex/seriesMap 判空与越界回退。

### 1.4 Room 与数据层
- `AppDataManager`：移除 `allowMainThreadQueries()`；所有 DAO 访问经 `runOnDb`（单线程专用执行器，串行化），
  主线程不再执行 SQLite 查询。
- Room schema：`AppDataBase version=2`，新增 `MIGRATION_1_2` 为 vodRecord/vodCollect 建
  `(sourceKey,vodId)`、`updateTime` 索引；log 模块 `LogDatabase version=2` + `MIGRATION_1_2` 为
  `log_entry` 建 `(taskKey,timestamp)`、`(category,timestamp)` 索引。schema 导出：1.json（基线）+2.json。
- `RoomDataManger`：Gson/TypeToken 静态单例复用；删除残留的陈旧 3.json。
- `CacheManager`/`RoomDataManger` 全部调用经 `AppDataManager.runOnDb`。

### 1.5 依赖 / 签名 / 构建
- 升级：Room 2.3.0→2.5.2、Gson 2.8.7→2.10.1、XStream 1.4.15→1.4.20（各模块 build.gradle 同步）。
- XStream 白名单：SourceViewModel 两个 fromXML 前 `NoTypePermission.NONE` + 业务 bean/JDK 包放行。
- 移除零引用/重复依赖：ZXing、Conscrypt、lifecycle-extensions（改为显式 viewmodel/livedata/runtime-ktx 2.6.2）、
  app 层重复 retrofuture（spider 层保留）、Glide（调用点统一到 Picasso 后移除，含 4 个文件残留 import 清理）。
- 删除未注册 ExoPlayer/FFmpeg 扩展源码（`player/src/main/java/com/google/android/exoplayer2/ext/ffmpeg`、
  `tv/danmaku/ijk/media/player/ffmpeg`）。
- Manifest：两个广播 Receiver `exported=false`；`allowBackup=false`(+`tools:replace`)；
  清理 READ_PHONE_STATE/GET_TASKS/ACCESS_FINE_LOCATION/REQUEST_INSTALL_PACKAGES 等无用权限；
  READ/WRITE_EXTERNAL_STORAGE 限 maxSdk 32/29。
- 签名：`TVBoxOSC.jks` 移出版本控制（`git rm --cached`），`.gitignore` 收编 `*.jks/keystore.properties`；
  `app/build.gradle` 从环境变量(`KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`)或
  `app/keystore.properties` 注入，缺失自动回退 debug 签名。
  ⚠️ 若仓库公开：旧 keystore+口令曾入库，需更换密钥并清理 git 历史；CI 请改用 secrets。
- 换签+换包名（2025 落地，解决与开源 TVBox 应用安装冲突）：正式签名改用全新专属库 `mbox-release.jks`
  （仓库根目录，不入库；本地 `app/keystore.properties` 读取，CI 经 GitHub Secrets `KEYSTORE_BASE64` 注入）；
  `applicationId` 由 `com.github.tvbox.osc` 改为专属 `com.github.tvbox.osc.mbox`（namespace 不变，代码/资源引用不受影响）。
  效果：与设备上其他同包名族系的开源 TVBox 应用互不冲突、可共存安装；旧 `TVBoxOSC.jks` 已成历史遗留（待删除）。
  注意：换包名后新包不覆盖旧包名版本，属全新应用（旧应用数据不随迁移），发布前务必备份 `mbox-release.jks`+口令。
- gradle.properties：`org.gradle.parallel=true`、`org.gradle.caching=true`；app 默认 `resConfigs 'zh-rCN','zh'`。

### 1.6 启动与运行性能
- 播放器缓存清理：`App.onCreate` 移除主线程递归删除 → `schedulePlayerCacheCleanup()`（延迟 5s、后台低优先级线程、
  `FileUtils.cleanPlayerCacheIfOverflow(100MB)` 阈值）。
- EPG JSON：启动不再解析，`EpgUtil.getEpgInfo` 首次使用懒加载（`init()` synchronized）。
- OkHttp 根统一：`OkGoHelper.newBaseBuilder()` 作为默认/免重定向/Exo 客户端公共根。
- UA.java：约 5400 条 → 47 条去重真实 UA（文件 ~733KB→8KB）。
- 下载进度：`DownloadManager.flushProgress()` 600ms 窗口合并落盘+广播（直链/HLS 分片/合并进度），终态强制落盘。
- 下载页刷新：新增 `DownloadProgressEvent(taskId)` 高频增量；`DownloadEvent` 仅结构性全量。
- 网络事件统一：删除 DownloadScheduler 自注册 ConnectivityManager 回调，改订阅
  SystemStateMonitor TYPE_NETWORK（WIFI→续传；CELLULAR 且非仅WiFi→续传；NONE→不处理）。
- LocalVideoAdapter：convert 内 O(n²) 全量统计移除 → 增量计数（syncSelection/setItemChecked/selectAll/
  cancelAllSelection；BRVAH notifyDataSetChanged 为 final，调用点已接线）。
- GridFragment：单套 RecyclerView+Adapter + 轻量快照栈（数据引用/page/滚动/loadMoreEnd），逐层新建视图移除。

### 1.7 改进.txt（模块边界）第一阶段
- DownloadFacade 补全（enqueue/pause/resume/remove/removeArchiveByPath/removeTasksByPath/getPosterFile/
  ensurePosterAsync/pauseAll/startAll/getTasks…）。
- UI 只走门面：DownloadFragment、DetailActivity、VideoListActivity.kt 的 `DownloadManager.get()` 清零
  （DownloadFragment 保留 MSG_* 常量引用）。

### 1.8 超大类物理拆分（已做部分）
- 新增 `app/.../util/EpisodeDownloadBatch.java`：DetailActivity “整剧批量下载”逻辑整体搬出
  （解析/拼名/统一剧集标识/门面入队/计数），并修复“均已下载/已在任务中”提示分支不可达问题。
- 新增 `app/.../util/DetailQuickSearchHelper.java` 并已接线：DetailActivity 快速搜索编排整体迁移
  （共享线程池 HeavyTaskUtil + epoch 去重/切换词取消、弹窗打开续跑/关闭暂停语义），
  Activity 内不再持有 searchExecutorService/pauseRunnable/quickSearchData 等搜索状态。
- 详情/播放页另有先期拆分的 `util/player/PlayParseHelper.java`（暂未被引用，见“待后续”）。

### 1.9 模块化(改进.txt 第二/三阶段已落地部分；当时的模块划分，现状见顶部「模块现状」)
- 新增 `:core-model`（纯 Java，23 个共享 DTO：Movie/MovieSort/AbsXml/AbsSortXml/AbsJson/AbsSortJson/
  SourceBean/Subscription/VodInfo(+嵌套)/DownloadTask/IJKCode/Live*/Subtitle*/TmdbVodInfo/Source 等）。
  现状：该模块已连同 `:core-utils`/`:state` 并入 `:common`（包名不变）。
- 新增 `:core-storage`（android-library）：迁入 data/cache 包(Entity/DAO/AppDataManager/RoomDataManger/CacheManager)，
  已去除对 App 单例、spider ApiConfig、HistoryHelper/SystemConfig/Hawk 的依赖；Room schema 统一导出。
- 原 `:common` 已更名挂接为 `:core-network`（projectDir=common，FQN 不变）；裁剪计划见
  `doc/phase2-core-modules-plan.md`。
  现状：`:common` 与 `:core-network` 为两个并存模块——`:common` 承接纯模型/算法工具(AES/MD5/AdBlocker)/
  系统状态(`.state.*`)，`:core-network` 只留网络职责；上述“更名挂接”是当时的一次性历史动作。

### 1.10 强类型蜘蛛契约(type3 试点,改进.txt §1/§4.3)
- `:spider`（原 `:spider-api`，现契约与实现同在 `:spider`）提供领域契约:`SpiderDetailApi`/`SpiderSearchApi`/`SpiderHomeApi`/`SpiderManualCheckApi`/
  `PlayUrlResolverApi`(含 ResolveResult)/`MediaUrlUtil`/`SortParser`(首页/分类 JSON+XML 解析,XStream 白名单,纯静态)。
- `:spider` 侧实现 `SpiderDetailImpl`/`SpiderSearchImpl`/`SpiderHomeImpl`/`SpiderManualCheckImpl`/`SpiderUrlResolverImpl`,
  解析下沉(spider 内拉串→Gson→Abs*/SortParser→返回类型化对象),链路日志 tag=`SpiderBridge`。
- `SourceViewModel` 全部 `ApiConfig.getCSP` 直调已替换为契约 Providers;detail/search(quick/聚合)/category/
  homeContent/homeVideoContent(type3)均为 **typed 优先 + 失败回退字符串通道**,15s 超时保护与旧链路一致。
- App 组合根 `AppCompositionRoot.init()` 注入全部服务;`SortParser` 由 app 迁入 :spider-api 后单测随迁
  (`SortParserTest`),排序/筛选解析可 JVM 验证。

### 1.11 playback 会话层原型(roadmap 2.1 第一部分)
- `:player`（原 `:player-api`，现契约与实现同在 `:player`）新增 `PlaybackSessions`:会话键注册表(bind/unbind/observe/release + 内建 PLAYER 日志)。
- app 新增 `VideoViewPlayerApi`(⑥ 适配层):包 doikki VideoView,以**轮询 getCurrentPlayState 差分**映射
  引擎无关 `PlayState`,不向共享视图挂额外 OnStateChangeListener(避免与既有 Controller 监听冲突)。
- `AppCompositionRoot` 注册 PlayerFactory type=1(IJK)/type=2(Exo) adapter(工厂语义与 PlayerHelper.updateCfg 对齐)。
- `PlayFragment` 播放入口 bind 会话(仅日志观察:state/buffering/error/completion)、切集与销毁时释放,
  不改变现有 mVideoView/Controller 控制流;真机回归后再收敛为 session.play/pause/observe 全驱动。

## 2. 待后续（需真机回归或架构决策）

| 项 | 说明 |
|---|---|
| PlayFragment(~1.8k) 进一步拆分 | 字幕/播放器控制器与宿主深度耦合，无回归环境不强行搬移 |
| `util/player/PlayParseHelper.java`（未引用） | 疑似拆分遗留件，未接入任何调用方；可选择接线或删除 |
| playback 会话全驱动 | 原型(观察/日志)已接;PlayFragment 收敛到 session.play/pause/observe 需真机回归 |
| 强类型收尾 | 字符串通道(SpiderContentApi)仍为过渡兼容层,待 FakeSpiderService 单测覆盖后可删 |
| 局域网服务热切换 | 有意不做：重启应用生效即可（热重启会打断回环播放代理流） |
| web 控制台静态资源(~260KB)精简 | 视觉设计类工作，另行处理 |

## 3. 真机回归矩阵（建议）

1. 详情页→下载整剧（含“全部已下载/已在任务中/解析失败”提示、多选、失败重下、暂停/恢复/删除）。
2. 下载页：进度平滑度（多任务+多分片）、单行刷新不打断长按多选、底部内存栏、完成列表计数。
3. 本地视频列表与下载完成文件列表：进多选/长按/全选/取消全选/删除后计数与删除按钮态。
4. 分类/文件夹 3+ 级下钻返回：滚动位置、加载更多续页、下拉刷新、旋转后列数。
5. 断网→恢复/飞行模式/仅WiFi 开关：下载暂停与自动续传行为。
6. 局域网：默认仅本机；设置开启后重启 → 局域网浏览器可开控制台、目录/上传/删除需令牌；
   未开启时局域网不可达 9978。
7. 自签名/证书错误站点：默认无法加载/播放，开启“忽略证书错误”后可访问。
8. 后台播放 + 通知栏控制；历史/收藏列表新增与删除后的刷新。
9. 冷启动速度（缓存清理不再阻塞主线程）与升级后历史/收藏数据保留（Room 迁移）。
10. **fMP4 / 字节范围源下载**（本次新增能力，必须真机确认兼容性）：找一条 `#EXT-X-MAP`（fMP4/CMAF）源与一条
    `#EXT-X-BYTERANGE`（整集一个大文件切片）源各下一集 —— 看①任务不因清单类型失败；②下载页进度/缺片提示正常；
    ③成品能播且**时长/拖动正确**（fMP4 拼接产物若 init 段缺失或错位，表现为"文件在、打不开"或只有开头几秒）；
    ④日志里 `fMP4, init=…` / `字节范围分片` / `init 段下载完成` 与重封装结果（成功，或失败且**保留 `.mp4`**）；
    ⑤暂停/继续/杀进程重启后续传不重下 init 段、不产出坏文件。
