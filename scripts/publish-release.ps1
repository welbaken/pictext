# 图读 PicText · 发布 Gitee 发行版（建 release + 上传 APK）
#
# 为什么需要脚本：git 只能推代码和 tag，「发行版 + 附件」是 Gitee 平台功能，
# 只能走网页或 REST API。本脚本在运行时从 Windows 凭据（git credential fill）里取
# token，**不会把它打印出来**，也不会写进任何文件。
#
# 首次使用前确认凭据已存在（推过一次代码就会有）：
#   git credential fill <<< "protocol=https`nhost=gitee.com"    # 能打印 username/password 即可
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/publish-release.ps1 -Version 0.5.3
#   powershell -ExecutionPolicy Bypass -File scripts/publish-release.ps1 -Version 0.5.3 -DryRun
#
# -DryRun 只打印将要提交的内容，不读凭据、不发请求。

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Owner = "noneoneone",
    [string]$Repo = "pictext",
    [string]$Apk = "",
    [string]$Notes = "",
    [string]$TargetCommitish = "main",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $tag = "v$Version"
    if ([string]::IsNullOrWhiteSpace($Apk)) { $Apk = "dist/pictext-$Version-v1.apk" }
    if ([string]::IsNullOrWhiteSpace($Notes)) { $Notes = "dist/RELEASE-$tag.md" }

    $apkFile = Get-Item $Apk -ErrorAction SilentlyContinue
    if (-not $apkFile) { throw "找不到安装包：$Apk（先跑 :app:assembleRelease 再拷到 dist/）" }
    $notesFile = Get-Item $Notes -ErrorAction SilentlyContinue
    $body = if ($notesFile) { Get-Content $notesFile.FullName -Raw } else { "版本 $Version" }
    $title = "图读 PicText $Version（v1-only）"

    $sha = (Get-FileHash $apkFile.FullName -Algorithm SHA256).Hash.ToLower()
    Write-Host "版本 : $tag"
    Write-Host "安装包: $($apkFile.FullName)  $($apkFile.Length) 字节"
    Write-Host "SHA256: $sha"
    Write-Host "说明  : $(if ($notesFile) { $notesFile.FullName } else { '（无，用默认一句话）' })"

    if ($DryRun) {
        Write-Host "`n--- DryRun：以下内容会被提交 ---"
        Write-Host "tag_name   = $tag"
        Write-Host "name       = $title"
        Write-Host "target     = $TargetCommitish"
        Write-Host "body 长度  = $($body.Length) 字符"
        Write-Host "附件       = $($apkFile.Name)"
        return
    }

    # ---- 取凭据（只在本进程内存里）----
    $env:GIT_TERMINAL_PROMPT = "0"
    $fill = "protocol=https`nhost=gitee.com`n`n" | git credential fill 2>$null
    $token = ($fill | Where-Object { $_ -like "password=*" }) -replace "^password=", ""
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Windows 凭据里没有 gitee.com 的 token。先用 git push 一次（让凭据管理器记住），或手动填写。"
    }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $base = "https://gitee.com/api/v5/repos/$Owner/$Repo"

    # ---- 建 release；已存在就复用（Gitee 的 tags 端点不可靠，直接翻列表匹配）----
    $release = $null
    try {
        $release = Invoke-RestMethod -Method Post -Uri "$base/releases" -TimeoutSec 60 -Body @{
            access_token     = $token
            tag_name         = $tag
            name             = $title
            body             = $body
            target_commitish = $TargetCommitish
            prerelease       = "false"
        }
        Write-Host "已创建 release id=$($release.id)"
    } catch {
        Write-Host "创建失败（多半是已存在），改为翻列表找 tag=$tag：$($_.Exception.Message)"
        $page = 1
        while ($page -le 5 -and -not $release) {
            # 注意：Invoke-RestMethod 把整个 JSON 数组**当成一个对象**写进管道，
            # 所以不能套 @(...)——那样会得到「1 个元素，元素还是数组」，后面的
            # `$item.tag_name` 就变成数组比较，永远匹配不上。
            $resp = Invoke-RestMethod -Method Get -Uri "$base/releases?access_token=$token&page=$page&per_page=100" -TimeoutSec 60
            $list = if ($resp -is [System.Array]) { $resp } else { @($resp) }
            if ($list.Count -eq 0) { break }
            foreach ($item in $list) {
                if ([string]$item.tag_name -eq $tag) { $release = $item; break }
            }
            $page++
        }
        if (-not $release -or -not $release.id) {
            throw "既创建不了也找不到 tag=$tag 的 release；请检查 token 权限（需要 projects 或 releases 权限）。"
        }
        Write-Host "复用已有 release id=$($release.id)"
    }

    # ---- 上传 APK（PowerShell 5.1 没有 -Form，用 HttpClient 拼 multipart）----
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(5)
    try {
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        $bytes = [System.IO.File]::ReadAllBytes($apkFile.FullName)
        # 注意：New-Object 会把 byte[] 拆成多个构造参数，必须用 ::new()
        $part = [System.Net.Http.ByteArrayContent]::new($bytes)
        $part.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/octet-stream")
        $multipart.Add($part, "file", $apkFile.Name)

        $url = "$base/releases/$($release.id)/attach_files?access_token=$token"
        $resp = $client.PostAsync($url, $multipart).Result
        $text = $resp.Content.ReadAsStringAsync().Result
        if (-not $resp.IsSuccessStatusCode) { throw "上传失败 HTTP $([int]$resp.StatusCode)：$text" }
        $asset = $text | ConvertFrom-Json
        Write-Host "已上传：$($asset.name)"
        Write-Host "直链：$($asset.browser_download_url)"
    } finally {
        $client.Dispose()
    }

    Write-Host "`n发行版页面：https://gitee.com/$Owner/$Repo/releases/tag/$tag"
} finally {
    Pop-Location
}
