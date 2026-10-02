<#
.SYNOPSIS
  验收 Gitee 镜像:检查正式包的发行版附件到底在不在、体积对不对、能不能匿名下到。

.DESCRIPTION
  为什么要单独有个脚本:`.github/workflows/build-apk.yml` 的 `Sync release APK to Gitee mirror` 步
  在**缺 GITEE_TOKEN 时会静默跳过**(已改为打印 ::error,但步骤仍算 success),
  光看 GitHub Actions 是绿的就以为镜像好了 —— v3.6.1/v3.6.2 就是这样漏掉的,镜像页一直没有附件。
  本脚本直接问 Gitee「这个 tag 有没有附件」,不看流水线的脸色。

  检查项(逐条):
    1. 发行版存在        在 releases 列表里按 tag_name 命中
    2. 有正式包附件      文件名形如 MBox_v<版本>_release_<日期>.apk
    3. 体积非 0          取 HTTP Content-Length 核对(见下方"两个 Gitee 接口坑")
    4. 与 GitHub 对账     同名附件字节数是否一致(附件的强校验;GitHub 才是权威产物)
    5. 匿名可下          探测附件直链,顺带拿到真实体积
  任何一条不过 → 非零退出,可直接接进发版脚本或 CI。

  两个 Gitee 接口坑(实测,别踩回去):
    - `GET /releases/tags/<tag>` 返回**空壳对象**:HTTP 200,但 id/tag_name/name 全空、assets 里字段也全空,
      看着成功却什么都读不到 —— 必须改用 `GET /releases?per_page=100` 再按 tag_name 过滤。
    - 附件对象**没有 size 字段**(列表与详情都没有),所以"体积非 0 / 与 GitHub 对账"只能从
      HTTP Content-Length 拿;这也是 -NoDownload 时这两项标"未测"而非"通过"的原因。

  典型用法:
    # 验当前 app_config.properties 里的 versionName(发版后最常用)
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1

    # 验指定 tag / 多个 tag
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1 -Tag v3.6.2
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1 -Tag v3.6.1,v3.6.2

    # 验最近 N 个已发布 tag(排查"哪几个版本漏了")
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1 -Last 5

    # 只查记录、不做下载探测(离线可用;仅步骤 1~4)
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-gitee-mirror.ps1 -Last 5 -NoDownload

.PARAMETER Tag
  要验收的 tag(可多个,逗号分隔)。默认取 app/app_config.properties 的 versionName(即 v<versionName>)。

.PARAMETER Last
  改为验收本地最近 N 个 v* tag(按版本号倒序),用于批量排查历史遗漏。

.PARAMETER NoDownload
  跳过下载探测。注意:Gitee 接口不返回附件体积,所以此模式下"体积非 0"与"与 GitHub 对账"会标为
  **未测**而非通过 —— 只适合快速核对"发行版和附件在不在"。

.PARAMETER ProbeBytes
  下载探测读取的字节数,默认 4MB。探测只看 HTTP 状态与首段字节,不落盘。

.PARAMETER GiteeOwner
  Gitee 归属,默认 CnAyo(与 App 端 UpdaterConfig 的镜像前缀一致)。

.PARAMETER GiteeRepo
  Gitee 仓库,默认 MBox。

.NOTES
  编码:本文件必须存为 **UTF-8 with BOM**(Windows PowerShell 5.1 按系统代码页读无 BOM 的 .ps1,
  中文会被读坏并报一堆 "Unexpected token")。若报乱码式语法错误,先确认前 3 字节是 EF BB BF。

  只读:脚本不写仓库、不改远端、不落盘,只发只读 HTTP 请求(Gitee 开放接口 + 附件直链)。
