<#
.SYNOPSIS
  用 Gitee 令牌本地驱动镜像同步:先验令牌,再按 tag 建发行版并上传正式包。

.DESCRIPTION
  为什么有这个脚本:配置 GitHub Secrets 里的 GITEE_TOKEN 之后,只有"等下一次发版"或"重跑 tag 的
  Build APK"才能知道配得对不对;而且 CI 那步在令牌无效时只打印 ::error 不失败,排查要翻日志。
  本脚本在本地把同一套 Gitee 开放接口走一遍,立刻给结论:

    1. 验令牌      GET /user 是否 200,并打印令牌作用域(确认勾了 projects/仓库读写)
    2. 看发行版    releases 列表里该 tag 在不在
    3. 建发行版    不在就按 tag 建(正文取 doc/release-notes-<tag>.md)
    4. 传附件      把 APK 传到该发行版(v3.6.2 这类历史遗漏可据此当场补齐)

  令牌安全(重要):
    - **不要把令牌直接写在命令行参数里**(会进 PowerShell 历史与进程列表)。用 -TokenFile 指定一个
      只含令牌的文件,或用交互式输入(不回显)。
    - 脚本不打印令牌内容,只打印长度与作用域。
    - 用完把令牌文件删掉。CI 侧请用 GitHub Secrets,不要写进仓库。

  典型用法:
    # 只验令牌好不好使(不发任何写请求)
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-gitee-release.ps1 -VerifyOnly

    # 验令牌 + 把 v3.6.2 的正式包补传到 Gitee 发行版(需先有 APK)
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-gitee-release.ps1 -Tag v3.6.2 -ApkPath <apk路径>

    # 没有现成 APK 时,让脚本自己构建(耗时,走本地 Gradle)
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-gitee-release.ps1 -Tag v3.6.2 -BuildApk

.PARAMETER Tag
  目标 tag,默认取 app/app_config.properties 的 versionName(即 v<versionName>)。

.PARAMETER ApkPath
  要上传的 APK 路径。不填且加了 -BuildApk 时,先构建再自动取产物。

.PARAMETER BuildApk
  没有现成 APK 时,调用 gradlew assembleRelease 构建(按 AGENTS §九 的固定堆配置,不临时改 -Xmx)。

.PARAMETER TokenFile
  存放 Gitee 令牌的文件(UTF-8,首尾空白会被去除)。不填则交互式输入(不回显)。

.PARAMETER VerifyOnly
  只验令牌与作用域,不建发行版、不传附件。

.PARAMETER GiteeOwner / GiteeRepo
  Gitee 目标仓库,默认 CnAyo/MBox。

.NOTES
  编码:本文件必须存为 **UTF-8 with BOM**(PS 5.1 按系统代码页读无 BOM 的 .ps1,中文会全乱)。
