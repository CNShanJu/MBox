# 视频下载重构与项目六模块设计方案

> **实施进度（2026-09-25）**：下载模块重构已落地——DownloadFacade 门面 + internal 包（Manager/Scheduler/Executor/Store/Policy/Archive/Config/Core）+ 任务对象化（BaseDownloadTask/NormalFile/M3u8）+ 前台服务 + 通知。
> 本文件保留作为原始设计参考（当时设计的"六模块"划分），实际实现以代码为准。六模块中的 spider/log/state/ui-common/player 已按 改进.txt 分层落地，下载模块见 `doc/项目目录结构.md` 第九节。
>
> **模块现状（2026-09）**：全仓 9 个模块 `:app`/`:common`/`:core-storage`/`:player`/`:thirdparty`/`:log`/`:core-network`/`:spider`/`:download`。本文件中提到的 `:core-model`/`:core-utils`/`:state` 已合并进 `:common`，`:spider-api`→`:spider`，`:player-api`→`:player`，`:crash`/`:TabLayout`/`:ViewPager1Delegate`/`:quickjs`→`:thirdparty`，`:ui-common`→`:app`（主题 JSON 在 `app/src/main/assets/theme/`）。下文历史记录保留当年模块名。

> **目标**：把 `DownloadManager.java`（1778 行单体）重构为**六个项目级模块 + 纵向分层架构**，
> 同时修复已确认的 5 个线上 bug，建立"可排查"的日志体系，并为播放内核升级（Media3）铺路。
>
> **六模块**：① 下载（本方案主体：门面 + 6 组件）② SystemStateMonitor（全局状态广播）
> ③ LogStore（日志）④ SpiderModule（爬虫全能力门面）⑤ ui-common（UI 资源）
> ⑥ PlayerModule（播放，PlayerApi 引擎无关）。
>
> **已确认决策**：
> A. 已下载档案长期保留，删文件联动删档案；
> B. 剧集级 + 视频级聚合查询；
> C. 事件订阅回调（去抖 500ms）；
> D. **7 天清理的是"任务日志"，不是任务元数据/档案**（排查问题需要详细日志）。

---

## 一、现状与问题

### 1.1 现状盘点

| 领域 | 现状 | 问题 |
|---|---|---|
| 下载 | `DownloadManager.java` 1778 行单体：入队/调度/直链/HLS/校验/合并/网络监听/持久化/查询全在一处 | 无法扩展、无法排查 |
| 播放 | 第三方内核 `:player`（vendored）已独立；**项目自有播放层 18 个类 + PlayFragment（1600+ 行）散在 app** | 无门面、播放页过重 |
| 爬虫 | `com.github.catvod.crawler` 独立包但无门面；下载用 `SourceViewModel.spThreadPool`（UI 层线程池）执行解析 | 架构倒挂、入口不收敛 |
| 设置 | `SettingActivity` 99 处直连 `Hawk.get/put`，仅下载配置走了门面 | 无归属、无变更订阅 |
| 资源 | layout 100 个 / drawable 103 个平铺（前缀有雏形）；values 已拆分 | 无强制规范、物理无法分类 |
| 日志 | `AppLog` 按天文件 + `logcat` 全量抓取（无 uid 过滤），日志页仅日期选择 | 无法模块筛选、非 package:mine |

### 1.2 五个 Bug 根因与修复

#### Bug 1：仅 WiFi 下载不生效

**根因**：`registerNetworkCallback` 只有 `onAvailable`（网络恢复→续传），**没有"WiFi 断开/切蜂窝→暂停"**；仅 WiFi 只在详情页入队前弹确认框（`DetailActivity.java:1197`）；且 `onAvailable` 在蜂窝可用时也会自动续传——方向反了。

**修复（Policy-Decider 订阅 ② 网络事件）**：
1. 新增内部状态 `NETWORK_PAUSED`（网络原因暂停，区别于用户手动 PAUSED）；
2. 仅 WiFi 开启时，收到"蜂窝激活 / WiFi 断开"事件 → 下发"全部 NETWORK_PAUSED"；
3. WiFi 恢复 → 自动恢复 NETWORK_PAUSED 任务（用户 PAUSED 不自动恢复）；
4. 网络恢复续传前检查：仅 WiFi 开启且当前是蜂窝 → 不续传。

#### Bug 2：m3u8 合并流程 + 删记录后重下白下

**根因**：
- **A（合并失败）**：merge 循环（`DownloadManager.java:1296-1315`）开始后用户删碎片 → `copyFile` 抛异常 → 外层最多重试 2 次 → FAILED，现场保留；
- **B（删记录后任务还在后台下）**：`remove()`（`548-570`）只关 HTTP 连接，**不取消下载线程**（孤儿任务继续写文件）；
- **C（重下白下 + 碎片泄漏）**：`remove(deleteFiles=true)` 删光碎片；重入队是新 taskId → 新 tmpDir → 全部重下；孤儿 tmpDir 永不清理（叠加 Bug5）；
- **D（残缺分片被信任）**：分片直接写 `.ts` 无中间文件，进程被杀留下"非零长度但残缺"的片，续传时被当成完整片。

**修复（Scheduler / 下载实例 / Cleaner）**：
1. 新增 `CANCELLED` 态；`remove()` 先置 CANCELLED 再关连接，循环每步检查，立即中止且不写 COMPLETED；
2. `remove()` 默认**只删记录保留碎片**；tmpDir 由 **episodeId 派生**，重入队复用续传；明确"删除文件"才删碎片；
3. 分片**先写 .part 再 rename** 原子写，进程被杀不产生残缺片；
4. 合并前做**唯一一次全盘比对生成缺失清单（4.7③）**，缺失片进 repair 循环，不抛给外层重试；
5. `segments.txt` 降级为调试日志，正确性只信磁盘 + 缺失清单。

#### Bug 3：合并后 mp4 相册显示时长只有 1 秒

**根因**：合并是 **TS 字节级拼接**后 rename 成 `.mp4`——产物仍是 TS 容器，各片时间戳不连续，系统 `MediaMetadataRetriever` 解析不出时长（播放器容忍所以正常）。

**修复（M3u8DownloadTask）**：
1. **重封装**：`MediaExtractor`（可 demux MPEG-TS）+ `MediaMuxer` 重封成标准 MP4，时长/缩略图正确，无需 ffmpeg；
2. 短期未实现前，产物后缀保留 `.ts`（不伪装 mp4）；直链保持原格式。

#### Bug 4：无存储权限时不知道下载到哪去了

**根因**：`getSaveDir()`（`1663-1673`）无权限时静默落到应用私有目录；`MANAGE_EXTERNAL_STORAGE` 只在"我的-本地"和"设置-备份"申请，下载入口从不申请、无路径提示。

**修复（Facade / Policy-Decider / 设置页）**：
1. **权限是硬门槛（不做私有目录兜底）**：无权限 → 弹申请框；**拒绝 → 不创建任务、不触发调度**；
2. Policy-Decider `canStart` 拒绝（无权限不允许启动）；**运行中权限撤销 → 下发暂停指令**，避免半截文件；
3. 下载页/设置页常驻显示当前保存路径 + "打开目录"入口；授权状态变化提示并刷新。

#### Bug 5：TS 碎片在相册可见

**根因**：a. tmpDir **没有 `.nomedia`**；b. 孤儿 tmpDir（删任务没删净/进程被杀/失败现场）**从不清理**。

**修复（下载实例 + File-Cleaner）**：
1. 创建 tmpDir **立即写入 `.nomedia`**（任何碎片下载之前），`.part` 父目录同样处理；
2. File-Cleaner **孤儿目录回收**：App 启动/下载页打开/定时扫描 `Download/TVBox/**/tmp`，无对应存活任务即递归删除；
3. 合并成功/失败收尾统一走 Cleaner，不残留。

---

## 二、目标架构（六模块 + 纵向分层）

### 2.1 项目整体架构图（Master View）

```
┌────────────────── MBox 项目整体架构 ──────────────────┐
│                   纵向分层 · 依赖自上而下单向                  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────── L1 · UI 层（app 模块，最前/最上层）──────────┐ │
│  │  详情页 │ 播放页 │ 下载页 │ 设置页 │ 首页 │ 搜索 │ 直播   │ │
│  │  │ 订阅 │ 我的 │ 日志页                                 │ │
│  │  纯展示 + 交互：只展示门面 DTO + 发指令，不持有业务数据   │ │
│  └──────────────────────────▲─────────────────────────────┘ │
│                             │ 查询 / 订阅 / 指令             │
│  ┌──────────────────────────▼─────────────────────────────┐ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐              │ │
│  │  │① 下载模块 │  │④ 爬虫模块 │  │⑥ 播放模块 │ L2 · 业务模块层│ │
│  │  │Download- │  │ Spider-  │  │ Player-  │（业务能力/数据）│ │
│  │  │ Facade+6 │  │ Api      │  │ Api      │              │ │
│  │  │ 组件     │  │ 全能力门面│  │ 适配器层  │              │ │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘              │ │
│  └───────┼─────────────┼─────────────┼─────────────────────┘ │
│          │ 日志/状态    │             │                       │
│  ┌───────▼─────────────▼─────────────▼─────────────────────┐ │
│  │  ┌──────────────────┐  ┌──────────────────┐             │ │
│  │  │② SystemState-    │  │③ LogStore 日志模块│ L3 · 基座层  │ │
│  │  │ Monitor 状态广播  │  │                  │（app 基座：  │ │
│  │  │ 网络/横竖屏/前后台│  │ 统一日志采集/存储/ │ 跨业务基础设施│ │
│  │  │ /电量/磁盘→全项目 │  │ 筛选/导出/崩溃捕获│             │ │
│  │  └──────────────────┘  └──────────────────┘             │ │
│  └───────────────────────────────┬─────────────────────────┘ │
│                                  │ 资源/内核                  │
│  ┌───────────────────────────────▼─────────────────────────┐ │
│  │⑤ ui-common（UI 资源底座）│ :player 内核(vendored)│:quickjs│ │
│  │ :TabLayout │ :ViewPager1Delegate │ :crash   L4 · 资源+基础库│ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                              │
│  横切（贯穿 L1~L3）：配置门面模式——数据在模块内，设置页查询+发通知+订阅 │
│  存储（贯穿）：Hawk（配置/任务）│ Room（日志/档案）│ 文件系统（产物/碎片）│
│                                                              │
│  依赖方向（自上而下单向）: L1→L2→L3→L4；L2 业务模块间可互调（①↔⑥经④解析）│
│  落地顺序: ③→②（L3 基座先立）→ ④→⑥→①（L2 业务）→ UI 薄层化；P1~P3 并行 │
└──────────────────────────────────────────────────────────────┘
```

