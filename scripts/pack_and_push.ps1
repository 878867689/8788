# pack_and_push.ps1 —— 一键「打包 + 上传 + 出 APK」（Windows PowerShell 版）
#
# 做三件事，全程不需要你手工操作 GitHub，也不需要本机有 Android SDK：
#   1) 把本工程源码推到 GitHub（仓库不存在就自动创建，空仓库直接推）；
#   2) 触发仓库里的 GitHub Actions（.github/workflows/build-apk.yml）云端编译；
#   3) 等编译结束，把编出来的 APK 下载回本地，并作为 Release 附件传回 GitHub。
#
# ── 用法 ─────────────────────────────────────────────────────────────
#   $env:GITHUB_TOKEN = "ghp_xxxxxxxx"     # 必填：PAT，需 repo + workflow 权限
#   $env:GITHUB_OWNER = "你的用户名"        # 可选：不填会用 token 反查
#   $env:GITHUB_REPO  = "mcp-android-bridge"  # 可选
#   powershell -ExecutionPolicy Bypass -File scripts/pack_and_push.ps1
#
# ── 可调参数（都是环境变量） ──────────────────────────────────────────
#   BRANCH / REPO_PRIVATE / WORKFLOW_FILE / RELEASE_TAG / WAIT_TIMEOUT
#   SKIP_WAIT=1 只推源码；SKIP_RELEASE=1 只留本地；FORCE_PUSH=1 覆盖远端

$ErrorActionPreference = "Stop"

# ------------------------------------------------------------------ 配置
function EnvOr($name, $default) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($v)) { return $default } else { return $v }
}

$Token        = EnvOr "GITHUB_TOKEN" ""
$Owner        = EnvOr "GITHUB_OWNER" ""
$Repo         = EnvOr "GITHUB_REPO" "mcp-android-bridge"
$Branch       = EnvOr "BRANCH" "main"
$RepoPrivate  = (EnvOr "REPO_PRIVATE" "true").ToLower()
$WorkflowFile = EnvOr "WORKFLOW_FILE" "build-apk.yml"
$ReleaseTag   = EnvOr "RELEASE_TAG" ("v1.0-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$WaitTimeout  = [int](EnvOr "WAIT_TIMEOUT" "1800")
$SkipWait     = EnvOr "SKIP_WAIT" "0"
$SkipRelease  = EnvOr "SKIP_RELEASE" "0"
$ForcePush    = EnvOr "FORCE_PUSH" "0"
$TargetDir    = EnvOr "TARGET_DIR" ""

$Api = "https://api.github.com"
$UA  = "mcp-android-bridge-pack"

# ------------------------------------------------------------------ 小工具
function Ok($m)   { Write-Host $m -ForegroundColor Green }
function Info($m) { Write-Host $m -ForegroundColor Cyan }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Err($m)  { Write-Host $m -ForegroundColor Red }
function Die($m)  { Err "错误：$m"; exit 1 }

if ($Token -match "^\s*$") { Die "未设置 GITHUB_TOKEN（PAT），无法上传。示例：`$env:GITHUB_TOKEN='ghp_xxx'" }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Die "未找到 git，请先安装 Git for Windows" }

$Headers = @{
    Authorization         = "Bearer $Token"
    Accept                = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"          = $UA
}

# 统一 API 调用，返回 @{ Code=...; Data=...; Error=... }
function Invoke-Gh {
    param([string]$Method, [string]$Path, $Body)
    $params = @{ Method = $Method; Uri = "$Api$Path"; Headers = $Headers; ErrorAction = "Stop" }
    if ($null -ne $Body) {
        $params.Body = $Body
        $params.ContentType = "application/json"
    }
    try {
        $data = Invoke-RestMethod @params
        return [pscustomobject]@{ Code = 200; Data = $data; Error = $null }
    } catch {
        $code = 0
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        return [pscustomobject]@{ Code = $code; Data = $null; Error = $_.Exception.Message }
    }
}

# 下载二进制（产物 zip 会 302 跳到 S3，手动跟随并去掉 Authorization，避免被 S3 拒绝）
function Get-ApiFile {
    param([string]$Path, [string]$OutFile)
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", "Bearer $Token") | Out-Null
    $client.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "application/vnd.github+json") | Out-Null
    $client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", $UA) | Out-Null

    $resp = $client.GetAsync("$Api$Path").GetAwaiter().GetResult()
    if ([int]$resp.StatusCode -ge 300 -and [int]$resp.StatusCode -lt 400) {
        $loc = $resp.Headers.Location.ToString()
        $client.DefaultRequestHeaders.Remove("Authorization") | Out-Null
        $resp = $client.GetAsync($loc).GetAwaiter().GetResult()
    }
    $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    [System.IO.File]::WriteAllBytes($OutFile, $bytes)
    $client.Dispose()
}