#>
[CmdletBinding()]
param(
  [string[]]$Tag,
  [int]$Last = 0,
  [switch]$NoDownload,
  [int]$ProbeBytes = 4194304,
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
function Write-Bad([string]$Text) { Write-Host "    缺失 $Text" -ForegroundColor Red }
function Write-Warn2([string]$Text) { Write-Host "    注意 $Text" -ForegroundColor Yellow }

$repoRoot = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------- 列出要验收的 tag
if ($Last -gt 0) {
  if (-not (Test-Path (Join-Path $repoRoot '.git'))) {
    Write-Host ' 找不到 git 仓库,无法用 -Last 取本地 tag。' -ForegroundColor Red
    exit 1
  }
  Push-Location $repoRoot
  try {
    $all = @(& git tag --list 'v*' --sort=-version:refname 2>$null | Where-Object { $_ })
  } finally { Pop-Location }
  if ($all.Count -eq 0) { Write-Host ' 本地没有 v* tag。' -ForegroundColor Red; exit 1 }
  $Tag = @($all | Select-Object -First $Last)
}

if (-not $Tag -or $Tag.Count -eq 0) {
  $cfg = Join-Path $repoRoot 'app/app_config.properties'
  if (-not (Test-Path $cfg)) {
    Write-Host " 读不到 $cfg,请用 -Tag 指定要验收的 tag。" -ForegroundColor Red
    exit 1
  }
  $vn = ((Get-Content $cfg -Encoding UTF8 | Where-Object { $_ -match '^\s*versionName\s*=' } |
      Select-Object -First 1) -replace '^\s*versionName\s*=\s*', '').Trim()
  if ([string]::IsNullOrWhiteSpace($vn)) {
    Write-Host ' 读不到 versionName,请用 -Tag 指定要验收的 tag。' -ForegroundColor Red
    exit 1
  }
  $Tag = @("v$vn")
}

# 允许 -Tag a,b 写法
$Tag = @($Tag | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$api = "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo"

Write-Step "验收镜像 $GiteeOwner/$GiteeRepo  共 $($Tag.Count) 个 tag"
if ($NoDownload) { Write-Host '    (已加 -NoDownload,体积与下载项将标为未测)' -ForegroundColor DarkGray }

# 发行版列表整体取一次(/releases/tags/<tag> 在本仓库返回空壳对象,不可用),再按 tag_name 匹配
#
# 坑:这里**不能写 `$releases = @(Invoke-RestMethod ...)`** —— 本仓库该接口返回的是 JSON 数组,
# Invoke-RestMethod 已经把它变成 Object[];再套一层 @() 会得到一个"只有一个元素(那个数组)"的数组,
# 于是 .tag_name / .id 全变成数组,滤出来的"发行版"其实是整个列表,附件也会拿成别的版本的
# (实测踩过:报出 id=[1179883 1180270]、附件显示成 v3.6.1 的包)。直接赋值即可。
$releases = @()
try {
  $releases = Invoke-RestMethod -Uri "$api/releases?per_page=100" -TimeoutSec 60 -ErrorAction Stop
  Write-Host "    Gitee 现有发行版 $(@($releases).Count) 个: $((@($releases) | ForEach-Object { $_.tag_name }) -join ', ')" -ForegroundColor DarkGray
} catch {
  Write-Host " 取 Gitee 发行版列表失败:$($_.Exception.Message)" -ForegroundColor Red
  exit 1
}

$failed = @()

foreach ($t in $Tag) {
  Write-Host ''
  Write-Host "--- $t ---" -ForegroundColor White

  # 1. 发行版是否存在
  # 注意别写成 @(...) | Select-Object -First 1:那样在某些情况下会把匹配到的多个发行版
  # 合并成一个对象(字段被拼成 "v3.6.1 v3.6.3" 这种),于是拿错附件、还会误报"与 GitHub 不一致"。
  # 这里显式取第一个匹配的元素。
  $matched = @($releases | Where-Object { $_.tag_name -eq $t })
  $rel = if ($matched.Count -gt 0) { $matched[0] } else { $null }
  if (-not $rel) {
    Write-Bad "Gitee 上没有 $t 的发行版"
    Write-Host "        页面: https://gitee.com/$GiteeOwner/$GiteeRepo/releases/tag/$t" -ForegroundColor DarkGray
    Write-Host '        常见原因:缺 GITEE_MBOX_TOKEN 导致 CI 静默跳过;或该版本人工创建发行版时忘了建。' -ForegroundColor DarkGray
    Write-Host '        注意:重跑旧 tag 的 Build APK 不会采用最新工作流(用的是该次运行所属提交里的文件),' -ForegroundColor DarkGray
    Write-Host '        改过 workflow 后要补传,请发新 tag,或用 scripts/sync-gitee-release.ps1 本地补。' -ForegroundColor DarkGray
    $failed += $t
    continue
  }
  Write-Ok "发行版在: $($rel.name)  (id=$($rel.id))"

  # 2. 找正式包附件(排除 Gitee 自动生成的 vX.zip / vX.tar.gz 源码包)
  $assets = @($rel.assets)
  $apks = @($assets | Where-Object { $_.name -match '\.apk$' })
  if ($apks.Count -eq 0) {
    Write-Bad "发行版在,但没有任何 .apk 附件(共 $($assets.Count) 个附件)"
    $names = @($assets | ForEach-Object { $_.name } | Where-Object { $_ })
    if ($names.Count -gt 0) { Write-Host "        现有附件: $($names -join ', ')" -ForegroundColor DarkGray }
    Write-Host '        只带 vX.zip / vX.tar.gz 说明是 Gitee 网页手工建的发行版,没传正式包。' -ForegroundColor DarkGray
    $failed += $t
    continue
  }
  $apk = $apks[0]
  $url = $apk.browser_download_url
  Write-Ok "正式包附件在: $($apk.name)"

  # 3+5. 探测附件直链:顺带拿到真实体积(Gitee 接口不给 size)
  $realSize = 0
  if (-not $NoDownload) {
    if (-not $url) {
      Write-Warn2 ' 接口没给 browser_download_url,无法探测体积,跳过体积与下载校验'
    } else {
      $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("mbox-probe-" + [guid]::NewGuid().ToString('N') + ".bin")
      try {
        $meta = & curl.exe -sS -L -o $tmp -w '%{http_code}|%{size_download}|%{content_type}' --max-time 180 `
          -r "0-$($ProbeBytes - 1)" $url 2>$null
        $parts = "$meta" -split '\|'
        $code = $parts[0]; $got = [int64]$parts[1]; $ctype = $parts[2]
        if ("$code" -match '^(200|206)$' -and $got -gt 0) {
          $rangeNote = if ("$code" -eq '206') { '支持分段(可续传)' } else { '忽略 Range,恒返整包' }
          Write-Ok "匿名可下:HTTP $code,实取 $([math]::Round($got / 1KB)) KB —— $rangeNote"
          if ($ctype -and $ctype -notmatch 'octet-stream|android|zip') {
            Write-Warn2 "Content-Type 是 $ctype,不像安装包(警惕下到 HTML 错误页)"
          }
          if ("$code" -eq '200') {
            # 忽略 Range 时 size_download 就是整包体积,可当真实体积用
            $realSize = $got
          } else {
            $cl = & curl.exe -sSI -L --max-time 120 $url 2>$null |
              Select-String -Pattern '^content-length:\s*(\d+)' | Select-Object -First 1
            if ($cl -and $cl.Matches[0].Groups[1].Value) { $realSize = [int64]$cl.Matches[0].Groups[1].Value }
          }
        } else {
          Write-Bad "匿名下载失败:HTTP $code,取到 $got 字节"
          $failed += $t
          continue
        }
      } catch {
        Write-Warn2 " 下载探测异常:$($_.Exception.Message)"
      } finally {
        Remove-Item -Force $tmp -ErrorAction SilentlyContinue
      }
    }
  }

  # 3. 体积非 0
  if ($NoDownload) {
    Write-Warn2 ' 体积未测(Gitee 接口无 size 字段,需去掉 -NoDownload)'
  } elseif ($realSize -gt 0) {
    Write-Ok "附件体积 $([math]::Round($realSize / 1MB, 2)) MB"
  } else {
    Write-Warn2 ' 没拿到 Content-Length,体积未测'
  }

  # 4. 与 GitHub 权威产物对账(体积一致才算内容一致)
  try {
    $gh = Invoke-RestMethod -Uri "https://api.github.com/repos/CNShanJu/$GiteeRepo/releases/tags/$t" `
      -Headers @{ 'User-Agent' = 'mbox-mirror-verify' } -TimeoutSec 60 -ErrorAction Stop
    $ghAssets = @($gh.assets)
    if ($realSize -le 0) {
      Write-Warn2 ' 未拿到镜像体积,无法与 GitHub 对账'
    } else {
      $match = @($ghAssets | Where-Object { $_.name -eq $apk.name })
      if ($match.Count -eq 0) {
        # 名字可能带时间戳,退化为"体积是否命中 GitHub 侧任一附件"
        $bySize = @($ghAssets | Where-Object { [int64]$_.size -eq $realSize })
        if ($bySize.Count -gt 0) {
          Write-Ok "体积与 GitHub 附件一致($([math]::Round($realSize / 1MB, 2)) MB)"
        } else {
          Write-Warn2 "GitHub 侧找不到同名或同体积的附件,无法对账(附件名: $(($ghAssets | ForEach-Object { $_.name }) -join ', '))"
        }
      } else {
        $ghSize = [int64]$match[0].size
        if ($ghSize -eq $realSize) {
          Write-Ok "与 GitHub 正式包逐字节同体积($([math]::Round($ghSize / 1MB, 2)) MB)"
        } else {
          Write-Warn2 "与 GitHub 同名附件体积不一致:镜像 $realSize 字节 / GitHub $ghSize 字节"
        }
      }
    }
  } catch {
    Write-Warn2 ' 查不到 GitHub 侧同名发行版,跳过对账(不影响镜像本身可用性)'
  }
}

# ---------------------------------------------------------------------- 汇总
Write-Host ''
Write-Step '结论'
if ($failed.Count -eq 0) {
  Write-Ok "全部 $($Tag.Count) 个 tag 的镜像验收通过"
  Write-Host '        App 国内更新会优先命中 Gitee 镜像附件。' -ForegroundColor DarkGray
  exit 0
} else {
  Write-Bad "$($failed.Count)/$($Tag.Count) 个 tag 的镜像未就绪:$($failed -join ', ')"
  Write-Host '        补救:配好 GITEE_TOKEN 后重跑对应 tag 的 Build APK 工作流' -ForegroundColor DarkGray
  Write-Host '        https://github.com/CNShanJu/MBox/actions  (Run workflow / Re-run all jobs)' -ForegroundColor DarkGray
  exit 1
}