> **现状注（2026-09）**：图中 L4 的资源与基础库已合并——`:ui-common` 已并入 `:app`（主题 JSON 在
> `app/src/main/assets/theme/`，公共资源在 `app/src/main/res/`，生成任务 `generateThemeColors` 在
> `app/build.gradle`）；`:quickjs`/`:TabLayout`/`:ViewPager1Delegate`/`:crash` 已合并为 `:thirdparty`。
> 同时 `:player` 现同时承载播放契约（原 `:player-api`）与具体内核实现，`:spider` 现同时承载爬虫契约
> （原 `:spider-api`）与实现（契约与实现同模块，边界改由源码层门禁守）。下文出现的同名模块名均按此理解。

### 2.2 六模块总览

| 编号 | 模块 | 层 | 定位 | 对外门面 |
|---|---|---|---|---|
| ① | 下载模块 | L2 | 本方案主体：任务全生命周期 | `DownloadFacade` |
| ② | SystemStateMonitor | L3 | 全局状态广播（网络/横竖屏/前后台/电量/磁盘） | `register/unregister/getCurrentState` |
| ③ | LogStore | L3 | 统一日志采集/存储/筛选（零业务依赖，最底层） | `register(category,枚举)→CategoryLogger` |
| ④ | SpiderModule | L2 | 爬虫全能力统一门面（内容爬取/播放解析/源代理） | `SpiderApi` |
| ⑤ | ui-common（现并入 `:app`） | L4 | 公共 UI 资源 + 命名规范（零业务依赖） | 资源模块（现为 app 内资源，主题 JSON 在 `app/src/main/assets/theme/`） |
| ⑥ | PlayerModule | L2 | 项目自有播放层（引擎无关，Media3 升级铺路） | `PlayerApi` |

### 2.3 分层原则

- **L1 · UI 层 = 纯展示 + 交互**（详见 3.8）：只展示门面返回的 DTO、发交互指令；不持有业务数据、不直连存储/内核/模块内部；
- **L2 · 业务模块层**：业务能力与数据所在，各自对外门面；业务模块间可互调（播放/下载共用 ④ 解析）；
- **L3 · 基座层（app 基座）**：状态广播 + 日志——跨业务基础设施，所有业务模块与 UI 依赖，**先于业务模块落地**；
- **L4 · 资源 + 基础库层**：ui-common（现并入 `:app`）+ `:player` 内核（vendored）+ 既有工具模块（`:quickjs`/`:TabLayout`/`:ViewPager1Delegate`/`:crash` 现并入 `:thirdparty`）；
- **横切**：配置门面模式贯穿 L1~L3（数据在模块内，设置页查询+发通知+订阅）。

### 2.4 数据生命周期

| 数据 | 生命周期 | 说明 |
|---|---|---|
| 已下载档案 | **长期** | 详情页"已下载"判断依据；删文件联动删档案 |
| 运行期任务记录 | 保留至闭环 + 合理窗口 | 状态查询 |
| 任务审计日志 / 全局日志 | **7 天**自动清理 | 排查问题（LogStore 统一管理） |

---

## 三、模块详细设计

### 3.1 ③ 基座层 · 日志模块 LogStore（独立模块，零业务依赖）

**定位**：独立包，自包含、零业务依赖；所有模块（下载/播放/订阅/爬虫/存储/UI）的日志输出
统一调用它对外提供的方法——业务代码不允许直连 Room、不允许写文件、不允许自己起 logcat。

**模块边界**：

```
┌─────────────────────────────────────────────┐
│ log 模块（独立包，自包含，零业务依赖）         │
│  LogStore（门面，唯一对外入口）               │
│   ├─ 采集队列（异步单线程批量，不卡 UI）       │
│   ├─ Room 存储（LogEntry 表，按天清理 7 天）  │
│   ├─ logcat 捕获（package:mine，--uid 过滤） │
│   └─ 清理 / 导出 / 崩溃捕获                  │
└─────────────────────────────────────────────┘
        ▲ 业务模块只 import LogStore（依赖方向单向）
   ┌────┴────┬────────┬─────────┐
 下载      播放     订阅      其他...
```

**对外公共 API**：

```java
// ── 注册（模块初始化时调用一次, 返回绑定大类型的限定对象, 模块持有）──
CategoryLogger<DownloadSubType> LOG = LogStore.register(Category.DOWNLOAD, DownloadSubType.class);
LOG.success(DownloadSubType.MERGE, "合并完成, size=6.4MB", extras);   // 成功
LOG.fail(DownloadSubType.MERGE, "分片缺失: 第3片(HTTP 404)", extras); // 失败+原因(必填)
LOG.fail(DownloadSubType.MERGE, "合并失败", throwable, extras);       // 异常重载(自动打印堆栈)
LOG.info(DownloadSubType.ENQUEUE, "加入任务 " + id, extras);          // 中性
LOG.warn(DownloadSubType.SEGMENT, "分片下载慢, 已重试");               // 告警
// 底层通用: LogStore.log(category, level, subTypeCode, detail, result, reason, extras)

// ── 每个模块自持的小类型枚举（归属业务模块, 不放在 log 模块）──
public enum DownloadSubType {
    ENQUEUE("enqueue","入队"), RESOLVE("resolve","解析"), PLAYLIST("playlist","拉列表"),
    SEGMENT("segment","分片"), VERIFY("verify","校验"), REPAIR("repair","补片"),
    MERGE("merge","合并"), REMUX("remux","重封装"), SAVE("save","落盘"),
    ARCHIVE("archive","档案"), CLEANUP("cleanup","清理"),
    CANCEL("cancel","取消"), DELETE("delete","删除");
    final String code;   // 落库/筛选稳定值(永久不变, 历史日志兼容)
    final String label;  // 日志页展示(中文)
}

// ── 限定对象接口（泛型化: 小类型枚举由各模块自持, 编译期类型安全）──
public interface CategoryLogger<T extends Enum<T>> {
    void success(T subType, String detail, JSONObject extras);
    void fail(T subType, String detail, JSONObject extras);
    void fail(T subType, String detail, Throwable t, JSONObject extras); // 异常必记(堆栈)
    void info(T subType, String detail, JSONObject extras);
    void warn(T subType, String detail, JSONObject extras);
    void debug(T subType, String detail, JSONObject extras);
}
// register(Category, Class<T>): 幂等 + 线程安全; 也支持 register("新大类型字符串") 动态注册
// 落库规则: 小类型以枚举 code 持久化(稳定值), label 仅展示——枚举改名不影响历史日志

// ── 查询 / 开关 / 运维 ──
List<LogEntry> query(LogFilter f);   // f:{category?, subType?, level?, fromDate?, toDate?, keyword?, episodeId?} 分页
List<LogEntry> queryByTask(String episodeId);  // 任务维度视图("任务详情→查看日志")
void setEnabled(boolean);            // 总开关（沿用 APP_LOG，默认关）
void setLevel(Level min);            // 全局最低级别（默认 INFO+）
void setCategoryEnabled(Category c, boolean);  // 按大类型动态开关
File export(LogFilter f);            // 按当前筛选导出（FileProvider 分享）
void clear(int retentionDays);       // 清理（默认 7 天）
void installCrashHandler();          // 崩溃捕获 → 落库 + 启动提示；魅族系统内部无害异常(com.meizu.internal./com.meizu.picker./ContentCapture 线程)跳过——与 App 崩溃页过滤器一致，不污染业务日志
```

**两级类型（大类型 + 小类型）**：
- **大类型 category**（稳定、数量少，日志页第一级筛选 icon）：`下载 / 播放 / 订阅 / 系统 / 其他`，定义在 log 模块内；
- **小类型 subType**（各大类内动作集，各业务模块自持枚举，第二级筛选）：
  - 下载：enqueue 入队 / resolve 解析 / playlist 拉列表 / segment 分片 / verify 校验 / repair 补片 / merge 合并 / remux 重封装 / save 落盘 / archive 档案 / cleanup 清理 / cancel 取消 / delete 删除；
  - 播放：init / switchSource / buffering / seek / error；
  - 订阅：refresh / import / parse；系统：network / storage / permission / boot；其他：misc。

**各模块接入方式**：
- 下载：模块 init 时 `DownloadCore.LOG = LogStore.register(Category.DOWNLOAD, DownloadSubType.class)`，任务内部直接 `LOG.fail/success(...)`（extras 带 episodeId）——任务审计日志 = 同表按 episodeId 的视图；
- 播放/订阅/其他：现有 `LogUtils` / `Log.i` 关键路径逐步收敛，或做兼容桥接（LogUtils 内部委托 LogStore），新旧并存不破坏；
- **禁止**：业务模块直接 new Room DAO / 写文件 / 起 logcat。

**内部实现**：
1. **双层存储，都按天**（先回答"文件还是数据库"）：
   - **业务日志 → Room 表**（结构化 category/subType/level/detail/result/reason/extras，筛选一条 SQL、按天清理一条 DELETE；项目已有 Room）；
   - **全部日志 → 按天文件（package:mine logcat）**：保留现有按天文件方案，`logcat` 加 `--uid=<本应用uid>` 过滤只抓本应用；release 受限则提示降级；
