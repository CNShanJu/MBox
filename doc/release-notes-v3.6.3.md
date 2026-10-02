<!-- dev -->
本版本**没有任何用户可感知的功能/修复改动**:v3.6.2..v3.6.3 全部提交均为发包与镜像同步的工具/流程改动
(见下方提交记录),不涉及 `app/` 业务代码。按 AGENTS §九.2「阈值豁免无效」,用户可见部分不编造内容。

本次发布的主要目的是**打通并验证 CI 把正式包同步到 Gitee 镜像的链路**:该步骤过去因仓库没有配置
Gitee 令牌而长期静默跳过(步骤照样显示 success),导致镜像页只有手工上传的版本。
本版本改用 Secret `GITEE_MBOX_TOKEN`,并在缺失时改为打印 `::error` 而不是 `::notice`。

验收方式(发版后必做):
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1 -Tag v3.6.3

对用户的实际影响:App 的「检查更新」在国内会优先命中 Gitee 镜像附件,下载速度明显快于加速代理。
<!-- /dev -->
