# 安全审计整改与架构迁移状态（跟踪文档）

> 本文件汇总 MBox（原名 TVBoxOS-Mobile）多轮整改（安全/性能审计 + 模块边界路线图 改进.txt）
> 的落地状态、关键改动点与真机回归矩阵。代码级验证：Debug/Release 双变体 BUILD SUCCESSFUL。

> **模块现状（2026-09）**：全仓 9 个模块 `:app`/`:common`/`:core-storage`/`:player`/`:thirdparty`/`:log`/`:core-network`/`:spider`/`:download`。本文件中提到的 `:core-model`/`:core-utils`/`:state` 已合并进 `:common`，`:spider-api`→`:spider`，`:player-api`→`:player`，`:crash`/`:TabLayout`/`:ViewPager1Delegate`/`:quickjs`→`:thirdparty`，`:ui-common`→`:app`（主题 JSON 在 `app/src/main/assets/theme/`）。下文历史记录保留当年模块名。

## 0. 近期进展补充（2026-09-25）

- **局域网服务弹窗（标题长按那个）三处收口（2026-09-27，用户反馈）**：
  - **「复制」改纯文字按钮 + 主题高亮色**：`item_lan_addr.xml` 原来是 `BtnGhost`（**带 1dp 描边**，所以看着像个描边按钮），文字色还是 `btn_plain_text`（主色）——同一个"复制"在播放详情弹窗里是 `text_accent` 的裸文字，两处观感不一致。现改 `TextView`：`text_accent` + 加粗 + `?selectableItemBackgroundBorderless` 涟漪，与详情页那份「复制」同档色（点击行为不变，整行/按钮都能复制）。
  - **两个按钮并排等宽**：原来「立即重启应用」「知道了」各占整行、上下叠着。现包一层横向 `LinearLayout`，两个按钮 `layout_width=0dp` + `layout_weight=1` + 定高 40dp —— 等宽、并排、吃掉整排；「立即重启应用」`GONE` 时「知道了」自动占满整排（顺序按其它弹窗口径：次要动作在左、主动作在右）。
  - **整段说明不再铺在弹窗里**：`setting_lan_server_tip`（"用它做什么…"六七行）原来直接铺在地址块下面，太碍眼。现收成一行可点的「用它做什么?」（`text_accent` + 12dp 小箭头 `ic_pre`），点开由 `TextTipDialog` 显示全文 —— **与设置行标题长按是同一个弹窗、同一份文案**（不抄第二份），且点开时下面那个局域网弹窗仍在（view 模式弹窗照旧保留，返回键由 `SettingActivity.onBackPressed` 先关它）。
  - 验证：`:app:assembleDebug` 通过；**真机观感（两按钮并排高度/间距、说明入口是否好找）待人工验证**。

- **"局域网服务开了却不知道访问地址"——设置页给出口 + 顺手修掉一个潜伏的回环服务坑（2026-09-27，用户反馈）**：
  - **问题**：设置里打开「局域网服务」只弹一句"重启应用后生效"，**地址（本机 IP + 端口）从头到尾没地方能看到** —— 用户只能自己猜路由器分配的 IP 和端口；而设置行是"标题 + 开关"的横排，手机端没有多余空间放地址（用户明确要求信息不进控件）。
  - **出口 = `LanServerDialog`（底部弹窗，两个入口同一实例）**：①开关由关变开时自动弹一次（此刻最需要知道地址）；②设置行标题**长按**随时再看（换网络后 IP 会变）。弹窗三块内容：**状态**（已开启 · 局域网设备现在就能访问 / 已开启 · 重启应用后才生效 / 未开启 · 仅本机可访问）、**访问地址列表**（逐个列出、每行可点复制）、**注意事项**（`setting_lan_server_tip` 与长按看到的是同一份文案，原文案里"本机 IP:端口"的占位说法已去掉，因为地址就在上面）。"已开启但当前进程仍是仅本机绑定"时额外给一个「立即重启应用」（CLEAR_TASK 重建任务栈，与备份还原后的重启同一套做法，不杀进程）。
  - **状态必须区分"开关开了"与"真的生效了"**：开关是**重启生效**的，只读开关会把"开了还没重启"说成已生效。`ControlManager` 因此记 `lanBound`（本次实例构造时到底传没传 hostname）并暴露 `lanState()`：开关关且实例仅本机 → `LAN_OFF`；开关开但没重启 → `LAN_PENDING_RESTART`；开关开且当前实例绑定了所有网卡 → `LAN_ACTIVE`；**开关关但实例仍绑着所有网卡 → `LAN_PENDING_CLOSE`**（关掉开关不会立刻关端口，同一个进程里服务还在跑；此时显示"仅本机可访问"等于把暴露面说小，所以单列一种状态并写"重启应用后才会停止局域网访问"）。状态、引导语、是否显示「立即重启应用」三处都按这四种状态走。
  - **地址怎么算（`RemoteServer.getLanIpv4Addresses()` + `common/util/LanAddressRules` 纯逻辑带单测）**：手机/电视上能列出好几种 IPv4，全部列出去只会让用户试错。口径两条：**①只认 RFC1918 内网地址**（10/8、172.16~31/12、192.168/16）—— 运营商地址（100.64/10 CGNAT、公网 IP）从局域网根本进不来；**②排除明确够不到的网卡**（蜂窝 `rmnet/ccmni/pdp`、VPN/隧道 `tun/tap/ppp`、Wi‑Fi Direct `p2p`、`dummy`）。用**排除法而不是允许名单**：各 ROM 网卡名五花八门（wlan0/eth0/en0/swlan0/ap0/softap0…），允许名单会把没见过的正常网卡一起滤掉，宁可多留一个也不要把唯一可用的地址藏起来。Wi‑Fi/以太网地址排前面（`isPreferredInterface`），热点等其它地址排在后面；一个都取不到（手机没连 Wi‑Fi）时弹窗明说"没取到局域网 IP"，退回 Wi‑Fi 接口报的地址兜一次底。**原来的 `getLocalIPAddress()` 保留不动**（它只回答"Wi‑Fi 那个地址"，取不到退 eth0/wlan0、最后兜 `0.0.0.0`，对展示没用）。
  - **顺手修掉一个潜伏坑（不是本次新引入的）**：`ControlManager.stopServer()` 以前只 `stop()` 而**不清 `mServer` 引用**，而 `startServer()` 开头是 `if (mServer != null) return;` —— 于是**同一个进程内的"重启应用"**（CLEAR_TASK 重建任务栈：备份还原后、以及本次新增的"开启局域网服务后立即重启"）会让新首页的 startServer 判定"已存在"直接返回，而旧实例已被 `HomeFragment.onDestroy()` 停掉：**回环服务从此一直是停的**（订阅、本地播放、`/proxy`、`/purify.m3u8` 全失效），用户只能手动杀掉进程才恢复。现在：`stopServer()` 清引用并把 `lanBound` 复位；`startServer()` 做成幂等自愈（实例在跑且绑定方式与配置一致 → 复用；已停或绑定方式变了 → 停旧建新），顺带记下本次的 `lanBound`。
  - **验证**：新增 JVM 单测 `LanAddressRulesTest`（RFC1918 三段边界、172.15/172.32 不算、回环/链路本地/CGNAT/公网一律不算、蜂窝与 VPN 网卡排除、**未知网卡名保留**、地址拼装只在合法输入下成立）。**真机行为（弹窗地址与实际可访问地址一致、一键重启后局域网真的能打开控制台、重启后回环播放/代理仍正常）待人工验证**。

