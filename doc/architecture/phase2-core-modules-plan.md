# 改进.txt 第二阶段实施工单：核心模块化（:core-model 起步）

> **已归档（2026-09）**：本文件是第二阶段实施工单，**工单内容已全部落地并已完成归档**——其后核心模块进一步合并（见下方「模块现状」），契约模块已回并业务模块。本文件仅作阶段二原始工单留档，实际模块划分以 `settings.gradle` 为准。

> **当前模块（2026-10-03）**：全仓 10 个模块，`:app`、`:common`、`:core-network`、`:core-storage`、`:log`、`:player`、`:spider`、`:download`、`:share`、`:thirdparty`。本工单中的旧模块名只用于复盘实施阶段，当前划分以 `settings.gradle` 为准。

> **实施进度（2026-09-25）**：核心模块化已基本完成——`:core-model`（纯模型）、`:core-network`（原 common 拆出网络）、`:core-storage`（Room+PrefsDataStore）、`:core-utils`（AES/MD5/AdBlocker）、`:spider-api`、`:player-api` 均已落地（现状：`:core-model`/`:core-utils`/`:state` 已并入 `:common`，`:spider-api` 已并入 `:spider`，`:player-api` 已并入 `:player`）。
> 剩余长线项：`:playback`/feature-* 模块未建（需真机回归）、播放器主线收口（P2-P4）、Hilt 按需评估。EventBus 已全仓移除，旧 ExoPlayer 已迁至 Media3 1.4.1，播放行为仍待人工真机回归。
> 本文件保留作为阶段二原始工单参考（工单已归档，见顶部说明）。

> 本工单把 改进.txt 第二~五阶段细化为可执行步骤。原则：每步保持全仓库可编译；
> 每次“搬一个模型→改 import→全量编译”作为一次原子提交；先建模块壳，再按依赖自底向上迁移。

## 0. 前置事实（已核查）

- 待迁核心模型绝大部分为纯 Java + Serializable（Movie/MovieSort/SourceBean/ParseBean/Subscription/
  VodInfo/VodRecord/VodCollect/AbsXml/AbsSortXml/DownloadTask/ArchiveItem 等，横跨 app/download/spider 三模块同名包）。
- Android 耦合仅少数类：
  - `app/.../bean/VideoInfo.java` → `android.graphics.Bitmap`（封面帧字段）
  - `spider/.../bean/ParseBean.java` → `android.util.Base64`
  - `spider/.../bean/Doh.java` → `Context`/`TextUtils`
  迁移到 core-model 前需先解耦（见 3.2）。
- 同名包问题：`com.github.tvbox.osc.bean` 同时存在于 app/spider/download——core-model 承接后应删除各模块内同名旧文件（同 FQN 重复定义会造成解析歧义，逐文件删除并编译验证）。

## 1. 模块布局（当时的落地目标；现状 10 模块见顶部「当前模块」）

```
:core-model     纯模型(无 Android 依赖): bean + 下载/爬虫 DTO + Entity?
:core-network   OkGoHelper/HttpClient/OkHttp/客户端创建(NetworkProvider)
:core-storage   AppDataManager/AppDataBase/DAO/RoomDataManger/Hawk 封装
:spider-api     Spider 契约 + PlayUrlResolver 契约(UI/下载只依赖接口)
:player-api     已有(player-api 模块保留)
```
依赖方向：app/feature → spider-api/download(门面)/core-storage → core-network → core-model。

## 2. 迁移顺序与门禁

- ⏳ 已建基座：原 `:common` 已更名挂接为 `:core-network`（`settings.gradle` 用 projectDir 指向 common 目录，
  FQN 不变、全仓零代码改动，Debug/Release 通过）。后续裁剪动作（网络面内聚 / 配置与工具迁出 / 事件收敛）
  按本计划逐批执行，每批“移出→清依赖→全量编译”。

- ✅ 已完成：`:core-model` 模块创建；第一批 Movie/MovieSort 迁入；第二批 18 个纯模型迁入
  （AbsJson/AbsSortJson/AbsSortXml/AbsXml/CastVideo/IpScanningVo/Live*/Source/Subscription/Subtitle/
  SubtitleData/TmdbVodInfo/SourceBean/LiveChannelGroup/LiveChannelItem）；第三批 DownloadTask 迁入。
  app/spider/download 已接 `implementation project(':core-model')`。core-model 依赖 XStream 1.4.20 +
  Gson 2.10.1，源码 UTF-8。每批均以 `:app:assembleDebug` 全量编译门禁通过。
