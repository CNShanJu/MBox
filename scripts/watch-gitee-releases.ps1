<#
.SYNOPSIS
  守护补传:自动把 GitHub 上已发布、而 Gitee 发行版缺 .apk 附件的正式包补传过去。

.DESCRIPTION
  为什么要它(见 AGENTS.md「代码托管与镜像同步」):
    - Gitee 的仓库镜像(Pull 方向)只同步**分支/标签/提交**,不含 Releases 与附件 ——
      代码和 tag 会自己跟过去,但 APK 附件永远需要一条国内链路;
    - GitHub 的 Azure 跑步机直连 Gitee 传 40MB 附件实测必失败(Connection reset / Empty reply),
      CI 里那一步只告警不失败,所以镜像页会一直缺附件;
    - 本脚本跑在国内机器上,轮询「最新几个 tag 在 Gitee 有没有 .apk 附件」,缺了就
      下载 GitHub 正式包 → 上传到 Gitee 发行版 → 复核验收。幂等,可重复执行。

  典型用法:
    # 只看要做什么(不写任何远端)
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/watch-gitee-releases.ps1 -DryRun

    # 检查最近 3 个 tag,缺附件的自动补传
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/watch-gitee-releases.ps1 -Count 3

    # 只处理指定 tag
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/watch-gitee-releases.ps1 -Tag v3.6.5

  挂计划任务(每 30 分钟检查一次,仅当前用户登录时运行):
    schtasks /create /tn "MBox-Gitee-Release-Watch" /sc minute /mo 30 ^
      /tr "powershell -NoProfile -ExecutionPolicy Bypass -File \"<仓库>\scripts\watch-gitee-releases.ps1\" -Count 3" /f

  令牌(与 sync-gitee-release.ps1 同口径,不写命令行):
    优先 -TokenFile;其次环境变量 GITEE_TOKEN;最后默认 %TEMP%\gitee-token.txt。
    都没有时先跑 scripts/save-gitee-token.ps1 生成一份(用完可删)。

.PARAMETER Tag
  指定要检查/补传的 tag(可多个)。不填则按 -Count 自动取 GitHub 上最新的几个 v* tag。

.PARAMETER Count
  未指定 -Tag 时检查的最新 tag 个数,默认 3。

.PARAMETER DryRun
  只报告「谁缺附件、准备做什么」,不下载、不写 Gitee。

.PARAMETER NoVerify
  补传成功后不再调 verify-gitee-mirror.ps1 复核。

.NOTES
  编码:本文件必须存为 **UTF-8 with BOM**(PS 5.1 按系统代码页读无 BOM 的 .ps1,中文会全乱)。
  只读远端到「确实缺附件」为止;没有令牌时一律不写 Gitee。
#>
[CmdletBinding()]
param(
  [string[]]$Tag,
  [int]$Count = 3,
  [string]$TokenFile,
  [string]$GiteeOwner = 'CnAyo',
  [string]$GiteeRepo = 'MBox',
  [string]$GithubRepo = 'CNShanJu/MBox',
  [string]$LogFile,
  [switch]$DryRun,
  [switch]$NoVerify
)

$ErrorActionPreference = 'Stop'
try {
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
  $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $LogFile) { $LogFile = Join-Path ([System.IO.Path]::GetTempPath()) 'MBox-gitee-watch.log' }
$downloadDir = Join-Path $repoRoot '.tmp-gitee-upload'

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "    OK   $Text" -ForegroundColor Green }
function Write-Bad([string]$Text) { Write-Host "    失败 $Text" -ForegroundColor Red }
function Write-Skip([string]$Text) { Write-Host "    跳过 $Text" -ForegroundColor DarkGray }
function Write-Warn2([string]$Text) { Write-Host "    注意 $Text" -ForegroundColor Yellow }

function Write-Log([string]$Text) {
  $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $Text"
  try { Add-Content -Path $LogFile -Value $line -Encoding UTF8 } catch { }
}