- **危险操作配色收敛为主题三件套（2026-09-27，用户要求）**：
  - **主题文件新增两个键**（两个主题文件 + 生成器默认表 + 派生表三处同步）：`swipe_red_text`（危险红底上的文字，白）与 `text_danger`（**不带底色**的危险文字/图标，归在文字分级那一族，与 `text_main`/`text_sub` 并列）；`swipe_red` 的 desc 从"列表右滑删除的红"改成"危险操作红底（左滑删除、删除确认按钮这类不可逆动作的填充色）"。生成后共 30 个颜色资源。**为什么分两档**：危险动作的"入口"（列表工具条的「删除」、条目上的删除图标）不需要底色，红字红图标就够；危险动作的"执行键"（确认删除/清空的按钮）才要红底 + 红底上的字 —— 混成一个键要么白字压在没有底色的工具条上看不见、要么红字压红底看不清。
  - **新增 `BtnDanger` 样式**（parent `BtnPrimary`，`backgroundTint=@color/swipe_red` + `textColor=@color/swipe_red_text`）：危险确认键只用它。
  - **全局接入点（盘点后一次接齐）**：工具条/列表的删除文字与图标 → `text_danger`（下载页 `DownloadFragment.tvDelete`、本地视频列表 `VideoListActivity`（原来用主色 `colorPrimary`，这正是"本地视频的删除文字和下载页不同色"的根因）、本地视频合集 `activity_movie_folders.xml`、订阅条目 `item_subscription.iv_del`、订阅/接口/备份列表 `item_stroke_button.tvDel`、日志页"清空" `activity_log.btn_clear`）；红底 + 红底上的文字 → 左滑删除按钮（`item_download_task_new.btn_swipe_delete` 原先写死 `@color/white`）与危险确认键（`dialog_delete_download.tv_ok` 改用 `BtnDanger`；`ConfirmDialog` 新增 `showDanger(...)` 与 `danger` 参数，下载页两处"删除任务"、本地视频"删除所选视频"、日志页"清空日志"改走危险色）。
  - **口径**：`@color/red`（= `swipe_red`）从此只表示"失败/错误"语义（下载失败状态、chip 失败字、更新失败圈、失败集图标）；危险操作一律走上面三件套。**故意没动**：`item_subscription.iv_pushpin`（置顶图钉的红不是危险语义）、订阅/接口列表其余图标、"清空缓存"这种可恢复动作。
  - **长按多选操作栏抽成公共组件 `ui/kit/SelectActionBar`**（用户反馈"颜色和透明度没跟着主题走" + 要求抽公共组件）：此前两个页面各写一条 LinearLayout + 三个 TextView —— 本地视频那条**写死 `android:background="@color/white"`**（暗色主题下一条白杠，且完全不吃主题的面透明度），下载页那条又没有背景。组件把两件事定死：背景=主题悬浮面 `bg_float`（颜色 + `bg_float_alpha` 透明度都在主题文件里）、键色两档（`Kind.NORMAL`→`text_highlight`、`Kind.DANGER`→`text_danger`、不可用→`text_disable`）；页面只 `addAction(文案, Kind, 回调)` 并用 `setActionEnabled` 报可用态，不再自己 `setTextColor`。接入：`VideoListActivity`（全选/删除/取消全选，`activity_movie_folders.xml` 里那段 `ll_menu` 删除）、`DownloadFragment`。
  - **下载页操作栏"临时替换底部存储条"**（用户口径）：全选/删除/取消全选三个键从卡片内的 `ll_menu` 挪到 `fragment_download.xml` 底部，与"可用空间 | Wi-Fi+流量 · 并发 N"那条**共用同一格、互斥显示**（`updateToolbar` 里 `selectActionBar` 与 `ll_storageBox` 一显一隐）；"全部暂停/全部开始"仍留在卡片底部（那是批量任务操作，不是选择操作）。
  - **两条操作栏必须逐像素同款**（用户接着反馈"下载里的删除控件和本地视频里的样式对不上"）：组件抽出来后键色/高度已同源，剩下的差异全在**外边距归属** —— 下载页整块内容原本靠根布局 `layout_margin="10dp"` 留边，操作栏因此在四边留白处变成"一条方形浮块"，而本地视频那条是贴底整条。改法：`fragment_download.xml` 根布局**不留边**，10dp 页面边距改由卡片自带（`ll_download_box` 加 `layout_margin="10dp"`），底部信息条自己带左右/下边距，操作栏则贴底整条 —— 与本地视频页那条同组件、同背景、同贴边。**新增页面时照这个归属摆**：卡片带边距、栏目贴边。
  - 验证：`:app:assembleDebug/:app:assembleRelease + 单测 + 模块门禁`全绿；主题生成无 WARN（`text_danger`/`swipe_red`/`swipe_red_text` 已生成到 values 与 values-night）；**深浅两个主题的实际观感、以及下载页多选时"存储条被顶掉"的位置是否顺手，待人工验证**。
  - **存储预检抽成中控层 `common/state/StorageSpace`**（自定义主题做背景图导入时要求"复用视频下载那套 1G 判定"）：全应用问"还剩多少/够不够写"的唯一入口 —— `StatFs` 测量 + 1s 按路径缓存 + 判定 + 文案；阈值一律来自 `StorageGuardPolicy`（1GB，中控层不存第二个数字），测量失败按"未知"放行。下载侧全部改读它：`StorageWatchdog`（同时删掉自己那份 StatFs/缓存）、`DownloadPolicy.checkDiskSpace`、`DownloadExecutor` 的合并/重封装空间检查、下载页底部"可用空间"、`SystemStateMonitor` 的磁盘轮询。写入前预检用 `StorageSpace.canWrite(need)`（写完仍要留住 1GB），主题背景图导入即走这条。钉住它的单测：`StorageSpaceContractTest`（**全仓只允许 `StorageSpace` 里出现 `new StatFs`**）+ `StorageGuardPolicyTest`（阈值语义 / 写入前预检 / 未知放行）。

- **用户日志"重封装失败 → 还是 ts 文件" + 三项下载侧改进（2026-09-27）**：
  - **日志本身**：`MediaMuxer.stop() err: -1007` / `Error during stop(), muxer would have stopped already`，栈落在 `DownloadExecutor.remuxTsToMp4`。同一段日志里 `MPEG4Writer` 已经打出 `Received total/0-length (132053/0) buffers and encoded 132053 frames. - Audio`、`(70427/0) … - Video` 与 `MOOV atom was written to the file` —— **每一帧都编码了、moov 也写进文件了**，只是 `stop()` 抛异常，于是旧实现整份丢弃 mp4、回退 `.ts`（用户拿到的就是那个 .ts）。`-1007` 的常见根因是**写到没空间**（重封装要再写一份与源文件同样大的 mp4，而同一个任务此刻磁盘上还躺着分片目录 + 合并产物 = 峰值 3× 成品），这也是本次"看门狗 + 空间预检"要一起做的原因。
  - **重封装两处收口**（`DownloadExecutor#remuxTsToMp4`）：①**动手前算空间** `ensureRemuxSpace`：需求 = 源文件 ×2（需重打包 192/204 时 ×3），保底只要求绝对下限 `DownloadPolicy.MIN_ABSOLUTE_FREE`(512MB)，不按看门狗阈值(1GB)拦 —— 这是已下完文件的最后一步，为它回退 `.ts` 最不划算；不够时先释放本任务的分片目录（合并产物已在，碎片的唯一价值是"失败可低成本重试"，这里拿它换空间，代价已落日志留痕），仍不够才明确失败并写清"需多少/剩多少"。②**`stop()` 报错后先校验产物再决定** `mp4Usable`：能开出提取器 + 有轨 + 时长 >0 + 时长 ≥ 源时长 90%（源时长拿不到就跳过）+ **seek 到末尾读得出样本**（moov 声称的时长与实际数据对不上时读不出来）；通过就照旧采用 MP4（业务日志 warn 记一笔"系统 stop() 报错但成品校验通过"），不通过才回退 `.ts`。③回退时**完成信息写明** `未封装为 MP4(保留 TS,原因见日志)`，用户不再只看到一个莫名的 .ts。
  - **m3u8 大小改为"抽样探测 + 推算"**（用户口径：不探测所有分片，随机抽一部分用平均大小推算全量）：新增纯逻辑 `common/util/HlsSizeEstimator`（分层抽样 `sampleIndices`：把清单均分成 N 段、每段随机取一片，保证铺满整份清单而不是全挤在一头；`averageSampleBytes`：样本 ≥5 片时去掉一个最大与一个最小再平均，抗关键帧长尾；`estimateTotalBytes`：平均片大小 × 片数）。执行器侧 `estimateHlsBytes` 三级口径：**①抽样探测**（12 片；字节范围清单 `#EXT-X-BYTERANGE` 直接读范围长度求和 = 精确值、零请求；其余 HEAD 取 `Content-Length`，不认 HEAD 退 `Range: bytes=0-0` 读 `Content-Range` 总长；单请求 `callTimeout` 8s + 整体 20s 预算，超预算就拿手上样本推算）→ **②`BANDWIDTH ×` EXTINF 累计时长**（原口径）→ **③片数 × 2MB**。任何一级失败都往下走，全失败返回 0（不阻塞下载，与旧行为一致）。抽样用 `downloadClient().newBuilder()` 加超时（共享连接池），不新建连接栈。
  - **存储看门狗**（用户要求：所有下载模块共用一个实现，低于 1GB 暂停全部任务，作为"预检通过后用户又干了别的把空间吃光"的兜底）：`common/util/StorageGuardPolicy`（阈值 `LOW_STORAGE_BYTES = 1GB`、`isLow(free)` **测量失败视为"不低"**（兜底机制不能因一次 statfs 失败把下载全停）、任务文案）+ `download/internal/StorageWatchdog`（**唯一实现**）。三个入口共用同一实例：**①热路径自检** `checkWhileDownloading()`（1s 缓存，直链写循环 500ms 一档、分片 64KB 读循环、HLS 每片循环、合并每 20 片各调一次；发现不足**当场**暂停全部并返回 true，调用方立即 flush 收尾退出）；**②后台巡检**（有任务在下载时 2s 一轮，覆盖"连接卡住、没有字节流过"的空窗；任务全停线程自退，不常驻）；**③调度闸门**（`schedule()` 里 `dm.watchdog.isLow()` 时不放行新任务，等待任务文案 `存储空间不足,清理后继续下载` —— 只停在跑的任务会留"刚入队的立刻又把空间吃回去"的口子）。暂停置 **`STATE_PAUSED`**（用户暂停）而不是 `STATE_SYSTEM_PAUSED`（调度让位，有空位会被立刻拉起，等于撤销暂停），**只暂停不自动续传**（空间刚过线自动恢复会变成"清一点又写满"的反复起停），清理后用户点继续即按原进度续传。合并/重封装阶段不按 1GB 拦（合并本来就要短暂占用 2 倍空间），各有按"需求 + 保底"的空间预检。
  - **验证**：新增 JVM 单测 `HlsSizeEstimatorTest`（抽样铺满/去重/边界、截尾平均、940 片 + 1 片大关键帧的真实分布下推算误差 ≤5%）、`StorageGuardPolicyTest`（1GB 阈值语义、未知一律放行、文案带两个数字）、`StorageWatchdogContractTest`（源码级绊线：看门狗只能有一套实现、四条写盘路径必须各有一处自检、调度闸门必须问同一个看门狗、暂停不得用 `STATE_SYSTEM_PAUSED`、重封装 stop 容错与抽样口径不许回退）。**真机行为（省空间时下载不再把设备写满、重封装不再回退 .ts）待人工验证**。

