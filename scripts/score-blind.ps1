# 把盲评导出的评分 CSV 与隐藏映射合并，输出 v1/v2 评分报告。
#
# 用法（pwsh 7）：
#   # 1) 打开 testdata/out/blind-review/index.html 逐张评分，点“导出评分 CSV”
#   # 2) 把下载的 blind-ratings.csv 放回 testdata/out/blind-review/（覆盖空模板）
#   pwsh -NoProfile -File scripts/score-blind.ps1
#   pwsh -NoProfile -File scripts/score-blind.ps1 -Ratings path/to/blind-ratings.csv
#
# 产物：
#   testdata/report-blind.md   汇总（v1/v2 各自 可用/勉强/不可用、顺序、丢内容、逐张对照）
#   testdata/report-blind.csv  逐张映射后的原始评分
param(
    [string]$OutDir = "testdata/out/blind-review",
    [string]$Ratings = "",
    [string]$OutMd = "testdata/report-blind.md",
    [string]$OutCsv = "testdata/report-blind.csv"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$mappingFile = Join-Path $OutDir "mapping.csv"
if (-not (Test-Path $mappingFile)) { throw "mapping not found: $mappingFile (run scripts/make-blind-review.ps1 first)" }
if ([string]::IsNullOrWhiteSpace($Ratings)) { $Ratings = Join-Path $OutDir "blind-ratings.csv" }
if (-not (Test-Path $Ratings)) { throw "ratings not found: $Ratings" }

$map = @{}
foreach ($m in Import-Csv $mappingFile) { $map[$m.page] = $m }
$rows = @(Import-Csv $Ratings)

$scoreOf = @{ '可用' = 2; '勉强' = 1; '不可用' = 0 }
function Get-Score([string]$v) { if ($scoreOf.ContainsKey($v)) { return $scoreOf[$v] } return $null }
function Blank([string]$v) { return [string]::IsNullOrWhiteSpace($v) }

$per = New-Object System.Collections.Generic.List[object]
$missing = 0
foreach ($k in ($map.Keys | Sort-Object)) {
    $m = $map[$k]
    $r = $rows | Where-Object { $_.page -eq $k } | Select-Object -First 1
    if ($null -eq $r) { $missing++; continue }
    $leftRating = $r.left_rating; $rightRating = $r.right_rating
    $v1Rating = if ($m.left -eq 'v1') { $leftRating } else { $rightRating }
    $v2Rating = if ($m.left -eq 'v2') { $leftRating } else { $rightRating }
    $v1Order = if ($m.left -eq 'v1') { $r.left_order } else { $r.right_order }
    $v2Order = if ($m.left -eq 'v2') { $r.left_order } else { $r.right_order }
    $v1Loss = if ($m.left -eq 'v1') { $r.left_loss } else { $r.right_loss }
    $v2Loss = if ($m.left -eq 'v2') { $r.left_loss } else { $r.right_loss }
    $note = $r.note
    $s1 = Get-Score $v1Rating
    $s2 = Get-Score $v2Rating
    $pref = '-'
    if ($null -ne $s1 -and $null -ne $s2) {
        $pref = if ($s2 -gt $s1) { 'v2 更好' } elseif ($s2 -lt $s1) { 'v1 更好' } else { '持平' }
    }
    $per.Add([pscustomobject]@{
        page = $k
        image = $m.image
        group = $m.group
        v1Rating = $v1Rating
        v2Rating = $v2Rating
        v1Order = $v1Order
        v2Order = $v2Order
        v1Loss = $v1Loss
        v2Loss = $v2Loss
        preference = $pref
        note = $note
    })
}

if ($per.Count -eq 0) { throw "no scoreable rows（$Ratings 与 mapping 的 page 对不上？）" }

function Count-Val($items, [string]$field, [string]$value) {
    return @($items | Where-Object { $_.$field -eq $value }).Count
}

$rated = @($per | Where-Object { -not (Blank $_.v1Rating) -or -not (Blank $_.v2Rating) })
$v1 = @($per | Where-Object { -not (Blank $_.v1Rating) })
$v2 = @($per | Where-Object { -not (Blank $_.v2Rating) })

$s1 = 0.0; $n1 = 0
$s2 = 0.0; $n2 = 0
foreach ($r in $per) {
    $a = Get-Score $r.v1Rating; if ($null -ne $a) { $s1 += $a; $n1++ }
    $b = Get-Score $r.v2Rating; if ($null -ne $b) { $s2 += $b; $n2++ }
}
$avg1 = if ($n1 -gt 0) { $s1 / $n1 } else { 0 }
$avg2 = if ($n2 -gt 0) { $s2 / $n2 } else { 0 }

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# v1 / v2 盲评结果")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("- 生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')")
[void]$sb.AppendLine("- 评分文件：``$Ratings``")
[void]$sb.AppendLine("- 已评：$($per.Count) 张（页面生成 $($map.Count) 张，缺评分 $missing 张）")
[void]$sb.AppendLine("- 评分口径：可用=2 / 勉强=1 / 不可用=0；顺序与内容丢失单独统计。")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 指标 | v1 | v2 |")
[void]$sb.AppendLine("|---|---|---|")
[void]$sb.AppendLine("| 可用 | $(Count-Val $per 'v1Rating' '可用') | $(Count-Val $per 'v2Rating' '可用') |")
[void]$sb.AppendLine("| 勉强 | $(Count-Val $per 'v1Rating' '勉强') | $(Count-Val $per 'v2Rating' '勉强') |")
[void]$sb.AppendLine("| 不可用 | $(Count-Val $per 'v1Rating' '不可用') | $(Count-Val $per 'v2Rating' '不可用') |")
[void]$sb.AppendLine("| 可用 + 勉强 | $((Count-Val $per 'v1Rating' '可用') + (Count-Val $per 'v1Rating' '勉强')) | $((Count-Val $per 'v2Rating' '可用') + (Count-Val $per 'v2Rating' '勉强')) |")
[void]$sb.AppendLine("| 平均分（0–2） | $('{0:N3}' -f $avg1) | $('{0:N3}' -f $avg2) |")
[void]$sb.AppendLine("| 顺序正确 | $(Count-Val $per 'v1Order' '对') | $(Count-Val $per 'v2Order' '对') |")
[void]$sb.AppendLine("| 内容丢失 | $(Count-Val $per 'v1Loss' '有丢失') | $(Count-Val $per 'v2Loss' '有丢失') |")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("**逐张偏好**：v2 更好 $(Count-Val $per 'preference' 'v2 更好') 张，v1 更好 $(Count-Val $per 'preference' 'v1 更好') 张，持平 $(Count-Val $per 'preference' '持平') 张。")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 页面 | 图片 | 分组 | v1 | v2 | v2 顺序 | v2 丢内容 | 偏好 | 备注 |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|---|")
foreach ($r in $per) {
    [void]$sb.AppendLine("| $($r.page) | $($r.image) | $($r.group) | $($r.v1Rating) | $($r.v2Rating) | $($r.v2Order) | $($r.v2Loss) | $($r.preference) | $($r.note) |")
}
$sb.ToString() | Set-Content -Path $OutMd -Encoding UTF8

$per | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8

Write-Host "md : $OutMd"
Write-Host "csv: $OutCsv"
Write-Host ("v1 可用/勉强/不可用 = {0}/{1}/{2}；v2 = {3}/{4}/{5}；v2 更好 {6} 张" -f `
        (Count-Val $per 'v1Rating' '可用'), (Count-Val $per 'v1Rating' '勉强'), (Count-Val $per 'v1Rating' '不可用'),
    (Count-Val $per 'v2Rating' '可用'), (Count-Val $per 'v2Rating' '勉强'), (Count-Val $per 'v2Rating' '不可用'),
    (Count-Val $per 'preference' 'v2 更好'))
