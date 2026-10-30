# 用真实样例跑一遍 CLI，生成 after/debug/plan，
# 并把 manifest.json、report.md、summary.png 全部由同一次运行生成（docs/06 P0-0）。
#
# 真实截图不随仓库分发：优先 testdata/private/samples，其次 testdata/samples。
# 用法：powershell -ExecutionPolicy Bypass -File scripts/test-samples.ps1 [-OutWidth 1080] [-SkipSummary]
param(
    [int]$OutWidth = 1080,
    [switch]$SkipSummary
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $sampleDir = if (Test-Path "testdata/private/samples") { "testdata/private/samples" } else { "testdata/samples" }
    $samples = @("twitter", "news_jp", "xerj", "arknights")
    $hard = @{ arknights = "硬样例：复杂美术背景，验收标准是安全回退、内容完整" }
    $records = @()
    foreach ($name in $samples) {
        $input = Join-Path $sampleDir "$name.jpg"
        if (-not (Test-Path $input)) {
            Write-Warning "跳过 $input（文件不存在）"
            continue
        }
        if ($hard.ContainsKey($name)) { Write-Host "（$($hard[$name])）" }
        Write-Host "===== $name ====="
        $cmdOut = & ".\gradlew.bat" -q :cli:run --args="$input testdata/out/$name --out-width $OutWidth" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "$name 运行失败" }
        $cmdOut | Write-Host
        $plan = Get-Content "testdata/out/$name/plan.json" -Raw | ConvertFrom-Json
        $records += [pscustomobject]@{
            name        = $name
            inputSha    = (Get-FileHash $input -Algorithm SHA256).Hash.ToLower()
            outputSha   = (Get-FileHash "testdata/out/$name/after.png" -Algorithm SHA256).Hash.ToLower()
            ranAt       = (Get-Date).ToString("s")
            src         = $plan.src
            lines       = $plan.lines
            figures     = $plan.figures
            roles       = $plan.roles
            figureKinds = $plan.figureReasons
            splitX      = $plan.splitX
            cleanRatio  = $plan.cleanRatio
            scale       = $plan.scale
            bodyScale   = $plan.bodyScale
            gain        = $plan.gain
            mode        = $plan.mode
            fallback    = $plan.fallback
            items       = $plan.items
            itemsDropped = $plan.itemsDropped
            height      = $plan.height
            audit       = $plan.audit
            analyzeMs   = $plan.analyzeMs
            planMs      = $plan.planMs
            options     = $plan.options
            note        = if ($hard.ContainsKey($name)) { $hard[$name] } else { "" }
        }
    }

    # manifest.json：输入/输出哈希、时间、参数、结构值
    $manifest = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("s")
        outWidth    = $OutWidth
        commit      = if (Test-Path .git) { (git rev-parse --short HEAD) } else { "no-git" }
        samples     = $records
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content "testdata/out/manifest.json" -Encoding UTF8
    Write-Host "manifest: testdata/out/manifest.json"

    # 报告：所有数字来自同一次运行的 plan.json
    $md = New-Object System.Text.StringBuilder
    [void]$md.AppendLine("# 真实图片测试报告（自动生成）")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("> 本文件由 ``scripts/test-samples.ps1`` 生成，数字直接取自同一次运行的 ``plan.json``；")
    [void]$md.AppendLine("> 结构值同样记录在 ``manifest.json``（含输入/输出 SHA-256）。不要手改。")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("运行时间：$($manifest.generatedAt) · 输出宽度 $OutWidth · targetEm 44 · 入口 ``:cli``（与 Android 共用 ``:core``）")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("## 结果总览")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| 样例 | 源尺寸 | 文字行 | 图块 | 切分列 | cleanRatio | scale | bodyScale | gain | 模式 | 输出 | 审计 |")
    [void]$md.AppendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
    foreach ($r in $records) {
        $audit = if ($r.audit.ok) { "OK" } else { "**$($r.audit.issues -join ', ')**" }
        $fallback = if ($r.fallback -eq "NONE") { "" } else { " / $($r.fallback)" }
        $outSize = if ($r.height -gt 0) { "$OutWidth×$($r.height)" } else { "$($r.src[0])×$($r.src[1])（原图）" }
        [void]$md.AppendLine("| $($r.name) | $($r.src[0])×$($r.src[1]) | $($r.lines) | $($r.figures) | $($r.splitX) | $([math]::Round($r.cleanRatio,2)) | $([math]::Round($r.scale,3)) | $([math]::Round($r.bodyScale,3)) | $([math]::Round($r.gain,3)) | $($r.mode)$fallback | $outSize | $audit |")
    }
    [void]$md.AppendLine("")
    [void]$md.AppendLine("图块类型：" + (($records | ForEach-Object { "$($_.name)=$($_.figureKinds | ConvertTo-Json -Compress)" }) -join " · "))
    [void]$md.AppendLine("")
    [void]$md.AppendLine("行角色：" + (($records | ForEach-Object { "$($_.name)=$($_.roles | ConvertTo-Json -Compress)" }) -join " · "))
    [void]$md.AppendLine("")
    [void]$md.AppendLine("## 逐样例")
    [void]$md.AppendLine("")
    foreach ($r in $records) {
        $outSize = if ($r.height -gt 0) { "$OutWidth×$($r.height)" } else { "原图" }
        [void]$md.AppendLine("### $($r.name)")
        [void]$md.AppendLine("")
        [void]$md.AppendLine("- 模式：``$($r.mode)``（fallback=$($r.fallback)），scale=$([math]::Round($r.scale,3))，gain=$([math]::Round($r.gain,3))")
        [void]$md.AppendLine("- 结构：文字行 $($r.lines) · 图块 $($r.figures) · 切分列 $($r.splitX) · cleanRatio $([math]::Round($r.cleanRatio,2)) · 渲染项 $($r.items) · 丢弃 $($r.itemsDropped)")
        [void]$md.AppendLine("- 输出：$outSize")
        [void]$md.AppendLine("- 审计：$($r.audit | ConvertTo-Json -Compress)")
        [void]$md.AppendLine("- 耗时：analyze=$($r.analyzeMs)ms plan=$($r.planMs)ms")
        if ($r.note -ne "") { [void]$md.AppendLine("- $($r.note)") }
        [void]$md.AppendLine("- 产物：[after](out/$($r.name)/after.png) · [debug](out/$($r.name)/debug.png) · [plan.json](out/$($r.name)/plan.json)")
        [void]$md.AppendLine("")
    }
    $md.ToString() | Set-Content "testdata/report.md" -Encoding UTF8
    Write-Host "report: testdata/report.md"

    if (-not $SkipSummary) {
        $after = $records | ForEach-Object { "testdata/out/$($_.name)/after.png" } | Where-Object { Test-Path $_ }
        if ($after.Count -ge 2) {
            & ".\gradlew.bat" -q :cli:run --args="summary testdata/out/summary.png $($after -join ' ')" --console=plain | Write-Host
        }
    }
    Write-Host "输出目录：testdata/out"
} finally {
    Pop-Location
}