- **"m3u8 下载总是 8 片 HTTP 404 / 整集失败"——用户给的日志是修复前的构建（2026-09-27）**：
  - 日志头部 `[3.5.8(66)]`、时间 `09-26 20:44`；同一晚两笔修复在它之后落地：`a5ac928b`（21:03，分片 404/410 分级为"源侧永久失效" + 死片记忆 + 缺片放宽档）、`55b96790`（21:37，下载侧改用与播放同一份净化清单，提交信息里写的**正是这批 8 片** `237/240/430/493/509/568/801/862`，共 940 片）。两笔都在 v3.5.9/HEAD，所以"补片 3 轮 × 8 片全 404 → 重新解析地址 → 再失败"在当前代码里不复现：净化先把这 8 片广告/占位分片从清单里剔掉（完成信息 `已完成(已过滤 N 片广告/占位分片)`），万一仍有真死片则记入 `goneSegments` 不再重发请求，缺片 ≤ max(8 片, 总数 1%) 且 ≤10‰ 走"缺片完成"而不是把整集判死。
  - **为什么不换第三方 m3u8 下载器（用户提议 JeffMony/VideoDownloader）**：这 8 片是**清单里列着、但源站已删**的地址，任何下载器请求同一个 URL 拿到的还是 404 —— 换引擎变不出字节，却要丢掉已落地的净化同源（"播放不缺、下载缺"那条口径）、死片记忆、跨线路补片 + PTS 接缝校验、重封装 MP4、档案/断点续传。**要换引擎，前提是先能证明"同一 URL 换个取流方式能拿到 200"**（例如那 404 其实是本机回源代理给的）。下载类型分发本身已存在（`DownloadExecutor.processTask` 按 `.m3u8` 分派 HLS/直链 + 内容嗅探纠偏，任务对象 `M3u8DownloadTask`/`NormalFileDownloadTask`），不是"没分派"。
  - 本次只补**诊断可见性**：分片失败的业务日志里带上该片 URL（截断 200 字符，`DownloadExecutor.segUrlForLog`）。原先 URL 只打 `Log.i("TVBox-Download", ...)`，而 logcat 捕获默认只收 E 级（`LogcatCapture.buildCommand`），用户从"运行日志"里根本看不到 —— 分不清 404 是**源站**给的（换谁下都一样）还是**本机回源代理** `127.0.0.1:9978/proxy?do=...` 给的（源 JS/代理的问题，能修）。下次复现请以业务日志里的 `url=` 为准。

- **本地视频合集封面留白 + Exo 拖动进度条后画面卡死（2026-09-27，用户反馈两处）**：
  - **合集封面不许等比缩放留白**：`item_folder.xml`（我的-本地视频最外层的"文件夹=剧集合集"卡片，120×72dp 槽位）的 `ImageView` **没写 `scaleType`**，走默认的 `FIT_CENTER` —— 缩略图是 `MediaMetadataRetriever` 取的关键帧，比例几乎不会和槽位一致，于是画面被缩成居中一条，而 `FolderAdapter` 铺在**背景层**的统一占位（`PicassoLoad` → `PosterPlaceholderDrawable`）就从四周露出来：竖视频取到的帧偏宽，留白落在上下，观感就是"底部的占位图都显示出来了""内容明明显示了占位图还在"。改为 `centerCrop` + `clipToOutline`（实图跟着占位背景的圆角轮廓一起裁，圆角卡片不露直角），并去掉布局里的旧静态占位图 `android:src="@drawable/iv_video"`（统一占位只有一套，运行时一律由适配器经 `PicassoLoad` 铺）。同页其它封面位（`item_local_video` 本地视频列表、`item_download_vod_grid` 下载页聚合宫格）本来就是 `centerCrop`，只有这一处漏了。
  - **Exo seek 不再做"精确 seek"**：`ExoMediaPlayer.initPlayer()` 未设 `SeekParameters`，而 Exo 对点播的默认值是 **`EXACT`**（已按 2.18.7 字节码核对：`DEFAULT = EXACT = (0,0)`）—— seek 后视频渲染器必须从关键帧一路解码到目标位置才允许出画，这中间的帧全被丢弃（画面停在旧帧），而音频帧很小、几步就对齐到目标继续响，于是"频繁拖动进度条 → 画面卡着不动、声音还在走"（上一次 seek 还没解码到目标就被下一次 flush 掉，画面永远追不上）。改为 `SeekParameters.PREVIOUS_SYNC`（`(MAX,0)`：落到目标**之前**的关键帧、解码出第一帧即出画，不越过用户落点），与 IJK 内核的口径一致（默认解码档带 `fflags=fastseek` + `enable-accurate-seek=0`，同样是关键帧 seek；系统内核 `MediaPlayer.seekTo(int)` 本身就是 `SEEK_PREVIOUS_SYNC`）。排查时确认：三个内置内核里**只有 Exo 默认精确 seek**，IJK/系统内核不具此问题。
  - 验证：构建与门禁见本文件顶部口径；**真机行为（合集封面铺满、拖动进度条不再卡帧）待人工验证**。

- **"网络不可用"页跳转链的口径修正（2026-09-27）**：原链路的两个判定口径不一致 —— "弹页条件"是**请求失败**（`NetworkGuardInterceptor` 按 `OkGoHelper.hasNetwork()` 判，快速失败，并把 UnknownHost/ConnectException/SocketException 也算断网），"返回条件"是**transport 变化事件**（`SystemState.network`）。这是下面多数问题的根，逐条修：
  - **P0 有可用链路时不弹整屏页**（`NetworkIssueRouter.hasUsableLink()`）：连着 Wi-Fi 但 DNS 被拦 / DoH 挂了 / 源站域名不存在时请求照样失败 → 原来"弹页 → `NoNetworkActivity` 见 `state.network=WIFI` 立刻自动返回 → 回原页 `GridFragment.bindNetworkListener` 见列表为空又 `initData()` → 再失败"，与两道 3s 节流串成"每 3 秒闪一屏"（每次还要跑一遍 `BaseActivity.onCreate`）。现在有链路一律不弹（只留一行"不弹无网络页: 当前有可用链路…"日志），失败由页面自己的空态/提示承担。
  - **P0 自动返回最短停留 + "白弹"抑制**：`NoNetworkActivity` 自动 finish 前至少停留 1.2s，并回报 `NetworkIssueRouter.reportAutoDismissed()`；连续两次"弹出即返回"→ 抑制到下次真实网络变化。用户点"我知道了"的抑制也补了 **10 分钟时间窗**：原来只在"非 NONE 网络事件"里解除，而"有链路取不到内容"这种状态根本没有事件 → 抑制等于持续到进程结束。
  - **P1 VPN/以太网/蓝牙共享不再被当成"没网"**：`SystemStateMonitor.currentTransport()` 原来只认 WIFI/CELLULAR，其它 transport 一律落回 `NONE` → 这些用户的无网络页永不自动返回、页面永不自动刷新；新增 `VAL_CONNECTED`，语义从"哪种传输方式"改成"有没有可用网络"。`OkGoHelper.hasNetwork()` 与 `SystemStateMonitor.hasUsableNetwork()` 的口径**必须逐字一致**（门禁明令 `:core-network` 禁止依赖 `:common`，故各留一份实现，改口径要两处一起改）。
  - **P1 后台链路失败不再弹页**：`OkGoHelper.newBaseBuilder(false)`（图片客户端、下载客户端）+ 请求级 `NetworkGuardInterceptor.markQuiet(request)`（检查更新、APK 下载）—— 后台预取/续传/自动检查更新失败不该把正在看本地内容的用户弹走（这正是拦截器注释里写明的设计意图，之前被"所有客户端都挂守卫"破坏了）。
  - **P1 播放中不弹页**：`NetworkIssueRouter` 弹页前查 `PlaybackSessions.activeCount()`（播放器播放期间登记会话），有会话就只在播放器内提示；否则 Exo 走带守卫的取流客户端，在线播放断网必弹整屏页、小窗(PiP)也被顶掉，而 IJK 不经 OkHttp 反而不弹（两个内核行为不一致）。
  - **P1 统一"有没有网"的页面判定**：新增 `SystemStateMonitor.isOfflineNow()`，替换三份语义相反的 `isOffline()`（`NoNetworkActivity` 的 catch 返 true、`GridFragment` 返 false、`HomeFragment` 又一份）；新增 `registerSafe/unregisterSafe`（`get()` 未 init 时返回 null，原调用点是裸链式会 NPE；`register` 同时按类型去重，重复登记会收两次事件）。
  - **P2**：事件统一主线程派发（磁盘事件原来在 `tvbox-disk` 线程直接 `emit`）；拦截器去掉每条请求 `Thread.sleep(150)`（断网时逐条睡、本项目还有同步请求 → 可能卡 UI），改成"最多每 500ms 复检一次"的时间戳窗口；无网络页显示 `EXTRA_REASON` 副标题（原来只进日志，页面上没有任何"为什么弹"的线索）；两个按钮归零 `insetTop/Bottom`（MaterialButton 默认上下各 6dp inset，40dp 高只剩 ~28dp 可见，比其它页按钮小一圈）；`FastSearchActivity` 补**整轮搜索看门狗**（某源 `getSearch` 抛异常被吞时批次不投递 → `allRunCount` 不归零 → "搜索中"永远转、"到底了"永不出现）。
  - **留给下个版本（v3.5.10 待办，2026-09-27 用户确认「先记录、下个版本搞」）**：
    ① **断网收尾要能区分"源是空的"与"没网"，并给页面内重试入口**：现在两者都落成 LoadSir 的空态（`view_empty.xml` 固定文案"暂无数据"、没有重试按钮），用户分不清是源没内容还是自己没网。做法：新增 `NetworkErrorCallback`（复用无网络页的插图与文案口径）+ 给 `BaseActivity`/`BaseVbFragment`/`BaseLazyFragment` 的 `setLoadSir` 加"带 reload 监听"的重载（现在注册传的是空 lambda，就算加了按钮也点不动），断网收尾处改 `showCallback(NetworkErrorCallback.class)`；顺带给播放器错误提示补重试入口（`PlayFragment` 的错误提示 `clickable=false`、无监听，第二次才给"切换播放器"的 span）。
    ② **按钮高度口径统一**：现在两套 —— 33dp（`dialog_*` 各弹窗）与 40dp（`dialog_confirm` / `dialog_delete_download` / `activity_no_network`）。方向待用户拍板（都收 33dp = 与弹窗口径一致；都放 40dp = 大按钮口径，整屏页更合适），定了以后一次改齐。注意 MaterialButton 必须写定高，`wrap_content` 会吃默认 `minHeight=48dp`（`dialog_loading` 那个"取消"就是这么大了一圈的）。
    ③ 无网络页返回时不回传"网络已恢复"标记（各页面 `onResume`/网络监听已自愈，够用，暂不做）。

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