$script:failed = 0

# ------------------------------------------------------------------ 取令牌
$token = ''
if ($TokenFile) {
  if (-not (Test-Path $TokenFile)) { Write-Bad "令牌文件不存在:$TokenFile"; exit 1 }
  $token = (Get-Content $TokenFile -Raw -Encoding UTF8).Trim()
} elseif ($env:GITEE_TOKEN) {
  $token = "$($env:GITEE_TOKEN)".Trim()
} else {
  $default = Join-Path ([System.IO.Path]::GetTempPath()) 'gitee-token.txt'
  if (Test-Path $default) { $token = (Get-Content $default -Raw -Encoding UTF8).Trim() }
}
if ([string]::IsNullOrWhiteSpace($token)) {
  if ($DryRun) {
    Write-Warn2 '没找到 Gitee 令牌(-DryRun 继续,只做只读检查)'
  } else {
    Write-Bad '没找到 Gitee 令牌。先跑 scripts/save-gitee-token.ps1,或用 -TokenFile / 环境变量 GITEE_TOKEN 指定。'
    exit 1
  }
} else {
  Write-Ok "已读取令牌(长度 $($token.Length),不显示内容)"
}

$api = 'https://gitee.com/api/v5'
$relApi = "$api/repos/$GiteeOwner/$GiteeRepo"

# ------------------------------------------------------------------ 目标 tag
if (-not $Tag -or $Tag.Count -eq 0) {
  Write-Step '从 GitHub 取最新 tag 列表(git ls-remote,不消耗接口配额)'
  $env:GIT_TERMINAL_PROMPT = '0'
  Push-Location $repoRoot
  try {
    $raw = & git ls-remote --tags origin 'v*' 2>$null
  } finally { Pop-Location }
  $names = @($raw | ForEach-Object { ($_ -split "`t")[1] } |
      Where-Object { $_ -and $_ -notmatch '\^\{\}$' } |
      ForEach-Object { $_ -replace '^refs/tags/', '' } |
      Sort-Object -Unique)
  $Tag = @($names | Sort-Object { try { [version]($_.TrimStart('v')) } catch { [version]'0.0.0' } } -Descending |
      Select-Object -First $Count)
  if ($Tag.Count -eq 0) { Write-Bad 'GitHub 上没有 v* tag,或本机连不上 GitHub。'; exit 1 }
}
Write-Step "本次检查 tag:$($Tag -join ', ')"
Write-Log "检查 tag=$($Tag -join ',') DryRun=$($DryRun.IsPresent)"

# ------------------------------------------------------------- Gitee 发行版表
$releases = @()
try {
  # 注意:该接口已返回 JSON 数组,**不要**再套 @(...) —— 会变成「只含该数组的数组」,
  # 字段全变数组、tag 匹配失效(verify-gitee-mirror.ps1 顶部也记了这个坑)。
  $q = "$relApi/releases?per_page=100"
  if ($token) { $q += "&access_token=$token" }
  $releases = Invoke-RestMethod -Uri $q -TimeoutSec 120 -ErrorAction Stop
  # 防御:万一被包了一层(单元素且该元素是数组),拆开
  if ($releases.Count -eq 1 -and $releases[0] -is [System.Array]) { $releases = $releases[0] }
  Write-Ok "Gitee 现有发行版 $(@($releases).Count) 个"
} catch {
  Write-Bad "读 Gitee 发行版列表失败:$($_.Exception.Message)"
  exit 1
}

