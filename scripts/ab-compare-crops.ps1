# 把一组图的 v1/v2 渲染结果裁出顶部，并排拼成一张对照图（人工判读用）。
#
# 为什么只裁顶部：这些图动辄上万像素高，整图缩放到屏幕上看不出字号差异；
# 裁顶部 1600px 才能直接比“一行字到底多大”。
param(
    # 用分号分隔（避免命令行里带空格的图名被拆成多个参数）
    [string]$Images,
    [int]$CropHeight = 1600,
    [string]$OutDir = "testdata/out/ab"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Add-Type -AssemblyName System.Drawing

$cli = Join-Path $root "cli/build/install/cli/bin/cli.bat"
if (-not (Test-Path $cli)) { throw "先跑 .\gradlew.bat :cli:installDist" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Load([string]$path) {
    return [System.Drawing.Bitmap]::FromFile((Resolve-Path $path))
}

foreach ($name in ($Images -split ';' | Where-Object { $_ -ne '' })) {
    $file = Get-ChildItem "testdata/samples" -File | Where-Object { $_.BaseName -eq $name } | Select-Object -First 1
    if (-not $file) { Write-Host "not found: $name"; continue }
    $work = Join-Path $OutDir ("work-" + $file.BaseName)
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    & $cli $file.FullName $work --layout --model "models/doclayout/PP-DocLayout-S.onnx" --compare | Out-Null

    $v1Path = Join-Path $work "after-v1.png"
    $v2Path = Join-Path $work "after-v2.png"
    if (-not (Test-Path $v1Path) -or -not (Test-Path $v2Path)) { Write-Host "no render: $name"; continue }

    $b1 = Load $v1Path
    $b2 = Load $v2Path
    $h = [Math]::Min($CropHeight, [Math]::Min($b1.Height, $b2.Height))
    $w = [Math]::Max($b1.Width, $b2.Width)

    $canvas = New-Object System.Drawing.Bitmap ($w * 2 + 24), ($h + 48)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.Clear([System.Drawing.Color]::FromArgb(240, 240, 240))
    $g.DrawImage($b1, (New-Object System.Drawing.Rectangle 0, 48, $b1.Width, $h),
        (New-Object System.Drawing.Rectangle 0, 0, $b1.Width, $h), [System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawImage($b2, (New-Object System.Drawing.Rectangle ($w + 24), 48, $b2.Width, $h),
        (New-Object System.Drawing.Rectangle 0, 0, $b2.Width, $h), [System.Drawing.GraphicsUnit]::Pixel)

    $font = New-Object System.Drawing.Font "Segoe UI", 20, ([System.Drawing.FontStyle]::Bold)
    $brush = [System.Drawing.Brushes]::Black
    $g.DrawString("v1", $font, $brush, 8, 8)
    $g.DrawString("v2 (hybrid)", $font, $brush, ($w + 32), 8)

    $outPath = Join-Path $OutDir ($file.BaseName + "-top.png")
    $canvas.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $canvas.Dispose(); $b1.Dispose(); $b2.Dispose()
    Write-Host ("{0}  v1={1}x{2} v2={3}x{4} -> {5}" -f $name, $b1.Width, $b1.Height, $b2.Width, $b2.Height, $outPath)
}

