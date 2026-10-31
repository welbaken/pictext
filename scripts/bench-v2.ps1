# V2-40 端到端性能基准（CLI）。
#
# 用法（pwsh 7）：
#   pwsh -NoProfile -File scripts/bench-v2.ps1
#   pwsh -NoProfile -File scripts/bench-v2.ps1 -Runs 2
#   pwsh -NoProfile -File scripts/bench-v2.ps1 -Sizes 1080x2400,1920x1080
#
# 产物：
#   testdata/out/bench/<size>.jpg        合成基准图（1080x2400 / 1920x1080 / 1080x20000）
#   testdata/report-perf.md              Markdown 表格（耗时分段 + CLI 峰值内存）
#
# 说明：
# - 基准图从 testdata/samples 的长截图拼出来（真实截图不入库，脚本没有样例时会报错）；
# - CLI 的数字是桌面 JVM，**不能当 Android 真机验收**；用途是同一台机器上比较改动前后。
#   模拟器 x86 约比骁龙 7 系慢 3 倍（docs/07 §13.5），真机估算方法见报告末尾。
# - 内存用 Runtime.totalMemory-freeMemory 的 20ms 峰值采样，只含 JVM 堆，不含 ORT native。
param(
    [string]$Model = "models/doclayout/PP-DocLayout-S.onnx",
    [string]$OutDir = "testdata/out/bench",
    [string[]]$Sizes = @("1080x2400", "1920x1080", "1080x20000"),
    [int]$Runs = 1,
    [switch]$FullBaseline
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Add-Type -AssemblyName System.Drawing

$cli = Join-Path $root "cli/build/install/cli/bin/cli.bat"
if (-not (Test-Path $cli)) {
    Write-Host "building cli distribution..."
    & (Join-Path $root "gradlew.bat") ":cli:installDist" --console=plain -q
}
if (-not (Test-Path $Model)) { throw "model not found: $Model" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 素材：长截图（1080x20000）+ 正常手机截图（1080x2400 / 1920x1080）。
$portraits = Get-ChildItem (Join-Path $root "testdata/samples") -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -match '^\.(jpg|jpeg|png)$' } |
    ForEach-Object {
        try {
            $im = [System.Drawing.Image]::FromFile($_.FullName)
            $w = $im.Width
            $h = $im.Height
            $im.Dispose()
            [pscustomobject]@{ File = $_; W = $w; H = $h; Aspect = $h / [double]$w }
        } catch { $null }
    } | Where-Object { $_ -and $_.H -gt $_.W }
$longImage = $portraits | Sort-Object H -Descending | Select-Object -First 1
$normalImage = $portraits | Where-Object { $_.Aspect -ge 1.8 -and $_.Aspect -le 2.6 } |
    Sort-Object H -Descending | Select-Object -First 1
if (-not $longImage) { throw "no portrait sample under testdata/samples (real screenshots are gitignored)" }
if (-not $normalImage) { $normalImage = $longImage }
Write-Host "source: $($longImage.File.Name) $($longImage.W)x$($longImage.H); normal: $($normalImage.File.Name) $($normalImage.W)x$($normalImage.H)"

function Save-Jpeg([System.Drawing.Bitmap]$bmp, [string]$path) {
    $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object { $_.MimeType -eq 'image/jpeg' }
    $params = New-Object System.Drawing.Imaging.EncoderParameters 1
    $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
        [System.Drawing.Imaging.Encoder]::Quality, [long]88)
    $bmp.Save($path, $codec, $params)
}

