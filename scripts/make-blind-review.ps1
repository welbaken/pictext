# 生成 v1/v2 盲评页面（V2-41 人工 A/B）。
#
# 用法（pwsh 7）：
#   pwsh -NoProfile -File scripts/make-blind-review.ps1
#   pwsh -NoProfile -File scripts/make-blind-review.ps1 -OutDir testdata/out/blind-review
#
# 产物（默认在 testdata/out/blind-review/，已在 gitignore 里）：
#   index.html          盲评页面：左右随机放 v1/v2，页内打分，导出 blind-ratings.csv
#   images/P01-left.jpg 每张图左右各一张缩略图（宽 540）
#   mapping.csv         隐藏映射（page,left,right）；**评分完成前不要打开**
#   blind-ratings.csv   评分模板（空）
#
# 评分后：
#   pwsh -NoProfile -File scripts/score-blind.ps1
#   → testdata/report-blind.md（按 mapping 还原 v1/v2 后的评分汇总）
param(
    [string]$Model = "models/doclayout/PP-DocLayout-S.onnx",
    [string]$OutDir = "testdata/out/blind-review",
    [string]$ReportCsv = "testdata/report-v2.csv",
    [int]$ThumbWidth = 540,
    [int]$JpegQuality = 82
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
if (-not (Test-Path $ReportCsv)) { throw "report not found: $ReportCsv (run scripts/score-v2.ps1 first)" }

$imgDir = Join-Path $OutDir "images"
$workDir = Join-Path $OutDir "work"
New-Item -ItemType Directory -Force -Path $imgDir, $workDir | Out-Null

function Save-Jpeg([System.Drawing.Bitmap]$bmp, [string]$path, [int]$quality) {
    $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object { $_.MimeType -eq 'image/jpeg' }
    $params = New-Object System.Drawing.Imaging.EncoderParameters 1
    $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
        [System.Drawing.Imaging.Encoder]::Quality, [long]$quality)
    $bmp.Save($path, $codec, $params)
}

function Find-Source([string]$name) {
    if ($name -like '*@*') {
        $p = Join-Path $root ("testdata/layout/screens/" + $name + ".png")
        if (Test-Path $p) { return $p }
        return $null
    }
    foreach ($ext in @('jpg', 'jpeg', 'png', 'webp')) {
        $p = Join-Path $root ("testdata/samples/" + $name + "." + $ext)
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Get-Group([string]$name) {
    if ($name -like '*@*') { return '夹具' }
    if ($name -match 'Screenshot|device|^image') { return '设备/截图' }
    return '真实样例'
}

function Get-Swap([string]$name) {
    # 用文件名哈希决定左右，保证重跑稳定，且不依赖遍历顺序。
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($name))
    } finally { $sha.Dispose() }
    return (($hash[0] -band 1) -eq 1)
}

function Save-Thumb([string]$source, [string]$dest) {
    $src = [System.Drawing.Image]::FromFile($source)
    try {
        $w = $ThumbWidth
        $h = [int][Math]::Round($src.Height * $w / [double]$src.Width)
        $h = [Math]::Max(1, [Math]::Min($h, 20000))
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.Clear([System.Drawing.Color]::White)
            $g.DrawImage($src, 0, 0, $w, $h)
        } finally { $g.Dispose() }
        try { Save-Jpeg $bmp $dest $JpegQuality } finally { $bmp.Dispose() }
        return $h
    } finally { $src.Dispose() }
}

$rows = Import-Csv $ReportCsv | Where-Object { $_.strategy -ne 'decode_failed' }
Write-Host "images: $($rows.Count)"
$pages = New-Object System.Collections.Generic.List[object]
$mapping = New-Object System.Collections.Generic.List[object]
$idx = 0
$failed = 0

