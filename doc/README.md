# MBox 文档索引

`doc/` 根**只放这份索引**，所有文档按性质分进五个子目录。新增/移动文档前先看下面的「存放规则」。

## 一、目录分类

| 目录 | 放什么 | 现有文档 |
| --- | --- | --- |
| [`architecture/`](architecture/) | 架构、模块边界、目录与设计方案（"系统长什么样"） | [ARCHITECTURE.md](architecture/ARCHITECTURE.md)、[项目目录结构.md](architecture/项目目录结构.md)、[项目全景脑图.md](architecture/项目全景脑图.md)、[架构图可视化.html](architecture/架构图可视化.html)、[module-refactor-design.md](architecture/module-refactor-design.md)、[phase2-core-modules-plan.md](architecture/phase2-core-modules-plan.md)、[spider-strong-type-design.md](architecture/spider-strong-type-design.md)、[分享导入导出设计.md](architecture/分享导入导出设计.md)、[自定义主题设计.md](architecture/自定义主题设计.md) |
| [`status/`](status/) | 现状基线、差距审计、改造评估（"现在做到哪一步"） | [项目状态速查.md](status/项目状态速查.md)（**口径基线**）、[全量功能链路清单.md](status/全量功能链路清单.md)、[gap-vs-improvements.md](status/gap-vs-improvements.md)、[audit-fixes-and-refactor-status.md](status/audit-fixes-and-refactor-status.md)、[后续改造评估.md](status/后续改造评估.md) |
| [`features/`](features/) | 单个功能的机制、协议与排查经验 | [接口解析.md](features/接口解析.md)、[直播说明.md](features/直播说明.md)、[开屏海报.md](features/开屏海报.md)、[config-transfer.md](features/config-transfer.md)、[download-auth-renewal.md](features/download-auth-renewal.md)、[hls-js.md](features/hls-js.md) |
| [`release/`](release/) | 更新说明与对外文稿 | [release-notes.md](release/release-notes.md)（**唯一维护来源**）、[release-notes-v3.6.11.md](release/release-notes-v3.6.11.md)（历史兜底）、[wechat-post-v3.6.11.md](release/wechat-post-v3.6.11.md) |
| [`guides/`](guides/) | 规范、纪律与回归验收 | [Git提交规范.md](guides/Git提交规范.md)、[device-regression-checklist.md](guides/device-regression-checklist.md) |

## 二、阅读动线

| 你要做的事 | 按顺序看 |
| --- | --- |
| 第一次接手这个仓库 | 根 [README](../README.md) → [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) → [architecture/项目目录结构.md](architecture/项目目录结构.md) |
| 准备动手改代码 | 根 [AGENTS.md](../AGENTS.md)（规则与红线）→ [status/项目状态速查.md](status/项目状态速查.md)（当前口径）→ [guides/device-regression-checklist.md](guides/device-regression-checklist.md)（要真机验什么） |
| 查某条链路现在怎么走 | [status/全量功能链路清单.md](status/全量功能链路清单.md) → 对应 [features/](features/) 文档 |
| 改主题 / 加页面组件 | [architecture/自定义主题设计.md](architecture/自定义主题设计.md) + [status/项目状态速查.md](status/项目状态速查.md) 的主题段 |
| 加 / 换节日海报素材 | [features/开屏海报.md](features/开屏海报.md)（素材表字段、锚点怎么定、换图与缓存） |
| 发版 | [guides/Git提交规范.md](guides/Git提交规范.md) → 在 [release/release-notes.md](release/release-notes.md) 顶部加一节 → AGENTS §九 发布流程 |

## 三、存放规则（强制）

1. **`doc/` 根不再新增文件**：新文档必须放进 `architecture/`、`status/`、`features/`、`release/`、`guides/` 之一，直接平铺在 `doc/` 下视为不合规。
2. **按性质归类，不按时间/版本建目录**：不要建 `doc/2026-11/`、`doc/v3.6.11/` 这类目录；单版本说明不新建文件，写进 `release/release-notes.md` 的 `## v<版本号>` 一节。
3. **历史快照不删不重写**：已落地/已作废的设计与审计文档在开头标注「历史快照 + 截止日期 + 当前口径指向（`status/项目状态速查.md`）」，保留可追溯性，只做标注与新路径修正。
4. **正文提到文档时写仓库根相对路径**（如 `` `doc/status/项目状态速查.md` ``）；Markdown 链接按所在目录写相对路径（GitHub / Gitee 才点得开），移动文件后链接要一起改。
5. **移动/改名必须同步引用**，一次改完这些地方，否则 CI 与发版脚本会断：
   - 根 `README.md` 的文档导航
   - 根 `AGENTS.md`、`改进.txt`
   - `scripts/release-notes.js`、`scripts/check-release-notes.js`、`scripts/lib-release-notes.ps1`
   - `.github/workflows/build-apk.yml`（更新说明取值路径）
6. **新增分类要先在本文登记**：目录 + 放什么 + 现有文档三列一起补，避免又变成"什么都往一个包里塞"。

## 四、维护状态一览

| 文档 | 类型 | 维护方式 |
| --- | --- | --- |
| [status/项目状态速查.md](status/项目状态速查.md) | 现状基线 | 每批改动同步追加，版本号随 `app/app_config.properties` 更新 |
| [status/全量功能链路清单.md](status/全量功能链路清单.md) | 现状盘点 | 重新盘点时整体刷新；日常只补"已变化"的链路 |
| [architecture/项目目录结构.md](architecture/项目目录结构.md) | 现状基线 | 模块/包/关键类变化时同步 |
| [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) | 架构导读 | 分层、依赖方向、关键决策变化时同步 |
| [architecture/module-refactor-design.md](architecture/module-refactor-design.md)、[architecture/phase2-core-modules-plan.md](architecture/phase2-core-modules-plan.md) | 历史设计 | 只读留档 |
| [status/gap-vs-improvements.md](status/gap-vs-improvements.md)、[status/audit-fixes-and-refactor-status.md](status/audit-fixes-and-refactor-status.md) | 历史审计 | 只读留档，结论已并入状态速查 |
| [status/后续改造评估.md](status/后续改造评估.md) | 持续评估 | 改造项完成/放弃时更新对应条目 |
| [release/release-notes.md](release/release-notes.md) | 发版说明 | 每次发版在顶部加一节 |
| [guides/device-regression-checklist.md](guides/device-regression-checklist.md) | 回归清单 | 每批重构追加该批需真机验证的点 |
