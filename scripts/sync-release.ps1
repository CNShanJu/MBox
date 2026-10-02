<#
.SYNOPSIS
  MBox 发包推送:一次把 main 与 tag 推到 GitHub(权威)与 Gitee(国内镜像)。

.DESCRIPTION
  背景:本仓库的代码托管在 GitHub(origin),Gitee 只是**代码 + 发行版附件的镜像**。
  镜像曾经靠手工推,导致 main 停留在 v3.6.1、落后 16 个提交、tag 缺 v3.6.2 —— 本脚本把这个
  缺口收口成一条命令,并保证 GitHub 永远是权威:GitHub 推失败就整体失败,Gitee 推失败只告警。

  与 CI 的分工:
    - 本脚本只负责 **git 对象**(提交与 tag);
    - 发行版正文与 APK 附件由 .github/workflows/build-apk.yml 在 tag 触发后创建/上传,
      其中 'Sync release APK to Gitee mirror' 步把 APK 传到 Gitee 发行版附件;
    - 所以顺序必须是「先把代码和 tag 推到 Gitee,再等 CI 建 Gitee 发行版」——
      CI 里那步要先按 tag 找到 Gitee 发行版,镜像仓库没有该 tag 时它会告警跳过。

  版本纪律(AGENTS.md §九.4):push 前自动升小版本号是**调用方**的事,本脚本不代为升版本,
  只在推 tag 时校验「要推的 tag == app/app_config.properties 的 versionName」,防止推错版本。

.PARAMETER DryRun
  只做前置校验并打印将要推送的内容,不真正推送(不写远端、不建远端)。

.PARAMETER GiteeOnly
  只同步 Gitee(用于镜像掉队后补推);GitHub 侧完全不动。

.PARAMETER AllTags
  把 Gitee 缺失的**全部历史 tag** 也推上去。默认只推 app_config.properties 里当前 versionName
  对应的那一个 tag —— 历史 tag 推上去补不出旧发行版,却会让 CI 对每个 v* tag 各跑一次构建。

.PARAMETER GiteeUrl
  Gitee 镜像仓库地址。默认 https://gitee.com/CnAyo/MBox.git。
  远端名 'gitee' 不存在时会以该地址自动补上(仅改本仓库 .git/config)。

.PARAMETER Branch
  要同步的分支,默认当前分支。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-release.ps1 -DryRun
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-release.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/sync-release.ps1 -GiteeOnly

.NOTES
  编码:本文件必须存为 **UTF-8 with BOM**。Windows PowerShell 5.1 对无 BOM 的 .ps1 按系统
  代码页(中文 Windows 是 GBK)读取,会把中文字符串读坏并报一堆 "Unexpected token"。
  若脚本报出乱码式语法错误,先确认 BOM 还在(前 3 字节 EF BB BF)。

  凭据:GitHub 与 Gitee 都走 git credential manager(Windows 凭据管理器)。
  未登录时会直接报错退出,不会卡在交互式账号输入上(已设 GIT_TERMINAL_PROMPT=0)。
#>
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$GiteeOnly,
  [switch]$AllTags,
  [string]$GiteeUrl = 'https://gitee.com/CnAyo/MBox.git',
  [string]$Branch
)

$ErrorActionPreference = 'Stop'

# ---- 输出统一 UTF-8,避免中文在 Windows PowerShell 5.1 下变问号 ----
try {
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
  $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "    OK  $Text" -ForegroundColor Green }
function Write-Warn2([string]$Text) { Write-Host "    警告 $Text" -ForegroundColor Yellow }
function Write-Err([string]$Text) { Write-Host "    失败 $Text" -ForegroundColor Red }

function Fail([string]$Message, [string[]]$Hints) {
  Write-Err $Message
  foreach ($h in $Hints) { Write-Host "        - $h" -ForegroundColor DarkGray }
  exit 1
}

# git 调用统一入口:$LASTEXITCODE 由调用方检查;禁止交互式索要账号密码。
#
# 关键:git 把进度/摘要写 **stderr**(如 "To https://..."、" * [new tag]"、fetch 的 "From ..."),
# 在 $ErrorActionPreference='Stop' 下,`2>&1` 把这些 stderr 行变成 ErrorRecord 并**直接终止脚本** ——
# 现象是推到一半(推完 GitHub、还没推 Gitee)就退出。这里在调用点临时把偏好降为 Continue,
# 只让 $LASTEXITCODE 承担判断;函数作用域内的赋值不会泄漏到脚本其余部分。
function Invoke-Git {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
  $prev = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    & git @GitArgs 2>&1
  } finally {
    $ErrorActionPreference = $prev
  }
}