2. **统一日志条目（事件化，所有日志统一结构）**：
   ```
   LogEntry {
     timestamp  时间        (LogStore 自动打, 业务不传)
     category   大类型      (作用域日志器自动带: 下载/播放/订阅/系统/其他)
     subType    小类型      (各大类内动作, 枚举 code 落库)
     level      级别        (按结果推导: 失败→ERROR, 可重试失败→WARN, 成功/中性→INFO)
     detail     补充说明    (动作详情, 如 "合并完成, 6.4MB")
     result     成功还是失败 (SUCCESS / FAILURE / INFO)
     reason     失败原因    (失败必有; 成功为空)
     extras     附加字段    (JSON: episodeId/url/...)
   }
   索引: category / subType / level / 日期; detail 截断(4KB), 异常栈截断(40行)
   ```
   **日志页一行展示**：
   ```
   [06-01 10:00:00.123] [下载] [合并] 合并完成, 6.4MB                    ✓
   [06-01 10:00:00.456] [下载] [合并] 失败: 分片缺失: 第3片(HTTP 404)    ✗
   ```
   成功/失败颜色 + icon 区分；筛选 = 大类型 icon → 大类型内小类型二次筛选；
3. **规则**：`fail()` 必须带 reason（失败原因必填），`success()` 不带；时间戳由 LogStore 自动打（各模块不传）；现有自由文本日志（Log.i 等）桥接时映射 `category=其他` + `subType=misc` + detail=原文；
4. **异常必记（程序报错必须打印）**：任何 catch 到异常的关键路径都必须记日志——**禁止空 catch 静默吞异常**；`fail(subType, detail, Throwable, extras)` 自动格式化"异常类型: 消息 + 堆栈"；确实可忽略的异常至少记 `debug` 并注明忽略原因；
5. **采集策略**：异步单线程队列 + 批量写；**高频进度类去抖采样**（不逐条入库），防库膨胀；
6. **UI（升级现有 LogActivity）**：Tab1 业务日志 = 日期 + 模块筛选 icon + 关键词 + 分页，下载条目一键跳"任务详情→任务日志"；Tab2 全部日志 = 按天看 package:mine logcat；导出/清空/保留天数设置；
7. **生命周期**：默认保留 7 天，Room 按日期删 + logcat 文件按天清理（补现有缺失）；总量上限（10 万条，超出删最旧）。

**后期扩展**：级别/模块动态开关、崩溃捕获落库 + 启动提示、**日志一键上报**（用户反馈打包上传）——预留，不在本期。

### 3.2 ② 基座层 · 全局状态监控模块 SystemStateMonitor（独立模块）

**定位**：项目级全局模块，独立于任何业务模块；统一监听系统状态变化，向所有订阅方广播。
下载（Policy-Decider）只是它的一个订阅者；播放器断网提示、页面横屏刷新、订阅恢复重试等
全部同源复用，不再各写一套监听。

**监控维度（可注册扩展，每维度一个 Source）**：

| 状态维度 | 事件 | 消费方示例 |
|---|---|---|
| 网络 | 断网 / 恢复、WiFi↔蜂窝切换 | 下载（仅WiFi策略）、播放器（断网提示）、订阅源刷新、备份 |
| 前后台 | 前台 / 后台切换 | 下载暂停策略、播放器后台行为 |
| 锁屏 / 亮屏 | 锁屏 / 解锁 | 下载暂停/继续策略 |
| 屏幕方向 | 横屏 / 竖屏切换 | 播放器全屏、页面布局 |
| 电量 / 充电 | 低电量、充电状态 | 下载策略（低电量暂停，可选） |
| 磁盘空间 | 可用空间阈值告警 | 下载磁盘水位、清理提示 |
| 系统设置 | 时间 / 时区 / 语言变化 | 日志时间戳、UI 刷新 |

**机制与 API**：
- 单例 + 按维度注册 Source（NetworkSource / OrientationSource / ...），App 启动初始化、随 Application 存活，**不持有 Activity 引用**（防泄漏）；
- 主线程派发 + 去抖合并网络抖动：
  ```java
  SystemStateMonitor.get().register(Listener, 维度...);   // 按维度过滤订阅
  SystemStateMonitor.get().unregister(Listener);
  void onChanged(SystemEvent e);      // SystemEvent{type, value, timestamp}
  SystemState getCurrentState();      // 快照查询:页面打开时一次取全量,免轮询
  ```

**对下载模块的意义（重构落点）**：Policy-Decider **不再自己注册 ConnectivityManager**
（现有 `DownloadManager.java:123-145,192-203` 迁出），改为订阅网络事件 → 重算策略 → 下发指令；
下载只关心"策略结果"，不关心"信号从哪来"。

**落点**：独立成模块（独立包），网络监听、`isMobileNetwork` 等现有实现迁入；自身状态事件
（网络切换/横屏等）经 LogStore 记日志（category=系统，小类型=network/orientation，可选）。

### 3.3 ④ 业务层 · 爬虫模块 SpiderModule（独立模块）

**现状与耦合问题**：
- 代码已独立：`com.github.catvod.crawler` 包（`Spider` 抽象基类 + `JsLoader` / `JarLoader` / `SpiderNull`），但**没有对外门面**，且存在架构倒挂：
  1. **下载模块依赖 UI 层线程池**：`DownloadManager.java:984` 用 `SourceViewModel.spThreadPool`（ViewModel 静态单线程池，quickjs 不能并发）执行地址重解析；
  2. **`PlayUrlResolver` 躺在 `util` 下**：职责是"调爬虫解析播放地址+请求头"，应归爬虫模块；
  3. 播放 / 详情 / 下载散用 `spThreadPool`，爬虫执行入口不收敛。

**对外 API（SpiderApi 统一门面——播放/搜索/首页/分类/详情/解析/代理全能力）**：

```java
// ── 内容爬取（首页/分类/详情/搜索, UI 层用; 内部取实例+串行执行+超时+记日志）──
String home(String sourceKey, boolean filter);
String category(String sourceKey, String tid, String pg, boolean filter, Map<String,String> extend);
String detail(String sourceKey, List<String> ids);
String search(String sourceKey, String key, boolean quick, String pg);

// ── 播放 ──
String play(String sourceKey, String flag, String id, List<String> vipFlags);  // 播放信息JSON

// ── 播放地址解析（播放器 / 下载 reResolve 共用, 内部复用 play + 直链判断 + 请求头）──
ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String rawUrl);
// ResolveResult{ url, headers }  — 防盗链源必须带请求头

// ── 源代理（RemoteServer 等用）──
Object[] proxyLocal(Map<String, String> params);

// ── 控制 / 生命周期 ──
void cancelByTag(String sourceKey);      // 取消该源进行中的请求(JS okhttp tag)
void reloadSources();                    // 源/爬虫更新后重载

// ── 串行执行器（quickjs 单线程限制的统一入口, 替代 SourceViewModel.spThreadPool 散用）──
<T> T submitSerial(Callable<T> task, long timeoutMs);
```

**内部结构**：`SpiderApi`（门面）+ `SpiderExecutor`（单线程串行 + 超时控制）+ `JsLoader` /
`JarLoader` / `SpiderNull` / `SpiderDebug`（保留）+ **`PlayUrlResolver` 迁入**（复用 `play`）。
所有方法统一：取 Spider 实例 → 串行执行 → 超时 → 结果归一（失败返回空 + `fail` 日志，不向 UI 抛裸异常）。

**解耦收益**：
- **UI 层不再直接持有 Spider 实例**：`SourceViewModel` 中散调的 `sp.homeContent / categoryContent / detailContent / searchContent / playerContent`（`SourceViewModel.java:94,221,387,427,490,550`）→ 统一 `SpiderApi.home / category / detail / search / play`；
- **播放 / 解析 / 下载共用同一解析**：下载 reResolve `SourceViewModel.spThreadPool.submit(PlayUrlResolver.resolveWithHeader(...))` → `SpiderApi.resolvePlayUrl(...)`（串行 + 20s 超时），不再依赖 UI 层线程池；
- **远端代理收敛**：`RemoteServer.proxyLocal`（`RemoteServer.java:117` 直连 `ApiConfig.getSpider()`）→ `SpiderApi.proxyLocal`；
- `SourceViewModel.spThreadPool` 移除，线程归属收进 SpiderModule；新增爬虫源 / 新解析协议 = 模块内扩展，UI / 播放 / 下载零改动。

**风险**：
- quickjs 单线程限制 → 所有 spider 调用必须走**同一串行执行器**（播放/详情/下载共享），串行池被占时排队——解析任务 20s 超时防占池；
- 源更新（ApiConfig 变更）→ `reloadSources()` 重建 Spider 实例；下载中任务解析失败走现有重试逻辑（地址过期 → 重解析 ≤3 次）。

### 3.4 ⑥ 业务层 · 播放模块 PlayerModule（独立模块）

**现状盘点**：
- **第三方内核已独立**：`:player` gradle 模块 = vendored 第三方库（DKVideoPlayer `xyz.doikki.videoplayer` + IJK `tv.danmaku.ijk` + Exo ffmpeg），作为内核**保持不动**；
- **项目自有播放层散在 app**：`player` 包 18 个类（EXOmPlayer / IjkMediaPlayer / MyVideoView / BaseController / VodController / LiveController / LocalVideoController / PlaybackSettingsController / SurfaceRenderView / Kodi / MXPlayer / ReexPlayer / VlcPlayer / RemoteTVBox...）+ `PlayFragment`（1600+ 行）+ `PlayerHelper`（内核选择）+ `PlayService` + `LivePlayerManager`——无门面、无解耦。

**对外 API（PlayerApi 门面）**：

```java
// ── 内核/配置（PlayConfig 自持）──
void init();                        // 解码器/内核/渲染/倍速/净化等取 PlayConfig
// ── 播放控制（地址经 ④ SpiderApi.resolvePlayUrl 解析后调用）──
void play(String url, Map<String,String> headers, PlayOptions opts);
void pause(); void resume(); void seekTo(long ms);
void setSpeed(float x); void setScale(int type);
// ── 状态查询 ──
PlayState getState(); long getPosition(); long getDuration(); int[] getVideoSize();
// ── 事件订阅 ──
void subscribe(PlayListener l);     // 状态/进度/缓冲/错误(错误必记日志)
// ── 小窗/后台 ──
void enterWindow(); void backgroundPlay(boolean);
```