- **设置项有效性核查（2026-09-26）**：四条"改了就生效"里 **3 真 1 误报**：IJK 解码 `click` 只改内存字段未写 `PlayConfig` → 已补；安全 DNS 下标越界（老备份 `doh_url` 4~6 vs 列表 4 项）→ 加 `OkGoHelper.dohCount/dohLabel` 夹取；清缓存只删内部 cacheDir、裸线程 + 提前 toast → 改走 `HeavyTaskUtil` + `FileUtils.clearAllCache()` + 删完再 toast；**运行日志开关"要重启"是误报**（`LogConfig.setEnabled` 内部早已调 `LogStore.get().setEnabled`，写日志门控读的就是那个实例字段）。以下四条**口径已定**（刻意选择，不要再当缺陷修）：
  - **局域网服务令牌**：保持现状 —— 令牌仍由本机（含回环）下发、`/token.js` 对局域网可见；开关默认关（显式 opt-in）。设置行不再写状态描述，"开启后到底能干啥"移到**标题长按**的 tip（`TextTipDialog` + `setting_lan_server_tip`，2026-09-26 用户要求：说明不塞进横排设置行）。要收紧需先定配对/一次性授权方案。
  - **无痕浏览**：只覆盖"历史 + 搜索历史"，**收藏照常记录**（口径不变；设置页不再写"不记历史与搜索(收藏照常)"那行文案，按用户要求去掉，2026-09-26）。
  - **老剧的播放设置**：保持现状 —— 历史里的 per-vod `playerCfg` 优先于主设置（老剧沿用首次播放时的解码/渲染/缩放），要改就在播放面板改（面板改的也是这份 per-vod 配置）；这是"手动微调优先"的刻意语义。
  - **本地（回环）HTTP 服务的启停时机**：保持现状 —— 由 `HomeFragment.init()` 起（`ControlManager.startServer()`）、`HomeFragment.onDestroy()` 停；`App.onCreate` 只在"服务已起"时注入基址，自己不起（2026-09-27 用户确认，日志里那句 `本机服务已启动: http://127.0.0.1:9978/` 是正常现象，不要再当"多起了一个服务"修）。**为什么不是"用到才起"**：端口是动态的（9978 被占则 +1，上限 9999），所有回环 URL 都得先拿到 `ApiConfig.setLanBase(mServer.getLoadAddress())` 注入的实际基址，而首页初始化是最早会用到源/播放的时机。**消费方**（都是 URL 形态，所以必须有 HTTP 端点）：`/proxy?do=js…`（JS/jar 源代理，`Global.getProxy()`）、`/purify.m3u8`（净化后的清单喂给播放器，`PlayFragment`）、`/file/…`（本地·局域网文件）、`/dns-query`；这几个都强制校验回环来源，`/` 与 `/token.js`（web 控制台）只有开启「局域网服务」才对外。要整成"懒启动"必须在上面每个调用点前接 `ensureStarted()`，漏一处就是播放/本地文件失败 —— 属另开批次的事。

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

- **JS 源桥调用失败引发进程 abort（JNI DETECTED ERROR）修复（2026-09-26，用户日志实证）**：日志显示 `js-spider-*` 线程在 `JsSpider.lambda$call$1 → Async.call → QuickJSContext.call` 报 `JNI NewObjectArray called with pending exception: java.lang.IllegalArgumentException: Unsupported Java type java.lang.Object` → `Runtime aborting`（SIGABRT，**不是 ANR**；用户看到的"52 个线程转储"是 ART abort 时的现场，不是 ANR traces）。崩溃链：JS 源调用 `@Function` 桥方法时传参与 Java 签名不符（native 把整数映射成 `Integer`、**整数值的 float64 映射成 `Long`**、非整数 `Double`、布尔 `Boolean`；少传/多传参数同样常见）或方法自身抛异常 → `JSObject.bind` 的 `catch` 吞掉后 **`return new Object()`** → native `toJSValue` 只认 String/Boolean/Integer/Long/Double/byte[]/JSObject/JSCallFunction，`java.lang.Object` 抛 `Unsupported Java type`，且该异常**留在该线程 JNI env 上成为 pending exception**（无 Java 帧回退去处理），下一次 JS 调 Java 时在 `jsFuncCall` 的 `NewObjectArray` 撞 CheckJNI → 进程直接 abort（**abort 现场与真正起因是两次调用，看着毫不相干**）。修复：
  - `:thirdparty`（本地补丁，非上游 quickjs-wrapper 代码）：`JSObject.bind` 改为"按签名归一化入参 + 返回值收敛 + 失败 `return null`（不再 `new Object()`）"；新增 `JSUtils.adaptArgs(Method,Object[])`（数值/布尔/字符串互转、补齐或截断参数、可变参数按数组装配）与 `JSUtils.toJsSafe(Object)`（`Float/Short/Byte/Character/Map/List/自定义 bean` 降级）；`QuickJSContext.set/setProperty/arrayAdd`（含 `JSArray.push/set`）的 Java→JS 值一并收敛。
  - `:spider`：`JsSpider.invoke`（jsapi 绑定路径，独立于 `bind`）同走 `adaptArgs`/`toJsSafe`；`Global.js2Proxy` 的 `headers` 空值不再 NPE（源只传 4 个参数即触发，是这条崩溃链的典型入口）。
  - **排查盲区一并修掉**：原实现只 `printStackTrace()`（写 `System.err`，Android 默认丢进 `/dev/null`，logcat 也看不到），且 `InvocationTargetException.getMessage()` 常为 null；改为 `Log.e("QuickJSBridge", 方法名(实参类型), 真实 cause)` → 会被 `LogcatCapture` 的 `*:E` 收进 `app_logs/logcat-*.log`、错误日志页可见（也是下次定位"哪个源、哪个函数、哪种传参"的入口）。
  - **debug/release 表现不同（重要口径）**：debuggable 应用默认开 CheckJNI → 直接 abort；release 默认不开 → 同一异常在 native `call` 返回 Java 时抛进 `Async.call`、future 异常完成 → 被爬虫层 catch 吞掉，表现为"该源静默没数据"（release 侧很可能一直在静默踩而未被发现）。
  - 验证：`assembleDebug/assembleRelease/testDebugUnitTest/checkModuleDependencies` 全绿（379 任务）。**未加 JVM 单测**：`adaptArgs` 纯逻辑可测，`toJsSafe` 的降级分支调 `android.util.Log`（JVM 侧未开 `returnDefaultValues`），需先定日志注入口径再补。**待人工验证**：原订阅启动不再整体退出；`QuickJSBridge` 日志能定位到出错的源与函数。
  - 文件：`thirdparty/.../wrapper/{JSObject,JSUtils,QuickJSContext}.java`、`spider/.../util/js/{JsSpider,Global}.java`。相关排期见 `doc/后续改造评估.md` §K7/§M（原生 abort/ANR 的进程退出原因回收：崩溃页与结构化日志对 native 死亡双双盲区）。

- **断网被误判成"域名/DNS 故障"的教训 + 无网络处理（2026-09-26，与上一条同一轮排查）**：手机 Wi-Fi 断开时，App 里所有请求的报错都是 `java.net.UnknownHostException: Unable to resolve host "xxx": No address associated with hostname` —— 与"域名被 DNS 拦截 / 解析器有问题"长得一模一样，排查因此先怀疑安全 DNS、再怀疑域名被拦（同一 Wi-Fi 的 PC 用系统 DNS 与阿里/腾讯/360 的 DoH 都能解析该域名，反而"坐实"了这个错误方向），直到开启安全 DNS 后**连 `doh.pub` 自己都解析不到**（DoH 初始化固定走系统 DNS，见 `OkGoHelper.newDohClientBuilder()`）才反过来怀疑"App 的解析整体失效"，最终确认为断网。根因不是解析，而是**信号没有被接到用户和日志看得见的地方**：`SystemStateMonitor` 一直在监听 `TYPE_NETWORK`（`registerDefaultNetworkCallback` + 300ms 去抖，`App` 启动即 init），但**全仓只有 `:download` 订阅**（`DownloadPolicy`/`DownloadScheduler`），UI/内容链路/网络层一个都没订阅，`strings.xml` 里也没有任何"无网络"文案。本轮补齐三处：
  - **网络层快速失败**：`:core-network` 公共 Builder 最前面加 `NetworkGuardInterceptor`（爬虫/API/下载/图片/播放客户端全部受益），无网络时直接抛 `当前无网络,请检查网络连接`，不再发出去白等一次超时。判定口径**故意宽松**（有任何带 `NET_CAPABILITY_INTERNET` 的网络即算有网，不要求"已验证联网"）+ 切换抖动 150ms 复检 + 回环地址放行（本机代理播放不受影响）+ 自身异常一律放行（`OkGoHelper.hasNetwork()` 读不到就当作"有网"）—— 宁可漏拦，不可错杀。
  - **内容页提示与自愈**：`GridFragment` 订阅 `TYPE_NETWORK`，断网显示顶部横幅（新增 `grid_offline_tip`，用主题色 `bg_float`/`text_highlight`，不新造样式），恢复联网自动补一次刷新（仅在"当前可见 + 列表为空 + 不在刷新/加载更多"时触发，避免与用户操作打架）。
  - **日志可判读**：失败日志带解析器与网络状态（形如 `请求失败(系统DNS;网络=无网络;无活动网络)`，见 `OkHttp.dnsName()`/`OkGoHelper.dnsEnvHint()`；后者对"无活动网络/读不到"给出明确原因，不再静默返回空串）；`:log` 的 WARN/ERROR 条目在断网时追加 ` [网络=无网络]`（`internal/NetworkTag`，正常网络下不标注以免噪音），`logcat-*.log` 每建一个新文件写一行 `===== 日志会话 <时间> 网络=<状态> =====`。
  - 文件：`core-network/.../{OkGoHelper,NetworkGuardInterceptor}.java`、`log/.../{LogStore,internal/LogcatCapture,internal/NetworkTag}.java`、`app/.../ui/fragment/GridFragment.java`、`app/src/main/res/{layout/fragment_grid.xml,values/strings.xml}`。**待人工验证**：断网横幅出现、恢复后自动出内容、错误日志页能看到 `网络=无网络`。

