# 批量跑样例目录，生成 scoreboard（docs/06 P1-2）。
#
# 真实截图不随仓库分发：不传 -Dir 时优先 testdata/private/samples2，其次 testdata/samples2。
# 用法：powershell -ExecutionPolicy Bypass -File scripts/score-samples.ps1 [-Dir testdata/samples2] [-Out testdata/scoreboard.md] [-OutWidth 1080]
#
# 只做“自动部分”：逐张跑 CLI，收集 plan.json 的结构值与审计结果；
# “可用 / 勉强 / 不可用”的评分列留空，供人工填。
param(
    [string]$Dir = "",
    [string]$Out = "testdata/scoreboard.md",
    [int]$OutWidth = 1080,
    [int]$Limit = 0,
    [switch]$SkipRun
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if ([string]::IsNullOrWhiteSpace($Dir)) {
        $Dir = if (Test-Path "testdata/private/samples2") { "testdata/private/samples2" } else { "testdata/samples2" }
    }
    if (-not (Test-Path $Dir)) { throw "样例目录不存在：$Dir" }
    $files = Get-ChildItem $Dir -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' } | Sort-Object Name
    # 人工评分：与样例同目录的 notes.csv（name,score,note），存在就合并进表格。
    $notes = @{}
    $notesPath = Join-Path $Dir "notes.csv"
    if (Test-Path $notesPath) {
        Import-Csv $notesPath | ForEach-Object { $notes[$_.name] = $_ }
    }
    if ($Limit -gt 0) { $files = $files | Select-Object -First $Limit }
    $rows = @()
    $index = 0
    foreach ($f in $files) {
        $index++
        $name = [System.IO.Path]::GetFileNameWithoutExtension($f.Name) -replace '[^\w\-]', '_'
        $outDir = "testdata/out2/$name"
        Write-Host ("[{0}/{1}] {2}" -f $index, $files.Count, $f.Name)
        # 样例文件名可能带空格/中文，先复制成安全名字再交给 CLI（--args 按空格切分）。
        $safeDir = "testdata/tmp/score"
        New-Item -ItemType Directory -Force -Path $safeDir | Out-Null
        $safeName = "{0:000}{1}" -f $index, $f.Extension.ToLower()
        $safePath = Join-Path $safeDir $safeName
        Copy-Item $f.FullName $safePath -Force
        if (-not $SkipRun) {
            $ErrorActionPreference = "Continue"
            $log = & ".\gradlew.bat" -q :cli:run --args="$safePath $outDir --out-width $OutWidth" --console=plain 2>&1
            $code = $LASTEXITCODE
            $ErrorActionPreference = "Stop"
        } else {
            $code = 0
        }
        if ($code -ne 0) {
            $rows += [pscustomobject]@{
                index = $index; name = $name; file = $f.Name; size = ""; lines = 0; figures = 0
                roles = ""; splitX = 0; clean = 0; scale = 0; gain = 0; mode = "ERROR"; fallback = ""
                items = 0; dropped = 0; audit = "run-failed"; auto = "不可用（运行失败）"; score = ""
            }
            continue
        }
        $planPath = "$outDir/plan.json"
        if (-not (Test-Path $planPath)) {
            $rows += [pscustomobject]@{
                index = $index; name = $name; file = $f.Name; size = ""; lines = 0; figures = 0
                roles = ""; splitX = 0; clean = 0; scale = 0; gain = 0; mode = "ERROR"; fallback = ""
                items = 0; dropped = 0; audit = "no-plan"; auto = "不可用（无 plan.json）"; score = ""
            }
            continue
        }
        $p = Get-Content $planPath -Raw | ConvertFrom-Json
        $auto = if ($p.mode -eq "ORIGINAL") {
            "回退原图（$($p.fallback)）"
        } elseif (-not $p.audit.ok) {
            "**审计失败**：$($p.audit.issues -join ', ')"
        } elseif ($p.scale -lt 1.25) {
            "**收益不足**（scale $([math]::Round($p.scale,2))）"
        } elseif ($p.gain -ge 1.5) {
            "可重排（gain $([math]::Round($p.gain,2))）"
        } else {
            "勉强（gain $([math]::Round($p.gain,2))）"
        }
        $rows += [pscustomobject]@{
            index      = $index
            name       = $name
            file       = $f.Name
            size       = "$($p.src[0])x$($p.src[1])"
            lines      = $p.lines
            figures    = $p.figures
            roles      = ($p.roles | ConvertTo-Json -Compress)
            splitX     = $p.splitX
            clean      = [math]::Round($p.cleanRatio, 2)
            scale      = [math]::Round($p.scale, 3)
            gain       = [math]::Round($p.gain, 3)
            mode       = $p.mode
            fallback   = $p.fallback
            items      = $p.items
            dropped    = $p.itemsDropped
            audit      = if ($p.audit.ok) { "OK" } else { "FAIL" }
            auto       = $auto
            score      = if ($notes.ContainsKey([System.IO.Path]::GetFileNameWithoutExtension($f.Name))) { $notes[[System.IO.Path]::GetFileNameWithoutExtension($f.Name)].score } else { "" }
            note       = if ($notes.ContainsKey([System.IO.Path]::GetFileNameWithoutExtension($f.Name))) { $notes[[System.IO.Path]::GetFileNameWithoutExtension($f.Name)].note } else { "" }
        }
    }

    $ok = @($rows | Where-Object { $_.auto -like "可重排*" }).Count
    $orig = @($rows | Where-Object { $_.mode -eq "ORIGINAL" }).Count
    $failCount = @($rows | Where-Object { $_.audit -eq "FAIL" }).Count
    $near = @($rows | Where-Object { $_.auto -like "勉强*" }).Count
    $md = New-Object System.Text.StringBuilder
    [void]$md.AppendLine("# 真实样例评分表（自动生成 + 人工填分）")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("> 自动部分由 ``scripts/score-samples.ps1`` 生成：目录 ``$Dir``，输出宽 $OutWidth，共 $($rows.Count) 张。")
    [void]$md.AppendLine("> 评分列（可用 / 勉强 / 不可用）需要人工看图填写；发布门槛见 docs/05 §6。")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("自动统计：可重排 $ok / $($rows.Count)，勉强 $near，回退 $orig，审计失败 $failCount。")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| # | 样例 | 尺寸 | 行 | 图块 | 角色 | 切分列 | clean | scale | gain | 模式 | 丢弃 | 审计 | 自动结论 | 人工评分 | 备注 |")
    [void]$md.AppendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    foreach ($r in $rows) {
        [void]$md.AppendLine("| $($r.index) | ``$($r.name)`` | $($r.size) | $($r.lines) | $($r.figures) | $($r.roles) | $($r.splitX) | $($r.clean) | $($r.scale) | $($r.gain) | $($r.mode)/$($r.fallback) | $($r.dropped) | $($r.audit) | $($r.auto) | $($r.score) | $($r.note) |")
    }
    [void]$md.AppendLine("")
    [void]$md.AppendLine("## 产物")
    [void]$md.AppendLine("")
    foreach ($r in $rows) {
        if (Test-Path "testdata/out2/$($r.name)/after.png") {
            [void]$md.AppendLine("- ``$($r.name)``：[after](out2/$($r.name)/after.png) · [debug](out2/$($r.name)/debug.png) · [plan.json](out2/$($r.name)/plan.json)")
        }
    }
    $md.ToString() | Set-Content $Out -Encoding UTF8
    Write-Host "scoreboard: $Out"
} finally {
    Pop-Location
}