**统一方法契约（引擎无关——为升级 Media3 铺路）**：
- **PlayerApi 只暴露项目自有类型**（`PlayState / PlayOptions / PlayListener / PlayError`），**任何内核类型（ExoPlayer2 / IJK / 未来 Media3）不得跨出适配层**；
- 调用方（PlayFragment / 小窗 / 后台播放 / 预览）只依赖 PlayerApi，感知不到内核是谁；
- 每个内核一个**适配器**（PlayerApiAdapter）：现有 18 个播放器类收敛为适配器（Exo2Adapter / IjkAdapter / MxAdapter / KodiAdapter / VlcAdapter / ReexAdapter / RemoteTvBoxAdapter...），统一实现 PlayerApi；
- **Media3 升级路径**：新增 `Media3Adapter`（或替换 Exo2 内核）→ 注册进 PlayerFactory（内核选择由 PlayConfig 控制）→ **PlayerApi 接口与所有调用方零改动**；升级影响面 = 适配层 + 内核依赖，业务层无感；
- 内核特有能力（track 选择、自定义渲染）需要时经 PlayerApi **显式暴露**，禁止调用方强转内核类型——保证引擎可替换。

**内部结构**：适配层（各内核 PlayerApiAdapter + PlayerHelper 内核选择）→ 控制器层（Vod/Live/Local 迁入）→ 播放页 UI（`PlayFragment` 薄层化）；`PlayService`（后台播放）、`LivePlayerManager` 迁入。

**依赖方向**：⑥ → ④（resolvePlayUrl 解析）+ ②（断网提示 / 横竖屏全屏）+ ③（播放日志，大类型=播放，小类型=init/switchSource/buffering/seek/error）+ PlayConfig（自持）+ ⑤ ui-common（播放页资源，现并入 `:app`）。

**风险**：
- `:player` 为 vendored 源码（DKVideoPlayer 基于 Exo2），升级 Media3 需评估 doikki 新版支持或自研薄内核；**PlayerApi 契约稳定是前提**；
- 直播（LiveController）与点播（VodController）差异大，PlayerApi 提供点播/直播两种模式或子接口；
- 内核特有能力禁止调用方强转内核类型，必须经 PlayerApi 显式暴露，否则 Media3 升级时调用方会破。

### 3.5 ① 业务层 · 下载模块（门面 + 6 组件）

**内部结构**：

```
Download-Facade（对外门面）
 ├─ Task-Recorder    任务建档 / 档案 / 审计日志 / 检查点持久化 / 启动磁盘对账
 ├─ Task-Scheduler   队列 / 并发 / 启停 / 工厂实例化 / 排队插队 / 恢复编排
 ├─ Policy-Decider   并发 / 仅WiFi / 磁盘水位 / 限速 / 权限硬门槛 / 错误分类
 ├─ BaseDownloadTask 抽象基类（NormalFileDownloadTask / M3u8DownloadTask）
 └─ File-Cleaner     碎片清理 / 孤儿回收 / 日志清理 / 档案对账
```

**组件职责表**：

| 组件 | 职责 |
|---|---|
| **Download-Facade** | 查询快照（剧集级/视频级）、事件订阅、控制入口、配置、存储权限硬门槛、保存路径展示、日志查询接口、档案管理 |
| **Task-Recorder** | 任务建档/状态落库、已下载档案（长期）、任务审计日志（7 天，经 ③）、检查点持久化 + 启动磁盘对账、TaskStore/ArchiveStore 接口 |
| **Task-Scheduler** | 队列/并发/启停、工厂注册表实例化、CANCELLED 立即中止、NETWORK_PAUSED 恢复调度、优先级排队 + 插队/抢占让位、启动恢复编排、有界执行线程池 |
| **Policy-Decider** | 并发/仅WiFi/磁盘水位/限速、存储权限硬门槛、订阅 ② 网络事件 → WiFi 暂停/恢复、ErrorClassifier 错误分类→重试策略 |
| **BaseDownloadTask** | 方法契约：start/pause/resume/cancel/getProgress/setSpeedLimit；TaskListener 上报协议；子类：NormalFileDownloadTask（直链）、M3u8DownloadTask（HLS，详见 4.7） |
| **File-Cleaner** | 碎片清理+回调、7 天日志清理、孤儿 tmpDir 回收、档案对账 |
| **DownloadNotifier（可选轻组件）** | 下载完成/失败/进度系统通知策略（关闭/仅失败/全部，可插拔）；点击通知打开文件 |

**Policy-Decider 决策输入与输出**：

| 决策输入 | 来源 |
|---|---|
| 网络类型 / 切换事件 | 订阅 ② SystemStateMonitor（原 `DownloadManager.java:123-145,192-203` 迁出） |
| 仅 WiFi 开关 | PlayConfig 模式：下载模块配置门面（单一事实源） |
| 并发数 | 同上 |
| 磁盘水位 | StatFs 预检 / 定时 |
| 存储权限状态 | 权限检查 / 授权回调（硬门槛） |

**输出指令（只向 Scheduler 下发，不直接改任务状态）**：
1. `ALLOW_START`——任务启动前许可/拒绝（权限硬门槛、并发、仅WiFi、磁盘）；
2. `PAUSE_ALL_NETWORK`——仅WiFi 切蜂窝 / 断WiFi（任务置 NETWORK_PAUSED）；
3. `RESUME_ALL_NETWORK`——WiFi 恢复（只自动恢复 NETWORK_PAUSED，不动用户 PAUSED）；
4. `RESCHEDULE`——并发数调低 / 磁盘水位告急（按新约束重排）；
5. `PAUSE_ALL_PERMISSION`——存储权限被撤销（暂停全部并提示授权）。

### 3.6 横切 · 配置门面模式（模块自持 + 设置页通知模式）

**模式**：设置页**不持有配置数据、不直连任何存储**——只做三件事：
1. **查询**：进入设置页时调各模块配置门面的查询方法取快照；
2. **操作（发通知）**：用户改设置 → 调对应模块的操作方法（`DownloadConfig.setWifiOnly(...)`）；
3. **订阅**：订阅模块配置变更事件，模块广播 → 设置页 / 关注方刷新。

**数据归属：维护在模块内部**——每个模块一个配置门面，**数据 + 持久化都在模块内**：

| 配置门面 | 数据/持久化位置 | 对外方法（操作 + 查询 + 订阅） |
|---|---|---|
| 下载 `DownloadConfig`（已有，模式样板） | 下载模块内部 | `isWifiOnly/setWifiOnly`、`getMaxConcurrent/setMaxConcurrent`、`getSaveDir`、`subscribe` |
| 日志 `LogConfig`（新） | LogStore 内部 | `isEnabled/setEnabled`、`getLevel/setLevel`、`getRetention/setRetention`、`subscribe` |
| 播放 `PlayConfig`（新） | 播放模块内部 | 解码器/内核/渲染/缩放/倍速/后台播放/净化/缓存 各 get/set + `subscribe` |
| 系统 `SystemConfig`（新） | 系统层内部 | DNS/主题/加载动画/首页推荐/历史数/直播源 各 get/set + `subscribe` |

**配置门面统一约定**（所有模块遵守）：
```java
// 模块内持有数据 + 模块内持久化(模块自己的 key/存储), 对外只暴露:
boolean isXxx();                 // ① 查询
void setXxx(boolean v);          // ② 操作: 内部校验 + 持久化 + 广播变更(发通知)
void subscribe(ConfigListener l);// ③ 变更通知(模块内 Listener 集合或 EventBus)
// 备份: Map<String,Object> exportConfig(); void importConfig(Map);  // 模块自导出/自导入
```

**变更传播**：模块 `setXxx` → 内部持久化 → **广播配置变更** → 订阅方响应：
设置页刷新该项 UI；Policy-Decider 订阅下载配置重算；LogStore 订阅 LogConfig 开关生效；
SystemStateMonitor 订阅策略项。

**设置页（我的-设置）**：静态分区（播放/下载/日志/系统四区），每区绑定对应模块配置门面；
打开页调各门面 get 取快照渲染，用户操作调 `setXxx`（只发通知），模块广播 → 订阅刷新。
**备份/恢复**：`BackupDialog` 聚合各模块 `exportConfig/importConfig`（取代散落 Hawk 键）。
不直连 Hawk（消除 99 处散落）；设置页资源归 ⑤ ui-common 命名规范（现状：资源已收在 `:app`）。

> 注：不再需要中央配置存储；仅保留一个**极轻量 ConfigRegistry**（可选）：只登记各模块
> 配置门面引用，用于备份聚合顺序与设置页分区渲染，**不存任何数据**。

### 3.7 ⑤ 资源层 · ui-common 模块 + 资源治理（现状：`:ui-common` 已并入 `:app`——主题 JSON 在 `app/src/main/assets/theme/`、`generateThemeColors` 任务在 `app/build.gradle`、公共资源在 `app/src/main/res/`）

**技术约束（决定方案）**：Android res 目录**物理上不允许子文件夹**（AAPT2 限制），
资源"分类"只有两条路：**① 命名前缀规范；② Gradle 模块拆分（每模块自带 res/）**。

**现状盘点**（命名已有雏形，乱在"平铺一个目录"）：

| 资源 | 现状 | 结论 |
|---|---|---|
| layout 100 个 | 平铺；前缀雏形：activity_ / fragment_ / item_ / dialog_ / view_ | 补全规范并强制 |
| drawable 103 个 | 平铺；前缀规律：ic_(41) / bg_(22) / shape_(14) / icon_(8) / item_ / button_ | 统一为 ic_/bg_/shape_/selector_ |
| values | attrs / colors / dimens / strings / styles 已拆分 | 保持 |
| gradle 模块 | 已有 app / player / quickjs / TabLayout / ViewPager1Delegate / crash | 拆 :ui-common 有基础（现状：quickjs/TabLayout/ViewPager1Delegate/crash 已合并为 `:thirdparty`；资源拆分一事终为止于 `:app` 内） |