#>
[CmdletBinding()]
param(
  [string]$Tag,
  [string]$ApkPath,
  [switch]$BuildApk,
  [string]$TokenFile,
  [switch]$VerifyOnly,
  [string]$GiteeOwner = 'CnAyo',
  [string]$GiteeRepo = 'MBox'
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

$repoRoot = Split-Path -Parent $PSScriptRoot

# ------------------------------------------------------------------ 取令牌
Write-Step '读取 Gitee 令牌'
if ($TokenFile) {
  if (-not (Test-Path $TokenFile)) { Write-Bad "令牌文件不存在:$TokenFile"; exit 1 }
  $token = (Get-Content $TokenFile -Raw -Encoding UTF8).Trim()
} else {
  Write-Host '    请粘贴 Gitee 私人令牌(输入不回显,粘贴后回车):' -ForegroundColor DarkGray
  $sec = Read-Host -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try { $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
  }
  $token = "$token".Trim()
}
if ([string]::IsNullOrWhiteSpace($token)) { Write-Bad ' 令牌为空。'; exit 1 }
Write-Ok "已读取令牌(长度 $($token.Length),不显示内容)"

$api = "https://gitee.com/api/v5"

# ------------------------------------------------------------ 1. 验令牌与作用域
Write-Step '验证令牌'
try {
  $resp = Invoke-WebRequest -Uri "$api/user?access_token=$token" -TimeoutSec 60 -UseBasicParsing -ErrorAction Stop
  $me = $resp.Content | ConvertFrom-Json
  Write-Ok "令牌有效,身份:$($me.login)  姓名:$($me.name)"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Write-Bad "令牌无效或接口拒绝(HTTP $code)"
  Write-Host '        - 401:令牌错误/已过期/被撤销 —— 重新生成一个' -ForegroundColor DarkGray
  Write-Host '        - 403:令牌缺少权限 —— 回去勾上「projects / 仓库读写」' -ForegroundColor DarkGray
  exit 1
}

$scopes = $resp.Headers['X-Oauth-Scopes']
if (-not $scopes) { $scopes = $resp.Headers['x-oauth-scopes'] }
if ($scopes) {
  Write-Ok "令牌作用域:$scopes"
  if ($scopes -notmatch 'projects') {
    Write-Warn2 ' 作用域里没看到 projects —— 建发行版/传附件可能 403,建议回 Gitee 勾上「projects」重建令牌'
  }
} else {
  Write-Warn2 ' 接口没回作用域头,无法确认;若后面建发行版报 403,就是权限不够'
}

if ($VerifyOnly) {
  Write-Host ''
  Write-Step '结论'
  Write-Ok '令牌可用(-VerifyOnly,未做任何写操作)'
  Write-Host '        接着把它写进 GitHub Secrets 名为 GITEE_TOKEN 即可让 CI 自动同步。' -ForegroundColor DarkGray
  exit 0
}

# ---------------------------------------------------------------- 目标 tag
if (-not $Tag) {
  $cfg = Join-Path $repoRoot 'app/app_config.properties'
  if (-not (Test-Path $cfg)) { Write-Bad " 读不到 $cfg,请用 -Tag 指定。"; exit 1 }
  $vn = ((Get-Content $cfg -Encoding UTF8 | Where-Object { $_ -match '^\s*versionName\s*=' } |
      Select-Object -First 1) -replace '^\s*versionName\s*=\s*', '').Trim()
  if ([string]::IsNullOrWhiteSpace($vn)) { Write-Bad ' 读不到 versionName。'; exit 1 }
  $Tag = "v$vn"
}
Write-Step "目标 tag:$Tag"

# ---------------------------------------------------------------- 找 APK
if (-not $ApkPath -and $BuildApk) {
  Write-Step '构建正式包(gradlew assembleRelease)'
  $gradlew = Join-Path $repoRoot 'gradlew.bat'
  if (-not (Test-Path $gradlew)) { Write-Bad " 找不到 $gradlew"; exit 1 }
  Push-Location $repoRoot
  try {
    # 按 AGENTS §九 构建内存纪律:堆固定写在 gradle.properties,不在这里临时改 -Xmx
    & $gradlew :app:assembleRelease --console=plain 2>&1 | Select-Object -Last 15
    if ($LASTEXITCODE -ne 0) { Write-Bad ' 构建失败,未上传。'; exit 1 }
  } finally { Pop-Location }
  Write-Ok '构建完成'
}

if (-not $ApkPath) {
  $found = @(Get-ChildItem (Join-Path $repoRoot 'app/build/outputs/apk/release') -Filter '*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending)
  if ($found.Count -gt 0) {
    $ApkPath = $found[0].FullName
    Write-Ok "自动选用产物:$($found[0].Name)"
  }
}
if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
  Write-Bad ' 没有可上传的 APK。请用 -ApkPath 指定,或加 -BuildApk 先构建。'
  exit 1
}
$apkItem = Get-Item $ApkPath
$apkName = $apkItem.Name
Write-Ok "待上传:$apkName($([math]::Round($apkItem.Length / 1MB, 2)) MB)"

$relApi = "$api/repos/$GiteeOwner/$GiteeRepo"

# ------------------------------------------------------------ 找/建发行版
Write-Step "查找 $Tag 的发行版"
$rel = @()
try {
  # 注意:不要写成 @(Invoke-RestMethod ...) —— 该接口已返回 JSON 数组,再套 @() 会变成
  # "只含一个元素(那个数组)"的数组,.id 会取成数组,导致拿错发行版。
  $all = Invoke-RestMethod -Uri "$relApi/releases?access_token=$token&per_page=100" -TimeoutSec 60 -ErrorAction Stop
  $matched = @($all | Where-Object { $_.tag_name -eq $Tag })
  if ($matched.Count -gt 0) { $rel = @($matched[0]) }
} catch {
  Write-Bad " 读发行版列表失败(HTTP $($_.Exception.Response.StatusCode.value__))"
  exit 1
}

if ($rel.Count -eq 0) {
  Write-Host "    发行版不存在,创建中:tag=$Tag" -ForegroundColor DarkGray
  $notes = Join-Path $repoRoot "doc/release-notes-$Tag.md"
  if (-not (Test-Path $notes)) {
    $tmpN = Join-Path ([System.IO.Path]::GetTempPath()) "gitee-notes-$Tag.md"
    "本发行版与 GitHub Release 同步,更新说明见 https://github.com/CNShanJu/$GiteeRepo/releases/tag/$Tag" |
      Set-Content -Path $tmpN -Encoding UTF8
    $notes = $tmpN
    Write-Warn2 " 缺 doc/release-notes-$Tag.md,正文先用指向 GitHub 的说明"
  }
  $body = (Get-Content $notes -Raw -Encoding UTF8)
  try {
    $created = Invoke-RestMethod -Uri "$relApi/releases" -Method Post -TimeoutSec 120 -ErrorAction Stop -Body @{
      access_token     = $token
      tag_name         = $Tag
      name             = "MBox $Tag"
      target_commitish = 'main'
      body             = $body
    }
    $relId = $created.id
    Write-Ok "发行版已创建 id=$relId"
  } catch {
    Write-Bad " 创建发行版失败(HTTP $($_.Exception.Response.StatusCode.value__))"
    Write-Host '        - 403 多为令牌缺 projects 权限' -ForegroundColor DarkGray
    Write-Host "        - 确认 Gitee 仓库 $GiteeOwner/$GiteeRepo 存在且该 tag 已推上去" -ForegroundColor DarkGray
    exit 1
  }
} else {
  $relId = $rel[0].id
  Write-Ok "发行版已存在 id=$relId"
}

# ------------------------------------------------------------ 传附件
Write-Step '检查同名附件'
$existing = @()
try {
  $existing = @(Invoke-RestMethod -Uri "$relApi/releases/$relId/attach_files?access_token=$token&per_page=100" -TimeoutSec 60 -ErrorAction Stop)
} catch { }
$dup = @($existing | Where-Object { $_.name -eq $apkName })
if ($dup.Count -gt 0) {
  Write-Ok "已有同名附件,跳过上传:$apkName"
} else {
  Write-Host "    上传中(约 $([math]::Round($apkItem.Length / 1MB, 2)) MB,请稍候)..." -ForegroundColor DarkGray
  # 用 curl.exe 传 multipart:避免 PS 5.1 表单编码把中文文件名/正文弄坏
  $json = & curl.exe -sS --max-time 900 --retry 3 --retry-delay 5 `
    -F "access_token=$token" `
    -F "file=@$($apkItem.FullName);filename=$apkName;type=application/vnd.android.package-archive" `
    "$relApi/releases/$relId/attach_files" 2>&1
  try {
    $res = $json | ConvertFrom-Json
    if ($res.browser_download_url) {
      Write-Ok "附件已上传:$($res.browser_download_url)"
    } else {
      Write-Bad "上传返回异常:$(("$json").Substring(0, [Math]::Min(200, "$json".Length)))"
      exit 1
    }
  } catch {
    Write-Bad "上传失败:$(("$json").Substring(0, [Math]::Min(200, "$json".Length)))"
    exit 1
  }
}

# ------------------------------------------------------------ 验收
Write-Step '用验收脚本复核'
$verify = Join-Path $PSScriptRoot 'verify-gitee-mirror.ps1'
if (Test-Path $verify) {
  & powershell -NoProfile -ExecutionPolicy Bypass -File $verify -Tag $Tag -GiteeOwner $GiteeOwner -GiteeRepo $GiteeRepo
  exit $LASTEXITCODE
} else {
  Write-Warn2 ' 找不到 verify-gitee-mirror.ps1,跳过复核'
  exit 0
}