function Make-BenchImage([string]$size) {
    $parts = $size -split 'x'
    $w = [int]$parts[0]
    $h = [int]$parts[1]
    $path = Join-Path $OutDir "$size.jpg"
    if (Test-Path $path) { return $path }
    $pick = if ($size -eq "1080x20000") { $longImage } else { $normalImage }
    $src = [System.Drawing.Image]::FromFile($pick.File.FullName)
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    if ($size -eq "1080x20000") {
        # 长截图按宽度缩放到 1080，再在底部补一段顶部内容到 20000。
        $scaledH = [int]($pick.H * 1080.0 / $pick.W)
        $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, 1080, $scaledH),
            (New-Object System.Drawing.Rectangle 0, 0, $pick.W, $pick.H),
            [System.Drawing.GraphicsUnit]::Pixel)
        $fillH = $h - $scaledH
        if ($fillH -gt 0) {
            $srcH = [int]($fillH * $pick.W / 1080.0)
            $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, $scaledH, 1080, $fillH),
                (New-Object System.Drawing.Rectangle 0, 0, $pick.W, $srcH),
                [System.Drawing.GraphicsUnit]::Pixel)
        }
    } elseif ($size -eq "1920x1080") {
        # 横屏：从正常截图中间裁一段 16:9 横条，避免纵向压扁。
        $srcH = [int]($pick.W * $h / $w)
        $srcH = [Math]::Min($srcH, $pick.H)
        $top = [int](($pick.H - $srcH) / 2)
        $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $w, $h),
            (New-Object System.Drawing.Rectangle 0, $top, $pick.W, $srcH),
            [System.Drawing.GraphicsUnit]::Pixel)
    } else {
        $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $w, $h),
            (New-Object System.Drawing.Rectangle 0, 0, $pick.W, $pick.H),
            [System.Drawing.GraphicsUnit]::Pixel)
    }
    $g.Dispose()
    Save-Jpeg $bmp $path
    $bmp.Dispose()
    $src.Dispose()
    return $path
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($size in $Sizes) {
    $img = Make-BenchImage $size
    $out = Join-Path $OutDir ("out-" + ($size -replace 'x', '-'))
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $extra = @()
    if ($FullBaseline) { $extra = @("--audit-downsample", "1", "--figure-full-res", "--serial-refine") }
    $best = $null
    for ($r = 1; $r -le $Runs; $r++) {
        $log = & $cli $img $out --out-width 1080 --layout --model $Model @extra 2>&1 | Out-String
        $ms = if ($log -match 'analyze=(\d+)ms') { [int]$Matches[1] } else { -1 }
        $peak = if ($log -match 'peakMem=(\d+)MB') { [int]$Matches[1] } else { -1 }
        $tiles = if ($log -match 'tiles=(\d+)') { [int]$Matches[1] } else { 0 }
        $stage = if ($log -match 'decode=(\d+)ms detect=(\d+)ms refine=(\d+)ms coverage=(\d+)ms order=(\d+)ms') {
            @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4], [int]$Matches[5])
        } else { @(0, 0, 0, 0, 0) }
        $row = [pscustomobject]@{
            size = $size; tiles = $tiles; ms = $ms; peak = $peak
            decode = $stage[0]; detect = $stage[1]; refine = $stage[2]
            coverage = $stage[3]; order = $stage[4]
        }
        if ($null -eq $best -or $row.ms -lt $best.ms) { $best = $row }
        if ($Runs -gt 1) { Write-Host ("  run {0}/{1}: {2}ms" -f $r, $Runs, $ms) }
    }
    $rows.Add($best)
    Write-Host ("{0,-12} tiles={1,-4} {2,6}ms  peak={3}MB  d={4} det={5} ref={6} cov={7}" -f `
            $best.size, $best.tiles, $best.ms, $best.peak, $best.decode, $best.detect, $best.refine, $best.coverage)
}

$md = Join-Path $root "testdata/report-perf.md"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# V2-40 端到端性能基准（CLI 桌面）")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("- 生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')")
[void]$sb.AppendLine("- 模型：$Model")
[void]$sb.AppendLine("- 素材：$($longImage.File.Name)（$($longImage.W)x$($longImage.H)）")
[void]$sb.AppendLine("- 口径：最佳 1 次（-Runs N 取最快）；内存是 JVM 堆 20ms 峰值，不含 ORT native")
if ($FullBaseline) { [void]$sb.AppendLine("- **本次是基线**：--audit-downsample 1 --figure-full-res --serial-refine（V2-50 之前的行为）") }
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 尺寸 | tile | 总耗时 | decode | detect | refine | coverage | order | 峰值内存 |")
[void]$sb.AppendLine("|---|---|---|---|---|---|---|---|---|")
foreach ($r in $rows) {
    [void]$sb.AppendLine("| $($r.size) | $($r.tiles) | $($r.ms)ms | $($r.decode)ms | $($r.detect)ms | $($r.refine)ms | $($r.coverage)ms | $($r.order)ms | $($r.peak)MB |")
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 目标与口径")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("- 目标（中端真机）：1080x2400 <=350ms、1080x20000 <=1.5s、1920x1080 <=400ms、峰值 <=180MB。")
[void]$sb.AppendLine("- CLI 不能当验收：CLI 是桌面 JDK 21，和 Android ART / BitmapRegionDecoder 的实现不同。")
[void]$sb.AppendLine("  Android 真机/模拟器的数字见 docs/07 13.4 / 13.5，峰值内存用 Debug.getMemoryInfo 的 totalPss。")
[void]$sb.AppendLine("- 模拟器换算：MuMu x86_64 大约比骁龙 7 系慢 3 倍（docs/07 13.5）。")
[void]$sb.AppendLine("  真机估算 = 模拟器实测 / 3；这不是验收，只用来判断离目标还差多少。")
$sb.ToString() | Set-Content -Path $md -Encoding UTF8
Write-Host "report: $md"