# 上传 Release 附件
function Send-ApiAsset {
    param([string]$Uri, [string]$File)
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", "Bearer $Token") | Out-Null
    $client.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "application/vnd.github+json") | Out-Null
    $client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", $UA) | Out-Null
    $content = New-Object System.Net.Http.ByteArrayContent([System.IO.File]::ReadAllBytes($File))
    $content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/vnd.android.package-archive")
    $resp = $client.PostAsync($Uri, $content).GetAwaiter().GetResult()
    $code = [int]$resp.StatusCode
    $client.Dispose()
    return $code
}

# ------------------------------------------------------------------ 0. 定位工程
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($TargetDir -ne "") { $Root = (Resolve-Path $TargetDir).Path } else { $Root = (Resolve-Path (Join-Path $ScriptDir "..")).Path }
if (-not (Test-Path (Join-Path $Root "settings.gradle.kts"))) { Die "在 $Root 找不到 settings.gradle.kts，请用 TARGET_DIR 指定工程目录" }
$OutDir = Join-Path $Root "out"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("pack-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null

Info "==> 工程目录：$Root"

# ------------------------------------------------------------------ 1. 确认账号 & 建仓
$me = Invoke-Gh "Get" "/user"
if ($me.Code -ne 200) { Die "PAT 校验失败（HTTP $($me.Code)）：$($me.Error)" }
$login = $me.Data.login
if ($Owner -eq "") { $Owner = $login }
Info "==> 账号：$login    目标仓库：$Owner/$Repo"

$exists = Invoke-Gh "Get" "/repos/$Owner/$Repo"
if ($exists.Code -eq 200) {
    Ok "    仓库已存在，直接复用"
} elseif ($exists.Code -eq 404) {
    $isPrivate = if ($RepoPrivate -eq "true") { $true } else { $false }
    $payload = @{ name = $Repo; private = $isPrivate; description = "MCP Android Bridge (auto pack & push)"; auto_init = $false } | ConvertTo-Json
    $created = Invoke-Gh "Post" "/user/repos" $payload
    if ($created.Code -ne 200 -and $created.Code -ne 201) {
        $created = Invoke-Gh "Post" "/orgs/$Owner/repos" $payload
    }
    if ($null -eq $created.Data) { Die "建仓失败：$($created.Error)" }
    Ok "    仓库已创建（空仓库）"
} else {
    Die "查询仓库失败（HTTP $($exists.Code)）：$($exists.Error)"
}

# ------------------------------------------------------------------ 2. 推源码
Set-Location $Root

if (-not (Test-Path (Join-Path $Root ".git"))) {
    & git init -q
    & git symbolic-ref HEAD "refs/heads/$Branch" 2>$null
}
& git rev-parse --verify HEAD 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { & git checkout -q -b $Branch 2>$null }

$uE = & git config user.email 2>$null
if ([string]::IsNullOrWhiteSpace($uE)) { & git config user.email "mcp-bridge@users.noreply.github.com" }
$uN = & git config user.name 2>$null
if ([string]::IsNullOrWhiteSpace($uN)) { & git config user.name "MCP Android Bridge" }

& git add -A
& git diff --cached --quiet 2>$null
if ($LASTEXITCODE -eq 0) {
    Warn "    没有新增改动，跳过提交"
} else {
    & git commit -q -m "chore: sync MCP Android Bridge source"
    Ok "    已生成提交"
}

& git remote remove origin 2>$null | Out-Null
& git remote add origin "https://github.com/$Owner/$Repo.git"

$pushUrl = "https://x-access-token:$Token@github.com/$Owner/$Repo.git"
Info "==> 推送源码到 $Branch 分支"
& git push -q $pushUrl "HEAD:refs/heads/$Branch" 2>&1 | Tee-Object -Variable pushOut | Out-Null
if ($LASTEXITCODE -ne 0) {
    if ($ForcePush -eq "1") {
        Warn "    普通推送失败，按要求强制覆盖远端历史"
        & git push -q --force $pushUrl "HEAD:refs/heads/$Branch"
        if ($LASTEXITCODE -ne 0) { Die "强制推送仍失败：$pushOut" }
    } else {
        Die "推送失败：$pushOut`n若远端已有旧历史，可设 `$env:FORCE_PUSH=1 后重试（会覆盖远端）"
    }
}
$sha = (& git rev-parse HEAD).Trim()
Ok "    推送完成：$($sha.Substring(0,8))"

if ($SkipWait -eq "1") {
    Ok "==> 已跳过等待。源码已上传，去仓库 Actions 页面即可看到编译："
    Write-Host "    https://github.com/$Owner/$Repo/actions"
    exit 0
}

# ------------------------------------------------------------------ 3. 等编译结束
Info "==> 等待 GitHub Actions 编译（同一提交 SHA 的 run）…"
$runId = $null
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    $runs = Invoke-Gh "Get" "/repos/$Owner/$Repo/actions/runs?head_sha=$sha&per_page=1"
    if ($runs.Data -and $runs.Data.workflow_runs.Count -gt 0) { $runId = $runs.Data.workflow_runs[0].id; break }
    Start-Sleep -Seconds 5
}
if ($null -eq $runId) { Die "没等到本次提交对应的 run，稍后可到 Actions 页面手动查看" }

