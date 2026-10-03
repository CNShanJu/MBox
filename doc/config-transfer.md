# 配置导入导出协议（schema 1）

## 入口与职责

| 入口 | 选择规则 | 文件/传输 | 导入语义 |
| --- | --- | --- | --- |
| 设置 → 局域网服务管理 → 局域网配置导入导出 | 连接另一台 MBox 后选类别；另一台通过本机地址和配对码取配置 | 一次 HTTP 下载一个 `mbox-config.zip` | 订阅、直播、主题、历史按现有去重/合并规则；所选设置覆盖对应键 |
| 设置 → 数据备份还原 | 全量备份/还原 | `tvbox_backup/*.zip` | DataStore 设置与 Room 数据库恢复；旧版目录仍可还原 |
| 订阅管理 | 单条订阅用 JSON；勾选多条用订阅专用 ZIP | 单条 `{schema,category,items}`；多条复用 `mbox-config.zip`，只含 `subscriptions` 域 | 导入后逐条校验订阅，保留原页面的启用和去重流程 |
| 主题编辑等单项入口 | 由所属功能直接处理 | 主题 JSON 或带背景图的 ZIP | 走该功能原有的校验和命名规则 |
| 后续在线导入导出 | 平台只负责上传/下载包，不解析配置 | 同一个 `mbox-config.zip` | 下载后交给 `ConfigBundle.importSelected` |

局域网地址、配对与传输由 `LanSyncClient` / `RemoteServer` 处理；连接地址只接受局域网 IP 字面量，避免 DNS 校验后重绑定。配置格式和合并由 `ConfigBundle` / `ConfigDataExchange` 处理。`ShareArchives` 是 `:share` 对业务公开的 ZIP 操作，所有批量解压都使用它的 Zip Slip、解压大小与条目数检查。当前配对导入一次请求一个 ZIP,并以会话令牌鉴权。

局域网服务由用户显式开启,开启后重启应用才会对外监听。确认关闭时立即停止对外监听、清除当前配对会话,再启动仅供本机使用的回环服务；状态页按实际监听状态提示异常残留。

现有 `ShareFacade` 的 LAN 实现是「本机挂分享会话、对端访问或推送」模式，缺少连接另一台 MBox 后按目录勾选并拉取的能力；所以当前配对式 LAN 导入继续由 `LanSyncClient` 承担。两条传输路径共用归档格式和解压校验。未来在线平台可直接走 `ShareFacade` 的上传/下载能力，不需再实现领域解析。

## 批量配置包

ZIP 顶层条目都是单段文件名，不允许额外目录或文件：

```text
manifest.json
subscriptions.json   # 选中时存在
live.json            # 选中时存在
settings.json        # 选中时存在
history.json         # 选中时存在
themes.json          # 选中时存在
theme-000.bin        # 每个主题一个，按 themes.json 的顺序
theme-001.bin
```

`manifest.json` 使用 `ShareManifest`：`schema=1`、`fileName="mbox-config.zip"`、`createdAt`、`domains`（类别 ID 列表）。包内的 `size` 和 `checksum` 留空，因为归档不能把自己的哈希写进自己；传输层如有外部清单，可对整个 ZIP 另做 SHA-256 校验。ZIP 的条目 CRC 在完整读取时校验。每个类别 JSON 使用 `ConfigDataExchange` 的 `{schema:1,category,createdAt,...}` 结构。主题 `.bin` 按内容识别 JSON 或主题 ZIP，保留主题背景图。

订阅管理导出多条时也使用该格式，但只包含 `subscriptions.json` 和清单，且 JSON 的 `items` 仅有用户勾选的条目。导入时先解压、校验，再把 `items` 交还订阅页面逐条处理，并删除下载的临时 ZIP 与解压目录。内嵌本地订阅文件先在共享执行器落盘，避免批量写文件卡住页面；文件写入使用原子替换。导出一条时保留直接 JSON 文件，同样带 `schema=1`、`category="subscriptions"` 和单条 `items`；旧版 JSON 数组仍可导入。本地订阅文件读不到时导出失败，不能只传原设备的 `clan://` 路径；批量导入也会跳过没有内嵌内容的外来本地路径。分享用的 ZIP 须保留到接收方读取完成，应用外部缓存只保留最近几份。

导入流程：下载至应用缓存 → 校验 ZIP 与清单及选择范围 → 在独立缓存目录解压并核对条目 → 解析所选 JSON → 按领域规则合并 → `finally` 删除下载的临时 ZIP 和解压目录。配置归档压缩后上限 64 MB，解压时单条目上限 64 MB、总量上限 128 MB；全量备份使用独立的较大上限。未选中的类别不写入本机。导入不是跨 DataStore、主题库和历史库的数据库事务；遇到应用层写入失败，应报告中断，重试时现有去重规则防止订阅和直播重复。

## 全量本地备份

同样使用 ZIP 与 `ShareManifest`，`fileName="mbox-backup.zip"`，`domains` 为 `prefs` 与可选的 `room`。条目是 `manifest.json`、`prefs.json` 和可选的 `room.db`。它与选类别的合并导入语义不同：还原会覆盖本机设置和数据库，因此入口先确认。旧版 `tvbox_backup/<时间>/` 目录仍从原文件名读取，子文件须留在该目录内且设置文件限 32 MB。数据库先复制到同目录临时文件，检查 SQLite 完整性、版本和必要表，随后才替换原数据库；设置保留 JSON 整数与长整数的类型，并在一次 DataStore 更新中写入。新的 ZIP 是用户保留的备份文件，不会在还原后删除；应用内部复制出来的临时 ZIP 和解压目录会删除。

## 后续扩展

在线平台实现只需收发 `SharePackage` 的 ZIP 字节，沿用已有 `ShareFacade` 平台选择、可用性和取消机制。下载完成后按 `manifest.fileName` 路由：`mbox-config.zip` 交给类别合并入口，`mbox-backup.zip` 交给全量恢复入口。单项配置继续由所属页面处理；新单项格式若要纳入通用包，可增加独立 `domain` 并保留旧格式读取，不让传输平台直接访问配置存储。

## 真机人工回归

1. 两台 MBox 配对，在接收端分别选一类与多类导入；确认未选类别不变化，重复导入不产生重复订阅或直播源。
2. 订阅管理分别导出一条和多条，检查得到 JSON 与 ZIP；用文件入口导回，确认本地订阅的内嵌内容、名称和启用状态符合选择。
3. 生成本地备份 ZIP，还原并重启；再还原一份旧版目录备份，确认设置与播放记录。取消还原确认框时数据不变化。
4. 传输中断或选择无效 ZIP 时应提示失败；再次导入有效包仍可完成。
