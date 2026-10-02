<#
.SYNOPSIS
  用 Gitee 令牌配置「从 GitHub 拉取」的仓库镜像(Pull 方向),让 Gitee 自己同步代码。

.DESCRIPTION
  为什么用脚本而不是网页点:网页要登 Gitee、绑 GitHub、再填一条 GitHub 私人令牌;
  用开放接口一次调用即可,且可重复执行(幂等:已配置会返回既有镜像)。

  官方依据:https://help.gitee.com/repository/settings/sync-between-gitee-github
  该文档同时写明:镜像**只同步 分支/标签/提交,不含 Releases 与附件** —— 发行版附件另用
  scripts/sync-gitee-release.ps1 本机补传(CI 从 Azure 直连上传已实测不可靠)。

  两个方向别搞混:
    - Pull(本脚本):Gitee **从 GitHub 拉** —— 推荐,走 Gitee 对 GitHub 的优化通道。
    - Push        :Gitee 往 GitHub 推 —— 本项目不需要。

.PARAMETER TokenFile
  存放 **Gitee 私人令牌** 的文件(UTF-8,首尾空白自动去除;令牌需含 projects 权限)。
  不填则交互式输入(不回显)。

.PARAMETER GitHubTokenFile
  存放 **GitHub 私人令牌** 的文件。拉取公开仓库时可为空;镜像私有仓库或想让 Gitee 自动建 webhook
  (勾选「自动从 GitHub 同步仓库」)时必填,且 GitHub 令牌需含 repo(自动建 webhook 还需 admin:repo_hook)。

.PARAMETER AutoSync
  勾选「自动从 GitHub 同步仓库」(由 Gitee 在 GitHub 侧自动建 webhook 触发)。
  需要 GitHubTokenFile 且该令牌含 admin:repo_hook 权限。

.PARAMETER GiteeOwner / GiteeRepo / GitHubOwner / GitHubRepo
  坐标,默认 CnAyo/MBox ← CNShanJu/MBox。

.PARAMETER ListOnly
  只列出现有镜像配置,不改动任何东西(排查用)。

.EXAMPLE
  # 先看现状(不写)
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-gitee-mirror.ps1 -ListOnly

  # 配置 Pull 镜像(手动同步;网页点「更新」或本地 push 后自行同步)
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-gitee-mirror.ps1

  # 配置 Pull 镜像 + 自动同步(需 GitHub 令牌含 admin:repo_hook)
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-gitee-mirror.ps1 -AutoSync -GitHubTokenFile <路径>

.NOTES
  编码:本文件必须存为 **UTF-8 with BOM**(PS 5.1 按系统代码页读无 BOM 的 .ps1,中文会全乱)。
  安全:令牌只从文件/交互输入读取,不写进命令行参数,也不打印内容。
#>
[CmdletBinding()]
param(
  [string]$TokenFile,
  [string]$GitHubTokenFile,
  [switch]$AutoSync,
  [switch]$ListOnly,
  [string]$GiteeOwner = 'CnAyo',
  [string]$GiteeRepo = 'MBox',
  [string]$GitHubOwner = 'CNShanJu',
  [string]$GitHubRepo = 'MBox'
)

$ErrorActionPreference = 'Stop'

try {
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
  $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "    OK   $Text" -ForegroundColor Green }
function Write-Bad([string]$Text) { Write-Host "    失败 $Text" -ForegroundColor Red }
function Write-Warn2([string]$Text) { Write-Host "    注意 $Text" -ForegroundColor Yellow }

function Read-Token([string]$Path, [string]$Prompt) {
  if ($Path) {
    if (-not (Test-Path $Path)) { Write-Bad " 令牌文件不存在:$Path"; exit 1 }
    return (Get-Content $Path -Raw -Encoding UTF8).Trim()
  }
  Write-Host "    $Prompt(输入不回显):" -ForegroundColor DarkGray
  $sec = Read-Host -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try { return ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)).Trim() }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$api = "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo"
$ghUrl = "https://github.com/$GitHubOwner/$GitHubRepo.git"