function Get-ApkUrl([string]$TagName) {
  # 先按「tag 提交的 UTC 日期」拼 GitHub 资产地址直接探测(不消耗 API 配额),
  # 探测不到再退回 GitHub Releases 接口。
  Push-Location $repoRoot
  try { $iso = (& git log -1 --format=%cI $TagName 2>$null) } finally { Pop-Location }
  $candidates = @()
  if ($iso) {
    try {
      $d = [datetimeoffset]::Parse($iso.Trim()).UtcDateTime
      $candidates += $d.ToString('yyyyMMdd')
      $candidates += $d.AddDays(1).ToString('yyyyMMdd')
    } catch { }
  }
  foreach ($day in ($candidates | Select-Object -Unique)) {
    $name = "MBox_${TagName}_release_$day.apk"
    $url = "https://github.com/$GithubRepo/releases/download/$TagName/$name"
    try {
      $head = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 60 -UseBasicParsing -MaximumRedirection 5 `
        -ErrorAction Stop
      if ($head.StatusCode -eq 200) { return @{ name = $name; url = $url } }
    } catch { }
  }

  try {
    $r = Invoke-RestMethod -Uri "https://api.github.com/repos/$GithubRepo/releases/tags/$TagName" `
      -Headers @{ 'User-Agent' = 'MBox-gitee-watch' } -TimeoutSec 90 -ErrorAction Stop
    $asset = @($r.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1)
    if ($asset.Count -gt 0) { return @{ name = $asset[0].name; url = $asset[0].browser_download_url } }
  } catch {
    if ("$($_.Exception.Message)" -match '403') {
      Write-Warn2 " GitHub 接口限流(403),本次无法确认 $TagName 的资产名"
    }
  }
  return $null
}

$uploaded = @()
foreach ($t in $Tag) {
  Write-Step "检查 $t"
  $rel = @($releases | Where-Object { $_.tag_name -eq $t } | Select-Object -First 1)

  if ($rel.Count -eq 0) {
    if ($DryRun) { Write-Skip "Gitee 还没有该发行版(正式执行时会创建)"; $script:failed++; continue }
    Write-Host '    Gitee 缺该发行版,创建中...' -ForegroundColor DarkGray
    $notes = Join-Path $repoRoot "doc/release-notes-$t.md"
    # 中文正文一律走 curl.exe --data-urlencode(PS 5.1 的表单编码会把中文发成 ?)
    $curlArgs = @('-sS', '--max-time', '120', '-X', 'POST', "$relApi/releases",
      '-d', "access_token=$token", '-d', "tag_name=$t", '-d', "name=MBox $t",
      '-d', 'target_commitish=main')
    if (Test-Path $notes) { $curlArgs += @('--data-urlencode', "body@$notes") }
    $created = & curl.exe @curlArgs 2>&1
    try { $relId = ($created | ConvertFrom-Json).id } catch { $relId = $null }
    if (-not $relId) {
      Write-Bad "创建发行版失败:$((("$created")).Substring(0, [Math]::Min(200, "$created".Length)))"
      $script:failed++; continue
    }
    Write-Ok "发行版已创建 id=$relId"
    $rel = @([pscustomobject]@{ id = $relId; tag_name = $t })
  } else {
    $relId = $rel[0].id
  }

  $atts = @()
  try {
    $aq = "$relApi/releases/$relId/attach_files?per_page=100"
    if ($token) { $aq += "&access_token=$token" }
    # 同上:该接口返回 JSON 数组,不要再套 @(...)
    $atts = Invoke-RestMethod -Uri $aq -TimeoutSec 90 -ErrorAction Stop
    if ($atts.Count -eq 1 -and $atts[0] -is [System.Array]) { $atts = $atts[0] }
  } catch { }
  $apkAtt = @($atts | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1)
  if ($apkAtt.Count -gt 0) {
    Write-Ok "附件已在:$($apkAtt[0].name)"
    continue
  }

  Write-Host '    Gitee 缺 .apk 附件' -ForegroundColor DarkGray
  $asset = Get-ApkUrl $t
  if (-not $asset) {
    Write-Bad "找不到 GitHub 上 $t 的 .apk 资产(可能该 tag 还没出包)"
    $script:failed++; continue
  }
  Write-Ok "GitHub 资产:$($asset.name)"

  if ($DryRun) { Write-Skip 'DryRun:跳过下载与上传'; $script:failed++; continue }

  if (-not (Test-Path $downloadDir)) { New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null }
  $apkPath = Join-Path $downloadDir $asset.name
  $needDownload = $true
  if (Test-Path $apkPath) {
    try {
      $head = Invoke-WebRequest -Uri $asset.url -Method Head -TimeoutSec 60 -UseBasicParsing -MaximumRedirection 5
      $remoteLen = [int64]$head.Headers['Content-Length']
      $localLen = (Get-Item $apkPath).Length
      if ($remoteLen -gt 0 -and $remoteLen -eq $localLen) { $needDownload = $false; Write-Ok "本地已有同体积包,跳过下载" }
    } catch { }
  }
  if ($needDownload) {
    Write-Host '    从 GitHub 下载正式包...' -ForegroundColor DarkGray
    $ProgressPreference = 'SilentlyContinue'
    try {
      Invoke-WebRequest -Uri $asset.url -OutFile $apkPath -TimeoutSec 900 -UseBasicParsing -ErrorAction Stop
    } catch {
      Write-Bad "下载失败:$($_.Exception.Message)"
      $script:failed++; continue
    }
  }
  $apkItem = Get-Item $apkPath
  Write-Ok "待上传:$($apkItem.Name)($([math]::Round($apkItem.Length / 1MB, 2)) MB)"

  Write-Host '    上传到 Gitee(国内链路,通常几秒到几十秒)...' -ForegroundColor DarkGray
  $json = & curl.exe -sS --max-time 900 --retry 3 --retry-delay 5 `
    -F "access_token=$token" `
    -F "file=@$($apkItem.FullName);filename=$($apkItem.Name);type=application/vnd.android.package-archive" `
    "$relApi/releases/$relId/attach_files" 2>&1
  $ok = $false
  try { $ok = [bool](($json | ConvertFrom-Json).browser_download_url) } catch { }
  if ($ok) {
    Write-Ok '附件已上传'
  } else {
    Write-Bad "上传失败:$((("$json")).Substring(0, [Math]::Min(200, "$json".Length)))"
    $script:failed++; continue
  }

  # 复核:重新列一次附件,确认真的在
  $after = @()
  try {
    $after = Invoke-RestMethod -Uri "$relApi/releases/$relId/attach_files?per_page=100&access_token=$token" -TimeoutSec 90
    if ($after.Count -eq 1 -and $after[0] -is [System.Array]) { $after = $after[0] }
  } catch { }
  if (@($after | Where-Object { $_.name -eq $apkItem.Name }).Count -gt 0) {
    Write-Ok "复核通过:$($apkItem.Name)"
    $uploaded += $t
  } else {
    Write-Bad '复核失败:附件列表里没看到刚上传的文件'
    $script:failed++
  }
}

if (-not $NoVerify -and $uploaded.Count -gt 0 -and (Test-Path (Join-Path $PSScriptRoot 'verify-gitee-mirror.ps1'))) {
  Write-Step '用验收脚本复核'
  & powershell -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $PSScriptRoot 'verify-gitee-mirror.ps1') `
    -Tag ($uploaded -join ',') -GiteeOwner $GiteeOwner -GiteeRepo $GiteeRepo
  if ($LASTEXITCODE -ne 0) { $script:failed++ }
}

Write-Host ''
Write-Step '结论'
if ($DryRun) {
  Write-Warn2 'DryRun:以上是「准备做什么」,没有下载、没有写 Gitee'
  Write-Log 'DRYRUN 结束'
  exit 0
}
if ($script:failed -eq 0) {
  Write-Ok '所有检查的版本在 Gitee 都有 .apk 附件'
  Write-Log 'OK 所有版本附件齐全'
  exit 0
} else {
  Write-Warn2 "有 $($script:failed) 项需要处理(见上面输出);日志:$LogFile"
  Write-Log "WARN $($script:failed) 项待处理"
  exit 1
}
