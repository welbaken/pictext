# 用 HTML 真值（DOM `data-*`）给 v2 的区域检测打分。
#
# 前置：先跑批量脚本，`testdata/out/v2/<id>@<width>/regions.json` 已存在：
#   pwsh -NoProfile -File scripts/score-v2.ps1 -Dirs testdata/layout/screens
#
# 用法：
#   pwsh -NoProfile -File scripts/score-detection.ps1
#   pwsh -NoProfile -File scripts/score-detection.ps1 -Cell 8 -Out testdata/report-detection.md
#
# ## 为什么用“面积覆盖”而不是逐框 IoU
# v2 的 RegionBuilder 会**有意合并**同列的文本碎片、丢弃被包含的小框。逐框一一匹配时，
# 一个合并后的区域与它覆盖的两个 GT 框都不到 IoU 0.5，召回率会凭空掉一半——那测的是
# “有没有一一对应”，不是“内容有没有被覆盖”。所以主指标是同一族（文字类 / 块类）的
# 面积覆盖率，用 8px 网格光栅化求交，对合并/拆分都不敏感：
#   召回 = GT 面积被检出覆盖的比例；准确 = 检出面积落在 GT 内的比例。
# 行覆盖单独看：GT 文字行的中心点是否落在任一“文字类”检出里。
param(
    [int]$Cell = 8,
    [string]$Out = "testdata/report-detection.md"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$textKinds = @("TEXT", "TITLE", "CAPTION")
$gtTextKinds = @("text", "title", "caption")

function New-Grid([int]$w, [int]$h, [int]$cell) {
    $cols = [Math]::Max(1, [int][Math]::Ceiling($w / $cell))
    $rows = [Math]::Max(1, [int][Math]::Ceiling($h / $cell))
    return @{ cols = $cols; rows = $rows; bits = (New-Object 'bool[]' ($cols * $rows)) }
}

function Mark-Rect($grid, [int]$cell, [double[]]$r) {
    $x0 = [Math]::Max(0, [int][Math]::Floor($r[0] / $cell))
    $y0 = [Math]::Max(0, [int][Math]::Floor($r[1] / $cell))
    $x1 = [Math]::Min($grid.cols - 1, [int][Math]::Floor(($r[2] - 0.01) / $cell))
    $y1 = [Math]::Min($grid.rows - 1, [int][Math]::Floor(($r[3] - 0.01) / $cell))
    for ($y = $y0; $y -le $y1; $y++) {
        $base = $y * $grid.cols
        for ($x = $x0; $x -le $x1; $x++) { $grid.bits[$base + $x] = $true }
    }
}

function Count-Bits($grid) {
    $n = 0
    foreach ($b in $grid.bits) { if ($b) { $n++ } }
    return $n
}

function Count-Both($a, $b) {
    $n = 0
    for ($i = 0; $i -lt $a.bits.Length; $i++) { if ($a.bits[$i] -and $b.bits[$i]) { $n++ } }
    return $n
}

$rowsOut = New-Object System.Collections.Generic.List[object]

Get-ChildItem "testdata/layout/ground-truth" -Filter "*.json" | Sort-Object Name | ForEach-Object {
    $gtFile = $_
    $name = $gtFile.BaseName                     # 01-article-light@1080
    $gt = Get-Content $gtFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $regionsFile = Join-Path "testdata/out/v2/$name" "regions.json"
    if (-not (Test-Path $regionsFile)) {
        Write-Host "skip $name（没有 regions.json，先跑 score-v2.ps1）"
        return
    }
    $det = Get-Content $regionsFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $w = [int]$gt.width
    $h = [int]$gt.height

    $gtText = New-Grid $w $h $Cell
    $gtBlock = New-Grid $w $h $Cell
    $detText = New-Grid $w $h $Cell
    $detBlock = New-Grid $w $h $Cell
    $textDets = @()

    foreach ($g in $gt.regions) {
        $rect = @(
            [double]($g.box.x), [double]($g.box.y),
            ([double]($g.box.x) + [double]($g.box.w)), ([double]($g.box.y) + [double]($g.box.h))
        )
        if ($gtTextKinds -contains [string]$g.kind) { Mark-Rect $gtText $Cell $rect }
        else { Mark-Rect $gtBlock $Cell $rect }
    }

    foreach ($r in $det.regions) {
        $rect = @(
            [double]($r.bounds[0]), [double]($r.bounds[1]),
            [double]($r.bounds[2]), [double]($r.bounds[3])
        )
        if ($textKinds -contains [string]$r.kind) {
            Mark-Rect $detText $Cell $rect
            $textDets += , $rect
        } else {
            Mark-Rect $detBlock $Cell $rect
        }
    }

    $gtTotal = (Count-Bits $gtText) + (Count-Bits $gtBlock)
    $detTotal = (Count-Bits $detText) + (Count-Bits $detBlock)
    $gtCovered = (Count-Both $gtText $detText) + (Count-Both $gtBlock $detBlock)
    $detInside = (Count-Both $detText $gtText) + (Count-Both $detBlock $gtBlock)

    # 行覆盖：GT 文字行中心落在任一文字类检出里。
    $lineTotal = 0
    $lineCovered = 0
    foreach ($g in $gt.regions) {
        foreach ($ln in $g.lines) {
            $lineTotal++
            $cx = [double]($ln.x) + [double]($ln.w) / 2
            $cy = [double]($ln.y) + [double]($ln.h) / 2
            $hit = $false
            foreach ($rect in $textDets) {
                if ($cx -ge $rect[0] -and $cx -le $rect[2] -and $cy -ge $rect[1] -and $cy -le $rect[3]) {
                    $hit = $true
                    break
                }
            }
            if ($hit) { $lineCovered++ }
        }
    }

    $rowsOut.Add([pscustomobject]@{
        name       = $name
        gtRegions  = $gt.regions.Count
        detRegions = $det.regions.Count
        recall     = if ($gtTotal -gt 0) { $gtCovered / $gtTotal } else { 0 }
        precision  = if ($detTotal -gt 0) { $detInside / $detTotal } else { 0 }
        lineCov    = if ($lineTotal -gt 0) { $lineCovered / $lineTotal } else { 1 }
        orderAmb   = [string]$det.orderAmbiguous
        strategy   = [string]$det.strategy
        fallback   = [string]$det.fallbackCode
    })
}

if ($rowsOut.Count -eq 0) { throw "没有任何结果：先跑 scripts/score-v2.ps1 生成 regions.json" }

$v2Rows = @($rowsOut | Where-Object { $_.strategy -eq "DOC_LAYOUT" })
$meanRecall = ($v2Rows | Measure-Object recall -Average).Average
$meanPrec = ($v2Rows | Measure-Object precision -Average).Average
$meanLine = ($v2Rows | Measure-Object lineCov -Average).Average
$minRecall = ($v2Rows | Measure-Object recall -Minimum).Minimum

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# v2 区域检测评分（对 HTML 真值）")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("- 生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')，网格 ${Cell}px，类别同族才算覆盖")
[void]$sb.AppendLine("- 只统计 v2 真正接管的 $($v2Rows.Count)/$($rowsOut.Count) 张（回退 v1 的页不计入均值）")
[void]$sb.AppendLine(("- 平均召回 **{0:P1}**，平均准确 **{1:P1}**，平均行覆盖 **{2:P1}**，最低召回 {3:P1}" -f `
            $meanRecall, $meanPrec, $meanLine, $minRecall))
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 夹具 | 链路 | GT 区域 | 检出区域 | 召回（面积） | 准确（面积） | 行覆盖 | 顺序歧义 |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|")
foreach ($r in $rowsOut) {
    [void]$sb.AppendLine(("| {0} | {1} | {2} | {3} | {4:P0} | {5:P0} | {6:P0} | {7} |" -f `
                $r.name, $r.strategy, $r.gtRegions, $r.detRegions, $r.recall, $r.precision, $r.lineCov, $r.orderAmb))
}
$sb.ToString() | Set-Content -Path $Out -Encoding UTF8

$rowsOut | Format-Table name, gtRegions, detRegions,
@{n = 'recall'; e = { "{0:P0}" -f $_.recall } },
@{n = 'prec'; e = { "{0:P0}" -f $_.precision } },
@{n = 'lineCov'; e = { "{0:P0}" -f $_.lineCov } }, orderAmb -AutoSize
Write-Host ("report: {0}  平均召回 {1:P1}  平均准确 {2:P1}  平均行覆盖 {3:P1}" -f `
        $Out, $meanRecall, $meanPrec, $meanLine)