foreach ($row in $rows) {
    $name = $row.name
    $src = Find-Source $name
    if (-not $src) { Write-Host "skip (no source): $name"; $failed++; continue }
    $idx++
    $id = "P{0:D2}" -f $idx
    $out = Join-Path $workDir $id
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $log = & $cli $src $out --out-width 1080 --layout --compare --model $Model 2>&1 | Out-String
    $v1 = Join-Path $out "after-v1.png"
    $v2 = Join-Path $out "after-v2.png"
    if (-not (Test-Path $v1) -or -not (Test-Path $v2)) {
        Write-Host "skip (no compare output): $name"
        $failed++
        continue
    }

    $swap = Get-Swap $name
    $leftFile = if ($swap) { $v2 } else { $v1 }
    $rightFile = if ($swap) { $v1 } else { $v2 }
    $leftVersion = if ($swap) { 'v2' } else { 'v1' }
    $rightVersion = if ($swap) { 'v1' } else { 'v2' }

    $leftJpg = Join-Path $imgDir ("$id-left.jpg")
    $rightJpg = Join-Path $imgDir ("$id-right.jpg")
    $lh = Save-Thumb $leftFile $leftJpg
    $rh = Save-Thumb $rightFile $rightJpg

    # 源图尺寸/tile 数从 plan.json 读，页面只展示“事实”，不展示链路。
    $plan = Join-Path $out "plan.json"
    $srcW = 0; $srcH = 0; $tiles = 0
    if (Test-Path $plan) {
        try {
            $pj = Get-Content $plan -Raw -Encoding UTF8 | ConvertFrom-Json
            $srcW = [int]$pj.src[0]
            $srcH = [int]$pj.src[1]
            if ($pj.layout) { $tiles = [int]$pj.layout.tiles }
        } catch { }
    }

    $pages.Add([ordered]@{
        id = $id
        name = $name
        group = Get-Group $name
        srcW = $srcW
        srcH = $srcH
        tiles = $tiles
        left = "images/$id-left.jpg"
        right = "images/$id-right.jpg"
    })
    $mapping.Add([pscustomobject]@{
        page = $id
        image = $name
        group = (Get-Group $name)
        left = $leftVersion
        right = $rightVersion
        leftHeight = $lh
        rightHeight = $rh
    })
    Write-Host ("{0} {1,-34} left={2} right={3}" -f $id, $name, $leftVersion, $rightVersion)
}

if ($pages.Count -eq 0) { throw "no pages generated" }

$mapping | Export-Csv -Path (Join-Path $OutDir "mapping.csv") -NoTypeInformation -Encoding UTF8

# 空评分模板：盲评页导出的 CSV 与它同列，便于直接替换。
@'
page,left_rating,left_order,left_loss,right_rating,right_order,right_loss,note
'@ | Set-Content -Path (Join-Path $OutDir "blind-ratings.csv") -Encoding UTF8 -NoNewline

