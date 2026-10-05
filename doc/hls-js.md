# 网页 HLS 播放库

- 版本：Hls.js 1.7.3，官方 UMD light 构建。
- 发布页：https://github.com/video-dev/hls.js/releases/tag/v1.7.3
- 下载地址：https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.light.min.js
- 仓库文件：`app/src/main/res/raw/hls_js.txt`，未修改上游脚本。
- SHA-256：`0251332c00a216a35d7d6919044d60da82b78beb3bd50ac6c662ae74a2f5474b`
- 许可证：`app/src/main/assets/licenses/hls-js-LICENSE.txt`（Apache 2.0）。

2026-10-05 从 1.5.17 更新，针对上游 #6627 的 AAC 配置猜测错误及 #7667 的 implicit HE-AAC 兼容修复。升级不能替代实际音频失真回归；当前现场没有可用于复现的音频样本。

离线验证：

```text
node app/src/test/js/hls-aac-config.test.js
node app/src/test/js/cast-push-retry.test.js
```

第一项检查实际发行脚本的 API、AAC-LC 与 implicit HE-AAC 配置，以及 UA/CODECS 不再影响 AAC-LC 封装；第二项覆盖投屏控制、会话清理、进度恢复与手动重载。

人工回归：同一来源长播、投屏时恢复手机进度、前后拖动、暂停后重载、换集、网页主动断开。声音仍异常时点击网页“重载播放”，并查看应用日志里的 `audio_config`、`media_rate`、`user_reload`；诊断只包含编码/采样率/声道/进度，不包含播放地址或令牌。light 构建继续不提供备用音轨/字幕控制器。