- **崩溃页观感修正（2026-09-26，用户反馈）**：`ic_crash.xml` 是 1600×1600 画布而图案只占 x 445~1140 / y 214~1289 —— **底部约 31dp 全是透明**，与文案 16dp 外边距叠加，观感就是"文字离图标太远"；图标底下还有一层 `#d9d9d9` 灰色椭圆底座。已按图案实际范围裁画布（viewport 700×1075、intrinsic 70×107.5dp、`group` 平移对齐；1 单位仍 = 0.1dp，**图形尺寸与裁切前逐像素一致**，只是不再带留白）、删除灰色椭圆、布局里图标改 `wrap_content`（否则 160dp 空盒子会把留白带回来）；整组内容上移约 24dp（ScrollView 底部留白 64dp > 顶部 16dp），内边距按 AGENTS §六挪到 ScrollView 上并加 `clipToPadding=false`。文件：`app/src/main/res/drawable/ic_crash.xml`、`thirdparty/src/main/res/layout/customactivityoncrash_default_error_activity.xml`。**待人工验证**：真机崩溃页的观感（上移量按 `paddingBottom` 一个值即可调）。

- **真机回归:播放器一批缺陷(1×P0 + 4×P1 + 6×P2,2026-09-26)**：回归时逐段读了取流/内核/控制器链路后确认并修复：
  - **P0 Exo 取流客户端从未注入(6e2b1e52 引入的回归)**：该提交删掉了 `App.init` 里的 `initExoOkHttpClient()`,改成"懒建 + 置脏后重建"的 `App.playbackHttpClient()`,但它的**唯一调用点**是 `AppCompositionRoot.network().playback()`,而 `NetworkProvider.playback()` 全仓零调用方 → `ExoMediaSourceHelper.mOkClient` 恒为 null(`getOkClient()` 也零调用点) → 取流时 `OkHttpDataSource` 内部 `checkNotNull(callFactory)` 抛 NPE,**Exo 每次起播即失败**。而且安全 DNS 变更只 `dropOkClient()`、无人重建,所以"补一次启动注入"并不够。改法：`:player` 暴露**客户端提供方** `setOkClientSupplier(Supplier<OkHttpClient>)`,`getHttpDataSourceFactory()` 在 client 为 null 时经提供方自取(懒建/置脏重建都在 `App.playbackHttpClient()` 内,**作废后自愈**),app 在组合根 `AppCompositionRoot.init()` 注册一次;拿不到时给明确的 `IllegalStateException("Exo 播放客户端未注入")` + 日志,不再是一句无从下手的 NPE。
  - **P1-1 播放 UA 被"就地"remove**：`ExoMediaSourceHelper.setHeaders` 与 app `IjkMediaPlayer.setDataSourceHeader` 都在传进来的 map 上 `remove("User-Agent")`,而那份 map 就是 `VideoView` 持有的 `mHeaders` 引用 → 第一次起播正常,`replay()`/错误重试/切线路再次 `setDataSource` 时只剩裸 UA(需要 UA 的源站 403)。改为先 `new LinkedHashMap<>(headers)` 再动。
  - **P1-2 面板切 IJK 解码档位是死的**：切档只改 per-vod 配置 + `replay`,而重播走 `reset() + setOptions()`(**播放器实例不重建**),`IjkMediaPlayer` 却优先读创建时的 codec 快照 → 永远停在首次的值。改法：`PlayerKernels` 增加"本次播放解码档"(`setCurrentCodec/currentCodec`,由 `PlayerHelper.updateCfg` 每次应用播放配置时刷新),`setOptions()` 按"本次播放 → 构造快照 → 全局设置"取值。**注意**：没有采用"每次直接读全局 `getCurrentIJKCode()`",那会破坏既有口径(老剧沿用自己那份 per-vod 解码档),而面板改的正是那份 per-vod 配置。
  - **P1-3 `speed=0` 让进度永久停摆**：控制器 `postDelayed((1000 - pos%1000)/getSpeed())`,内核未就绪时 `getSpeed` 返回 0 → `(long)Infinity = Long.MAX_VALUE` → 进度条与时间不再刷新(不崩,观感像卡住)。两侧兜底：`tv.danmaku...IjkMediaPlayer.getSpeed(float)` 不再忽略入参(拿不到/非正数回 1f)、`IjkPlayer.getSpeed()` 传 1f、控制器侧 `speed<=0` 按 1f 计算。
  - **P1-4 变速轮询在控制器移除后仍自循环**：`VodController` 的 1004(设速度)在非播放态每 100ms 自我重投,而 `onDetachedFromWindow` 只 `removeCallbacks(myRunnable2)` → MessageQueue 里的消息经 Handler 持有 View/Activity,起播失败时还会 100ms 空转。改为两个 handler `removeCallbacksAndMessages(null)`(判空) + `BaseVideoController` 补 `onDetachedFromWindow()` 调 `stopProgress()`。**同类缺陷 `LocalVideoController` 一并修**。
  - **P2(6 项)**：① ijk option 解析整体入 try(源站配置缺 `|`/分类非数字原来直接崩在起播路径),坏 key 跳过并落 `LogStore(Category.PLAYER)`;② IJK 动态库加载失败不再置 `mIsLibLoaded=true`(原来 `ijkffmpeg/ijksdl` 的异常被空吞后照样置位 → 之后永不重试,症状是"IJK 每次播都失败");③"填充"档把 `MeasureSpec` 打包值(`size<<2|mode`)当尺寸返回,而 `TextureRenderView/SurfaceRenderView` 直接 `setMeasuredDimension` → 渲染视图约 4 倍大,改为用解包后的 `width/height`;④ Exo 起播错误改为**按错误码白名单重试**(仅网络瞬时类;403/404、解析、解码、不支持类直接透传 `onError`),去掉 `Log.e("tag--")` 调试残留并把原因写进 LogStore(`PARSING_CONTAINER_UNSUPPORTED` 保留可重试——它是"改用 m3u8 容器再解一次"兜底的另一半,见 `ExoMediaSourceHelper#getMediaSource`);⑤ 空 URL 时 `prepareDataSource()` 返回 false 由 IDLE 改置 `STATE_ERROR`,否则 `release()` 的 `!isInIdleState()` 守卫会把整段清理跳掉,`mMediaPlayer`/`mRenderView`/`AudioFocusHelper` 全泄漏;⑥ m3u8 相对地址拼接对"无路径域名"回退到 `scheme://host[:port]`(原来 `indexOf('/', 9) == -1` → `substring(0, -1)` 抛 `StringIndexOutOfBoundsException`),既有正常输入逐字符不变;⑦ `PlayService` 静态强引用 `MyVideoView` 改 `WeakReference` + 控制指令空转留痕 + 新增 `onHostDestroyed(Context)` 由 `DetailActivity.onDestroy` 调用(后台播放中宿主销毁时不再把 Activity 钉在进程里;**不**把"宿主销毁"当"停止播放",通知栏/`onClose`/`stop()` 既有语义未改)。
  - 文件：`player/.../{exo/ExoMediaSourceHelper,exo/ExoMediaPlayer,player/VideoView,controller/BaseVideoController,ijk/IjkPlayer,tv/danmaku/ijk/media/player/IjkMediaPlayer,render/MeasureHelper}.java`、`app/.../{di/AppCompositionRoot,player/IjkMediaPlayer,player/PlayerKernels,player/controller/VodController,player/controller/LocalVideoController,util/PlayerHelper,ui/fragment/PlayFragment,service/PlayService,ui/activity/DetailActivity}.java`。门禁全绿。**待人工验证**：Exo/IJK 双内核起播与切线路、重播/错误重试后 UA 仍在、面板切解码档与"填充"缩放档、变速后进度刷新、退出播放页无空转、后台播放宿主销毁后通知栏操作。

