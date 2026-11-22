<#
  取某版本「用户可读更新说明」的正文文件,供 sync-gitee-release.ps1 / watch-gitee-releases.ps1 共用。

  解析逻辑只有一份:scripts/release-notes.js —— 它读 doc/release/release-notes.md 里 `## v<tag>` 那一节
  (更新说明已汇总到一个文件,不再逐版本新建)。本文件只是 PowerShell 侧的薄包装:

    1. 有 node → 调 release-notes.js get <tag> --out <临时文件>;
    2. 取不到(或没有 node)→ 兜底老的逐版本文件 doc/release/release-notes-<tag>.md(仓库里只留最新一个);
    3. 都没有 → 返回 $null,由调用方决定写什么占位正文。

  返回:临时文件路径(string)或 $null。
#>
function Get-ReleaseNotesFile {
  param(
    [Parameter(Mandatory = $true)][string]$RepoRoot,
    [Parameter(Mandatory = $true)][string]$Tag
  )

  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) "mbox-release-notes-$Tag.md"
  if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }

  $resolver = Join-Path $RepoRoot 'scripts/release-notes.js'
  if ((Test-Path $resolver) -and (Get-Command node -ErrorAction SilentlyContinue)) {
    & node $resolver get $Tag --out $tmp 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0 -and (Test-Path $tmp)) { return $tmp }
  }

  # 兼容兜底:老的逐版本文件(仅 release-notes-v3.6.11.md 这类最新一份还在)
  $legacy = Join-Path $RepoRoot "doc/release/release-notes-$Tag.md"
  if (Test-Path $legacy) { return $legacy }

  return $null
}
