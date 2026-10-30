# V1 vs 混合（v2 优先、失败回退 v1）的收益量化。
#
# 为什么单独一个脚本：`report-v2.csv` 只记 v2 自己的数字，看不出“相比 v1 到底好了多少”。
# 这里对同一张图跑两遍（v1-only / v2+回退），用**用户能感知的指标**对比：
#   - bodyScale：正文字号放大倍率（决定“字有多大”）
#   - mode：是否真的重排（ORIGINAL 表示只能看原图）
#   - lines：被重排的文字行数（决定“有多少内容受益”）
#   - height：重排后的页面高度（滚动长度，仅供参考）
#
# 用法：
#   pwsh -NoProfile -File scripts/ab-v1-v2.ps1
#   pwsh -NoProfile -File scripts/ab-v1-v2.ps1 -Dirs testdata/layout/screens
param(
    [string[]]$Dirs = @("testdata/samples", "testdata/layout/screens"),
    [string]$Model = "models/doclayout/PP-DocLayout-S.onnx",
    [string]$Out = "testdata/report-ab.md"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$cli = Join-Path $root "cli/build/install/cli/bin/cli.bat"
if (-not (Test-Path $cli)) { throw "先跑 .\gradlew.bat :cli:installDist" }
if (-not (Test-Path $Model)) { throw "模型不存在：$Model" }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("pictext-ab-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp | Out-Null

function Parse($text, $pattern, $default) {
    if ($text -match $pattern) { return $Matches[1] }
    return $default
}

$rows = New-Object System.Collections.Generic.List[object]
$files = @()
foreach ($d in $Dirs) {
    if (-not (Test-Path $d)) { continue }
    $files += Get-ChildItem $d -File | Where-Object { $_.Extension -match '^\.(png|jpg|jpeg)$' }
}

foreach ($f in $files) {
    $name = $f.BaseName
    $srcW = 0
    try {
        $img = [System.Drawing.Image]::FromFile($f.FullName)
        $srcW = [int]$img.Width
        $img.Dispose()
    } catch {
        Write-Host "skip $name（读不到图片尺寸）"
        continue
    }
    $outV1 = Join-Path $tmp "v1"
    $outV2 = Join-Path $tmp "v2"
    New-Item -ItemType Directory -Force -Path $outV1, $outV2 | Out-Null

    # ---- v1 only ----
    $t0 = Get-Date
    $l1 = (& $cli $f.FullName $outV1 2>&1 | Out-String)
    $ms1 = [int]((Get-Date) - $t0).TotalMilliseconds
    if ($l1 -match "input not found|cannot decode|Exception") {
        Write-Host "skip $name（v1 解不开）"
        continue
    }
    $v1Mode = Parse $l1 'mode=(\w+)' '?'
    $v1Scale = Parse $l1 'scale=([\d.]+)' '0'
    $v1LineH = Parse $l1 'lineH=([\d.]+)' '0'
    $v1Lines = Parse $l1 'lines=(\d+)' '0'
    $v1Height = Parse $l1 'output=\d+x(\d+)' '0'

    # ---- v2（含回退）----
    $t1 = Get-Date
    $l2 = (& $cli $f.FullName $outV2 --layout --model $Model 2>&1 | Out-String)
    $ms2 = [int]((Get-Date) - $t1).TotalMilliseconds
    $strategy = Parse $l2 'strategy=(\w+)' '?'
    $v2Code = Parse $l2 'v2fallback=(\S+)' 'none'
    $v2ScaleRaw = Parse $l2 'scale=([\d.]+)' '0'
    $v2LineHRaw = Parse $l2 'lineH=([\d.]+)' '0'
    $v2Height = Parse $l2 'output=\d+x(\d+)' '0'
    $v2LinesRaw = Parse $l2 'lines=(\d+)' '0'
    $v2Mode = Parse $l2 'mode=(\w+)' '?'

    # 混合结果：v2 接管就用 v2，否则就是 v1（代码路径完全一致）
    $tookV2 = ($strategy -eq "DOC_LAYOUT")
    $hybScale = if ($tookV2) { [double]$v2ScaleRaw } else { [double]$v1Scale }
    $hybLineH = if ($tookV2) { [double]$v2LineHRaw } else { [double]$v1LineH }
    $hybLines = if ($tookV2) { [int]$v2LinesRaw } else { [int]$v1Lines }
    $hybMode = if ($tookV2) { $v2Mode } else { $v1Mode }
    $hybHeight = if ($tookV2) { [int]$v2Height } else { [int]$v1Height }

    # **可比的“字号”**：输出图里一行字的像素高度。
    # - REFLOW/NO_SPLIT：planner 的 scale × 该布局的行高中位数；
    # - ORIGINAL：没有 planner scale，页面按“适宽”显示，实际倍率是 outW / srcWidth。
    # 直接比 bodyScale 会得出错误结论：v1 的 NO_SPLIT 报 3.0 其实是把窄图缩小了，
    # 而 v2 的 ORIGINAL 反而是适宽放大。
    $outW = 1080
    $v1Eff = if ($v1Mode -eq "ORIGINAL") { [double]$v1LineH * $outW / $srcW } else { [double]$v1Scale * [double]$v1LineH }
    $hybEff = if ($hybMode -eq "ORIGINAL") { $hybLineH * $outW / $srcW } else { $hybScale * $hybLineH }

    # 两边都是 ORIGINAL 时渲染完全一致（都按适宽显示），行高估计的差异不算收益/回归。
    $gain = if ($v1Mode -eq "ORIGINAL" -and $hybMode -eq "ORIGINAL") { 1.0 }
            elseif ($v1Eff -gt 0) { $hybEff / $v1Eff } else { 1.0 }
    $rows.Add([pscustomobject]@{
            name      = $name
            v2        = $tookV2
            code      = $v2Code
            v1Mode    = $v1Mode
            hybMode   = $hybMode
            v1Scale   = [double]$v1Scale
            hybScale  = $hybScale
            v1Eff     = [math]::Round($v1Eff, 1)
            hybEff    = [math]::Round($hybEff, 1)
            gain      = [math]::Round($gain, 3)
            v1Lines   = [int]$v1Lines
            hybLines  = $hybLines
            v1Height  = [int]$v1Height
            hybHeight = $hybHeight
            msV1      = $ms1
            msV2      = $ms2
        })
    Write-Host ("{0,-40} v2={1,-5} 字号 {2,5:N1}->{3,5:N1}px gain={4,5:N2} lines {5,3}->{6,3}" -f `
            $name, $tookV2, $v1Eff, $hybEff, $gain, $v1Lines, $hybLines)
}

Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue

if ($rows.Count -eq 0) { throw "没有任何可比对的结果" }

$v2Rows = @($rows | Where-Object { $_.v2 })
$v1Rows = @($rows | Where-Object { -not $_.v2 })

$better = @($v2Rows | Where-Object { $_.gain -ge 1.05 }).Count
$same = @($v2Rows | Where-Object { $_.gain -gt 0.98 -and $_.gain -lt 1.05 }).Count
$worse = @($v2Rows | Where-Object { $_.gain -le 0.98 }).Count
$medianGain = if ($v2Rows.Count -gt 0) {
    ($v2Rows | Sort-Object gain | Select-Object -ExpandProperty gain)[[int]($v2Rows.Count / 2)]
} else { 1.0 }
$meanGain = if ($v2Rows.Count -gt 0) { ($v2Rows | Measure-Object gain -Average).Average } else { 1.0 }

# v1 只能看原图（ORIGINAL）的页里，有多少被 v2 真的重排了
$v1Original = @($rows | Where-Object { $_.v1Mode -eq "ORIGINAL" })
$rescued = @($v1Original | Where-Object { $_.hybMode -ne "ORIGINAL" }).Count

# 行数收益：v2 接管页里，重排文字行的净增
$lineDelta = @($v2Rows | ForEach-Object { $_.hybLines - $_.v1Lines } | Measure-Object -Sum).Sum
$meanMsV1 = [int](($rows | Measure-Object msV1 -Average).Average)
$meanMsV2 = [int](($rows | Measure-Object msV2 -Average).Average)

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# v1 vs 混合（v2 优先 + 回退）收益对比")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("- 生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')，样本 $($rows.Count) 张")
[void]$sb.AppendLine("- 混合 = 同一张图先走 v2；v2 回退时**代码路径与 v1 完全相同**，所以回退页的指标就是 v1 的指标")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 汇总")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 指标 | 数值 |")
[void]$sb.AppendLine("|---|---|")
[void]$sb.AppendLine("| v2 接管 | $($v2Rows.Count)/$($rows.Count)（其余 $($v1Rows.Count) 张回退 v1） |")
[void]$sb.AppendLine("| 字号明显变大（≥1.05×） | $better 张 |")
[void]$sb.AppendLine("| 字号基本持平（0.98–1.05×） | $same 张 |")
[void]$sb.AppendLine("| 字号变小（≤0.98×，**回归**） | $worse 张 |")
[void]$sb.AppendLine("| 字号放大中位数 | **${medianGain}×**（均值 ${meanGain}×） |")
[void]$sb.AppendLine("| 重排文字行净增 | $lineDelta 行 |")
[void]$sb.AppendLine("| v1 只能看原图、混合后能重排 | $rescued/$($v1Original.Count) 张 |")
[void]$sb.AppendLine("| 平均耗时 v1 → 混合 | ${meanMsV1}ms → ${meanMsV2}ms |")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 逐张（按字号增益降序）")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 图 | v2 | 回退码 | v1 行高 | 混合行高 | 增益 | 行数 v1→混合 | 模式 |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|")
foreach ($r in ($rows | Sort-Object gain -Descending)) {
    $mode = if ($r.v1Mode -ne $r.hybMode) { "$($r.v1Mode)→$($r.hybMode)" } else { "$($r.hybMode)" }
    [void]$sb.AppendLine(("| {0} | {1} | {2} | {3:N1}px | {4:N1}px | {5:N2} | {6}→{7} | {8} |" -f `
                $r.name, $(if ($r.v2) { "✓" } else { "—" }), $r.code, $r.v1Eff, $r.hybEff, $r.gain, `
                $r.v1Lines, $r.hybLines, $mode))
}
$sb.ToString() | Set-Content -Path $Out -Encoding UTF8

Write-Host ""
Write-Host ("report: {0}" -f $Out)
Write-Host ("接管 {0}/{1}；字号中位 {2}x；变大 {3} / 持平 {4} / 变小 {5}；行数净增 {6}；耗时 {7}ms -> {8}ms" -f `
        $v2Rows.Count, $rows.Count, $medianGain, $better, $same, $worse, $lineDelta, $meanMsV1, $meanMsV2)




