# 图读 · 发行版 V1-only 门禁
#
# 目的：证明 release APK 里**没有任何 v2（版面模型）逻辑**。
#
# 两道检查：
#   1. classes.dex 里不能出现 v2 专有的字符串常量（提示码/诊断码/日志前缀）；
#   2. R8 的 mapping.txt 里不能出现 v2 的业务类——mapping 只列出真正进入产物的类，
#      所以"类不在 mapping 里"就是"类被整类剥掉了"。
#
# v2 的**数据类型**（LayoutRegion / LineCutFeatures / LayoutStrategy / LayoutSummary 等）
# 会被 v1 的模型字段引用，允许残留；它们没有方法体逻辑。脚本会把它们单独列出来。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/verify-v1-only.ps1            # 构建 + 检查
#   powershell -ExecutionPolicy Bypass -File scripts/verify-v1-only.ps1 -SkipBuild # 只检查现有产物
param([switch]$SkipBuild)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not $SkipBuild) {
        Write-Host "构建 release（R8 + 资源压缩）..."
        & ".\gradlew.bat" :app:assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw "release 构建失败，退出码 $LASTEXITCODE" }
    }

    $apk = Get-ChildItem "app\build\outputs\apk\release\*.apk" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $apk) { throw "找不到 release APK：请先运行 :app:assembleRelease" }
    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host "APK: $($apk.Name)  $sizeMb MB"

    # ---- 1. classes.dex 里的 v2 专有常量 ----
    $markers = @(
        "layout_no_detection",
        "layout_too_many_tiles",
        "layout_too_few_regions",
        "layout_single_wall",
        "layout_low_coverage",
        "layout_residual_high",
        "layout_vertical",
        "layout_model=",
        "layout_coverage=",
        "layout_invert=1",
        "layout_audit_failed",
        "layout_plan_failed",
        "layout_strategy=DOC_LAYOUT",
        "items_vertical_overlap(",
        "forced_cut_high(",
        "sensitive_compressed(",
        "region_order_violations("
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        $dexEntries = $zip.Entries | Where-Object { $_.Name -like "classes*.dex" }
        $hits = @()
        foreach ($entry in $dexEntries) {
            $buffer = New-Object System.IO.MemoryStream
            try {
                $entry.Open().CopyTo($buffer)
                # dex 里的 MUTF-8 常量用单字节编码（ISO-8859-1）读一遍，足够做 ASCII 子串匹配
                $latin1 = [System.Text.Encoding]::GetEncoding(28591)
                $text = $latin1.GetString($buffer.ToArray())
                foreach ($m in $markers) {
                    if ($text.Contains($m)) { $hits += "$($entry.Name): $m" }
                }
            } finally {
                $buffer.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
    if ($hits.Count -gt 0) {
        Write-Host "FAIL：release dex 里发现 v2 专有常量：" -ForegroundColor Red
        $hits | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        throw "v2 逻辑没有被剥离（$($hits.Count) 个标记）"
    }
    Write-Host "OK：classes.dex 无 v2 专有常量（$($dexEntries.Count) 个 dex）" -ForegroundColor Green

    # ---- 2. mapping.txt：v2 业务类必须整类消失 ----
    $mapping = "app\build\outputs\mapping\release\mapping.txt"
    if (-not (Test-Path $mapping)) {
        Write-Host "WARN：缺少 $mapping，跳过类级检查" -ForegroundColor Yellow
    } else {
        $mapText = Get-Content $mapping -Raw
        $forbidden = @(
            "app.pictext.core.layout.LayoutPipeline",
            "app.pictext.core.layout.CoverageAuditor",
            "app.pictext.core.layout.RegionRefiner",
            "app.pictext.core.layout.PageComposer",
            "app.pictext.core.layout.ReadingOrder",
            "app.pictext.core.layout.RegionBuilder",
            "app.pictext.core.layout.RegionDeduper",
            "app.pictext.core.layout.RegionKindFixer",
            "app.pictext.core.layout.LayoutTiler",
            "app.pictext.core.layout.LayoutPreprocessor",
            "app.pictext.core.layout.ModelLabels",
            "app.pictext.core.layout.DetectionPostprocessor",
            "app.pictext.core.layout.CutPolicy",
            "app.pictext.core.plan.ReflowPlannerV2",
            "app.pictext.core.plan.LayoutAuditor",
            "app.pictext.core.plan.SmartReflow",
            "app.pictext.core.plan.TrustGate"
        )
        $leaked = $forbidden | Where-Object { $mapText -match ("(?m)^" + [regex]::Escape($_) + "\s") }
        if ($leaked) {
            Write-Host "FAIL：以下 v2 类仍留在 release 产物里：" -ForegroundColor Red
            $leaked | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
            throw "v2 业务类未被 R8 剥离"
        }
        Write-Host "OK：mapping.txt 中无 v2 业务类（检查 $($forbidden.Count) 个）" -ForegroundColor Green

        # 允许残留的 v2 **数据类型**（只被 v1 模型字段引用，无逻辑）。
        $allowedTypes = @(
            "app.pictext.core.model.LayoutRegion",
            "app.pictext.core.model.LineCutFeatures",
            "app.pictext.core.model.LayoutStrategy",
            "app.pictext.core.model.LayoutSummary",
            "app.pictext.core.model.CutKind",
            "app.pictext.core.model.RegionKind",
            "app.pictext.core.model.RegionSource"
        )
        $residue = $allowedTypes | Where-Object { $mapText -match ("(?m)^" + [regex]::Escape($_) + "\s") }
        if ($residue) {
            Write-Host "残留（纯数据类型，允许）：$($residue -join ', ')" -ForegroundColor DarkGray
        } else {
            Write-Host "残留：无" -ForegroundColor Green
        }
    }

    Write-Host "PASS：发行版为 v1-only（$sizeMb MB）" -ForegroundColor Green
} finally {
    Pop-Location
}
