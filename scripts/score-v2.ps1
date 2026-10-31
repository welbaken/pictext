# 批量跑 v2 版式链路，产出可比对的统计表（docs/07 V2-13 / V2-33）。
#
# 用法（脚本是 UTF-8 无 BOM，请用 pwsh 7，Windows PowerShell 5.1 会按 ANSI 读成乱码）：
#   pwsh -NoProfile -File scripts/score-v2.ps1
#   pwsh -NoProfile -File scripts/score-v2.ps1 -Dirs testdata/samples
#
# 输出：
#   testdata/report-v2.csv      每张图一行（strategy / v2ok / fallback / regions / lines / tiles / coverage / 耗时）
#   testdata/report-v2.md       表格版，直接贴进 docs
#
# 说明：真实截图在 testdata/samples（gitignore），HTML 夹具在 testdata/layout/screens（入库）。
param(
    [string]$Model = "models/doclayout/PP-DocLayout-S.onnx",
    [string]$OutRoot = "testdata/out/v2",
    [string[]]$Dirs = @("testdata/samples", "testdata/layout/screens")
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$cli = Join-Path $root "cli/build/install/cli/bin/cli.bat"
if (-not (Test-Path $cli)) {
    Write-Host "building cli distribution..."
    & (Join-Path $root "gradlew.bat") ":cli:installDist" --console=plain -q
}
if (-not (Test-Path $Model)) {
    throw "model not found: $Model (run tools/doclayout_reference.py or docs/07 V2-00 first)"
}

$rows = New-Object System.Collections.Generic.List[object]

$images = @()
foreach ($d in $Dirs) {
    if (Test-Path $d) {
        $images += Get-ChildItem $d -File | Where-Object { $_.Extension -match '^\.(png|jpg|jpeg|webp)$' }
    }
}
Write-Host "images: $($images.Count)"

foreach ($img in $images) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($img.Name)
    $out = Join-Path $OutRoot $name
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $log = & $cli $img.FullName $out --out-width 1080 --layout --model $Model --dump-regions 2>&1
    $sw.Stop()
    $text = ($log | Out-String)

    # 解析 CLI 的稳定输出行（改格式时同步改这里）。
    $strategy = $null
    $decodeFailed = $text -match 'cannot decode image'
    $v2code = if ($text -match 'v2fallback=(\w+)') { $Matches[1] } else { "-" }
    if ($v2code -eq "-") {
        if ($text -match '(?m)^v2 (layout_\w+)') { $v2code = $Matches[1] }
    }
    if ($decodeFailed) {
        $strategy = "decode_failed"
    } elseif ($text -match 'strategy=(\w+)') {
        $strategy = $Matches[1]
    } else {
        $strategy = "?"
    }
    $fallback = if ($text -match 'fallback=([a-z_]+)') { $Matches[1] } else { "none" }
    $mode = if ($text -match 'mode=(\w+)') { $Matches[1] } else { "?" }
    $v2ok = ($strategy -eq "DOC_LAYOUT" -and $v2code -eq "none")
    $regions = if ($text -match 'regions=(\d+)') { [int]$Matches[1] } else { 0 }
    $lines = if ($text -match 'lines=(\d+)') { [int]$Matches[1] } else { 0 }
    $tiles = if ($text -match 'tiles=(\d+)') { [int]$Matches[1] } else { 0 }
    $coverage = if ($text -match 'coverage=([\d.]+)') { [double]$Matches[1] } else { 0 }
    $ambiguous = if ($text -match 'ambiguous=(\w+)') { $Matches[1] } else { "-" }
    $auditOk = if ($text -match 'auditV2=(\w+)') { $Matches[1] } else { "-" }
    $height = if ($text -match 'output=\d+x(\d+)') { [int]$Matches[1] } else { 0 }

    # regions.json 里有每类的数量，用来观察模型在这张图上“认出了什么”。
    $kinds = "-"
    $regionsJson = Join-Path $out "regions.json"
    if (Test-Path $regionsJson) {
        try {
            $rj = Get-Content $regionsJson -Raw -Encoding UTF8 | ConvertFrom-Json
            $kinds = ($rj.regions | Group-Object kind | Sort-Object Count -Descending |
                ForEach-Object { "$($_.Name):$($_.Count)" }) -join " "
        } catch {
            $kinds = "-"
        }
    }

    # plan.json：同一 src 被渲染多次（V2-48）与丢失的块（itemsDropped）都是硬缺陷。
    $dupSrc = 0
    $dropped = 0
    $planJson = Join-Path $out "plan.json"
    if (Test-Path $planJson) {
        try {
            $textPlan = Get-Content $planJson -Raw -Encoding UTF8
            if ($textPlan -match '"itemsDropped":\s*(-?\d+)') { $dropped = [int]$Matches[1] }
            # 只匹配 itemsDetail 里的 `"src":[…]`（顶层是 `"src": [w, h]`，带空格，不会命中）。
            $srcSeen = @{}
            foreach ($m in [regex]::Matches($textPlan, '"src":\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\]')) {
                $key = $m.Value
                $srcSeen[$key] = 1 + [int]$srcSeen[$key]
            }
            $dupSrc = @($srcSeen.Values | Where-Object { $_ -gt 1 }).Count
        } catch {
            $dupSrc = -1
            $dropped = -1
        }
    }

    $row = [pscustomobject]@{
        name     = $name
        strategy = $strategy
        v2code   = $v2code
        v2ok     = $v2ok
        fallback = $fallback
        mode     = $mode
        regions  = $regions
        lines    = $lines
        tiles    = $tiles
        coverage = $coverage
        ambig    = $ambiguous
        audit    = $auditOk
        height   = $height
        ms       = [int]$sw.ElapsedMilliseconds
        dupSrc   = $dupSrc
        dropped  = $dropped
        kinds    = $kinds
    }
    $rows.Add($row)
    Write-Host ("{0,-34} {1,-13} v2ok={2,-5} mode={3,-8} code={4,-22} regions={5,-3} cover={6,-6} dup={7} drop={8} {9}ms" -f `
            $name, $row.strategy, $row.v2ok, $row.mode, $row.v2code, $row.regions, $row.coverage, $row.dupSrc, $row.dropped, $row.ms)
}

$csv = Join-Path $root "testdata/report-v2.csv"
$rows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8

$v2 = ($rows | Where-Object { $_.v2ok }).Count
$v2Fail = ($rows | Where-Object { $_.strategy -eq "DOC_LAYOUT" -and -not $_.v2ok }).Count
$decodeFail = ($rows | Where-Object { $_.strategy -eq "decode_failed" }).Count
$ok = ($rows | Where-Object { $_.audit -eq "ok" }).Count
$ambig = ($rows | Where-Object { $_.ambig -eq "true" }).Count
$dupPages = ($rows | Where-Object { $_.dupSrc -gt 0 }).Count
$dupGroups = ($rows | Measure-Object -Property dupSrc -Sum).Sum
$dropPages = ($rows | Where-Object { $_.dropped -gt 0 }).Count
$dropItems = ($rows | Measure-Object -Property dropped -Sum).Sum

$md = Join-Path $root "testdata/report-v2.md"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# v2 批量统计")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("- 生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')")
[void]$sb.AppendLine("- 模型：$Model")
[void]$sb.AppendLine("- 图片：$($rows.Count) 张（v2 成功 $v2，v2 失败显示原图 $v2Fail，解码失败 $decodeFail，审计通过 $ok，顺序歧义 $ambig）")
[void]$sb.AppendLine("- 说明：强化重排失败**不再回退 v1**；mode=ORIGINAL + fallback=LAYOUT_FALLBACK 即失败")
[void]$sb.AppendLine("- 内容完整性：重复 src $dupPages 张 / $dupGroups 组，丢块 $dropPages 张 / $dropItems 个")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 图片 | 链路 | v2 成功 | 模式 | v2 诊断 | 计划回退 | 区域 | 行 | tile | 覆盖 | 歧义 | 审计 | 高度 | 重复src | 丢块 | 耗时 |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
foreach ($r in $rows) {
    [void]$sb.AppendLine("| $($r.name) | $($r.strategy) | $($r.v2ok) | $($r.mode) | $($r.v2code) | $($r.fallback) | $($r.regions) | $($r.lines) | $($r.tiles) | $($r.coverage) | $($r.ambig) | $($r.audit) | $($r.height) | $($r.dupSrc) | $($r.dropped) | $($r.ms)ms |")
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## v2 诊断码分布")
[void]$sb.AppendLine("")
$rows | Group-Object v2code | Sort-Object Count -Descending | ForEach-Object {
    [void]$sb.AppendLine("- ``$($_.Name)``：$($_.Count)")
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 区域类别分布（regions.json）")
[void]$sb.AppendLine("")
foreach ($r in $rows) {
    if ($r.kinds -ne "-") { [void]$sb.AppendLine("- $($r.name)：$($r.kinds)") }
}
$sb.ToString() | Set-Content -Path $md -Encoding UTF8

Write-Host ""
Write-Host "csv: $csv"
Write-Host "md : $md"
Write-Host "v2=$v2 v2Fail=$v2Fail decodeFail=$decodeFail auditOk=$ok ambiguous=$ambig dupPages=$dupPages dupGroups=$dupGroups dropPages=$dropPages dropItems=$dropItems"
