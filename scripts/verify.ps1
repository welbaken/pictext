# 图读一键验证：算法单测 + Android debug 构建。
# 用法：powershell -ExecutionPolicy Bypass -File scripts/verify.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (Test-Path ".\gradlew.bat") {
        & ".\gradlew.bat" :core:test :app:assembleDebug --console=plain
    } else {
        $gradle = Get-Command gradle -ErrorAction SilentlyContinue
        if (-not $gradle) {
            throw "未找到 gradlew.bat 或 gradle；请先安装 JDK 17 与 Gradle 8.9。"
        }
        & $gradle.Source ":core:test" ":app:assembleDebug" "--console=plain"
    }
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
    Write-Host "OK: :core:test 与 :app:assembleDebug 通过"
} finally {
    Pop-Location
}
