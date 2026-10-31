# 图读一键验证：算法单测 + Android debug 构建。
# 用法：powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 [-Release]
#   -Release  追加发行版门禁（release 构建 + v1-only 检查，约 1 分钟）
param([switch]$Release)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (Test-Path ".\gradlew.bat") {
        & ".\gradlew.bat" :core:test :core:longPageMemoryTest :app:assembleDebug --console=plain
    } else {
        $gradle = Get-Command gradle -ErrorAction SilentlyContinue
        if (-not $gradle) {
            throw "未找到 gradlew.bat 或 gradle；请先安装 JDK 17 与 Gradle 8.9。"
        }
        & $gradle.Source ":core:test" ":core:longPageMemoryTest" ":app:assembleDebug" "--console=plain"
    }
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
    Write-Host "OK: :core:test / :core:longPageMemoryTest / :app:assembleDebug 通过"

    if ($Release) {
        Write-Host "发行版门禁：release 构建 + v2 剥离检查..."
        & (Join-Path $PSScriptRoot "verify-v1-only.ps1")
        if ($LASTEXITCODE -ne 0) { throw "v1-only 门禁失败" }
    }
} finally {
    Pop-Location
}