Write-Step "Gitee 令牌"
$token = Read-Token -Path $TokenFile -Prompt '粘贴 Gitee 私人令牌'
if ([string]::IsNullOrWhiteSpace($token)) { Write-Bad ' 令牌为空。'; exit 1 }
Write-Ok "已读取(长度 $($token.Length),不显示内容)"

# 列出现有镜像(同时用于验证令牌)
Write-Step '读取现有镜像配置'
$existing = @()
try {
  $existing = Invoke-RestMethod -Uri "$api/remote_mirrors?access_token=$token" -TimeoutSec 60 -ErrorAction Stop
  if (@($existing).Count -eq 0) {
    Write-Ok '当前没有任何镜像配置'
  } else {
    foreach ($m in @($existing)) {
      $dir = if ($m.mirror_direction -eq 'pull') { 'Pull(Gitee 从 GitHub 拉)' } else { 'Push(Gitee 推到 GitHub)' }
      Write-Ok "已有镜像: $dir  $($m.url)  自动=$($m.auto_sync)"
    }
  }
} catch {
  Write-Bad " 读取镜像配置失败(HTTP $($_.Exception.Response.StatusCode.value__))"
  Write-Host '        - 401:令牌错误或已过期' -ForegroundColor DarkGray
  Write-Host '        - 403:令牌缺 projects(仓库读写)权限' -ForegroundColor DarkGray
  exit 1
}

if ($ListOnly) {
  Write-Host ''
  Write-Step '结论'
  Write-Ok '仅列出现状(-ListOnly,未做任何写操作)'
  exit 0
}

# 已存在 Pull 镜像就不重复建(该接口不保证幂等)
$dup = @($existing | Where-Object { $_.mirror_direction -eq 'pull' })
if ($dup.Count -gt 0) {
  Write-Host ''
  Write-Step '结论'
  Write-Ok "Pull 镜像已存在,未重复创建:$($dup[0].url)"
  Write-Host '        如需立刻同步一次:到 Gitee「仓库镜像管理」点该镜像的「更新」按钮。' -ForegroundColor DarkGray
  exit 0
}

$ghToken = ''
if ($GitHubTokenFile) {
  $ghToken = Read-Token -Path $GitHubTokenFile -Prompt '粘贴 GitHub 私人令牌'
}
if ($AutoSync -and [string]::IsNullOrWhiteSpace($ghToken)) {
  Write-Warn2 '-AutoSync 需要 GitHub 私人令牌(含 admin:repo_hook)才能自动建 webhook;将退回手动同步模式。'
  $AutoSync = $false
}

Write-Step "创建 Pull 镜像:$ghUrl -> $GiteeOwner/$GiteeRepo"
$body = @{
  access_token     = $token
  password         = $ghToken
  mirror_direction = 'pull'
  url              = $ghUrl
  auto_sync        = if ($AutoSync) { 'true' } else { 'false' }
}
try {
  $res = Invoke-RestMethod -Uri "$api/remote_mirrors" -Method Post -TimeoutSec 90 -ErrorAction Stop -Body $body
  Write-Ok "已创建:id=$($res.id)  方向=$($res.mirror_direction)  自动同步=$($res.auto_sync)"
  Write-Host ''
  Write-Step '后续'
  Write-Host '    - Gitee 会在你 push 到 GitHub 后按镜像策略同步;手动模式到「仓库镜像管理」点「更新」即可。' -ForegroundColor DarkGray
  Write-Host '    - 镜像只同步代码/标签,不含发行版附件;发版后仍须用 sync-gitee-release.ps1 本机补传。' -ForegroundColor DarkGray
  Write-Host '    - 最短同步间隔 5 分钟;连续 5 次失败会被强制停止。' -ForegroundColor DarkGray
} catch {
  Write-Bad " 创建失败(HTTP $($_.Exception.Response.StatusCode.value__))"
  Write-Host '        - 403:令牌缺 projects 权限,或该仓库未与 Gitee 账号绑定' -ForegroundColor DarkGray
  Write-Host '        - 400:url 不含 .git,或 auto_sync=true 但未提供有效 GitHub 令牌' -ForegroundColor DarkGray
  Write-Host '        兜底:直接用 Gitee 网页「管理 → 仓库镜像管理 → 添加镜像」配置 Pull 方向。' -ForegroundColor DarkGray
  exit 1
}