**① 命名规范（强制执行，防回退）**：
- layout：`activity_`（页面）/ `fragment_`（片段）/ `item_`（列表项）/ `dialog_`（弹窗）/ `view_`（自定义视图）/ `include_`（公共 include）；
- drawable：`ic_`（图标）/ `bg_`（背景）/ `shape_`（形状）/ `selector_`（状态选择器）/ `img_`（位图）；
- values：保持 attrs/colors/dimens/strings/styles 拆分；
- **强制手段**：Gradle 校验任务 / Lint 自定义规则（资源命名），构建期校验，违规即失败。

**② :ui-common 模块（公共 UI 资源独立，零业务依赖）——现状：已并入 `:app`（主题 JSON 在 `app/src/main/assets/theme/`，生成任务 `generateThemeColors` 在 `app/build.gradle`，公共资源在 `app/src/main/res/`）**：
- 内容：主题（styles/themes）、颜色（colors）、字体（font）、全局通用 drawable（ic_/bg_/shape_/selector_ 跨页面通用部分）、通用 include 布局、通用自定义控件（加载/空态/进度条等）；
- app 只留页面级资源（activity_/fragment_/item_/dialog_）；后续 download / player 等业务模块各自 res 只含自身页面资源；
- 依赖方向：app 及业务模块 UI → :ui-common；:ui-common **零业务依赖**（可被 player 复用）。

**③ 渐进式路径（行为零变化）**：
1. **第一步（零风险，先做）**：命名规范落地 + 违规资源纯改名（git mv，无行为变化）+ Lint/Gradle 强制校验；
2. **第二步**：建 :ui-common，公共资源迁入（引用自动解析，构建通过即无回归）；
3. **第三步（按需）**：业务模块树完善（crawler / download / state / log 独立 gradle 模块）。

### 3.8 UI 层原则：纯展示 + 交互，不持有业务数据

**定位**：UI 层（app 模块各页面）是**纯展示与交互组件**——只做两件事，不做第三件：
1. **展示**：调模块门面查询方法，拿**展示模型（DTO / UI 状态）**直接渲染；
2. **交互**：用户操作 → 调模块门面操作方法（发指令）；
3. **不做**：不持有业务数据、不写业务逻辑、不直连任何存储 / 加载器 / 内核 / 模块内部。

**单向数据流**：
```
业务模块(数据/状态/能力) ──查询 / 订阅──▶ UI(展示)
UI(用户操作) ──────────方法调用(指令)────▶ 业务模块
```

**UI 不触碰清单（禁止项）**：
- ❌ 直连 Hawk / Room / 文件系统——数据读写一律经模块门面；
- ❌ 直接调用爬虫加载器（JsLoader / JarLoader / `ApiConfig.getSpider()`）——走 SpiderApi；
- ❌ 直接操作播放器内核（ExoPlayer2 / IJK / Media3）——走 PlayerApi；
- ❌ 直接碰下载内部组件（Scheduler / Recorder / Policy / BaseDownloadTask）——走 DownloadFacade；
- ❌ 在页面里拼装 / 计算业务数据（解析地址、聚合状态、格式转换）——模块门面返回展示模型；
- ❌ 持有业务状态副本（进度 / 列表）——订阅门面事件，展示即最新。

**门面返回展示模型**：模块门面对外返回 DTO / UI 状态（`VideoSummary` / `ArchiveItem` /
`LogEntry` / `PlayState` / `TaskListView`），UI 直接渲染，不做二次加工。

**现有代码下沉方向**（改造时逐项搬）：
- `DetailActivity`：解析地址、下载状态聚合、选集副本 → 下载门面 / SpiderApi；
- `PlayFragment`（1600+ 行）：播放控制 / 解析 / 小窗 / 横竖屏业务 → PlayerApi；
- `DownloadFragment`：任务列表对账 / 聚合 / 状态计算 → DownloadFacade 查询 + 订阅；
- `SettingActivity`：99 处 Hawk → 各模块配置门面；
- `SourceViewModel`：爬虫散调 → SpiderApi。

**验收标准**：页面代码 grep 不到 `Hawk.`、`new Spider`、`ExoPlayer`、`DownloadManager.get()`、
`File(`（业务文件操作）等——只剩门面调用与布局。
**例外**：页面级 UI 状态（选中态、弹窗开关、滚动位置）属 UI 自身状态，留在页面内。

---

## 四、下载模块关键机制

### 4.1 内部状态机（对外仍 5 态：未下载/下载中/已暂停/失败/已下载）

```
QUEUED(待排队) → WAITING(等待调度) → DOWNLOADING(下载中)
                                      │
                    ┌─────────────────┼──────────────────┐
                    ▼                 ▼                  ▼
               VERIFYING(校验中)  PAUSED(用户暂停)   NETWORK_PAUSED(仅WiFi切流量)
                    │                 │                  │
                    ▼                 │  resume          │ WiFi恢复/自动
               REPAIRING(补片≤3轮)    │                  │
                    │                 ▼                  ▼
                    ▼              WAITING            WAITING
               MERGING(合并中)
                    │
                    ▼
               REMUXING(重封装) ──直链跳过──┐
                    │                      │
                    ▼                      │
               CLEANING(等清理回调) ◀───────┘
                    │
                    ▼
               COMPLETED(写档案)      FAILED(可重试/看日志)
                    │
                    ▼
               CANCELLED(删除记录,立即中止线程,保留碎片)
```

> **策略暂停家族**：`NETWORK_PAUSED`（仅WiFi 切蜂窝/断WiFi）与 `PERMISSION_PAUSED`
> （存储权限被撤销）同属"策略暂停"——由对应策略指令触发、条件恢复后**自动恢复**；
> 与用户手动 PAUSED 严格区分（**用户 PAUSED 永不自动恢复**）。

### 4.2 HLS 完整流程（每个检查点都写任务日志）

1. **入队**：门面.enqueue → Recorder 建档（**tmpDir 由 episodeId 派生**，可复用旧碎片）→ 日志"入队" → Scheduler 加入等待队列；
2. **调度放行**：Policy-Decider 检查（**存储权限（硬门槛）** / 仅WiFi+当前网络类型 / 并发 / 磁盘水位）→ 放行或策略暂停；
3. **实例化**：Scheduler 按资源类型工厂创建 `M3u8DownloadTask` → 日志"开始下载"；
4. **拉播放列表**：主列表 → 变体 → 解析分片 N 个 → 日志"播放列表:N片"；
5. **生成分片元数据清单**：写入清单（片名/来源/集数/分辨率/分片数/分片URL列表）→ 磁盘比对已有碎片 X/N → 日志"清单:分片N,复用X"；
6. **逐片下载**：每片先写 `.part` 再 rename（原子写）；每片完成 → 进度上报 + 日志"片i完成"；
7. **校验（缺失清单驱动）**：首次全盘比对生成「缺失清单」→ 日志"校验:缺M片"；
8. **补片**：REPAIRING 循环（≤3 轮）：**只补缺失清单项 → 只复检清单项（不反复全盘扫）** → 日志"补片第k轮:缺M→补K→剩J"；第3轮仍缺失 → FAILED + 日志；
9. **合并**：缺失清单为空才 MERGING；字节拼接 → `merged.tmp`（与成果隔离）→ **合并失败不清理碎片**（回补片循环，计入同一 3 轮上限）→ 日志"合并完成:size"；
10. **重封装**：MediaExtractor demux + MediaMuxer → 标准 MP4 → 日志"重封装完成"；
11. **原子落盘**：rename 到最终文件（原子，杜绝半成品）→ 日志"落盘:路径"；
12. **写已下载档案（先于清理）**：档案写入成功 = 任务成功正式落定 → 日志"档案已写入"；
13. **清理（危险操作，置于最后）**：**只有档案写成功**才通知 File-Cleaner 删 tmpDir → 等回调 → 日志"碎片清理完成/失败"。清理是**不可逆删文件操作**，必须排在"落盘 → 写档案"全部成功之后；清理回调**尽力而为**：失败**不反转成功状态**，残留由孤儿回收兜底（.nomedia 保证不进相册）；
14. **闭环**：任务 COMPLETED → 门面广播事件 → 日志"完成"；
15. **归档**：7 天后 Recorder 清任务日志（档案保留）。

> **顺序铁律**：落盘 → **档案** → **清理** → COMPLETED。
> 危险操作（删碎片）只允许在所有前置成功路径通过后执行；档案写失败 → 保留碎片现场可重试，
> 碎片不删、数据不丢。

### 4.3 异常分支（每条都有日志）

| 场景 | 处理 |
|---|---|
| 网络错误（断网/切网） | 指数退避重试（≤8 次）→ 仍失败标 FAILED(networkFailed)，网络恢复自动续传；**仅WiFi开启时切蜂窝 → 立即 NETWORK_PAUSED** |
| 地址过期（403/404/HTML防盗链） | 经 ④ 重新解析地址+请求头（≤3 次），日志记录新旧地址 |
| 磁盘不足 | 下载前预检 + 合并前检查 → FAILED + 提示需清理量级 |
| 用户删除记录 | 置 CANCELLED → 立即中止线程、不写最终文件、**保留碎片**；重入队复用续传 |
| 用户明确"删除文件" | CANCELLED + Cleaner 删碎片/成品/档案 |
| 校验 3 轮仍缺 | FAILED + 日志缺失片号（保留现场供排查） |
| **档案写入失败** | **不进入清理**（保留碎片现场），重试档案写入，成功后才允许清理；重试仍失败 → FAILED + 日志 |
| **清理失败** | **不反转成功状态**（任务已落盘+档案已写），残留 tmpDir 交给孤儿回收兜底；日志记录 |
| **存储权限未授权/被撤销** | **拒绝入队、拒绝启动**（硬门槛，不做私有目录兜底）；运行中撤销 → PAUSE_ALL_PERMISSION 暂停并提示授权 |