$pagesJson = ConvertTo-Json -InputObject $pages -Depth 5 -Compress
$template = @'
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>图读 · v1/v2 盲评</title>
<style>
  :root { --bg:#14161a; --panel:#1d2026; --line:#2c313a; --fg:#e7e9ee; --muted:#9aa3b2; --accent:#4c8dff; --ok:#2fbf71; --warn:#e0a100; --bad:#e05252; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
  header { position:sticky; top:0; z-index:20; background:rgba(20,22,26,.96); border-bottom:1px solid var(--line); padding:10px 16px; }
  h1 { margin:0 0 4px; font-size:16px; }
  .hint { color:var(--muted); font-size:12px; }
  .bar { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-top:8px; }
  button, select, textarea { font:inherit; }
  button { background:var(--panel); color:var(--fg); border:1px solid var(--line); border-radius:6px; padding:5px 10px; cursor:pointer; }
  button:hover { border-color:var(--accent); }
  button.active { background:var(--accent); border-color:var(--accent); color:#fff; }
  button.ok.active { background:var(--ok); border-color:var(--ok); }
  button.warn.active { background:var(--warn); border-color:var(--warn); color:#1a1a1a; }
  button.bad.active { background:var(--bad); border-color:var(--bad); }
  #progress { color:var(--muted); font-variant-numeric:tabular-nums; }
  .meta { padding:6px 16px; color:var(--muted); font-size:12px; }
  .compare { display:grid; grid-template-columns:1fr 1fr; gap:12px; padding:0 12px 12px; }
  .panel { background:var(--panel); border:1px solid var(--line); border-radius:10px; overflow:hidden; display:flex; flex-direction:column; min-width:0; }
  .tag { padding:6px 10px; font-weight:600; border-bottom:1px solid var(--line); }
  .scroll { height:calc(100vh - 360px); min-height:260px; overflow:auto; background:#fff; }
  .scroll img { display:block; width:100%; height:auto; }
  .controls { padding:8px 10px; border-top:1px solid var(--line); display:grid; gap:6px; }
  .row { display:flex; flex-wrap:wrap; gap:6px; align-items:center; }
  .row .lbl { color:var(--muted); font-size:12px; min-width:44px; }
  .note { width:100%; background:#15171c; color:var(--fg); border:1px solid var(--line); border-radius:6px; padding:6px 8px; resize:vertical; min-height:34px; }
  @media (max-width:900px) { .compare { grid-template-columns:1fr; } .scroll { height:60vh; } }
</style>
</head>
<body>
<header>
  <h1>图读 · v1/v2 盲评</h1>
  <div class="hint">左右是同一张图的两种排版结果，顺序已随机打乱；评分时不要猜哪边是 v2。评分保存在本机浏览器；完成后点“导出评分 CSV”，把下载的 blind-ratings.csv 放回本目录，再运行 scripts/score-blind.ps1 出汇总。</div>
  <div class="bar">
    <button id="prev">← 上一张</button>
    <select id="jump"></select>
    <button id="next">下一张 →</button>
    <select id="group" title="按分组筛选"></select>
    <span id="progress"></span>
    <label class="hint"><input type="checkbox" id="sync" checked> 同步滚动</label>
    <button id="export">导出评分 CSV</button>
    <button id="reset">清空本机评分</button>
  </div>
</header>
<div class="meta" id="meta"></div>
<div class="compare">
  <section class="panel">
    <div class="tag">左</div>
    <div class="scroll" id="scrollL"><img id="imgL" alt="左侧排版结果"></div>
    <div class="controls" data-side="left">
      <div class="row"><span class="lbl">可用度</span>
        <button class="ok" data-side="left" data-field="rating" data-value="可用">可用</button>
        <button class="warn" data-side="left" data-field="rating" data-value="勉强">勉强</button>
        <button class="bad" data-side="left" data-field="rating" data-value="不可用">不可用</button>
      </div>
      <div class="row"><span class="lbl">顺序</span>
        <button class="ok" data-side="left" data-field="order" data-value="对">对</button>
        <button class="bad" data-side="left" data-field="order" data-value="错">错</button>
      </div>
      <div class="row"><span class="lbl">内容</span>
        <button class="ok" data-side="left" data-field="loss" data-value="无丢失">无丢失</button>
        <button class="bad" data-side="left" data-field="loss" data-value="有丢失">有丢失</button>
      </div>
      <textarea class="note" data-side="left" placeholder="左侧问题（可空）"></textarea>
    </div>
  </section>
  <section class="panel">
    <div class="tag">右</div>
    <div class="scroll" id="scrollR"><img id="imgR" alt="右侧排版结果"></div>
    <div class="controls" data-side="right">
      <div class="row"><span class="lbl">可用度</span>
        <button class="ok" data-side="right" data-field="rating" data-value="可用">可用</button>
        <button class="warn" data-side="right" data-field="rating" data-value="勉强">勉强</button>
        <button class="bad" data-side="right" data-field="rating" data-value="不可用">不可用</button>
      </div>
      <div class="row"><span class="lbl">顺序</span>
        <button class="ok" data-side="right" data-field="order" data-value="对">对</button>
        <button class="bad" data-side="right" data-field="order" data-value="错">错</button>
      </div>
      <div class="row"><span class="lbl">内容</span>
        <button class="ok" data-side="right" data-field="loss" data-value="无丢失">无丢失</button>
        <button class="bad" data-side="right" data-field="loss" data-value="有丢失">有丢失</button>
      </div>
      <textarea class="note" data-side="right" placeholder="右侧问题（可空）"></textarea>
    </div>
  </section>
</div>
<script>
const PAGES = __PAGES__;
const STORE = 'pictext-blind-review-v1';
let scores = {};
try { scores = JSON.parse(localStorage.getItem(STORE) || '{}'); } catch (e) { scores = {}; }
let idx = 0;
let filter = '全部';

const el = (id) => document.getElementById(id);
const viewPages = () => filter === '全部' ? PAGES : PAGES.filter((p) => p.group === filter);
const page = () => viewPages()[idx];
const rec = () => (scores[page().id] = scores[page().id] || {});

function save() { try { localStorage.setItem(STORE, JSON.stringify(scores)); } catch (e) { } }

function fillSelect() {
  const ps = viewPages();
  const s = el('jump');
  s.innerHTML = '';
  ps.forEach((p, i) => {
    const o = document.createElement('option');
    o.value = String(i);
    const r = scores[p.id] || {};
    const done = r.left_rating && r.right_rating ? ' ✓' : '';
    o.textContent = p.id + ' · ' + p.group + done;
    s.appendChild(o);
  });
  s.value = String(idx);
}

function fillGroups() {
  const g = el('group');
  const groups = Array.from(new Set(PAGES.map((p) => p.group)));
  g.innerHTML = '';
  ['全部'].concat(groups).forEach((v) => {
    const o = document.createElement('option');
    o.value = v;
    o.textContent = v;
    g.appendChild(o);
  });
  g.value = filter;
}

function applyButtons() {
  const r = rec();
  document.querySelectorAll('button[data-side][data-field]').forEach((b) => {
    const side = b.dataset.side, field = b.dataset.field;
    const v = r[side + '_' + field];
    b.classList.toggle('active', v === b.dataset.value);
  });
  const noteL = document.querySelector('textarea[data-side="left"]');
  const noteR = document.querySelector('textarea[data-side="right"]');
  noteL.value = r.left_note || '';
  noteR.value = r.right_note || '';
}

function render() {
  const p = page();
  el('meta').textContent = p.id + ' · ' + p.name + ' · ' + (p.srcW && p.srcH ? p.srcW + '×' + p.srcH : '?') + ' · tiles=' + (p.tiles || '?');
  el('imgL').src = p.left;
  el('imgR').src = p.right;
  el('imgL').onload = () => { el('scrollL').scrollTop = 0; };
  el('imgR').onload = () => { el('scrollR').scrollTop = 0; };
  applyButtons();
  fillSelect();
  const ps = viewPages();
  const done = ps.filter((q) => { const x = scores[q.id] || {}; return x.left_rating && x.right_rating; }).length;
  el('progress').textContent = (idx + 1) + '/' + ps.length + ' · 已评 ' + done;
  try { history.replaceState(null, '', '#' + p.id); } catch (e) { }
}

function go(n) {
  idx = Math.max(0, Math.min(viewPages().length - 1, n));
  render();
}

document.querySelectorAll('button[data-side][data-field]').forEach((b) => {
  b.addEventListener('click', () => {
    const r = rec();
    const key = b.dataset.side + '_' + b.dataset.field;
    r[key] = (r[key] === b.dataset.value) ? '' : b.dataset.value;
    save(); applyButtons(); fillSelect();
    const done = viewPages().filter((q) => { const x = scores[q.id] || {}; return x.left_rating && x.right_rating; }).length;
    el('progress').textContent = (idx + 1) + '/' + viewPages().length + ' · 已评 ' + done;
  });
});

document.querySelectorAll('textarea[data-side]').forEach((t) => {
  t.addEventListener('input', () => {
    const r = rec();
    r[t.dataset.side + '_note'] = t.value;
    save();
  });
});

function syncScroll(from, to) {
  if (!el('sync').checked) return;
  const a = el(from), b = el(to);
  const am = a.scrollHeight - a.clientHeight;
  const bm = b.scrollHeight - b.clientHeight;
  if (am <= 0 || bm <= 0) return;
  b.scrollTop = Math.round(a.scrollTop / am * bm);
}

el('prev').onclick = () => go(idx - 1);
el('next').onclick = () => go(idx + 1);
el('jump').onchange = (e) => go(Number(e.target.value));
el('group').onchange = (e) => { filter = e.target.value; idx = 0; render(); };
el('scrollL').addEventListener('scroll', () => syncScroll('scrollL', 'scrollR'));
el('scrollR').addEventListener('scroll', () => syncScroll('scrollR', 'scrollL'));

function csvCell(v) {
  v = (v === undefined || v === null) ? '' : String(v);
  return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}

el('export').onclick = () => {
  const lines = ['page,left_rating,left_order,left_loss,right_rating,right_order,right_loss,note'];
  PAGES.forEach((p) => {
    const r = scores[p.id] || {};
    const note = [r.left_note ? '左:' + r.left_note : '', r.right_note ? '右:' + r.right_note : '']
      .filter(Boolean).join(' | ');
    lines.push([p.id, r.left_rating || '', r.left_order || '', r.left_loss || '',
      r.right_rating || '', r.right_order || '', r.right_loss || '', note].map(csvCell).join(','));
  });
  const blob = new Blob(['\ufeff' + lines.join('\n') + '\n'], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'blind-ratings.csv';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
};

el('reset').onclick = () => {
  if (!confirm('清空本机所有评分？此操作不可撤销。')) return;
  scores = {}; save(); render();
};

window.addEventListener('hashchange', () => {
  const id = location.hash.replace('#', '');
  const i = viewPages().findIndex((p) => p.id === id);
  if (i >= 0 && i !== idx) go(i);
});

fillGroups();
render();
</script>
</body>
</html>
'@
$html = $template.Replace('__PAGES__', $pagesJson)
Set-Content -Path (Join-Path $OutDir "index.html") -Value $html -Encoding UTF8 -NoNewline

Write-Host ""
Write-Host "page   : $(Join-Path $OutDir 'index.html')"
Write-Host "mapping: $(Join-Path $OutDir 'mapping.csv')  (评分完成前不要打开)"
Write-Host "pages=$($pages.Count) failed=$failed"
