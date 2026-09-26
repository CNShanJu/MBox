# 资源命名规范校验（设计方案 P1：资源治理第一步）
# 用法: pwsh -File scripts/check-res-naming.ps1
# 规则:
#   layout  : 前缀必须为 activity_ / fragment_ / item_ / dialog_ / view_ / include_
#   drawable: 前缀必须为 ic_ / bg_ / shape_ / selector_ / img_
# 退出码: 0=合规; 违规数(>0)=有违规
#
# 后续可接入 Lint 自定义规则 / Gradle 校验任务(构建期强制, 违规即失败)。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$errors = 0

$layoutRe    = '^(activity_|fragment_|item_|dialog_|view_|include_)'
$drawableRe  = '^(ic_|bg_|shape_|selector_|img_)'

# 校验范围: app(页面级 + 公共资源,原 :ui-common 已并入 :app)
$layoutDirs   = @(
    (Join-Path $root 'app\src\main\res\layout')
)
$drawableDirs = @(
    (Join-Path $root 'app\src\main\res\drawable')
)

Write-Host '== 资源命名规范校验 =='

foreach ($layoutDir in $layoutDirs) {
    if (Test-Path $layoutDir) {
        Get-ChildItem $layoutDir -File -Filter '*.xml' | ForEach-Object {
            if ($_.Name -notmatch $layoutRe) {
                Write-Host ("[layout] 违规: " + $_.Name)
                $errors++
            }
        }
    } else {
        Write-Host "[layout] 目录不存在: $layoutDir"
    }
}

foreach ($drawableDir in $drawableDirs) {
    if (Test-Path $drawableDir) {
        Get-ChildItem $drawableDir -File | ForEach-Object {
            if ($_.Name -notmatch $drawableRe) {
                Write-Host ("[drawable] 违规: " + $_.Name)
                $errors++
            }
        }
    } else {
        Write-Host "[drawable] 目录不存在: $drawableDir"
    }
}

if ($errors -eq 0) {
    Write-Host '资源命名全部合规 OK'
} else {
    Write-Host "共 $errors 个违规(见上), 请按前缀规范重命名(git mv 同步引用)"
}
exit $errors