### 4.4 排队与插队执行机制

**现状**：调度器**纯 FIFO**（`schedule()` 按 `createTime` 排序，`DownloadManager.java:713-761`），无优先级、无插队。

**① 排队机制（默认）**：
- 任务带 `priority` 字段：`HIGH / NORMAL / LOW`（默认 NORMAL，随任务持久化）；
- 调度排序键：**priority 降序 → createTime 升序**（同优先级内严格 FIFO）；
- 批量入队按加入顺序排队；并发满时多余任务停在 WAITING；空位释放 / 并发调低时按调度顺序重新调度。

**② 插队机制（两个语义）**：
- `moveToFront(episodeId)`——**排队插队（温和）**：提到队首（该优先级最前），不打断运行中任务，有空位即启动；
- `setPriority(episodeId, HIGH)` / "优先下载"——**抢占插队（激进）**：并发满时，抢占"运行中任务里优先级最低者（同级取最后加入）"让位（复用现有 resume 的让位逻辑 `DownloadManager.java:475-485`）；
- **被抢占者处置（关键）**：置 SYSTEM_PAUSED 后**排到等待队列最前**（同优先级第一位），**下一个空位立即恢复**，进度保留——它本来就在下载中，不应排到队尾受双重惩罚；
- 插队动作写任务日志 + 门面广播事件（UI 顺序实时刷新）。

**调度顺序（空位释放 / 并发调低时）**：
1. 优先级高者先（HIGH > NORMAL > LOW）；
2. 同优先级内：**被抢占者（SYSTEM_PAUSED）先于普通 WAITING**；
3. 多个被抢占者：**后抢占的先恢复**（最近被挤下的最先续跑）；
4. 普通 WAITING：按 createTime FIFO。

**规则与边界**：
1. 抢占插队只替换运行槽位，**并发总数不变**；被抢占任务保留 .part/分片进度，恢复后继续不重下；
2. 与网络策略交互：仅WiFi 开启且当前为蜂窝 → 插队任务也只能 NETWORK_PAUSED，恢复后按新顺序启动；
3. 与"同目标去重"兼容：插队不绕过 `isSameTargetDownloading` 与 episodeId 去重；
4. 详情页发起下载（用户单独点选某集）默认置 HIGH——**用户主动下载优先于批量队列**；
5. 不新增状态：排队/插队只影响 WAITING 排序与 SYSTEM_PAUSED 让位，状态机不变。

**UI 落点**：下载页等待中任务长按菜单 → "移到最前" / "优先下载" / "降低优先级"；队列按排序键展示，插队后事件广播即时刷新；详情页单独点选的集自动 HIGH。

### 4.5 进程被杀 / 崩溃恢复机制

**原则：磁盘实况即事实，Hawk/DB 只是加速索引**——任何内存计数被杀后都可能滞后，恢复一律以磁盘对账为准。

**持久化检查点（Recorder）**：状态跳变即写（入队/开始/校验/合并/落盘/档案/清理/完成/失败/取消）；进度每 800ms 合并写；关键中间产物天然可恢复：直链 `.part`、HLS 有效分片（rename 后）、`merged.tmp`、最终文件。

**启动恢复编排（Scheduler，App 启动时执行）**：
1. 加载任务列表（Hawk/DB）；
2. **磁盘对账（修正 stale 计数）**：
   - 直链任务：`downloadedBytes = .part.length()`——修复"内存计数滞后于实际文件 → Range 续传错位 → 文件中间空洞/垃圾字节"的 bug；
   - HLS 任务：扫描 tmpDir **有效分片**（rename 后的 .ts），丢弃残留 `.part`；`doneSegments` 以磁盘为准；
3. **快进恢复（"落盘→档案→清理"窗口被杀）**：
   - 最终文件已存在 + 任务未 COMPLETED → 直接补"写档案 → 清理 → COMPLETED"，**不重下不重合并**；
   - 残留 `merged.tmp`（合并中被杀）→ 删除后重进合并（分片齐全，不重下）；
4. **状态归位**：下载中/等待/调度暂停 → 置 PAUSED（**默认手动续传**，避免半夜自动烧流量；设置项"重启后自动续传"可选，开启后置 WAITING 并按 Policy-Decider 规则续传）；
5. **地址重解析**：续传前统一 `needReResolve`（代理签名重启后已过期）；
6. 所有恢复动作写任务日志。

**各阶段被杀恢复矩阵**：

| 被杀时处于 | 磁盘现场 | 恢复动作 |
|---|---|---|
| 直链下载中 | `.part`（可能长于内存计数） | 对账 → 断点续传；无 .part → 重下 |
| HLS 分片下载中 | tmpDir 部分有效 .ts + 残留 .part | 续下缺失片；丢弃残留 .part |
| 校验 / 补片中 | 同上 | 重进校验 → 补片循环（≤3 轮） |
| 合并中 | `merged.tmp` 半成品 + 分片齐全 | 删 merged.tmp → 重合并（不重下） |
| 落盘后、档案前 | 最终文件已在 + tmpDir 在 | **快进**：写档案 → 清理 → COMPLETED |
| 档案后、清理前 | 最终文件 + 档案 | **快进**：清理 → COMPLETED |
| 清理中 | 部分碎片残留 | 孤儿回收兜底 + .nomedia 防相册 |
| 排队 / 等待 / 暂停 | 无中间现场 | 按状态恢复，手动/自动续传 |

**防杀增强（可选）**：前台服务 + 常驻通知（下载中显示进度）——Manifest 已有 FOREGROUND_SERVICE 权限；WAKE_LOCK 防息屏休眠断网；下载模块 4.4 阶段评估。

### 4.6 任务对象设计（抽象基类 + 工厂 + 横向扩展点）

**设计目标**：下载"算法"与"框架"分离。框架（Scheduler / Recorder / Cleaner / Policy / 门面）
提供调度、并发、持久化、日志、清理、恢复等通用能力；任务对象只做一件事——"把资源下到本地"。
**新增下载类型 = 新增一个子类 + 注册类型识别，框架零改动**。

**类结构**：

```
TaskMeta(任务元数据: episodeId/片名/来源/集数/分辨率/url/headers/savePath/tmpDir/priority/解密key...
         + 分片元数据清单[分片数/分片URL列表], 下载前写入)
   ▲
BaseDownloadTask(抽象基类, 定义方法契约)
 ├─ NormalFileDownloadTask   直链(HTTP Range 断点续传, .part)
 ├─ M3u8DownloadTask         HLS(清单驱动下载/缺失清单校验/补片/合并/重封装)
 └─ (未来横向扩展) DashDownloadTask / TorrentDownloadTask / 加密HLS ...
```

**抽象基类方法契约**：

```java
public abstract class BaseDownloadTask {
    protected final TaskMeta meta;           // 不可变任务元数据(由 Recorder 建档生成)
    protected final TaskListener listener;   // 上报通道(进度/状态, 任务与框架解耦)

    // ── 生命周期(由 Scheduler 在独立线程调用; start 阻塞执行) ──
    public abstract void start() throws Exception;   // 执行直到 完成/失败/暂停
    public abstract void pause();                    // 协作式: 检查点退出, 现场保留
    public abstract void resume() throws Exception;  // 从磁盘现场续传(不重下)
    public abstract void cancel();                   // 立即中止, 不落最终文件, 现场保留

    // ── 查询(门面/UI 读取, 线程安全) ──
    public abstract int  getProgress();        // 0-100
    public abstract long getDownloadedBytes();
    public abstract long getTotalBytes();

    // ── 子类核心 ──
    protected abstract void doRun() throws Exception;   // 下载算法本体
    protected abstract boolean isResumableFromDisk();   // 是否有可复用磁盘现场(对账用)
}
```

**上报协议 TaskListener**：

```java
public interface TaskListener {
    void onProgress(BaseDownloadTask t, long downloaded, long total);   // 框架负责800ms合并持久化
    void onState(BaseDownloadTask t, TaskState state, String message);  // 状态跳变→落库+广播
    void onCleanupRequest(BaseDownloadTask t);  // 请求清理碎片(由框架仲裁顺序, 见4.2铁律)
    // 注意: 日志不再经 TaskListener——任务内部持 CategoryLogger(3.1注册制) 直接写 LogStore
}
```

**状态归属（铁律）**：子类**只上报事件，不直接改状态/队列/并发**——`onState` 后由
Scheduler / Recorder 统一落库、写日志、广播、驱动调度。

**工厂与类型识别（Scheduler 内）**：

```java
// 注册表: 类型特征 → 工厂(特征链: URL后缀 → Content-Type → 内容嗅探, 现有 isM3u8Response 可复用)
DownloadTaskRegistry.register(Feature.m3u8Url(),   meta -> new M3u8DownloadTask(meta, listener));
DownloadTaskRegistry.register(Feature.directUrl(), meta -> new NormalFileDownloadTask(meta, listener));
// 未来: registry.register(Feature.dashManifest(), meta -> new DashDownloadTask(meta, listener));
```

**横向扩展 CheckList（新增一种下载类型）**：
1. 继承 `BaseDownloadTask`，实现 `doRun()` / `isResumableFromDisk()`（按需 getProgress 等）；
2. 在 `DownloadTaskRegistry` 注册类型特征；
3. 框架能力**全自动生效**：断点对账、校验重试、日志、清理顺序、网络策略、排队插队、恢复；
4. 需要特殊资源（如解密 key）→ TaskMeta 加字段，Recorder 持久化，随任务记录生命周期管理；
5. 子类内实现细节归位：原子写、缺失清单校验、重封装、.nomedia。

**线程模型**：Scheduler 为每个运行中任务分配独立线程（现有 startTask 模式），start() 阻塞运行；
pause/cancel 通过协作式状态标志在检查点退出；任务对象不感知 UI、不感知队列，可独立单测。

### 4.7 M3u8DownloadTask 详细设计（元数据清单 + 缺失清单 + 合并校验）

**① 下载前：写入分片元数据清单（只写一次）**