- **新增独立的"网络不可用"页(2026-09-26,用户需求)**：断网时不再靠内容页的横幅提示,改为整页接管：
  - **触发口径(用户明确要求)**：<b>"断网 + 真的发起了网络请求"才跳页</b>,覆盖两类 —— ① 请求发出前就被判定没网(`NetworkGuardInterceptor` 快速失败);② **请求发到一半断网**(`UnknownHost/ConnectException/NoRouteToHost/SocketException`;读超时只在"确认没网"时才算,免得源站慢就弹页)。通知点放在**网络层**(`:core-network` 新增 `OkGoHelper.NetworkIssueListener`,由上述两处上报,3s 节流),而不在页面侧监听系统状态 —— 否则"只是断网、用户在看本地视频/本地文件"也会被弹一屏。
  - **页面**:`ui/activity/NoNetworkActivity`(独立页面,`launchMode=singleTop`,manifest 未导出)。自己订阅系统网络状态单点 `SystemStateMonitor` 的 `TYPE_NETWORK`,**一有网立即 finish 回到原页面**;拉起瞬间已有网也直接返回(不留"假无网")。两个按钮:`返回`＝直接回原页面;`我知道了`＝回原页面并在**本次断网期间**不再自动弹出(恢复联网自动解除,下次断网照常提示)。
  - **路由**:`util/NetworkIssueRouter`(组合根 `AppCompositionRoot.init()` 装一次)。抑制与节流:网络层 3s 节流 + 路由侧 3s 最小间隔 + 已在无网络页则不重复拉 + **后台不弹**(`SystemState.foreground`,Android 10+ 也禁止后台起 Activity)。
  - **图标/样式**:图标由"网络加载异常.svg"导出为 `drawable/ic_no_network.xml`,本地**去掉底部灰色椭圆底座**并按图案范围裁剪(画布 1600×1600 → 898×976,留白去掉;图形本身不缩放变形)。页面文字/按钮全部走主题资源(`text_main`/`text_sub`/`colorPrimary`/`btn_confirm_text` + 既有共享底 `bg_r_common_solid_primary`/`bg_r_common_stroke_primary`),`theme_colors.json` 改色自动跟随;根部**不设不透明背景**,否则会把 `PageBackgroundView` 的用户自定义背景图盖掉。
  - **同时移除**:内容页(`fragment_grid.xml`/`GridFragment`)的断网横幅(`grid_offline_tip` 已删除);**保留**"恢复联网后自动补一次刷新"(断网期间列表通常是空的,自动出内容不必手动下拉)。
  - 文件:`core-network/.../{OkGoHelper,NetworkGuardInterceptor}.java`、`app/.../{util/NetworkIssueRouter,ui/activity/NoNetworkActivity,di/AppCompositionRoot,ui/fragment/GridFragment}.java`、`app/src/main/{AndroidManifest.xml,res/layout/{activity_no_network,fragment_grid}.xml,res/drawable/ic_no_network.xml,res/values/strings.xml}`。**待人工验证**:断网后发起请求→跳页;恢复网络自动返回;`我知道了`后本次断网不再弹;后台/看本地内容时不弹;主题换色后页面跟随。

  - **贴补(同日,真机反馈后)**：① **按钮改用全局主题按钮样式** `BtnPrimary`/`BtnSecondary`(MaterialButton + 主题 `backgroundTint`/`cornerRadius`/`strokeColor`)—— 原来手写 `android:background` + `textColor` 会被 Material 的主题 tint 盖掉,次按钮出现"文字与底色同色、看不见字";② **判定口径放宽**：有网时仍只认典型断网异常,<b>已经没网时任何 `IOException` 都算</b>(覆盖解析被拦/连接被中间设备重置/读超时等非典型表现);③ **补决策日志**：`NetworkIssueRouter` 现在会打 `网络层报告: <原因>` / `不弹无网络页: <原因>` / `已拉起无网络页(原因: …)`(后者同时进 LogStore) —— 下次"断网了为什么没跳"先看有没有第一行：没有就是请求没走收口客户端或不是网络类失败(实测遇到的是**源站返回空内容**、jar 自己抛 `JSONException: End of input at character 0`,不属于断网)。

  - **贴补二(同日,真机反馈"断网后首页 loading 一直转")**：根因是结束刷新的唯一入口是 `listResult` 观察者,而断网时请求被网络层**快速失败**、异常被上层吞成"无结果" → LiveData 从不发射 → 没人收尾;且断网事件常发生在页面不可见期间(无网络页盖住时 `GridFragment` 已注销网络监听),回到首页也不会补。修法二处:① **断网即收尾** `stopLoadingForOffline()`(结束下拉刷新 + 列表空则显示空态 + 收掉底部"加载中"footer),网络监听回调与"页面重新可见时按当前离线态补一次"都调用它;② **刷新看门狗** `startRefreshWatchdog()` —— 带轮次号的 45s 兜底(长于 VM 侧 typed 15s + 字符串通道 15s 之和,正常慢请求不被打断),到点仍在刷新就强制收尾,兜住其它"没人回结果"的静默失败。

  - **贴补三(同日,真机反馈"离线冷启动后 loading 一直转、恢复网络也刷不出来")**：两条根因 ——
    ① `SourceViewModel.getList` 的 `type==3` 分支 `catch` 里只 `printStackTrace`、**从不 `postValue`**：离线时请求被网络层快速失败并把异常带到那里,首页永远收不到"本轮结束" → `showLoading()` 一直挂着(与"JS Promise 每条路径都要 settle"同一条教训);
    ② "恢复网络"的状态变化通常发生在页面<b>不可见期间</b>(无网络页盖住/切走),而网络监听是可见时注册、不可见时注销 → 回到首页时已错过,没人触发加载。
    修法：① VM 每条路径都收尾(`type==3` 的 catch 与"回退旧路径自身抛异常"两处补 `postValue(null)`);② `GridFragment` 看门狗从"只管下拉刷新"扩到"初始加载 + 下拉刷新"(45s、带轮次号,收到任何结果即作废),到点仍在加载就收尾并显示空态 —— 至少保证用户能重新拉动刷新(此前 loading 视图盖住列表,下拉都点不动);③ 页面重新可见时:有网 + 从未成功加载 + 没有加载在途 → 自动补一次 `initData()`。

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

### 1.12 崩溃/资源审计清单（23 项，重编号）与第一批整改（2026-09-27）

一次性全仓审计（启动即崩 / native / 资源耗尽 / 外部可控 / 兜底机制五类）共 23 项，**统一编号如下**
（此前口头引用过 20/21/23、第 11 条、9→10→12→13，均以本表编号为准）。第一批（启动崩溃循环面）已落地代码，
其余按批推进。

| # | 问题（一句话） | 位置 | 状态 |
|---|---|---|---|
| 1 | 任务归一/对账循环在 try 之外，坏文件里出现 null 任务即 NPE → 开机死循环 | `download/internal/DownloadStore.load()` | ✅ 本批 |
| 2 | 前台服务写成相对名 `.download.X`，解析到不存在的 `...download.download.X` | `download/src/main/AndroidManifest.xml` | ✅ 本批 |
| 3 | minSdk 24 上裸调 API 26 的 `isInPictureInPictureMode()`，7.x 离开播放页必崩 | `DetailActivity`/`LocalPlayActivity`/`PipHelper` | ✅ 本批 |
| 4 | 大图查看器用 XPopup 的 `SmartGlideImageLoader`，而 Glide 依赖已全仓移除 → 点图即崩 | `ui/dialog/VideoDetailDialog` | ✅ 本批 |
| 5 | 崩溃兜底顺序倒挂（QuickJS/P2P 早于 CAOC/LogStore），且 `catch (Exception)` 兜不住 `Error` | `App.onCreate` / `App.getp2p` | ✅ 本批 |
| 6 | `IjkPlayer.release()` 丢进裸线程、不清引用；`TextureRenderView` 未先 `setSurface(null)` 就释放 Surface → native SIGSEGV | `player/ijk/IjkPlayer`、`render/TextureRenderView` | ✅ 第二批 |
| 7 | `SubtitleCoordinator` 的 `postDelayed(800ms)` 无 token、销毁后仍 seekTo | `util/player/SubtitleCoordinator` | ✅ 第二批 |
| 8 | `Global.setTimeout` 失败分支在 Timer 线程 `func.release()` → `checkSameThread()` 抛异常杀进程 | `spider/util/js/Global` | ✅ 第二批 |
| 9 | `assets.open("ua.db")` 与 `FileUtils.getAsOpen` 双流不关（后者在每个 JS 源/每次 import 上） | `spider/util/UA`、`spider/util/FileUtils` | ✅ 第三批 |
| 10 | `JsLoader.spiders` 无上限/LRU：每源 1 个 QuickJSContext + 1 executor + 1 Timer | `spider/catvod/crawler/JsLoader` | ✅ 第三批 |
| 11 | `NativeCleaner.clean()` 全仓从未调用，`hold()` 注册的 phantomReference 只增不减 | `thirdparty/wrapper/NativeCleaner` | ✅ 第三批 |
| 12 | 无 contentLength 上限的响应体（`body().string()` / `.bytes()`）→ 单个超大响应即 OOM | `core-network/util/HttpClient`、`spider/util/js/Connect` | ✅ 第三批 |
| 13 | `PicassoLoad.sessionLoaded` 无上限、无 clear（同文件 `sessionFailed` 有 500 上限 + LRU） | `util/PicassoLoad` | ✅ 第三批 |
| 14 | 全仓 `onTrimMemory`/`onLowMemory` 零命中，上面的累积没有回收兜底 | 全仓 | 未做（见下） |
| 15 | 坏 `tvbox-torrent:` 串 → NumberFormatException；`stop(true)` 已置 null 的列表仍直读 → NPE | `util/thunder/Thunder` | 待后续 |
| 16 | `infoList.get(0).beanList.get(0)` 两级不判（运行在 Thunder 自建线程池、任务体无 try） | `viewmodel/SourceViewModel` | 待后续 |
| 17 | JS 源 `proxy()` 返回 `["200",null]` → ClassCastException；`serve()` 整段无 try | `server/RemoteServer` | 待后续 |
| 18 | 非线程安全集合跨线程读写（WebView 线程 add / 主线程 poll）+ detach 后 `requireActivity()` | `util/player/PlayParseCoordinator` | 待后续 |
| 19 | 进程级单例把 1×1 WebView `addContentView` 到当前 Activity，全类无 remove/destroy；URL 集合普通 HashMap | `util/WebSniffResolver` | 部分修复（见 §1.13 四：空闲 60s 释放 + removeView + destroy + Cookie flush；`loadedUrls` 跨线程读写未动） |
| 20 | 崩溃熔断只看"距上次崩溃 < 2s"，用户点重启到新进程必然超窗 → 熔断永不命中；无计数、无安全模式 | `App.initCrashConfig` | ✅ 本批 |
| 21 | `:error_activity` 进程照跑完整 `App.onCreate`，且该进程不装 CAOC Provider、无兜底 handler | `App.onCreate` | ✅ 本批 |
| 22 | `AppDataManager` 无 `fallbackToDestructiveMigration`，DB 损坏/迁移缺口时启动即崩无退路 | `core-storage/AppDataManager` | 本批有意未动（涉及清库=用户数据，另行确认） |
| 23 | 崩溃只 `insertAllAsync`，紧接着被 `killProcess + System.exit` 带走 → 首次崩溃落不了库 | `log/internal/CrashReporter`(实为 `LogStore` 崩溃 sink) | ✅ 本批 |

**第一批改了什么（本批已落地）**
- **1**：`DownloadStore.load()` 除读盘外的两段（状态归位、磁盘对账）整体入 `try`（与 `DownloadArchive` 口径一致，
  另对逐个任务的 tmpDir 迁移再兜一层），并在装载时**剔除列表里的 null 任务**（Gson 对 `[null]` 会产出 null 元素）。
  基调：启动路径上的本地文件不可信；坏数据最多表现为"任务少了几条"，绝不能让 `onCreate` 抛异常。