Info "    run_id=$runId"
$status = ""; $conclusion = ""
$deadline = (Get-Date).AddSeconds($WaitTimeout)
while ((Get-Date) -lt $deadline) {
    $run = Invoke-Gh "Get" "/repos/$Owner/$Repo/actions/runs/$runId"
    $status = $run.Data.status
    $conclusion = $run.Data.conclusion
    Write-Host ("`r    状态: {0,-10} 结论: {1,-10}" -f $status, $conclusion) -NoNewline
    if ($status -eq "completed") { break }
    Start-Sleep -Seconds 10
}
Write-Host ""
if ($status -ne "completed") { Die "等待超时（${WaitTimeout}s），构建仍在进行，可稍后重跑本脚本并设 SKIP_WAIT=1" }

if ($conclusion -ne "success") {
    Err "构建未成功（conclusion=$conclusion）。尝试下载日志到 $OutDir\build-logs.zip"
    try { Get-ApiFile "/repos/$Owner/$Repo/actions/runs/$runId/logs" (Join-Path $OutDir "build-logs.zip") } catch { }
    Write-Host "    运行详情：https://github.com/$Owner/$Repo/actions/runs/$runId"
    exit 1
}
Ok "==> 编译成功"

# ------------------------------------------------------------------ 4. 取 APK
$arts = Invoke-Gh "Get" "/repos/$Owner/$Repo/actions/runs/$runId/artifacts"
if (-not $arts.Data -or $arts.Data.artifacts.Count -eq 0) { Die "该 run 没有产物，请确认 workflow 里 upload-artifact 的 path 是否匹配 APK" }
$artifactId = $arts.Data.artifacts[0].id
Info "==> 下载产物 artifact=$artifactId"
$zip = Join-Path $TmpDir "art.zip"
Get-ApiFile "/repos/$Owner/$Repo/actions/artifacts/$artifactId/zip" $zip
$unzipDir = Join-Path $TmpDir "art"
Expand-Archive -Path $zip -DestinationPath $unzipDir -Force

$apk = Get-ChildItem -Path $unzipDir -Recurse -Filter *.apk | Select-Object -First 1
if ($null -eq $apk) { Die "产物里没有找到 .apk 文件" }
$apkName = $apk.Name
Copy-Item $apk.FullName (Join-Path $OutDir $apkName) -Force
Ok "==> APK 已保存：$OutDir\$apkName  （$((Get-Item (Join-Path $OutDir $apkName)).Length) 字节）"

# ------------------------------------------------------------------ 5. 把 APK 传回 GitHub（Release 附件）
if ($SkipRelease -eq "1") {
    Info "==> 已跳过 Release 上传（SKIP_RELEASE=1）"
} else {
    Info "==> 创建 Release 并上传 APK 到 GitHub"
    $relBody = @{ tag_name = $ReleaseTag; name = $ReleaseTag; body = "由 pack_and_push.ps1 自动打包：源码经 GitHub Actions 编译生成 APK。"; draft = $false; prerelease = $false } | ConvertTo-Json
    $rel = Invoke-Gh "Post" "/repos/$Owner/$Repo/releases" $relBody
    if ($null -eq $rel.Data) {
        Warn "    Release 创建失败：$($rel.Error)，APK 仍已保存在本地 $OutDir\"
    } else {
        $uploadUrl = $rel.Data.upload_url -replace "\{.*\}$", ""
        $code = Send-ApiAsset "$uploadUrl?name=$apkName" (Join-Path $OutDir $apkName)
        if ($code -eq 201) { Ok "    已上传 APK 到 Release：$ReleaseTag" }
        else { Warn "    附件上传失败（HTTP $code），APK 仍在本地 $OutDir\" }
    }
}

# ------------------------------------------------------------------ 汇总
Write-Host ""
Ok "══════════════ 完成 ══════════════"
Write-Host "  仓库      : https://github.com/$Owner/$Repo"
Write-Host "  本次构建  : https://github.com/$Owner/$Repo/actions/runs/$runId"
Write-Host "  Release   : https://github.com/$Owner/$Repo/releases/tag/$ReleaseTag"
Write-Host "  本地 APK  : $OutDir\$apkName"
Write-Host ""
Info "下一步：把 $apkName 装到手机上 → 启动 App 的 MCP 服务 → 把界面上的地址+令牌交给 AI 客户端。"

Remove-Item $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
