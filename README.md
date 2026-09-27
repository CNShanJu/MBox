# MBox

> 仓库/工程名:MBox(原名 TVBoxOS-Mobile / TVBoxMobile,更名后旧地址由 GitHub 301 重定向到新仓库)

基于 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 的 TVBox 点播/直播应用:多源订阅、JS 爬虫、内置下载、HLS 合并、字幕、局域网遥控。

> 精力有限,未必会及时维护,仅用于学习。

## 功能特性

- **多源订阅**:远程 JSON 配置,兼容 BOM/注释/图片+base64/AES 等非标准套路(见 `doc/接口解析.md`);支持本地 `.txt`/`.json` 文件导入(SAF `content://` 流)
- **点播 / 直播 / 快捷搜索**:多线路选集、EPG、倍速、字幕、投屏桩;直播源基于 [vbskycn/iptv](https://github.com/vbskycn/iptv)
- **内置下载**:并发调度(1-5)、断点续传、m3u8 分段下载合并 mp4、磁盘空间预检、断网自动续传、来源/剧名分目录;无存储权限时弹授权引导
- **自适应卡片**:剧集卡片统一 宽:高 = 3:4,列数随屏幕宽度自适应(单卡 ≤190dp),大屏旋转自动刷新
- **主题**:浅色/深色两套,构建期由 JSON 生成颜色资源,界面配色统一走 `@color/*`;播放控制抽屉固定深色白字
- **局域网服务**:默认仅本机回环(订阅/本地播放/爬虫代理/m3u8 代理);开启后局域网设备可访问 web 控制台与文件共享,管理型请求带进程令牌鉴权。开启时设置页会弹出**访问地址**(形如 `http://192.168.x.x:9978/`,可复制)并提示是否还需重启,之后长按「局域网服务」标题随时可再看
- **检查更新**:GitHub Releases 检测,APK 下载代理优先、直连兜底
- **崩溃兜底 + 运行日志**:崩溃页与 logcat 完整捕获查看(设置开关)

## 构建环境(核心版本)

| 项                         | 版本                                                                                |
| -------------------------- | ----------------------------------------------------------------------------------- |
| **JDK**                    | **17**(AGP 8.x 必需)                                                                |
| **Gradle**                 | **8.4**(Wrapper 自带,见 `gradle/wrapper/gradle-wrapper.properties`)                 |
| **Android Gradle Plugin**  | **8.2.2**                                                                           |
| **Kotlin**                 | **1.9.22**                                                                          |
| **compileSdk / targetSdk** | **34**                                                                              |
| **minSdk**                 | **24**                                                                              |
| Java 兼容级别              | 1.8(source/target)                                                                  |
| 关键依赖                   | AndroidX、Material 1.9.0、OkHttp 4.12.0、ExoPlayer 2.18.7、Picasso 2.71828、QuickJS |

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

产物路径:`app/build/outputs/apk/release/`(如 `MBox_v3.5.5_release_YYYYMMDD.apk`;debug 包版本号末段 -1,便于自测更新)。
应用名/版本号/图标统一在 `app/app_config.properties` 维护,改完重新构建即可。

## GitHub Actions 打包

仓库内置工作流 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml),在 GitHub 上即可出包:

1. 推送代码到 GitHub(main 分支或 `v*` 标签自动触发;也可进 **Actions → Build APK → Run workflow** 手动触发)
2. 构建完成后在本次运行的 **Artifacts** 区下载 APK

签名与包名说明:

- **包名(applicationId)** 为专属包名 `com.github.tvbox.osc.mbox`,与 TVBox 系开源应用通用的 `com.github.tvbox.osc` 区分开,可与设备上已安装的其他开源 TVBox 应用**共存安装、互不冲突**。
- **正式签名**为本项目专属 `mbox-release.jks`(2025 年新建,仓库根目录,**不入库**;不再使用 TVBox 开源圈流传/曾泄露的 `TVBoxOSC.jks`)。本地构建读取 `app/keystore.properties`(不入库);CI 通过 GitHub Secrets(`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`)注入,未配置时产物退回 debug 签名(仅测试用)。
- ⚠️ 换包名+换签名后,新包是"全新应用":不会覆盖/升级旧包名安装的版本(旧数据在新包内从零开始);请妥善备份 `mbox-release.jks` 与口令(存于 `app/keystore.properties`),丢失将无法再升级已发布的正式包。

## 模块结构

按 `改进.txt` 目标架构分层,依赖自上而下:

```
:app / feature
    ↓
业务契约(spiderapi/player 契约包 + DownloadFacade,现与实现同模块)
    ↓
业务实现: :spider、:player、:download
    ↓
基础设施: :core-network、:core-storage、:log、:common(工具+共享模型+系统状态)
    ↓
纯模型: :common 的 com.github.tvbox.osc.bean.*
```

**现状 9 个模块**:`:app`、`:common`、`:core-network`、`:core-storage`、`:log`、`:player`、`:spider`、`:download`、`:thirdparty`。
契约模块已回并业务模块(`:spider-api`→`:spider`、`:player-api`→`:player`),原 `:core-model`/`:core-utils`/`:state` 已并入 `:common`,`:crash`/`:TabLayout`/`:ViewPager1Delegate`/`:quickjs` 已并入 `:thirdparty`(第三方归堆:TabLayout + CustomActivityOnCrash 崩溃页 + QuickJS JS 引擎),主题/通用资源(原 `:ui-common`)已并入 `:app`。边界改由 `checkModuleDependencies` 源码层门禁守护(见 AGENTS.md §二)。

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