- **2**：清单改为全限定类名 `com.github.tvbox.osc.download.internal.DownloadForegroundService`（真实类所在的
  `internal` 包与模块 `namespace` 不同，相对名必然解析错）。
- **3**：新增 `PipHelper.isInPip(Activity)`（`SDK_INT < O` 直接 false + try 兜底），四处调用点统一改走它。
- **4**：新增 `ui/dialog/PicassoImageLoader`（按 XPopup 2.10.0 的 `XPopupImageLoader` 契约用 Picasso 实现：
  `loadImage` 进度圈跟随 + 失败切统一占位、`loadSnapshot` 复用缩略图 drawable 做转场首帧、"保存图片"经共享图片
  客户端下载到 cache 并带 8MB 上限），`VideoDetailDialog` 换用它。
- **5**：`App.onCreate` 改为「`initCrashConfig()` → `initParams()` → `LogStore.installCrashHandler()`」
  最先就位，`PlayerHelper.init()`/`QuickJSLoader.init()` 等全部挪到兜底之后；`App.getp2p()` 的
  `catch (Exception)` 改 `catch (Throwable)`（`P2PClass` 静态块是 `System.loadLibrary("p2p")`，
  so 缺失抛的是 `ExceptionInInitializerError`）。
- **20/21/23**：新增 `base/StartupGuard` 承载三件事——① **错误页进程最小化启动**（读 `/proc/self/cmdline`
  判定 `:error_activity`，只装一个"留痕后安静退出"的兜底 handler，不再跑业务初始化）；② **启动崩溃熔断**
  （同一 5 分钟窗口内连续 **3 次真实崩溃** → 本次进安全模式：跳过 `QuickJSLoader.init()` 的 native
  加载、Toast + 业务日志告知用户；进入后**保持 10 分钟冷却期**，避免"三次崩换一次降级启动、然后又崩"的抖动，
  健康启动清零计数、冷却期一过即恢复正常；计数用 `SharedPreferences.commit()` 同步落盘，同步提交是为了
  进程随时可能死时计数不丢）；③ **崩溃日志阻塞落库**（`LogRepository.insertAllBlocking` →
  `LogCollector.flushNowBlocking`，上限 1.5s，超时只影响日志不影响崩溃处理；写线程自己崩时直接同步写，
  避免自己等自己），并把"分类开关"从 `force` 路径上摘掉——否则用户关掉"系统"分类后崩溃会被静默丢弃。
  崩溃页「详细错误信息」里带上启动计数/安全模式状态。
- 验证（机器侧）：`:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest checkModuleDependencies`
  全绿；新增 JVM 单测 `StartupGuardPolicyTest`（12 条：窗口内累计/窗口外重新计数/时钟回拨不清零/脏负数计数/
  阈值边界/冷却期开启与不续期/冷却期内的健康启动仍走安全模式），合并后清单已核对为
  `com.github.tvbox.osc.download.internal.DownloadForegroundService`（debug + release 均已确认）。
  **真机行为（坏任务文件不再开机死循环、:download 前台服务通知、7.x 离开播放页、点缩略图看大图、
  连续启动失败进安全模式、崩溃后能在日志页看到该次崩溃）一律待人工验证。**

**第二批改了什么（native/时序，本批已落地）**
- **6｜内核释放时序（真机最可能报的闪退）**：三处一起改，核心是"释放 Surface 之前必须先把播放器与 Surface
  解绑"——① `AbstractPlayer` 新增 `detachSurface()`（`setSurface(null)`，在播放器还活着时调用是安全的）
  与模块级共享的串行释放执行器 `releaseAsync()`（原来每次 release 各起一条裸线程，随换源/切集无界涨线程；
  现改为单线程串行，AGENTS §六口径）；② `VideoView.release()`/`addDisplay()` 在 `mRenderView.release()`
  **之前**先 `detachSurface()`（IJK 的 release 是异步的，而 `TextureRenderView.release()` 会立刻释放
  Surface/SurfaceTexture —— 顺序反了就是原生输出线程往已释放窗口写 = SIGSEGV，触发点正是"播放中返回/切集/换源"）；
  ③ `IjkPlayer.release()` 改为"先抓本地引用再异步释放"（原来匿名类里直接读字段，理论上会释放到之后新建的那一个）、
  `catch (Throwable)`（native 释放失败抛的是 Error）；`AndroidMediaPlayer.release()` 同步改用共享执行器 + 解绑 Surface；
  ④ `TextureRenderView.release()` 释放后把 `mSurface`/`mSurfaceTexture`/`mMediaPlayer` **置空**（否则重新 attach 时
  `onSurfaceTextureAvailable` 会拿已释放的 SurfaceTexture 再 `setSurfaceTexture` 一次）。
  `mMediaPlayer` 字段**有意不置空**：app 的 `IjkMediaPlayer` 子类 `setOptions()` 直接读它，置空会让每次
  reset/起播都 NPE；且置空会把"原生侧空指针检查返回默认值"变成 Java NPE。
- **7｜轨道切换后的进度恢复（800ms 延迟任务）**：原来两处 `new Handler().postDelayed(..., 800)` 不可取消、
  任务体在外层 try 之外。现收口为 `SubtitleCoordinator.postTrackRestore()`：可被新增的 `release()` 取消
  （宿主 `PlayFragment.onDestroyView` 调用）、按播放上下文 epoch 失效（800ms 内切集就不再拿旧进度 seek）、
  执行前校验内核仍是当时那一个，任务体自带 try/catch(Throwable)。判定条件抽成纯策略
  `TrackRestoreGuard.shouldRun()` + 单测 5 条。
- **8｜JS 定时器的跨线程释放（外部可控杀进程）**：`Global.setTimeout` 的失败分支（`executor.isShutdown()`、
  `submit` 被拒、`timer` 已 cancel）原来在 Timer 线程上直接 `func.release()`，而
  `JSObject.release → QuickJSContext.freeValue` 有 `checkSameThread()` → 抛出未捕获 `QuickJSException` = 杀进程
  （触发：源用了 setTimeout、此刻源正在销毁）。现改为 `releaseOnJsThread()`：执行器还活着就 hop 回 JS 线程释放，
  已 shutdown 则**不释放**（同一线程上紧接着 `ctx.destroy()`，整块上下文一起回收，跨线程去碰才是崩因）；
  另外给"JS 线程内"的释放加了 `safeRelease()`（finally/回调里的 release 在上下文已销毁时同样会抛，
  逃出 JS 线程任务就是未捕获异常）。`_http` 失败分支、`submitComplete` 两处一并收敛。
- 验证（机器侧）：`:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest checkModuleDependencies`
  全绿；新增单测 `TrackRestoreGuardTest`（5 条）。
  **真机行为（播放中返回/切集/换源不再闪退、切音轨与内置字幕的进度恢复正常、退出播放页 800ms 内不崩、
  批量换订阅/离开播放页时 JS 定时器不再杀进程）一律待人工验证。**

**第三批改了什么（资源耗尽，本批已落地）**
- **9｜fd 泄漏**：`UA.random()` 原来每次请求都 `assets.open("ua.db")` 且**两个流都不关**（catch 分支也不关），
  调用点又正好在豆瓣热门的循环里（每条视频一个 UA）—— 现改为"首次读到内存缓存 + try-with-resources"，
  顺带省掉每次请求重读 670KB 的开销（解析口径未变，只把"取第 N 条"拆成 `uaCount/uaAt` 两个纯函数好单测）；
  `FileUtils.getAsOpen`（JS 源加载热路径：每个源、每次 import 都走）同样改为 try-with-resources，
  并修掉原来 `available()` 估长 + 单次 `read()` 可能把模块文件读短的问题。顺带修了同类的
  `BaseActivity.getAssetText`（该方法是仓内零调用的死代码，一并把资源处理修对）。
- **10｜JS 源实例无上限**：`JsLoader` 增加常驻上限 `MAX_LIVE_SPIDERS = 16` + `LAST_USED` LRU，
  创建新源后按"最久未用的**空闲**实例"回收（`JsSpider.isIdle()`，回收走既有的 `destroy()`：退役 +
  空闲即销毁，晚到调用只会拿到 null 而不是踩已销毁的 QuickJS 上下文）。取 16 是因为聚合搜索/首页会同时
  用到多个源，回收在跑的源会立刻触发秒级重建；真正冷下来的才回收。
- **11｜NativeCleaner 只增不减**：`clean()`（把 GC 回收掉的 hold 引用对应的 `dupValue(+1)` 还回去）
  在原库中从未被调用，于是 `phantomReferences` 只增不减、JS 侧对象也永不释放。现于
  `QuickJSContext.hold()` 与 `QuickJSContext.call()`（都在 JS 线程上）各调一次
  `cleanRecycledRefs()`：无对象可回收时只是一次引用队列 poll，代价可忽略。
- **12｜无界响应体**：新增 `core-network` 的 `HttpBodyReader`（有 Content-Length 先快速拒绝、
  分块传输按累计字节兜底；顺带保持 `string()` 原有的"Content-Type charset + 跳过 UTF-8 BOM"口径），
  `HttpClient` 的异步 GET / `getSync` / `getQuietly` 三处改走文本上限 16MB，JS 源桥
  `Connect.success` 改走二进制上限 24MB（原来的 `body().bytes()` 完全无上限）。两个模块共用同一份实现。
- **13｜海报已加载表无上限**：`PicassoLoad.sessionLoaded` 由无界 Set 改为 1000 条 LRU
  （与同文件 `sessionFailed` 的 500 条 LRU 同口径），淘汰只影响"滑回时是否再扫一次骨架屏"，不影响正确性。
- **14｜未做**：`onTrimMemory/onLowMemory` 仍是零命中。它属于"再加一道兜底"，且要决定回收什么、
  在哪些页面生效（涉及 Picasso 内存缓存与源实例缓存），本批先把各个泄漏源本身修掉。