| 字段 | 来源 |
|---|---|
| 片名 / 集数 | 详情页选集 |
| 来源 | sourceName / sourceKey |
| 分辨率 | 详情页播放器分辨率标签（可空） |
| 分片数 + 分片 URL 列表 | 拉取播放列表后立即解析生成 |

- 写入时机：解析播放列表后、**下载任何分片之前**（TaskMeta / Recorder 持久化）；
- 用途：合并校验的唯一依据（不依赖内存计数）；分片数即"预期全集"，后续所有校验以清单为准。

**② 下载阶段**：正常逐片下载（.part + rename 原子写）。

**③ 合并阶段：缺失清单驱动，不反复全盘扫**

```
首次进入合并前:
  基于元数据清单 一次性全盘比对 → 生成「缺失清单」(仅缺失分片序号) → 记录持久化
  └─ 缺失清单为空 → 直接合并
每轮补片(≤3轮):
  只针对「缺失清单」里的项下载 → 下载后只复检清单项(不扫全目录)
  └─ 清单项全部补齐 → 清空缺失清单 → 进入合并
合并失败:
  不清理碎片(铁律,见⑤) → 回到缺失清单检查 → 补下 → 复检 → 重合并
  └─ 合并失败计入同一 3 轮上限
第3轮后仍缺失/仍合并失败 → FAILED + 日志(保留碎片现场)
```

**④ 性能要点**：
- **全盘扫描只发生一次**（首次生成缺失清单）；
- 每轮只检查缺失清单项——**O(missing) 而非 O(total)**，不再逐片 stat 全目录；
- 缺失清单持久化（Recorder），进程被杀恢复时直接续跑（配合 4.5），不重扫。

**⑤ 合并与碎片（PS 铁律）**：
- 合并阶段**绝不清理碎片**：合并成功 → 按 4.2 顺序铁律"落盘→档案→清理"；
- **合并失败 → 碎片 .ts 原样保留**，只有半成品 `merged.tmp` 可删（重试时重新生成）。

**⑥ 重试与合并过程日志（走任务内 CategoryLogger → LogStore，7 天审计日志）**：

| 时机 | 日志事件（subType / detail / result / reason） |
|---|---|
| 首次校验 | `校验 / 开始 / 清单N片, 缺失M项:[序号]` |
| 每轮补片开始 | `补片 / 第k轮开始 / 目标[序号], 轮次k/3` |
| 单项补下 | `补片 / 片i / 成功 bytes` 或 `补片 / 片i / 失败 原因+HTTP码+异常栈` |
| 每轮补片结束 | `补片 / 第k轮结束 / 补K, 成功K1, 失败K2, 剩余J:[序号]` |
| 补片上限失败 | `补片 / FAILED / 第3轮仍缺失:[完整缺失清单]` |
| 合并开始 | `合并 / 开始 / 分片N, 缺失清单状态, 分片总size, 目标路径` |
| 合并成功 | `合并 / 完成 / 最终size, 耗时ms` |
| 合并失败 | `合并 / 失败 / 第k次, 原因(IO/缺片/校验不符), 碎片保留` |
| 合并重试 | `合并 / 重试 / 第k次开始, 上次失败原因` |

要点：
- 所有事件按**统一日志条目结构（3.1）**：时间（自动）/ 大类型（category=下载 自动带）/ 小类型 / 结果 / 原因（失败必填）；
- **合并开始前必落"合并/开始"日志（含缺失清单状态）**——合并失败时能从日志还原"当时到底缺没缺片"；
- 补片失败 / 合并失败时**缺失清单全量落日志（不截断）**，供事后核对；
- 状态跳变（VERIFYING / REPAIRING / MERGING）同时走 onState 落库，UI 可见；
- 日志纯审计用途，不参与正确性判断（正确性只信磁盘 + 缺失清单）。

---

## 五、对外门面 API（下载）

```java
// ── 查询（快照）──
int[]        queryEpisodes(String videoId, int episodeCount);   // 每集展示态
VideoSummary queryVideo(String videoId);                        // 视频级聚合
DownloadTask getTask(String episodeId);                         // 单任务详情
List<DownloadTask> getTasks();                                  // 下载管理页

// ── 任务日志（委托 LogStore.queryByTask；7 天）──
List<LogEntry> getTaskLog(String episodeId, int offset, int limit);

// ── 订阅 ──
void register(DownloadStatusListener l);  void unregister(DownloadStatusListener l);
// onChanged(changedEpisodeIds) 去抖 500ms

// ── 控制 ──
void enqueue(...); void pause/resume/episodeId; void pauseAll/startAll;
void remove(episodeId, boolean deleteFiles);   // 默认只删记录保留碎片

// ── 排队/插队 ──
void moveToFront(String episodeId);            // 排队插队:提到队首,不抢占
void setPriority(String episodeId, int level); // HIGH/NORMAL/LOW;置 HIGH 并发满时抢占让位

// ── 已下载档案管理 ──
List<ArchiveItem> queryArchive(String videoId);            // 已下载列表
ArchiveItem getArchive(String episodeId);
void deleteArchive(String episodeId, boolean deleteFile);  // 删档案(联动删文件)
void renameArchive(String episodeId, String newName);      // 重命名成品文件

// ── 配置/存储 ──
File getSaveDir();  boolean hasStoragePermission();  void requestStoragePermission(...);
// enqueue 参数组: url / sourceKey / playFlag / episodeRawUrl / episodeId / pic / headers
//                / 片名 / 来源 / 集数 / 分辨率 / priority / 解密key(如有)
```

---

## 六、落地顺序（③日志 → ②状态监控 → ④爬虫 → ⑥播放 → ①下载）

### 主线（业务 / 基础设施模块，顺序即依赖顺序）

| 顺序 | 模块 | 内容 | 验证 |
|---|---|---|---|
| **1** | **③ LogStore + 配置门面模式**（3.1 / 3.6） | LogStore 独立包：register/CategoryLogger/Room 业务日志 + logcat `--uid` / 两级类型 / 按天 7 天；LogConfig 归位 + 设置页日志区改通知模式 | 日志可筛选（大类型→小类型）、按天、7 天清理；设置页走模块门面 |
| **2** | **② SystemStateMonitor**（3.2） | 独立包；网络/前后台/锁屏/横竖屏/电量/磁盘 Source；自身事件经 ③ 记日志 | 断网/横屏事件全项目可订阅 |
| **3** | **④ SpiderModule**（3.3） | SpiderApi 门面化；SpiderExecutor 收口 spThreadPool；PlayUrlResolver 迁入；UI/播放/下载收敛单入口 | 播放/搜索/解析全部走 SpiderApi；解析不依赖 UI 线程池 |
| **4** | **⑥ PlayerModule**（3.4） | PlayerApi 门面化（适配器层 + PlayerHelper）；控制器层迁入；PlayFragment 薄层化；解析走 ④、日志经 ③、状态订阅 ② | 播放全流程回归；新增内核只动适配层 |
| **5** | **① 下载模块** | 见下方"下载模块内部阶段" | 下载全流程回归 |

### ① 下载模块内部阶段（主线第 5 步的子阶段）

| 阶段 | 内容 | 验证 |
|---|---|---|
| **5.1 纯拆分** | DownloadManager 按职责拆 5 类（Scheduler / Executor / Recorder / Cleaner / Policy），行为不变 | 下载全流程回归 |
| **5.2 修 Bug** | **Bug5** `.nomedia`+孤儿回收 → **Bug1** WiFi 暂停/恢复（订阅 ②）→ **Bug2** CANCELLED+碎片保留复用+分片原子写 → **Bug4** 存储权限硬门槛+保存路径展示 → **启动恢复对账** | 逐个 bug 复测；杀进程重启续传验证 |
| **5.3 门面+日志中心** | Facade 升级（档案+订阅+聚合查询+日志接口+档案管理 API）；任务日志体系（7 天，经 ③）；详情页/下载页改造（下载完成 tab 走档案表）；UI"查看日志" | 详情页实时 5 态；排查有日志可看 |
| **5.4 重封装+增强** | **Bug3** TS→MP4 MediaMuxer 重封装；磁盘水位策略化、限速（setSpeedLimit）；前台服务保活 + DownloadNotifier（可选） | 相册时长/缩略图正确 |

### 并行线（UI 资源治理，零风险，可与主线并行）

| 阶段 | 内容 | 验证 |
|---|---|---|
| **P1 命名规范** | 资源命名前缀规范 + 违规纯改名（git mv，无行为变化）+ Lint/Gradle 强制校验 | 构建通过、资源命名合规 |
| **P2 :ui-common 模块** | 建 `:ui-common`，公共资源迁入；app 只留页面级资源（实施后该模块已回并 `:app`，见下方落地状态表） | 构建通过、UI 无回归 |
| **P3 模块树（按需）** | crawler / download / state / log 独立 gradle 模块 | 按需评估 |

### 落地状态（执行记录）

