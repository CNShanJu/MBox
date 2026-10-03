<#
.SYNOPSIS
  交互式把 Gitee 私人令牌安全落盘到一个临时文件,供本机补传/验收脚本使用。

.DESCRIPTION
  为什么要单独有这个脚本(别再用 `powershell -Command "..."` 那种一行式):
  在已打开的 PowerShell 里执行 `powershell -Command "…$t…"` 时,**外层会话会先把 $t / $b / $p 展开**,
  等传给子进程时变量已经变成空值,于是报一堆「"("后面应为表达式」「必须在"+"运算符后面提供一个值表达式」。
  本脚本用单引号路径调用,不经过外层展开,直接跑就行。

  文件内容只有令牌本身(UTF-8 无 BOM,首尾空白已去除),默认写到 %TEMP%\gitee-token.txt。
  **用完请删掉**:脚本末尾会打印删除命令。

.PARAMETER Path
  令牌落盘路径。默认 %TEMP%\gitee-token.txt。

.PARAMETER Force
  目标文件已存在时直接覆盖(默认会先问一次,避免覆盖别的令牌)。

.EXAMPLE
  # 在仓库目录下直接跑(注意用【单引号】路径,或直接写相对路径)
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\save-gitee-token.ps1

.NOTES
  编码:本文件存为 **UTF-8 with BOM**(PS 5.1 按系统代码页读无 BOM 的 .ps1 会中文全乱)。
  安全:输入不回显;脚本不打印令牌内容,只打印长度与落盘路径。
#>
[CmdletBinding()]
param(
  [string]$Path,
  [switch]$Force
)

$ErrorActionPreference = 'Stop'

try {
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

if (-not $Path) { $Path = Join-Path $env:TEMP 'gitee-token.txt' }

if ((Test-Path $Path) -and -not $Force) {
  Write-Host "目标文件已存在:$Path" -ForegroundColor Yellow
  $ans = Read-Host '覆盖它?输入 y 继续,其他任意键取消'
  if ($ans -ne 'y') { Write-Host '已取消,未改动任何文件。'; exit 0 }
}

Write-Host ''
Write-Host '请粘贴 Gitee 私人令牌后回车(输入不回显,不会写入命令历史):' -ForegroundColor Cyan
$sec = Read-Host -AsSecureString

$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
try {
  $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
  [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
$plain = "$plain".Trim()

if ([string]::IsNullOrWhiteSpace($plain)) {
  Write-Host '未读到内容(空输入),已取消。' -ForegroundColor Red
  exit 1
}

# UTF-8 无 BOM:脚本读取端用 UTF8 解析,带 BOM 会把 BOM 当成令牌的一部分
[System.IO.File]::WriteAllText($Path, $plain, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host ("已写入:{0}" -f $Path) -ForegroundColor Green
Write-Host ("令牌长度:{0}(不显示内容)" -f $plain.Length) -ForegroundColor Green
Write-Host ''
Write-Host '接下来可以跑(把 <路径> 换成上面的路径):' -ForegroundColor DarkGray
Write-Host '  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-gitee-mirror.ps1 -TokenFile "<路径>"' -ForegroundColor DarkGray
Write-Host '  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sync-gitee-release.ps1 -TokenFile "<路径>" -Tag vX.Y.Z -ApkPath "<APK路径>"' -ForegroundColor DarkGray
Write-Host ''
Write-Host '用完删除(建议):' -ForegroundColor Yellow
Write-Host ("  Remove-Item -Force '{0}'" -f $Path) -ForegroundColor Yellow
