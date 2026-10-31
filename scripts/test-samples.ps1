# 把 testdata/samples 里的每张图跑一遍 CLI（v1 批量复核），产出：
#   testdata/out/<slug>/{after.png, debug.png, plan.json}
#   testdata/out/manifest.json（机读）
#   testdata/out/summary.md（人读表格）
#   testdata/out/index.html（逐张 前后对比 + 结构数值，浏览器打开）
#   testdata/out/contact-sheet.png（所有输出的缩略总览）
#
# 真实截图与产物都不入库（.gitignore 已覆盖 testdata/samples*/、private*/、out*/）。
# webp（含扩展名写成 .jpg 的）先用 ffmpeg 转 png 再喂给 CLI：JVM 的 ImageIO 不认 webp。
# 用法：powershell -ExecutionPolicy Bypass -File scripts/test-samples.ps1 [-OutWidth 1080] [-SampleDir testdata/samples]
param(
    [int]$OutWidth = 1080,
    [string]$SampleDir = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not $SampleDir) {
        $SampleDir = if (Test-Path "testdata/private/samples") { "testdata/private/samples" } else { "testdata/samples" }
    }
    if (-not (Test-Path $SampleDir)) { throw "样例目录不存在：$SampleDir" }

    $out = Join-Path $root "testdata/out"
    $srcCache = Join-Path $out "_src"
    $exe = Join-Path $root "cli/build/install/cli/bin/cli.bat"
    New-Item -ItemType Directory -Force $out | Out-Null
    if (-not (Test-Path $exe)) {
        Write-Host "先构建 CLI 启动脚本..."
        & ".\gradlew.bat" -q :cli:installDist --console=plain
        if ($LASTEXITCODE -ne 0) { throw "cli:installDist 失败" }
    }

    $files = Get-ChildItem $SampleDir -File |
        Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' } |
        Sort-Object Name
    Write-Host "样例 $($files.Count) 张，输出宽 $OutWidth"

    $records = @()
    $index = 0
    foreach ($f in $files) {
        $index++
        $slug = ($f.BaseName -replace '[^A-Za-z0-9\-]+', '-').Trim('-').ToLowerInvariant()
        if (-not $slug) { $slug = "sample-$index" }
        if ($slug.Length -gt 44) { $slug = $slug.Substring(0, 44).Trim('-') }
        $baseSlug = $slug
        $dup = 2
        while (@($records | Where-Object { $_.name -eq $slug }).Count -gt 0) { $slug = "$baseSlug-$dup"; $dup++ }

        $dst = Join-Path $out $slug
        $input = $f.FullName
        $note = ""
        # 按文件头判断，不信任扩展名（样例里有 .jpg 其实是 WebP 的）
        $magic = [System.IO.File]::ReadAllBytes($f.FullName)[0..11]
        $isWebp = ($magic[0] -eq 0x52 -and $magic[1] -eq 0x49 -and $magic[2] -eq 0x46 -and $magic[3] -eq 0x46 -and
                   $magic[8] -eq 0x57 -and $magic[9] -eq 0x45 -and $magic[10] -eq 0x42 -and $magic[11] -eq 0x50)
        if ($isWebp) {
            New-Item -ItemType Directory -Force $srcCache | Out-Null
            $png = Join-Path $srcCache "$slug.png"
            & ffmpeg -y -loglevel error -i "$($f.FullName)" "$png" 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $png)) {
                Write-Warning "[$index/$($files.Count)] $($f.Name)：ffmpeg 解不了 webp，跳过"
                $records += [pscustomobject]@{ name = $slug; file = $f.Name; status = "skipped"; note = "webp 转换失败" }
                continue
            }
            $input = $png
            $note = if ($f.Extension -eq '.webp') {
                "webp → png 再分析（CLI 的 ImageIO 不支持 webp）"
            } else {
                "扩展名是 $($f.Extension)，实际是 WebP：转 png 再分析"
            }
        }

        Write-Host "[$index/$($files.Count)] $($f.Name)"
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $log = & $exe "$input" "$dst" --out-width $OutWidth 2>&1
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prevEap
        $elapsed = [math]::Round($sw.Elapsed.TotalMilliseconds)
        $planPath = Join-Path $dst "plan.json"
        if ($code -ne 0 -or -not (Test-Path $planPath)) {
            $tail = ((@($log) | ForEach-Object { "$_" } | Select-Object -Last 3) -join ' / ')
            Write-Warning "[$index/$($files.Count)] $($f.Name) 失败：$tail"
            $records += [pscustomobject]@{ name = $slug; file = $f.Name; status = "failed"; note = $tail }
            continue
        }
        $plan = Get-Content $planPath -Raw | ConvertFrom-Json
        $records += [pscustomobject]@{
            name         = $slug
            file         = $f.Name
            status       = "ok"
            src          = "$($plan.src[0])x$($plan.src[1])"
            analysis     = "$($plan.analysis[0])x$($plan.analysis[1])"
            mode         = $plan.mode
            fallback     = $plan.fallback
            lines        = $plan.lines
            figures      = $plan.figures
            scale        = $plan.scale
            bodyScale    = $plan.bodyScale
            gain         = $plan.gain
            items        = $plan.items
            height       = $plan.height
            analyzeMs    = $plan.analyzeMs
            planMs       = $plan.planMs
            peakMemMb    = $plan.peakMemMb
            auditOk      = [bool]$plan.audit.ok
            auditIssues  = @($plan.audit.issues).Count
            notes        = ($plan.notes -join ', ')
            wallMs       = $elapsed
            extra        = $note
        }
    }

    $ok = @($records | Where-Object { $_.status -eq 'ok' })
    $bad = @($records | Where-Object { $_.status -ne 'ok' })
    $reflow = @($ok | Where-Object { $_.mode -ne 'ORIGINAL' })
    $original = @($ok | Where-Object { $_.mode -eq 'ORIGINAL' })
    $auditBad = @($ok | Where-Object { -not $_.auditOk })

    # ---------- manifest.json ----------
    $manifest = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("s")
        sampleDir   = $SampleDir
        outWidth    = $OutWidth
        total       = $records.Count
        reflow      = $reflow.Count
        original    = $original.Count
        failed      = $bad.Count
        auditFailed = $auditBad.Count
        records     = $records
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $out "manifest.json") -Encoding UTF8

    # ---------- summary.md ----------
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# 样例批量结果（$((Get-Date).ToString('yyyy-MM-dd HH:mm'))）")
    $md.Add("")
    $md.Add("- 样例：``$SampleDir``，共 $($records.Count) 张；输出宽 $OutWidth")
    $md.Add("- 重排 $($reflow.Count) / 回退原图 $($original.Count) / 失败 $($bad.Count)；审计失败 $($auditBad.Count)")
    $md.Add("")
    $md.Add("| 文件 | 源尺寸 | 模式 | 回退 | 行 | 图块 | scale | gain | 渲染项 | 输出高 | 分析 | 审计 |")
    $md.Add("|---|---|---|---|---|---|---|---|---|---|---|---|")
    foreach ($r in $records) {
        if ($r.status -ne 'ok') { $md.Add("| ``$($r.file)`` | — | $($r.status) | $($r.note) | — | — | — | — | — | — | — | — |"); continue }
        $md.Add("| ``$($r.file)`` | $($r.src) | $($r.mode) | $($r.fallback) | $($r.lines) | $($r.figures) | $([math]::Round($r.scale,3)) | $([math]::Round($r.gain,2)) | $($r.items) | $($r.height) | $($r.analyzeMs)ms | $(if ($r.auditOk) { 'ok' } else { "fail($($r.auditIssues))" }) |")
    }
    if ($bad.Count -gt 0) {
        $md.Add("")
        $md.Add("## 失败 / 跳过")
        foreach ($r in $bad) { $md.Add("- ``$($r.file)``：$($r.note)") }
    }
    $md.Add("")
    $md.Add("产物：``index.html``（逐张前后对比）、``manifest.json``、``<slug>/after.png`` / ``debug.png`` / ``plan.json``。")
    Set-Content (Join-Path $out "summary.md") -Value ($md -join "`r`n") -Encoding UTF8

    # ---------- index.html ----------
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>图读 · 样例批量结果</title><style>')
    [void]$sb.AppendLine('body{font:14px/1.55 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;margin:24px;color:#111;background:#f6f6f7}')
    [void]$sb.AppendLine('h1{font-size:20px;margin:0 0 6px}.sum{color:#333;margin-bottom:18px}')
    [void]$sb.AppendLine('.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(560px,1fr));gap:18px}')
    [void]$sb.AppendLine('.card{background:#fff;border:1px solid #e3e3e6;border-radius:10px;padding:12px 14px}')
    [void]$sb.AppendLine('.card h2{font-size:13px;margin:0 0 8px;word-break:break-all;font-weight:600}')
    [void]$sb.AppendLine('.pair{display:flex;gap:10px;align-items:flex-start}.pair figure{margin:0;flex:1;min-width:0}')
    [void]$sb.AppendLine('.pair img{width:100%;height:auto;border:1px solid #eee;display:block;background:#fff}')
    [void]$sb.AppendLine('figcaption{font-size:12px;color:#777;margin-top:4px}')
    [void]$sb.AppendLine('.kv{font-size:12px;color:#444;margin-top:8px;line-height:1.8;word-break:break-word}')
    [void]$sb.AppendLine('.tag{display:inline-block;padding:1px 7px;border-radius:4px;font-size:12px;margin-right:6px}')
    [void]$sb.AppendLine('.ok{background:#e6f4ea;color:#1e7b34}.warn{background:#fff2e0;color:#96500a}.bad{background:#fdecea;color:#a4161a}')
    [void]$sb.AppendLine('table{border-collapse:collapse;margin:18px 0 26px;font-size:13px}td,th{border:1px solid #ddd;padding:4px 8px;text-align:left}th{background:#f1f1f3}')
    [void]$sb.AppendLine('a{color:#0b5fff;text-decoration:none}a:hover{text-decoration:underline}')
    [void]$sb.AppendLine('</style></head><body>')
    [void]$sb.AppendLine("<h1>图读 · 样例批量结果</h1>")
    [void]$sb.AppendLine("<div class=sum>样例 <b>$($records.Count)</b> 张 · 重排 <b>$($reflow.Count)</b> · 回退原图 <b>$($original.Count)</b> · 失败 <b>$($bad.Count)</b> · 审计失败 <b>$($auditBad.Count)</b> · 输出宽 $OutWidth</div>")
    [void]$sb.AppendLine('<table><tr><th>文件</th><th>源尺寸</th><th>模式</th><th>回退</th><th>行</th><th>scale</th><th>gain</th><th>分析</th></tr>')
    foreach ($r in $records) {
        if ($r.status -ne 'ok') { [void]$sb.AppendLine("<tr><td>$($r.file)</td><td colspan=6>$($r.status)：$($r.note)</td></tr>"); continue }
        $cls = if ($r.mode -eq 'ORIGINAL') { 'warn' } else { 'ok' }
        [void]$sb.AppendLine("<tr><td><a href=`"#$($r.name)`">$($r.file)</a></td><td>$($r.src)</td><td><span class=`"tag $cls`">$($r.mode)</span></td><td>$($r.fallback)</td><td>$($r.lines)</td><td>$([math]::Round($r.scale,3))</td><td>$([math]::Round($r.gain,2))</td><td>$($r.analyzeMs)ms</td></tr>")
    }
    [void]$sb.AppendLine('</table><div class=grid>')
    foreach ($r in $records) {
        if ($r.status -ne 'ok') { continue }
        $cls = if ($r.mode -eq 'ORIGINAL') { 'warn' } else { 'ok' }
        $notes = if ($r.notes) { $r.notes } else { '—' }
        $auditCls = if ($r.auditOk) { 'ok' } else { 'bad' }
        $auditTxt = if ($r.auditOk) { '审计通过' } else { "审计失败 $($r.auditIssues)" }
        [void]$sb.AppendLine("<div class=card id=`"$($r.name)`"><h2>$($r.file)</h2><div class=pair>")
        [void]$sb.AppendLine("<figure><a href=`"../samples/$($r.file)`" target=_blank><img src=`"../samples/$($r.file)`" loading=lazy></a><figcaption>原图 $($r.src)</figcaption></figure>")
        [void]$sb.AppendLine("<figure><a href=`"$($r.name)/after.png`" target=_blank><img src=`"$($r.name)/after.png`" loading=lazy></a><figcaption>输出 $OutWidth×$($r.height)</figcaption></figure>")
        [void]$sb.AppendLine("</div><div class=kv><span class=`"tag $cls`">$($r.mode)</span><span class=`"tag $auditCls`">$auditTxt</span>")
        [void]$sb.AppendLine("fallback=$($r.fallback) · 行 $($r.lines) · 图块 $($r.figures) · scale $([math]::Round($r.scale,3)) · bodyScale $([math]::Round($r.bodyScale,3)) · gain $([math]::Round($r.gain,2)) · 渲染项 $($r.items)<br>")
        [void]$sb.AppendLine("分析 $($r.analyzeMs)ms · 规划 $($r.planMs)ms · 峰值 $($r.peakMemMb)MB · notes: $notes")
        if ($r.extra) { [void]$sb.AppendLine("<br>$($r.extra)") }
        [void]$sb.AppendLine("<br><a href=`"$($r.name)/debug.png`" target=_blank>debug.png</a> · <a href=`"$($r.name)/plan.json`" target=_blank>plan.json</a></div></div>")
    }
    [void]$sb.AppendLine('</div></body></html>')
    [System.IO.File]::WriteAllText((Join-Path $out "index.html"), $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

    Write-Host ""
    Write-Host "完成：重排 $($reflow.Count) / 回退 $($original.Count) / 失败 $($bad.Count) / 审计失败 $($auditBad.Count)"
    Write-Host "看 index.html 或 summary.md"
} finally {
    Pop-Location
}