- ⏳ 下一批候选：`DownloadTask`（download，纯 Java）与 `IJKCode`（先解耦 HawkConfig 后迁）。
- ⏳ 解耦后迁：VideoInfo(去 Bitmap)、ParseBean(Base64→调用侧)、Doh(Context→参数化)、VodInfo(去 ApiConfig 行为)。

1. 建 `:core-model` 壳：`settings.gradle` 加 `include ':core-model'`；新模块 `java-library` 风格
   （`sourceCompatibility 1.8`；不依赖任何 Android 模块）。
2. 第一批迁移（无依赖、无 Android）：`Movie`(download)、`MovieSort`(spider)、`SourceBean`(spider?)、
   `AbsXml`/`AbsSortXml`(app) 等 —— 每个文件搬入 core-model 后，删除旧文件、全仓 import 不变（包名保持
   `com.github.tvbox.osc.bean` 则无需改 import），执行 `gradlew assembleDebug` 验证。
   ⚠ 若依赖方模块(app/download/spider)须显式 `implementation project(':core-model')`。
3. Android 耦合类解耦后再迁：
   - VideoInfo：封面帧改存 `byte[]`(JPEG) 或移到 UI 侧子类；进度/时长保持基本类型。
   - ParseBean：Base64 解码移交给使用处(spider)或用 java.util.Base64(API26+) / 小型编解码工具。
   - Doh：改传参数而非 Context。
4. Entity(VodRecord/VodCollect/Cache) 是否进 core-model 需定夺：Room @Entity 无 Android 依赖时可进，
   与 Room DAO 一起留 core-storage 亦可（推荐后置，先迁纯 bean）。
5. core-storage 成立后：AppDataManager.runOnDb 等基础设施内聚，UI 只经 Repository/门面。

## 3. 关键风险与对策

- **同名类双定义**：core-model 与旧模块同包同 FQN 会编译歧义——必须“搬一个删一个”，单次全量编译门禁。
- **import 爆炸**：迁移后每个使用方模块需补 `implementation project(':core-model')`（用 implementation 而非 api）。
- **循环依赖**：core-model 不可依赖任何现有模块；发现被依赖类（如 VodInfo 引用 ApiConfig/SourceBean 行为）
  先抽除行为再迁移。
- **下载/爬虫内部类型**：download 内部使用 spider 具体类型（如 PlayUrlResolver 在 spider），第二阶段先抽
  `spider-api` 契约，download 改依赖 spider-api。
- **Room schema/索引**：实体若跨模块迁移，保留 tableName/索引注解与 schema 版本(当前 2)，避免迁移数据失效。

## 4. 首个可执行步骤（下一个工作会话从这开始）

1. `settings.gradle` include ':core-model'；创建 `core-model/build.gradle`（java-library, 1.8）。
2. 把 `spider/.../bean/MovieSort.java` 与 `download/.../bean/Movie.java` 搬入 core-model（包名不变），
   删除两模块旧文件，三处 build.gradle 补 `implementation project(':core-model')`，全量编译。
3. 重复该模式迁移 SourceBean/Subscription/AbsXml/AbsSortXml → 完成 core-model 首批。
4. 单独小步解耦 VideoInfo/ParseBean/Doh 的 Android 引用后再迁（每步编译）。
5. 迁移完成后重建 改进.txt §8 的依赖方向，最后处理 spider-api 与 core-storage。

## 5. 会话内已完成的相关铺垫（勿重复）

- Room 单线程执行器/索引/迁移(版本2)已落地(见 audit-fixes-and-refactor-status.md)。
- DownloadFacade 门面补全并接入三个 UI；DetailActivity 已拆 EpisodeDownloadBatch + DetailQuickSearchHelper；
  PlayFragment 已外移 M3u8Cleaner。
- 被移除的解析 WIP `PlayParseHelper` 备份于 `doc/backup/PlayParseHelper.java.wip`（保留内容，不参与编译，
  需要接线/重用时取出）。