- 验证（机器侧）：全量门禁全绿；新增单测 `UaDbParseTest`（6 条：按索引取每一条/最后一条边界/单条库/
  越界返回 null/残缺文件降级/上下文未注入走兜底）与 `HttpBodyReaderTest`（9 条：正常读取/跳过 BOM/
  Content-Type charset/缺省 UTF-8/声明超限被拒/分块超限被拒/正好等于上限放行/空体/二进制上限独立生效）。
  另核对 debug 与 release 两个 APK 的 dex 均含本批新增符号（`HttpBodyReader`/`isIdle`/`TrackRestoreGuard`/
  `PicassoImageLoader`/`safe_mode_until`），确认产物是"改完之后"构建的。
  **真机行为（长会话内存与线程数不再单调上涨、几百个源规模不再 Too many open files、超大响应不再 OOM）
  一律待人工验证。**

### 1.13 第二批审计（发热/耗电）整改（2026-09-27）

同一轮审计的第二份清单，主题是"越用越烫/息屏也烫"。**注意这一批与 §1.12 的 23 条是两套编号**，
下面用小节名（二/三/四）指代。

**二｜骨架屏扫光会永久泄漏（已修）**
- `PicassoShimmer`：那个 `ValueAnimator` 是 `INFINITE` 的，而"停止"原先只有 Picasso 的
  onSuccess/onError 一条路 —— 请求被取消（Picasso 取消后不再回调）、延迟启动的 run 在图片已出图之后才跑到、
  宿主被 detach 之后，动画器都会一直按 60fps tick，并通过 `Drawable → Callback` 强引用把 ImageView
  （及其 Activity）一起留住。现补三道自愈：①宿主 detach / 窗口不可见 → 自停；②超过 30s 没人停 → 自停；
  ③自停时摘掉 foreground，断开引用链。另外 `draw()` 里每帧 `new LinearGradient` + 两个颜色/位置数组
  改为**按尺寸缓存渐变 + localMatrix 平移**（像素结果不变，每帧零分配）。
- 两处"孤儿延迟任务"补严：`FastSearchAdapter.loadPoster` 排队新的延迟启动前先 `cancelShimmer`
  （与 PicassoLoad 口径一致），并在 run 内校验"view 仍是这个 URL 且实图还没出图"；
  `PicassoLoad.into` 的 run 内也补了同样的"实图已出图就不再叠扫光"判据。

**三｜卡死的 JS 源线程（能做的都做了，并写清了做不到的部分）**
- 结论先说：**这个 QuickJS 封装（预编译 .so）没有暴露中断接口**。我逐个核对了
  `libquickjs-android-wrapper.so` 的 JNI 入口（只有 createRuntime/createContext/evaluate/call/compile/… ，
  没有 setInterruptHandler 之类；QuickJS 自己的 `JS_SetInterruptHandler` 没被暴露到 Java），
  所以"纯死循环（`while(1);`）"在进程内**没有任何办法真正打断**。原来代码注释里"漏一块 native 内存
  而不是闪退"的选择仍然成立，本批做的是"防止它变得更糟 + 让用户看得见"：
  - `JsSpider.createCtx` 给每个源的 QuickJS 设 **64MB 内存上限**：分配型跑飞（死循环里 push 数据）
    会在 OOM 前被 QuickJS 抛 RangeError 打断，调用正常失败返回，线程得以释放（纯自旋型仍需下面的约束）。
  - `JsSpider.isIdle()` 把**卡死的源排除在 LRU 之外**：卡死源若被回收，下次调用会**重建**实例 ——
    新线程 + 新运行时，而旧的死循环线程并不会因此停下，等于多烧一个核。留在缓存里，后续调用命中
    `wedged` 直接返回 null，不再新建、也不再堆任务。
  - 卡死登记给 UI（`SpiderFaults.markUnavailable(siteKey, "该源脚本卡死(疑似死循环)…")`）：
    页面给出说法而不是一直"暂无数据"，用户能据此换源；`cancelByTag()`（搜索页销毁/重载订阅）时撤掉登记，
    作为一次"复活尝试"。销毁路径的超时不登记（那只是我们自己发起的销毁在等 JS 线程，误报会把好源说成坏源）。
  - `destroyNow` 的注释写清代价：销毁等不到 → 那条 `js-spider-*` 线程会一直占一个核直到进程结束，
    重载订阅只会换新实例、不会停它。
- **待决策的根治方向**（都不在本批：需要真机回归或架构改动）：①给这个 wrapper 补一个
  `setInterruptHandler` JNI 入口（需要上游源码 + NDK 重编 .so）；②把 JS 源跑在独立进程里，
  卡死时直接杀进程重启该进程。

**四｜次要放大项**
- `LogcatCapture`（写路径三处）：`batch` 原来从不 `clear` —— 凑满 100 行后**每来一行都把整批重写一遍**，
  写入量 O(n²) 且同一批内容在文件里被重复追加；现改为"写入成功才清空，失败保留重试（待重试行数有上界）"。
  目录清理（list + 逐个 stat + 排序）从"每次追加"改为**60s 节流**（滚动检查仍每次做，8MB 上限不变）。
  写失败原来用 `Log.e` 上报，而捕获读的正是本应用 E 级流 → 自放大回环；现降到 `Log.w` + 一次性守卫。
  （`LogcatCapture.stop()` 仍零调用，属"错误日志常驻"的有意设计，未动。）
- `DownloadManager`：进度落盘原来跟着 450ms 广播窗口走（整表 JSON + 写 .tmp + rename，≈2.2 次/秒）；
  现改为**2s 间隔 + 进度指纹**两道闸（指纹覆盖进度回调会改的全部字段），结构性变更（入队/暂停/完成/删除/
  改地址）仍走各自的强制 `persist()` 立即落盘。代价：进程被杀最多丢约 2s 的进度计数，
  且续传启动还会用 `.part` 实际长度与磁盘分片对账，实际重下量极小。
- `WebSniffResolver`：1×1 嗅探 WebView 原来"只重置不销毁"，它被 `addContentView` 挂在当时的 Activity 上，
  等于把那个 Activity 钉在进程级单例上、页面 JS 也一直留着；现改为**空闲 60s 释放**（`removeView` → `destroy`，
  销毁前 `CookieManager.flush()` 保住登录会话），新一轮嗅探开始前撤掉释放计时，一批连续嗅探的复用不受影响
  （真被销毁了 `ensureWebView()` 也会按需重建）。
- `PosterPlaceholderDrawable`：占位是海报的背景层，扫光每帧重绘都会走到"覆盖条避让高度"的判定
  （`indexOfChild` + 遍历兄弟量几何 = 每帧遍历视图树）。现按"宿主几何 + 每个兄弟的身份/可见性/几何"做键缓存，
  键没变直接复用上次结果；判定规则与结果完全不变（AGENTS §七 的覆盖条识别口径逐条保留）。
- `ScrollThumbIndicator`：滚动指示条原来每帧 `setLayoutParams`（一动就 requestLayout = 整个弹窗每帧重新布局）；
  现改为**几何真的变了才设**。
- `PageBackgroundView`：全屏底图 + 单独一层全屏 scrim 子视图 → 改为在 `dispatchDraw` 里同一次绘制画遮罩色
  （少一个全屏子视图的测量/布局/绘制与其 RenderNode；绘制次序仍是"图片之上"，像素等价）。
- `UpdateBubbleView.onDraw`：每帧 `getResources().getDisplayMetrics()`（下载中气泡持续重绘）→ 改为缓存密度、
  attach 时刷新。（这条是"少调一次 getResources"的确定性收益，与下面对 `getResources()` 本身的判定无关。）
- **跑马灯（`MarqueeTextView`/`RoundChip`）：核对后判定"无需改"（原判断不成立）**。
  API 34 的 `TextView` 源码里 `startMarquee()` 要求 `isAggregatedVisible()`，而
  `onVisibilityAggregated`/`onFocusChanged`/`onWindowFocusChanged` 都会 start/stop，
  `View.dispatchDetachedFromWindow()` 会触发 `onVisibilityAggregated(false)` ——
  即**不可见/脱离窗口时框架自己就停了**，重新可见再启；`RoundChip` 内层还带默认重复次数上限。
  唯一残留是"VISIBLE 但被非回收型容器滚出视口仍算 aggregated-visible"，属框架口径，非本类引入。
- **`BaseActivity.getResources()`：核对后判定"原判断不成立"，未改**。`ThemeResources.syncFrom()` 的第一行是
  `latest == this.base` 的**引用比较**——只要 `ContextThemeWrapper` 缓存住 `mResources`（API 34 源码：
  资源建好后 `applyOverrideConfiguration` 直接抛异常，说明实例是稳定的），热点路径上就**没有**锁、
  没有 Configuration/DisplayMetrics 读取、也没有 equals，只是一次引用比较。另外"只在配置变化时才同步"的
  缓存在这里**并不安全**：`Configuration`/`DisplayMetrics` 会被 `Resources.updateConfiguration` 原地改写，
  按对象身份做键会漏掉真实配置变化，而那个 `cur.equals(now)` 恰恰是唯一安全的变更检测。
  → 结论：**先不改**。若真机 profiler 仍显示这里占帧时间，再按"记录上一次实测到的 Configuration 内容指纹
  （而非对象身份）+ 复用同一个 theme 资源实例"的方案做，并需要真机复核换肤/字号/日夜切换后的观感。

- 验证（机器侧）：`:app:assembleDebug :app:testDebugUnitTest` 全绿；新增单测
  `DownloadProgressSignatureTest`（6 条：无变化不落盘/字节变化落盘/分片进度落盘/状态与合并文案落盘/
  表结构变化落盘/null 项不抛异常）。
  **真机行为（长时间下载与长时间浏览时的发热与电量、息屏后是否仍有持续 CPU、卡死源是否给出"脚本卡死"说法、
  扫光在图片已出图/页面离开后是否彻底停、滚动指示条与占位图观感不变）一律待人工验证。**

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
