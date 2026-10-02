<#
.SYNOPSIS
  检查 Gitee「从 GitHub 拉取」镜像(Pull 方向)的配置状态,并给出准确的手工配置指引。

.DESCRIPTION
  **重要实测结论(2026-10-02):Gitee 的仓库镜像配置没有开放 v5 API,只能到网页配置。**
  证据(同一枚有效令牌下对照):
    - `POST /repos/{o}/{r}/releases`            -> HTTP 400(接口可用,只是参数不全)
    - `GET/POST /repos/{o}/{r}/remote_mirrors`  -> HTTP 404 / 405(无此路由 / 方法不允许)
  所以本脚本**不能**替你建镜像,它只做两件事:
    1. 用令牌确认身份与仓库可访问(顺带确认令牌可用);
    2. 打印准确的手工配置步骤与校验方式,而不是让你去猜。

  官方依据:https://help.gitee.com/repository/settings/sync-between-gitee-github
  该文档同时写明:镜像**只同步 分支/标签/提交,不含 Releases 与附件** ——
  发行版附件必须用 scripts/sync-gitee-release.ps1 本机补传(CI 从 Azure 直连上传已实测不可靠)。

.PARAMETER TokenFile
  存放 Gitee 私人令牌的文件(UTF-8,首尾空白自动去除)。不填则交互式输入(不回显)。
  仅用于"确认身份"这一步,本脚本不做任何写操作。

.PARAMETER GiteeOwner / GiteeRepo / GitHubOwner / GitHubRepo
  坐标,默认 CnAyo/MBox ← CNShanJu/MBox。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-gitee-mirror.ps1 -TokenFile <路径>

.NOTES
  编码:本文件必须存为 **UTF-8 with BOM**(PS 5.1 按系统代码页读无 BOM 的 .ps1,中文会全乱)。
#>
[CmdletBinding()]
param(
  [string]$TokenFile,
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

$giteeUrl = "https://gitee.com/$GiteeOwner/$GiteeRepo"
$mirrorPage = "$giteeUrl/settings#mirror"
$ghUrl = "https://github.com/$GitHubOwner/$GitHubRepo"

if ($TokenFile) {
  if (-not (Test-Path $TokenFile)) { Write-Bad " 令牌文件不存在:$TokenFile"; exit 1 }
  $token = (Get-Content $TokenFile -Raw -Encoding UTF8).Trim()
} else {
  Write-Host '    粘贴 Gitee 私人令牌(输入不回显):' -ForegroundColor DarkGray
  $sec = Read-Host -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try { $token = ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)).Trim() }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
if ([string]::IsNullOrWhiteSpace($token)) { Write-Bad ' 令牌为空。'; exit 1 }

Write-Step '确认令牌与仓库'
try {
  $me = (Invoke-WebRequest -Uri "https://gitee.com/api/v5/user?access_token=$token" -TimeoutSec 60 -UseBasicParsing).Content | ConvertFrom-Json
  Write-Ok "令牌有效,身份:$($me.login)"
} catch {
  Write-Bad " 令牌无效(HTTP $($_.Exception.Response.StatusCode.value__))"
  exit 1
}
try {
  $null = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo/releases?access_token=$token&per_page=1" -TimeoutSec 60
  Write-Ok "仓库可访问:$GiteeOwner/$GiteeRepo"
} catch {
  Write-Bad " 仓库不可访问(HTTP $($_.Exception.Response.StatusCode.value__))"
  exit 1
}

Write-Host ''
Write-Step '镜像配置(必须手工,API 未开放)'
Write-Host '    这个能力 Gitee 没有开放接口,请到网页配置(约 1 分钟):' -ForegroundColor Yellow
Write-Host ''
Write-Host "    1) 打开 $mirrorPage" -ForegroundColor White
Write-Host "       (即「$GiteeRepo 仓库 → 管理 → 仓库镜像管理」)" -ForegroundColor DarkGray
Write-Host '    2) 点「添加镜像」' -ForegroundColor White
Write-Host '    3) 镜像方向选【Pull】(Gitee 从 GitHub 拉;别选 Push)' -ForegroundColor White
Write-Host "    4) 镜像仓库选 $ghUrl" -ForegroundColor White
Write-Host '    5) 个人令牌填【GitHub】私人令牌:' -ForegroundColor White
Write-Host '       - 勾 repo(访问仓库,必选)' -ForegroundColor DarkGray
Write-Host '       - 想让它自动同步,再勾 admin:repo_hook(自动建 webhook)' -ForegroundColor DarkGray
Write-Host '       - 申请:GitHub → Settings → Developer settings → Personal access tokens' -ForegroundColor DarkGray
Write-Host '    6) 点「添加」保存' -ForegroundColor White
Write-Host ''
Write-Host '    触发同步的方式(最短间隔 5 分钟):' -ForegroundColor DarkGray
Write-Host '      - 自动模式:每次 push 到 GitHub 后由 webhook 触发' -ForegroundColor DarkGray
Write-Host '      - 手动模式:在该页面点镜像的「更新」按钮' -ForegroundColor DarkGray
Write-Host ''
Write-Host '    注意:该镜像只同步 分支/标签/提交,不含 Releases 与附件。' -ForegroundColor Yellow
Write-Host '    附件仍按 AGENTS §九.5 用本机补传:scripts/sync-gitee-release.ps1' -ForegroundColor Yellow
Write-Host ''
Write-Host '    配置完成后确认两端一致:' -ForegroundColor DarkGray
Write-Host '      powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-release.ps1 -DryRun -GiteeOnly' -ForegroundColor DarkGray
exit 0
