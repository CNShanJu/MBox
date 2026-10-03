# MBox

> 仓库/工程名:MBox(原名 TVBoxOS-Mobile / TVBoxMobile,更名后旧地址由 GitHub 301 重定向到新仓库)

基于 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 的 TVBox 点播/直播应用:多源订阅、JS 爬虫、内置下载、HLS 合并、字幕、局域网遥控。

> 精力有限,未必会及时维护,仅用于学习。

## 功能特性

- **多源订阅**:远程 JSON 配置,兼容 BOM/注释/图片+base64/AES 等非标准套路(见 `doc/接口解析.md`);支持本地 `.txt`/`.json` 文件导入(SAF `content://` 流)
- **点播 / 直播 / 快捷搜索**:多线路选集、EPG、倍速、字幕;DLNA 投屏代码已接入,待用户真机回归。直播源基于 [vbskycn/iptv](https://github.com/vbskycn/iptv)
- **内置下载**:并发调度(1-3)、断点续传、HLS 分段下载及合并(符合条件时封装 MP4)、磁盘空间预检、断网自动续传、来源/剧名分目录;无公共目录权限时可用应用私有目录
- **自适应卡片**:剧集卡片统一 宽:高 = 3:4,列数随屏幕宽度自适应(单卡 ≤190dp),大屏旋转自动刷新
- **主题**:内置浅色/深色与自定义主题,运行时调色板统一供色;视频画面上的播放控件使用固定配色,带主题面板底的控件跟随主题
- **局域网服务**:默认仅本机回环(订阅/本地播放/爬虫代理/m3u8 代理)。在「设置 → 局域网服务」二级页开启后重启应用,可查看运行状态、复制访问地址、管理配对设备并导入导出配置;远程管理和文件访问需设备配对。确认关闭后立即停止对外监听,本机回环服务继续运行
- **检查更新**:GitHub Releases 检测,APK 依次尝试 Gitee 发行版镜像、GitHub 加速代理、GitHub 直连
- **分享与导入导出**:在线、局域网与本地文件传输由 `:share` 模块统一提供
- **崩溃兜底 + 运行日志**:崩溃页与 logcat 完整捕获查看(设置开关)

## 构建环境与依赖版本

| 项 | 当前版本 | 配置来源 |
| --- | --- | --- |
| JDK | 17 | `.github/workflows/build-apk.yml` |
| Gradle | 8.4 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin（application / library） | 8.2.2 | 根目录 `build.gradle` |
| Kotlin Android 插件 | 1.9.22 | 根目录 `build.gradle` |
| 应用版本 / versionCode / debug 展示版本 | 3.6.5 / 74 / 3.6.4 | `app/app_config.properties` |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 | 各模块 `build.gradle` |
| Java source / target、Kotlin JVM target | 1.8 | 各模块 `build.gradle` |
| GitHub Actions（checkout / setup-java / setup-gradle / upload-artifact / action-gh-release） | v4 / v4 / v4 / v4 / v2 | `.github/workflows/build-apk.yml` |

以下为各模块 `build.gradle` **直接声明**的依赖版本；同一依赖在不同模块声明了不同版本时分别列出。

| 依赖 | 声明版本 |
| --- | --- |
| AndroidX Annotation | 1.3.0 |
| AndroidX Core / Core KTX | Core 1.6.0；Core KTX 在 `:app` 为 1.6.0、`:thirdparty` 为 1.3.1 |
| AndroidX AppCompat | `:app` 1.3.0；`:thirdparty` 1.3.1 |
| AndroidX ConstraintLayout / SwipeRefreshLayout / RecyclerView / MultiDex | 2.0.4 / 1.0.0 / 1.2.1 / 2.0.1 |
| AndroidX Lifecycle（ViewModel / LiveData / Runtime KTX） | 2.6.2 |
| AndroidX Room（Runtime / Compiler） / DataStore Preferences RxJava3 | 2.5.2 / 1.0.0 |
| AndroidX Media3（ExoPlayer / DASH / HLS / RTSP / RTMP） | 1.4.1（全部一致） |
| Kotlin Stdlib JDK 8 | 1.9.22 |
| Material Components / Picasso / Lottie / BlurView | 1.9.0 / 2.71828 / 6.7.1 / 1.6.3 |
| DKPlayer UI / TVRecyclerView / BaseRecyclerViewAdapterHelper | 3.3.5 / 3.0.0 / 2.9.45-androidx |
| OkHttp（核心 / Logging Interceptor / DNS over HTTPS） | 4.12.0（全部一致） |
| Gson / Jsoup / XStream / Commons IO | 2.10.1 / 1.14.1 / 1.4.20 / 2.11.0 |
| NanoHTTPD / ZXing Core / juniversalchardet / android-retrofuture | 2.3.1 / 3.4.1 / 1.0.3 / 1.7.4 |
| LoadSir / AndroidAutoSize / XXPermissions / ImmersionBar | 1.3.8 / v1.2.1 / 13.6 / 3.2.2 |
| TitleBar / XPopup / utilcodex / ShadowLayout / ExpandableTextView | 10.5 / 2.10.0 / 1.31.0 / 3.3.3 / v1.6.1-x |
| JUnit（JVM 单测） | 4.13.2 |
| 本地 JAR：Commons Lang 3 / dec | 3.12.0 / 0.1.2（以文件名标识） |
| 本地 JAR：thunder；内置 TabLayout、CustomActivityOnCrash | 仓库未记录可核实的上游版本 |
| 内置 QuickJS | `:thirdparty` 内的源码及预编译 `.so`；仓库引入提交 `4f80c383`，未记录可核实的上游版本 |

> `local.properties`(本机 SDK 路径)不入库,首次构建需用 Android Studio 打开或自行配置。

## 本地构建

Windows:

```bat
set JAVA_HOME=D:\path\to\jdk17
gradlew.bat assembleRelease
```

macOS / Linux:

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleRelease
```

产物路径:`app/build/outputs/apk/release/`(当前版本示例:`MBox_v3.6.5_release_YYYYMMDD.apk`)。debug 包显示 `app/app_config.properties` 中明确配置的上一发布版本(`debugVersionName`),不按正式版本号的末段推算。
应用名/版本号/图标统一在 `app/app_config.properties` 维护,改完重新构建即可。

## GitHub Actions 打包

仓库内置工作流 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml),在 GitHub 上即可出包:

1. 推送代码到 GitHub(`main`/`master` 分支或 `v*` 标签自动触发;也可进 **Actions → Build APK → Run workflow** 手动触发)
2. 构建完成后在本次运行的 **Artifacts** 区下载 APK

签名与包名说明:

- **包名(applicationId)** 为专属包名 `com.github.tvbox.osc.mbox`,与 TVBox 系开源应用通用的 `com.github.tvbox.osc` 区分开,可与设备上已安装的其他开源 TVBox 应用**共存安装、互不冲突**。
- **正式签名**为本项目专属 `mbox-release.jks`(2025 年新建,仓库根目录,**不入库**;不再使用 TVBox 开源圈流传/曾泄露的 `TVBoxOSC.jks`)。本地构建读取 `app/keystore.properties`(不入库);CI 通过 GitHub Secrets(`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`)注入。非 tag 构建缺密钥时退回 debug 签名(仅测试用);`v*` 正式版本 tag 缺密钥时中止发布。
- ⚠️ 换包名+换签名后,新包是"全新应用":不会覆盖/升级旧包名安装的版本(旧数据在新包内从零开始);请妥善备份 `mbox-release.jks` 与口令(存于 `app/keystore.properties`),丢失将无法再升级已发布的正式包。

## 模块结构

按 `改进.txt` 目标架构分层,依赖自上而下:

```
:app / feature
    ↓
业务契约(spiderapi/player 契约包 + DownloadFacade + ShareFacade,现与实现同模块)
    ↓
业务实现: :spider、:player、:download、:share
    ↓
基础设施: :core-network、:core-storage、:log、:common(工具+共享模型+系统状态)
    ↓
纯模型: :common 的 com.github.tvbox.osc.bean.*
```

**现状 10 个模块**:`:app`、`:common`、`:core-network`、`:core-storage`、`:log`、`:player`、`:spider`、`:download`、`:share`、`:thirdparty`。
`:spider`、`:player`、`:download`、`:share` 在各自模块内提供公开契约。`:common` 包含共享模型、工具和系统状态;`:thirdparty` 收纳 TabLayout、CustomActivityOnCrash 与 QuickJS 源码及本地库;主题和通用 UI 资源位于 `:app`。模块边界由 `checkModuleDependencies` 门禁守护(见 AGENTS.md §二)。

## 目录结构与文档

- `doc/` — 项目文档:
    - [项目目录结构.md](doc/项目目录结构.md) — 模块/包/关键类总览
    - [项目状态速查.md](doc/项目状态速查.md) — 版本、主题、组件、功能、已知注意速查
    - [接口解析.md](doc/接口解析.md) — 订阅解析机制与排查经验
    - [直播说明.md](doc/直播说明.md) — 直播源说明(基于 [vbskycn/iptv](https://github.com/vbskycn/iptv))
    - [Git提交规范.md](doc/Git提交规范.md) — 提交信息格式要求

## 致谢

- 上游:[q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)
- 推荐使用:[takagen99/Box](https://github.com/takagen99/Box)、[FongMi/TV](https://github.com/FongMi/TV)

## License

见 [LICENSE](LICENSE)