| 项 | 状态 | 说明 |
|---|---|---|
| ③ LogStore + LogConfig | ✅ 已落地 | log 独立包、register/CategoryLogger、Room + logcat `--uid`、LogConfig 配置门面、设置页日志开关改造、LogActivity 双 Tab（业务日志筛选） |
| ② SystemStateMonitor | ✅ 已落地 | state 独立包、网络/前后台/锁屏/横竖屏/电量/磁盘/权限维度、订阅 API |
| ④ SpiderModule | ✅ 已落地 | SpiderApi 全能力门面、SpiderExecutor 串行收口、PlayUrlResolver 迁入、下载 reResolve 不再依赖 UI 线程池 |
| ⑥ PlayerModule（契约层） | ✅ 已落地 | PlayerApi 引擎无关契约 + PlayerFactory（Media3 升级铺路） |
| ⑥ 适配器层 + PlayFragment 薄层化 | ✅ 代码侧完成，⏳ 真机回归 | 18 个播放器类收敛为 PlayerApiAdapter、消除 `instanceof EXOmPlayer` 强转；PlayFragment 全部 6 处内核强转收敛进 PlayerTrackHelper（轨道切换/字幕回调/中文轨道选择），内核 import 清零 |
| ① 5.1 纯拆分 | ✅ 已落地 | DownloadManager 门面 + Store/Scheduler/Executor/Policy/Cleaner 5 组件 |
| ① 5.2 修 5 Bug | ✅ 已落地 | Bug5 .nomedia+孤儿回收 / Bug1 网络暂停恢复 / Bug2 CANCELLED+碎片复用+原子写 / Bug4 权限硬门槛+撤销暂停 / 启动对账 |
| ① 5.3 门面+日志中心 | ✅ 已落地 | DownloadFacade（5 态快照/聚合/订阅/日志/档案管理）、DownloadArchive、任务日志接 LogStore；详情页/下载页 UI 迁移 ⏳ 待真机 |
| ① 5.4 重封装 | ✅ 已落地 | TS→MP4 MediaMuxer 重封装（失败回退 .ts）+ MSG_REMUX |
| ① 4.6 任务对象化 | ✅ 已落地 | BaseDownloadTask 契约 + TaskListener 上报协议 + DownloadTaskRegistry（Feature→Factory）+ NormalFileDownloadTask/M3u8DownloadTask 子类；startTask 经注册表创建对象执行（行为零变化） |
| ① 4.7 日志粒度收尾 | ✅ 已落地 | 校验/开始（含完整缺失清单）、补片轮次（开始目标/单项成功失败/轮末 补K成功K1失败K2剩余J）、补片 FAILED 全量清单、合并（开始含 size/重试含上次原因/完成含耗时/失败含第k次+碎片保留） |
| 3.6 配置门面 PlayConfig | ✅ 已落地 | `:player`（原 player-api，现契约与实现同模块）内自持 内核/渲染/缩放/步进/解码/缓存/后台播放/净化/倍速/字幕 12 项；设置页播放区 + 播放内核消费方 12 文件收敛；沿用旧 Hawk key 兼容历史设置 |
| 3.6 配置门面 SystemConfig | ✅ 已落地 | common 内自持 DNS/主题/动画/首页/历史/直播源/无痕 7 项；设置页系统区 + 系统键消费方 9 文件收敛；设置页 44 处 Hawk 直连清零（四门面 下载/日志/播放/系统） |
| 3.7 P2 :ui-common | ✅ 已落地 | 公共 UI 资源独立成模块：主题生成任务 + styles/colors/attrs/dimens + values-night + 通用 drawable 99 个 + anim 4 个；app 仅留页面级资源；主题 JSON 统一在 ui-common/assets/theme；assembleDebug 通过（行为零变化）。（现状：该模块已并入 `:app`——主题 JSON 现位于 `app/src/main/assets/theme/`，生成任务在 `app/build.gradle`，公共资源在 `app/src/main/res/`） |
| P1 资源命名规范 | ✅ 脚本 + 两批治理 | check-res-naming.ps1 校验（含 :ui-common）；icon_*→ic_*、layout 归位；剩余第三方库风格改名 ⏳ 收益递减 |
| P2 :ui-common | ✅ 已落地 | 公共 UI 资源独立成模块（主题生成任务/styles/colors/dimens/通用 drawable/anim），app 仅留页面级资源（现状：该模块已并入 `:app`，资源与主题 JSON 均在 app 内） |
| 可选增强 | ✅ 已落地 | 限速 setSpeedLimit（5.4）/ DownloadNotifier 完成通知（5.3）/ 前台服务保活（下载中防杀 + 通知栏状态，API 34 dataSync 类型） |
| 分片级并发下载 | ⏳ 未来扩展 | 8.2 扩展点：分片队列 + 小线程池；依赖 activeResponses 任务级连接表改多连接管理，改动非局部，留待真机验证后再做 |

---

## 七、风险点

1. **重封装（Bug3）**：MediaExtractor 对部分源（音视频轨分离/加密流）可能失败——失败时回退"保留 .ts 后缀"，日志记录回退原因；
2. **碎片复用（Bug2）**：tmpDir 由 episodeId 派生后，同一集换源（不同 sourceKey）会冲突——派生规则必须含 sourceKey+playFlag，避免跨源串碎片；
3. **仅WiFi暂停（Bug1）**：NETWORK_PAUSED 与用户 PAUSED 必须区分，WiFi 恢复只自动恢复前者；
4. **孤儿回收（Bug5）**：回收扫描必须排除"有存活任务正在写入"的 tmpDir（按任务快照比对），避免误删进行中的碎片；
5. **7 天日志**：日志清理只删日志，不碰任务元数据与档案；
6. **并发与线程**：拆分时保持现有锁粒度（tasks 同步、persist 合并写、worker 单线程调度），新增 CANCELLED 检查点不能引入竞态；
7. **插队抢占（4.4）**：抢占只替换运行槽位、并发总数不变；被抢占者排同优先级队列最前（后抢占先恢复）；抢占选择按"优先级最低→同级最后加入"固定规则；
8. **清理顺序（关键，4.2）**：清理（删碎片）是**危险操作**，必须排在"落盘 → 写档案"之后；档案写失败则保留碎片现场可重试；清理失败不反转成功状态，残留由孤儿回收兜底；
9. **重启恢复（4.5）**：直链 `.part` 实际长度可能滞后于内存计数，启动对账必须"以 .part 实际长度为准"（否则 Range 续传错位产生空洞）；HLS 只信任 rename 后的完整分片；快进恢复依赖"落盘→档案→清理"顺序铁律；
10. **任务对象状态归属（4.6）**：子类只上报事件（TaskListener），状态/队列/并发统一由 Scheduler / Recorder 管理；扩展新类型时必须遵守该契约。

---

## 八、可扩展性设计

### 8.1 支持边界声明（先划清，再扩展）

| 能力 | 现状 | 边界 / 未来 |
|---|---|---|
| 播放列表类型 | 仅 VOD（含 #EXT-X-ENDLIST） | **直播流（无 ENDLIST）明确拒绝** + 日志"不支持直播下载" |
| HLS 高级特性 | 未支持 | 未来扩展：AES-128 解密（#EXT-X-KEY）、独立音轨（#EXT-X-MEDIA）、CMAF/fMP4（#EXT-X-MAP）、BYTERANGE、DISCONTINUITY |
| 多码率变体 | 取第一个变体 | 未来：按 4.7 分辨率字段选择 / 用户选清晰度 |
| 其他协议 | 直链 + HLS | DASH(MPD) / 磁力 / 加密流 = 新子类 + 注册（4.6） |

### 8.2 扩展点清单（新能力落点速查）

| 扩展点 | 落点 | 做法 |
|---|---|---|
| 新下载协议 | 4.6（BaseDownloadTask + Registry） | 新子类 + 注册特征，框架零改动 |
| 新爬虫源 / 新解析协议 | 3.3（SpiderModule） | 新 Spider 实现 / 新 ResolveResult 解析，下载/播放零改动 |
| 新播放内核 / Media3 | 3.4（PlayerModule） | 适配层加适配器（PlayerApi 契约不变）；**Media3 升级 = 新增 Media3Adapter** |
| 新 UI 资源 / 新页面 | 3.7（ui-common，现归 `:app`） | 按命名前缀规范 + 归属模块 res；app 内资源/组件零改动 |
| 新日志小类型 | 3.1（LogStore） | 各模块枚举类加一项（code+label），LogStore 零改动 |
| 新配置项 | 3.6（配置门面模式） | 模块内自持数据 + get/set/subscribe + exportConfig/importConfig；设置页分区加一行 |
| 分片级并发下载（单任务多线程拉片） | 4.7 内部 | 分片队列 + 小线程池，改动局部 |
| 每任务限速 | BaseDownloadTask.setSpeedLimit + Policy 指令 | 抽象方法 + 默认空实现 |
| 下载引擎替换 | 子类内部网络 IO 收敛 | 可抽 DownloadEngine 接口（留口不先建） |
| 错误分类扩展 | ErrorClassifier（新抽象） | 网络/鉴权/协议/空间/IO → 重试策略映射 |
| 持久化后端替换 | TaskStore / ArchiveStore 接口 | Hawk 实现先行，可换 Room/加密存储 |
| 任务依赖（下完A再下B） | Scheduler 队列 | meta.dependsOn 字段（未来） |
| 通知策略 | DownloadNotifier（可选轻组件） | 关闭/仅失败/全部；点击打开文件 |
| 系统状态维度扩展 | 3.2（SystemStateMonitor） | 注册新 Source（如电量/时区），订阅方零改动 |
| 日志维度/上报扩展 | 3.1（LogStore） | 新增小类型枚举 / 大类型动态注册；动态级别开关；崩溃捕获；一键上报 |

### 8.3 工程纪律（对照检查）

- 新能力能落进"4.6/4.7 子类内部"就不动框架（**算法局部化**）；
- 新决策能落进"Policy-Decider 指令/策略"就不动 Scheduler 主体（**决策局部化**）；
- 新持久化字段先过 TaskMeta / 档案模型，Recorder 统一落库（**数据局部化**）；
- 新事件先走 TaskListener / 门面订阅，UI 只认门面（**接口局部化**）；
- **新项目级基础设施独立成模块**（如新监控维度 / 新日志能力），业务只依赖其对外接口，不并入业务模块（六模块边界，见 2.2）；
- **异常必记（3.1）**：catch 到异常必须落日志（`fail` 带堆栈），禁止空 catch 静默吞异常；框架层兜底崩溃捕获（UncaughtExceptionHandler），业务层负责把可捕获的异常记全；
- **配置归属（3.6）**：配置数据维护在所属模块内部（XxxConfig 门面自持+自持久化），设置页只做"查询快照 + 发操作通知 + 订阅刷新"，禁止直连 Hawk；模块变更必须广播（subscribe）供关注方响应；
- **UI 纯展示（3.8）**：页面只展示门面返回的 DTO + 发交互指令，不持有业务数据、不写业务逻辑、不直连存储/加载器/内核；页面级 UI 状态（选中/滚动）除外。