function Get-GitOutput {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
  $prev = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    $out = & git @GitArgs 2>&1
  } finally {
    $ErrorActionPreference = $prev
  }
  return ($out | Out-String).Trim()
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
  Fail ' 找不到 git,请先安装并加入 PATH。' @()
}

$env:GIT_TERMINAL_PROMPT = '0'
$env:GIT_PAGER = 'cat'

# 本仓库根目录 = 本脚本所在目录的上一级
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $repoRoot '.git'))) {
  Fail " 脚本不在 MBox 仓库内:$repoRoot" @('请在仓库里运行: pwsh -File scripts/sync-release.ps1')
}
Push-Location $repoRoot
try {
  # ---------------------------------------------------------------- 前置校验
  Write-Step '前置校验'

  $head = Get-GitOutput rev-parse --abbrev-ref HEAD
  if ([string]::IsNullOrWhiteSpace($head) -or $head -eq 'HEAD') {
    Fail ' 当前处于分离头指针(detached HEAD)状态,无法推送分支。' @('先 git switch main 再执行。')
  }
  if (-not $Branch) { $Branch = $head }
  Write-Ok "仓库:$repoRoot"
  Write-Ok "分支:$Branch(当前 HEAD:$head)"

  $dirty = Get-GitOutput status --porcelain
  if ($dirty) {
    Fail ' 工作区有未提交改动,拒绝推送(避免把半成品推上去)。' @(
      '先 git status 看清改动并提交或 stash,再重新执行。'
    )
  }
  Write-Ok '工作区干净'

  # 远端检查
  $remotes = (Get-GitOutput remote) -split "`r?`n" | Where-Object { $_ }
  if (-not $GiteeOnly -and ($remotes -notcontains 'origin')) {
    Fail " 缺少 GitHub 远端 origin。" @('git remote add origin https://github.com/CNShanJu/MBox.git')
  }
  $giteeRemoteExists = $remotes -contains 'gitee'
  if (-not $giteeRemoteExists) {
    if ($DryRun) {
      Write-Warn2 "远端 gitee 不存在;正式执行时会自动添加 ->$GiteeUrl"
    } else {
      Write-Warn2 "远端 gitee 不存在,自动添加 ->$GiteeUrl"
      Invoke-Git remote add gitee $GiteeUrl | Out-Null
      if ($LASTEXITCODE -ne 0) { Fail ' 添加 gitee 远端失败。' @() }
      $giteeRemoteExists = $true
    }
  }
  $giteePushUrl = if ($giteeRemoteExists) { (Get-GitOutput remote get-url gitee) } else { $GiteeUrl }
  Write-Ok "Gitee 远端:$giteePushUrl"

  # 本地分支必须不落后于来源远端(避免把旧历史推成回退)
  if (-not $GiteeOnly) {
    Invoke-Git fetch origin --tags --prune | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Fail ' git fetch origin 失败(网络或凭据问题)。' @('确认能访问 https://github.com/CNShanJu/MBox.git。')
    }
    # 抢先拦下"上游有本地没有的提交":这种发散直接 push 必然被拒,而且会造成
    # GitHub 推成功、Gitee 推失败的半同步状态(镜像掉队还得多跑一次 -GiteeOnly)。
    # 注意:GiteeOnly 模式不校验——只补推镜像时本地暂时落后也无妨。
    $b = (Get-GitOutput rev-list --left-right --count "origin/$Branch...$Branch")
    if ($LASTEXITCODE -eq 0 -and $b -match '^(\d+)\s+(\d+)$') {
      $behind = [int]$Matches[1]
      if ($behind -gt 0) {
        Fail " 本地 $Branch 落后 origin/$Branch $behind 个提交,已拒绝推送。" @(
          "先 git pull --rebase origin $Branch 把上游提交并进来,再重新执行。",
          '直接推会被远端拒绝,并留下 GitHub/Gitee 一端的半同步状态。'
        )
      }
    }
  }

  # ------------------------------------------------------------ 待推送内容
  Write-Step '待推送内容'

  # 本地相对上游领先的提交
  $upstream = "origin/$Branch"
  $aheadList = @()
  if (-not $GiteeOnly) {
    $hasUpstream = (Get-GitOutput rev-parse --verify --quiet "refs/remotes/$upstream")
    if ($hasUpstream) {
      $aheadList = (Get-GitOutput log --oneline "$upstream..$Branch") -split "`r?`n" | Where-Object { $_ }
    } else {
      Write-Warn2 "远端分支 $upstream 不存在,将新建(推送全部历史)。"
    }
  }
  if ($aheadList.Count -gt 0) {
    Write-Ok "领先 $upstream $($aheadList.Count) 个提交:"
    $aheadList | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
  } elseif (-not $GiteeOnly) {
    Write-Ok "与 $upstream 一致,无新提交(仍会补推缺失的 tag)"
  }

  # tag 漂移:本地有、Gitee 没有的
  $localTags = @((Get-GitOutput tag --list 'v*') -split "`r?`n" | Where-Object { $_ })
  $giteeTags = @()
  if ($giteeRemoteExists) {
    $giteeTagsOut = Get-GitOutput ls-remote --tags gitee
    if ($LASTEXITCODE -eq 0 -and $giteeTagsOut) {
      $giteeTags = $giteeTagsOut -split "`r?`n" |
        ForEach-Object { ($_ -split 'refs/tags/')[-1] } |
        Where-Object { $_ -and $_ -notlike '*^{}' }
    } else {
      Write-Warn2 ' 读不到 Gitee 现有 tag(网络或鉴权问题),按"全部本地 tag 都缺"继续。'
    }
  } else {
    Write-Warn2 ' 远端 gitee 尚不存在,DryRun 按"全部本地 tag 都缺"估算;正式执行会先建远端再核对。'
  }
  $missingOnGitee = @($localTags | Where-Object { $giteeTags -notcontains $_ })

  # 当前版本 tag:与 app/app_config.properties 的 versionName 对齐,防推错版本
  $versionName = ((Get-Content (Join-Path $repoRoot 'app/app_config.properties') -Encoding UTF8 |
      Where-Object { $_ -match '^\s*versionName\s*=' } |
      Select-Object -First 1) -replace '^\s*versionName\s*=\s*', '').Trim()
  if ([string]::IsNullOrWhiteSpace($versionName)) {
    Fail ' 读不到 app/app_config.properties 的 versionName。' @()
  }
  $currentTag = "v$versionName"

  # 默认只推"当前版本 tag":镜像的用途是当前发版的下载源,历史 tag 推上去既补不出旧发行版,
  # 又会让 CI 对每个 v* tag 各跑一次构建(白烧 Actions 配额)。要全量对齐历史才加 -AllTags。
  $tagCandidates = if ($AllTags) { $missingOnGitee } else { @($missingOnGitee | Where-Object { $_ -eq $currentTag }) }
  $tagSkipped = @($missingOnGitee | Where-Object { $tagCandidates -notcontains $_ })

  if ($missingOnGitee -contains $currentTag) {
    Write-Ok "Gitee 缺少当前版本 tag:$currentTag(推送后会触发 CI 建发行版并同步 APK)"
  } elseif ($localTags -contains $currentTag) {
    Write-Ok "Gitee 已有当前版本 tag:$currentTag"
  } else {
    Write-Warn2 "本地没有当前版本 tag:$currentTag(确认是否已打 tag)"
  }
  if ($tagCandidates.Count -gt 0) {
    Write-Ok "本次将推送 tag:$($tagCandidates -join ', ')"
  }
  if ($tagSkipped.Count -gt 0) {
    Write-Warn2 "Gitee 另缺 $($tagSkipped.Count) 个历史 tag,已跳过(加 -AllTags 才推)"
  }

  if ($DryRun) {
    Write-Step 'DryRun:以下为将要执行的推送(未真正执行)'
    if (-not $GiteeOnly) {
      Write-Host "        git push origin $Branch" -ForegroundColor DarkGray
      if ($tagCandidates.Count -gt 0) {
        Write-Host "        git push origin $($tagCandidates -join ' ')" -ForegroundColor DarkGray
      }
    }
    Write-Host "        git push gitee $Branch" -ForegroundColor DarkGray
    if ($tagCandidates.Count -gt 0) {
      Write-Host "        git push gitee $($tagCandidates -join ' ')" -ForegroundColor DarkGray
    }
    Write-Ok 'DryRun 结束,未写远端。'
    exit 0
  }

  # ------------------------------------------------------- 推送 GitHub(权威)
  $ghOk = $true
  if (-not $GiteeOnly) {
    Write-Step "推送 GitHub(权威):$Branch"
    Invoke-Git push origin $Branch
    if ($LASTEXITCODE -ne 0) {
      $ghOk = $false
      Write-Err ' GitHub 推送失败。'
      Write-Host '        常见原因与处理:' -ForegroundColor DarkGray
      Write-Host '          - 远端有新提交:git pull --rebase origin '"$Branch"' 后重试' -ForegroundColor DarkGray
      Write-Host '          - 凭据失效:git credential-manager github login' -ForegroundColor DarkGray
    } else {
      Write-Ok "GitHub 已推送 $Branch"
      # 只推本次认定的 tag:GitHub 侧 tag 本应已随 push.followTags 上去,这里是重跑兜底
      if ($tagCandidates.Count -gt 0) {
        Invoke-Git push origin @($tagCandidates)
        if ($LASTEXITCODE -ne 0) {
          $ghOk = $false
          Write-Err ' GitHub tag 推送失败(tag 已存在且不一致时需人工确认)。'
        } else {
          Write-Ok "GitHub tag 已推送:$($tagCandidates -join ', ')"
        }
      }
    }
  }

  # ------------------------------------------------------- 推送 Gitee(镜像)
  Write-Step "同步 Gitee 镜像:$Branch"
  $giteeOk = $true
  Invoke-Git push gitee $Branch
  if ($LASTEXITCODE -ne 0) {
    $giteeOk = $false
    Write-Err ' Gitee 分支推送失败。'
    Write-Host '        常见原因与处理:' -ForegroundColor DarkGray
    Write-Host '          - 无权限:需 CnAyo/MBox 的写权限(Gitee 侧通过 https://gitee.com/CnAyo/MBox 申请/授予)' -ForegroundColor DarkGray
    Write-Host '          - 凭据未登录:cmdkey /delete:LegacyGeneric:target=git:https://gitee.com 后重推,按提示登录' -ForegroundColor DarkGray
    Write-Host '          - 网络不通:确认能访问 https://gitee.com' -ForegroundColor DarkGray
  } else {
    Write-Ok "Gitee 已推送 $Branch"
  }

  if ($tagCandidates.Count -gt 0) {
    Invoke-Git push gitee @($tagCandidates)
    if ($LASTEXITCODE -ne 0) {
      $giteeOk = $false
      Write-Err ' Gitee tag 推送失败。'
      Write-Host '        处理:远端已有同名不一致 tag 时,先人工确认不会覆盖他人的 tag 再决定是否强推。' -ForegroundColor DarkGray
    } else {
      Write-Ok "Gitee tag 已推送:$($tagCandidates -join ', ')"
    }
  } else {
    Write-Ok "Gitee tag 无待推送项(当前版本 $currentTag)"
  }

  # ------------------------------------------------------------------ 汇总
  Write-Step '结果'
  if (-not $GiteeOnly) {
    if ($ghOk) { Write-Ok 'GitHub(权威):已同步' } else { Write-Err 'GitHub(权威):失败' }
  }
  if ($giteeOk) {
    Write-Ok 'Gitee(镜像):已同步'
    Write-Host ''
    Write-Host '    提示:tag 推送后 CI 会构建并创建发行版,Gitee 发行版附件由流水线的' -ForegroundColor DarkGray
    Write-Host '    Sync release APK to Gitee mirror 步上传,可在 GitHub Actions 看进度。' -ForegroundColor DarkGray
  } else {
    Write-Warn2 'Gitee(镜像):未同步 —— 镜像掉队不影响发版,补推用:'
    Write-Host '        pwsh -File scripts/sync-release.ps1 -GiteeOnly' -ForegroundColor DarkGray
  }

  # GitHub 是权威:它失败才算整体失败;Gitee 落后按纪律只告警
  if (-not $ghOk) { exit 1 }
  exit 0
} finally {
  Pop-Location
}
