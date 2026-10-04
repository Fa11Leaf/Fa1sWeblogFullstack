<#
    weblog-fullstack 启动脚本
    ================================================================

    目标运行环境与平台
    ------------------
    平台      Windows 10 / 11（本脚本不支持 Linux / macOS）
    外壳      Windows PowerShell 5.1 或 PowerShell 7+
    必需软件  JDK 17+（推荐 21）· Node.js 22+ · Python 3.11+ · Docker Desktop
    端口占用  3306（MySQL）· 8000（Python）· 18090（后端）· 5174（前台）
              后端刻意避开 8080（Steam 固定占用）与 8090 等端口（落在 Windows 保留范围内）
    网络      首次运行需要联网：Maven Wrapper 要下载 Maven，pip / npm 要下载依赖

    需要启动的服务（四个进程，按依赖顺序）
    --------------------------------------
    1. mysql           Docker 容器 weblog-mysql        →  127.0.0.1:3306
    2. python-service  uvicorn app.main:app            →  127.0.0.1:8000
    3. backend         mvnw spring-boot:run            →  127.0.0.1:18090
    4. admin           npm run dev（Vite 开发服务器）   →  127.0.0.1:5174

    依赖的安装与检查
    ----------------
    脚本会自动完成（幂等，已就绪则跳过）：
      · python-service\.venv        不存在则用 Python 创建虚拟环境
      · Python 依赖                 按 requirements.txt 的 SHA256 判断是否需要重装
      · admin\node_modules          不存在则执行 npm install
    脚本只负责检查、不代你安装的：JDK、Node.js、Python、Docker Desktop。
    它们属于开发环境，装法因机器而异，脚本只报告版本是否满足要求。

    环境变量
    --------
    从项目根目录的 .env 读取；首次运行会自动从 .env.example 复制一份出来。
    优先级：当前 shell 已设的环境变量  >  .env 文件  >  脚本内置默认值。
    必需项（为空时非严格模式会回退到开发默认值；加 -Strict 则判为错误）：
      MYSQL_ROOT_PASSWORD   仅容器内部健康检查使用
      MYSQL_PASSWORD        Spring Boot 连库口令，必须与 compose 中的一致
      PY_INTERNAL_TOKEN     Spring Boot 与 Python 服务共享的内部令牌
    .env.example 里的默认值与本机开发配置一致，因此刚 clone 下来也能直接跑通。
    允许留空（后续阶段才用到）：
      GITHUB_TOKEN          发布文章用的凭据，P4 才需要
      BLOG_ADMIN_PASSWORD   首次启动创建管理员的口令，P2 才需要

    启动前的校验
    ------------
    校验阶段是只读的，不启动、不安装、不改任何文件。逐项打印 OK / WARN / FAIL：
    软件版本、Docker 守护进程、.env 完整性、端口是否被占用、必要文件是否存在。
    出现 FAIL 立即中止并给出修复建议；WARN 只提示、不阻断。
    只想跑校验：start.cmd -Check

    失败时的错误提示与日志
    ----------------------
    · 每一步都有独立超时（默认：数据库 180s、Python 60s、后端 420s、前端 60s）
    · 任一步失败会打印四样东西：失败的环节、该服务的日志路径、日志末尾若干行、可能原因
    · 全部控制台输出同时写入 logs\start-<时间戳>.log
    · 每个服务的标准输出与错误分别写入 logs\<服务>.out.log 与 logs\<服务>.err.log

    常用用法
    --------
    start.cmd                        启动全部四个服务
    start.cmd -Check                 只做启动前校验
    start.cmd -Service db,python     只启动其中一部分
    start.cmd -SkipInstall           跳过 pip / npm 安装步骤
    start.cmd -TimeoutSeconds 600    放宽超时（首次启动要下载 Maven 时有用）
    start.cmd -Strict                把"占位口令"当成错误而不是警告
    start.cmd -Stop                  停止本脚本启动的进程与数据库容器

    .PARAMETER Service
    要启动的服务，可多选：db, python, backend, admin。默认全启。
    依赖会自动带上：选 backend 就会先起 db 与 python。

    .PARAMETER Check
    只执行启动前校验，然后退出。不启动、不安装。

    .PARAMETER Stop
    停止本脚本启动的进程（按 logs\pids.json 记录）与数据库容器。数据卷保留。

    .PARAMETER SkipInstall
    跳过 pip 与 npm 的依赖安装，只启动。适合依赖已确定就绪、想快启的场景。

    .PARAMETER SkipDocker
    不启动 MySQL 容器。适合本机已另行运行 MySQL 的情况。

    .PARAMETER Strict
    把 .env 里"仍是 change-me 占位串"的情形从警告升级为错误并中止。
    默认是宽容的：占位串会自动换成内置开发默认值，只提示不阻断，
    这样刚 clone 下来也能一键跑通。准备部署到其他机器时加上它。

    .PARAMETER TimeoutSeconds
    等待各服务就绪的基础超时秒数，实际会按服务乘以不同系数。
#>

[CmdletBinding()]
param(
    [ValidateSet('all', 'db', 'python', 'backend', 'frontend', 'admin')]
    [string[]]$Service = @('all'),

    [switch]$Check,
    [switch]$Stop,
    [switch]$SkipInstall,
    [switch]$SkipDocker,
    [switch]$Strict,
    [switch]$NoBrowser,

    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

# ── 路径 ────────────────────────────────────────────────────────────────
$Root = $PSScriptRoot
if ([string]::IsNullOrEmpty($Root)) {
    $Root = Split-Path -Parent $MyInvocation.MyCommand.Path
}
$LogDir = Join-Path $Root 'logs'
$PidFile = Join-Path $LogDir 'pids.json'
$EnvFile = Join-Path $Root '.env'
$EnvExample = Join-Path $Root '.env.example'

# 各服务端口与路径
$PortDb = 3306
$PortPython = 8000
$PortBackend = 18090
$PortFrontend = 4321
$PortAdmin = 5174
# 数据库镜像。必须与 docker-compose.yml 里写的保持一致。
$DbImage = 'mysql:8.4'

# compose 里的服务名是 mysql，容器名是 weblog-mysql —— 两者不能混用：
#   docker compose up / stop / ps   要【服务名】mysql
#   docker inspect / logs           要【容器名】weblog-mysql
$DbService = 'mysql'
$DbContainer = 'weblog-mysql'

$BackendDir = Join-Path $Root 'backend'
$PythonDir = Join-Path $Root 'python-service'
$AdminDir = Join-Path $Root 'admin'

# 各服务就绪超时（在基础超时上按服务放大）
$TimeoutPython = [Math]::Max(60, $TimeoutSeconds)
$TimeoutDb = [Math]::Max(180, $TimeoutSeconds)
$TimeoutBackend = [Math]::Max(420, $TimeoutSeconds)
$TimeoutAdmin = [Math]::Max(60, $TimeoutSeconds)
# Nuxt 首次启动要做一次依赖预构建，比 Vite 慢，给足时间
$TimeoutFrontend = [Math]::Max(240, $TimeoutSeconds)

# 脚本内置默认值（优先级最低）。
# 刻意【不含】MYSQL_PASSWORD：数据库凭据没有合理的默认值，
# 缺了就应该直接失败，而不是用一个猜出来的值去连库。
$Defaults = @{
    'MYSQL_USERNAME'    = 'root'
    'PY_INTERNAL_TOKEN' = 'dev-internal-token'
}

# 必需项：缺失或仍是占位串时的处理见 Invoke-Preflight。
# MYSQL_ROOT_PASSWORD 不在其中 —— 它只被 docker-compose 的容器用到，
# 本机已有 MySQL 时完全不需要。
$RequiredKeys = @('MYSQL_USERNAME', 'MYSQL_PASSWORD', 'PY_INTERNAL_TOKEN')
$PlaceholderHints = @('change-me', 'changeme', 'your-', 'xxx')
# 与 .env.example、application-dev.yml 一致的开发默认值。
# 不加 -Strict 时视为正常（这样 clone 下来就能直接跑）；
# 加 -Strict 时视为不合格，用来防止带着开发口令上线。
$DevDefaults = @('dev-internal-token')

# 失败时的排查提示
$Hints = @{
    'mysql'   = @(
        '连不上本机 MySQL？先确认 MySQL80 服务在运行（services.msc 里看），再核对 .env 里的 MYSQL_USERNAME / MYSQL_PASSWORD。',
        '报 Public Key Retrieval is not allowed？JDBC URL 里的 allowPublicKeyRetrieval=true 被删了 —— 本机 MySQL 用 caching_sha2_password，这一项必须保留。',
        '报 Access denied？口令不对，或这个账号不允许从 127.0.0.1 登录。',
        '报 Unknown database ''weblog''？库还没建，见 README §18.6。',
        '若走 Docker 备用方案：报 502 / failed to resolve reference 是访问 Docker Hub 的网络问题，重启 Docker Desktop 或配 registry-mirrors。',
        '报 no such service？把容器名当服务名了。compose 里服务名是 mysql，容器名才是 weblog-mysql。'
    )
    'python'  = @(
        '先手动跑一次看真实报错：cd python-service; .venv\Scripts\python.exe -m uvicorn app.main:app --port 8000',
        '依赖没装全？删掉 python-service\.venv 后重跑本脚本。',
        '端口 8000 被占？改 PY_PORT 环境变量与后端 app.python.base-url 需同步改。'
    )
    'backend' = @(
        '首次运行要下载 Maven 与依赖，可能超过 7 分钟，用 -TimeoutSeconds 600 放宽。',
        '编译报错？日志里搜 "ERROR" 与 "symbol"，多半是某个 API 在 Spring Framework 7 下签名不同。',
        '连不上数据库？看日志里是 Access denied（口令错）还是 Communications link failure（MySQL 没启动）。',
        '端口起不来？先看校验表说的是「被某进程占用」还是「系统不允许绑定」—— 后者说明该端口落在 Windows 保留范围内（netsh int ipv4 show excludedportrange protocol=tcp 可查），换个端口即可。'
    )
    'frontend' = @(
        'Nuxt 首次启动要预构建依赖，通常 20～60 秒；超过 4 分钟仍未就绪，看日志里的 ERROR。',
        '报 Cannot find module / ERR_MODULE_NOT_FOUND？在部署仓库目录手动跑一次 npm install。',
        '端口 4321 起不来？Nuxt 默认用 3000，本脚本显式指定了 4321；若被占会直接失败。',
        '改了 content/posts/ 下的 .md 但没有刷新出新文章？Nuxt Content 会监听文件变化，稍等一两秒再看。'
    )
    'admin'   = @(
        'npm install 失败？先在 admin 目录手动执行 npm install 看完整报错。',
        '端口 5174 被占？vite.config.ts 里设了 strictPort，会直接失败而不是换端口。'
    )
}

# ── 输出helper ──────────────────────────────────────────────────────────
function Write-Title {
    param([string]$Text)
    Write-Host ''
    Write-Host "── $Text " -ForegroundColor Cyan -NoNewline
    Write-Host ('─' * [Math]::Max(0, 62 - $Text.Length)) -ForegroundColor DarkCyan
}

function Write-Ok    { param([string]$Text) Write-Host "  [ OK ] $Text"   -ForegroundColor Green }
function Write-Info  { param([string]$Text) Write-Host "  [ .. ] $Text"   -ForegroundColor Gray }
function Write-Warn  { param([string]$Text) Write-Host "  [WARN] $Text"   -ForegroundColor Yellow }
function Write-Fail  { param([string]$Text) Write-Host "  [FAIL] $Text"   -ForegroundColor Red }
function Write-Note  { param([string]$Text) Write-Host "         $Text"   -ForegroundColor DarkGray }

# ── 调用外部命令 ────────────────────────────────────────────────────────
# PowerShell 5.1 的一个陷阱：当 $ErrorActionPreference = 'Stop' 时，
# 对原生命令（docker / java / pip ...）用 2>&1 重定向 stderr，会把普通错误输出
# 升级成 NativeCommandError 终止异常，脚本当场崩掉。
# 而这些命令把信息写进 stderr 本来就是正常行为 —— java -version 就是典型例子。
# 所以所有外部命令统一走这个包装器：内部临时把 EAP 降回 Continue。
function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Arguments.Count -gt 0) {
            $out = & $FilePath @Arguments 2>&1
        } else {
            $out = & $FilePath 2>&1
        }
        $code = $LASTEXITCODE
    } catch {
        $out = $_.Exception.Message
        $code = -1
    } finally {
        $ErrorActionPreference = $prev
    }
    return [pscustomobject]@{
        Output   = (($out | ForEach-Object { "$_" }) -join "`n")
        ExitCode = $code
    }
}

# 按"显示宽度"右侧补空格。中文字符占两列，直接用 PadRight 会让表格歪掉。
function Format-PadRight {
    param([string]$Text, [int]$Width)
    $w = 0
    foreach ($ch in $Text.ToCharArray()) {
        $c = [int]$ch
        $isWide = (($c -ge 0x1100 -and $c -le 0x115F) -or
                   ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
                   ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
                   ($c -ge 0xF900 -and $c -le 0xFAFF) -or
                   ($c -ge 0xFE30 -and $c -le 0xFE6F) -or
                   ($c -ge 0xFF00 -and $c -le 0xFF60) -or
                   ($c -ge 0xFFE0 -and $c -le 0xFFE6))
        if ($isWide) { $w += 2 } else { $w += 1 }
    }
    if ($w -ge $Width) { return $Text }
    return $Text + (' ' * ($Width - $w))
}

# ── 日志 ────────────────────────────────────────────────────────────────
$Script:LogFile = $null
$Script:TranscriptOn = $false

function Start-Logging {
    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $Script:LogFile = Join-Path $LogDir "start-$stamp.log"
    try {
        Start-Transcript -Path $Script:LogFile -Append | Out-Null
        $Script:TranscriptOn = $true
    } catch {
        # 已经在记录（比如嵌套调用）时忽略即可
        Write-Warn "无法开启转录日志：$($_.Exception.Message)"
    }
}

function Stop-Logging {
    if ($Script:TranscriptOn) {
        try { Stop-Transcript | Out-Null } catch { }
    }
}

function Show-LogTail {
    param([string]$Path, [int]$Lines = 15)
    if ([string]::IsNullOrEmpty($Path)) { return }
    if (-not (Test-Path $Path)) {
        Write-Note "（日志文件不存在：$Path）"
        return
    }
    Write-Host ''
    Write-Host "  日志末尾 $Lines 行 — $Path" -ForegroundColor DarkGray
    $content = Get-Content -Path $Path -Tail $Lines -ErrorAction SilentlyContinue
    if ($null -eq $content) {
        Write-Host '    （空）' -ForegroundColor DarkGray
        return
    }
    foreach ($line in $content) {
        Write-Host "    $line" -ForegroundColor DarkGray
    }
}

# ── .env ────────────────────────────────────────────────────────────────
function Read-DotEnv {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }

    foreach ($raw in (Get-Content -Path $Path -Encoding UTF8)) {
        $line = $raw.Trim()
        if ($line.Length -eq 0) { continue }
        if ($line.StartsWith('#')) { continue }

        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }

        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim()

        # 去掉包裹的引号
        if ($val.Length -ge 2) {
            $first = $val.Substring(0, 1)
            $last = $val.Substring($val.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $val = $val.Substring(1, $val.Length - 2)
            }
        }
        $map[$key] = $val
    }
    return $map
}

function Test-Placeholder {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $true }
    $lower = $Value.ToLowerInvariant()
    if ($lower -eq 'dev-internal-token') { return $false }   # 开发期允许
    foreach ($hint in $PlaceholderHints) {
        if ($lower.Contains($hint)) { return $true }
    }
    return $false
}

# ── 环境探测 ────────────────────────────────────────────────────────────
function Resolve-PythonExe {
    $candidates = New-Object System.Collections.ArrayList
    if ($env:PYTHON_EXE) { [void]$candidates.Add($env:PYTHON_EXE) }
    [void]$candidates.Add('C:\Python314\python.exe')

    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) {
        # 排除 Microsoft Store 的占位程序，它会把 python 变成"打开应用商店"
        if ($cmd.Source -notmatch 'WindowsApps') {
            [void]$candidates.Add($cmd.Source)
        }
    }

    foreach ($c in $candidates) {
        if (-not $c) { continue }
        if (-not (Test-Path $c)) { continue }
        $out = (Invoke-Native -FilePath $c -Arguments @('--version')).Output
        if ($out -match 'Python (\d+)\.(\d+)') {
            $major = [int]$Matches[1]
            $minor = [int]$Matches[2]
            if ($major -gt 3 -or ($major -eq 3 -and $minor -ge 11)) {
                return [pscustomobject]@{
                    Exe     = $c
                    Version = "$major.$minor"
                    Raw     = $out.Trim()
                }
            }
        }
    }
    return $null
}

function Resolve-Java {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $cmd) { return $null }
    # java -version 的输出本来就走 stderr，正是不能用 2>&1 直接接的原因
    $out = (Invoke-Native -FilePath 'java' -Arguments @('-version')).Output
    if ($out -match 'version "(\d+)') {
        return [pscustomobject]@{ Exe = $cmd.Source; Major = [int]$Matches[1]; Raw = $out.Trim() }
    }
    return [pscustomobject]@{ Exe = $cmd.Source; Major = 0; Raw = $out.Trim() }
}

function Resolve-Node {
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if (-not $cmd) { return $null }
    $out = (Invoke-Native -FilePath 'node' -Arguments @('--version')).Output
    if ($out -match 'v(\d+)') {
        return [pscustomobject]@{ Exe = $cmd.Source; Major = [int]$Matches[1]; Raw = $out.Trim() }
    }
    return $null
}

# 前台（公开站点）的源码目录。
# 按架构约定，公开站点的源码放在独立的部署仓库 Fa11Leaf.github.io 里，
# 不在本项目内部，所以这里要跨目录定位。默认取本项目的同级目录，
# 可用环境变量 FRONTEND_DIR 覆盖（写在 .env 里即可）。
function Resolve-FrontendDir {
    $v = [System.Environment]::GetEnvironmentVariable('FRONTEND_DIR', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
    return (Join-Path (Split-Path -Parent $Root) 'fa11leaf.github.io')
}

function Test-PortInUse {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($conn) { return $true }
        return $false
    } catch {
        # 老系统没有 Get-NetTCPConnection，退回 netstat
        $res = Invoke-Native -FilePath 'netstat' -Arguments @('-ano')
        return [bool]($res.Output -match ":$Port\s")
    }
}

function Get-ListeningProcessName {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
                Select-Object -First 1
        if (-not $conn) { return $null }
        $p = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($p) { return "$($p.ProcessName) (PID $($p.Id))" }
    } catch { }
    return $null
}

# 判断端口能否真的被绑定。
#
# 只看"有没有进程在监听"是不够的：Windows 会保留一批端口范围（Hyper-V / WSL /
# Docker Desktop 留下的"排除端口范围"）。这些端口上没有任何监听者，
# netstat 也查不到，但任何程序都绑不上，而报错信息是"端口已被占用" ——
# 极易被误判成"有别的程序在抢"。实际遇到过：8090 落在保留区间 8081-8180 里。
function Test-PortBindable {
    param([int]$Port)
    $listener = $null
    try {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener) {
            try { $listener.Stop() } catch { }
        }
    }
}

# ── 等待就绪 ────────────────────────────────────────────────────────────
function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Url,
        [hashtable]$Headers = @{},
        [int]$TimeoutSeconds = 60,
        [string]$Name = 'HTTP 服务',
        # 传了 PID 就能识别"进程已经死了"——比单纯等超时有用得多：
        # 进程没了说明它启动就失败了，再等下去只是让人以为脚本卡住。
        [int]$ProcessId = 0,
        [string]$LogHint = ''
    )
    $start = Get-Date
    $deadline = $start.AddSeconds($TimeoutSeconds)
    $lastError = '尚未发出请求'

    Write-Note "等待就绪，最多 ${TimeoutSeconds}s（每 2s 探测一次）"

    while ((Get-Date) -lt $deadline) {
        if ($ProcessId -gt 0) {
            if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
                $extra = ''
                if ($LogHint) { $extra = " 详见 $LogHint" }
                throw "$Name 的进程已经退出（PID $ProcessId），根本没在监听 $Url。$extra"
            }
        }

        try {
            # -UseBasicParsing 是必需的：PowerShell 5.1 默认用 IE 引擎解析，
            # 在没有完成 IE 首次配置的机器上会直接失败。
            return Invoke-RestMethod -Uri $Url -Headers $Headers -Method Get `
                -TimeoutSec 8 -UseBasicParsing -ErrorAction Stop
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Seconds 2
        }
    }
    throw "等待 $Name 就绪超时（${TimeoutSeconds}s）。最后错误：$lastError"
}

function Wait-DbHealthy {
    param([int]$TimeoutSeconds = 180)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $res = Invoke-Native -FilePath 'docker' `
            -Arguments @('inspect', '--format', '{{.State.Health.Status}}', $DbContainer)
        $status = $res.Output
        if ($res.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($status)) {
            $status = $status.Trim()
            if ($status -eq 'healthy') { return $true }
            if ($status -eq 'unhealthy') {
                throw 'MySQL 容器健康检查连续失败（状态 unhealthy）'
            }
        }
        Start-Sleep -Seconds 3
    }
    throw "等待数据库就绪超时（${TimeoutSeconds}s）。容器日志：docker logs $DbContainer"
}

# ── 启动各服务 ──────────────────────────────────────────────────────────
$Script:Pids = @{}

function Save-Pids {
    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    # 合并已有记录，避免只启一部分时覆盖掉之前启动的进程信息
    $existing = @{}
    if (Test-Path $PidFile) {
        try {
            $old = Get-Content -Raw $PidFile | ConvertFrom-Json
            foreach ($p in $old.PSObject.Properties) { $existing[$p.Name] = $p.Value }
        } catch { }
    }
    foreach ($k in $Script:Pids.Keys) { $existing[$k] = $Script:Pids[$k] }
    ($existing | ConvertTo-Json) | Set-Content -Path $PidFile -Encoding UTF8
}

function Start-DbService {
    Write-Title '准备 MySQL'

    # 先看 3306 上有没有现成的 MySQL。
    # 本机装了 MySQL 就直接用它 —— 比拉镜像快，也不会冒出第二个实例抢同一个端口。
    if (Test-PortInUse $PortDb) {
        $owner = Get-ListeningProcessName $PortDb
        if ($owner -match 'mysql') {
            Write-Ok "检测到 3306 上已有 MySQL（$owner），直接使用它，不启动 Docker 容器"
            return
        }
        throw "端口 $PortDb 被 $owner 占用，但它看起来不是 MySQL。请腾出该端口，或改 application-dev.yml 的连接地址。"
    }

    Write-Info '本机 3306 上没有 MySQL，改用 Docker 容器'

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw '未找到 docker 命令。请安装 Docker Desktop，或在本机安装 MySQL 后重试。'
    }

    Push-Location $Root
    try {
        # 首次运行要联网拉镜像（数百 MB）。先看本地有没有，好给出可预期的提示，
        # 否则用户会以为卡死了。
        $img = Invoke-Native -FilePath 'docker' -Arguments @('image', 'inspect', $DbImage)
        if ($img.ExitCode -ne 0) {
            Write-Warn "本地没有 $DbImage 镜像，需要联网拉取（数百 MB，视网络情况可能要几分钟）"
        }

        Write-Info "docker compose up -d $DbService"
        $res = Invoke-Native -FilePath 'docker' -Arguments @('compose', 'up', '-d', $DbService)
        if ($res.ExitCode -ne 0) {
            throw "docker compose up 失败（退出码 $($res.ExitCode)）：`n$($res.Output)"
        }
    } finally {
        Pop-Location
    }

    Write-Info '等待容器健康检查通过…'
    [void](Wait-DbHealthy -TimeoutSeconds $TimeoutDb)
    Write-Ok "MySQL 已就绪（$DbContainer，127.0.0.1:$PortDb）"
}

function Start-PythonService {
    Write-Title '启动 Python 能力服务'

    $venvDir = Join-Path $PythonDir '.venv'
    $venvPy = Join-Path $venvDir 'Scripts\python.exe'
    $reqFile = Join-Path $PythonDir 'requirements.txt'
    $stampFile = Join-Path $venvDir '.requirements.sha256'

    if (-not (Test-Path $venvPy)) {
        Write-Info "未找到虚拟环境，正在创建：$venvDir"
        $venv = Invoke-Native -FilePath $Script:PythonExe -Arguments @('-m', 'venv', $venvDir)
        if ($venv.ExitCode -ne 0) {
            throw "创建虚拟环境失败（退出码 $($venv.ExitCode)）：$($venv.Output)"
        }
        Write-Ok '虚拟环境已创建'
    }

    if (-not $SkipInstall) {
        $needInstall = $true
        $hash = (Get-FileHash -Path $reqFile -Algorithm SHA256).Hash
        if (Test-Path $stampFile) {
            $prev = (Get-Content -Raw $stampFile).Trim()
            if ($prev -eq $hash) { $needInstall = $false }
        }

        if ($needInstall) {
            Write-Info '安装 / 更新 Python 依赖（pip install -r requirements.txt）'
            [void](Invoke-Native -FilePath $venvPy `
                -Arguments @('-m', 'pip', 'install', '--upgrade', 'pip', '--quiet'))
            $pip = Invoke-Native -FilePath $venvPy `
                -Arguments @('-m', 'pip', 'install', '-r', $reqFile, '--quiet')
            if ($pip.ExitCode -ne 0) {
                throw "pip 安装依赖失败（退出码 $($pip.ExitCode)）。请在 $PythonDir 手动执行：.venv\Scripts\python.exe -m pip install -r requirements.txt`n$($pip.Output)"
            }
            Set-Content -Path $stampFile -Value $hash -Encoding UTF8
            Write-Ok 'Python 依赖已就绪'
        } else {
            Write-Ok 'Python 依赖无变化，跳过安装'
        }
    } else {
        Write-Warn '-SkipInstall：跳过 Python 依赖检查'
    }

    $outLog = Join-Path $LogDir 'python-service.out.log'
    $errLog = Join-Path $LogDir 'python-service.err.log'

    Write-Info "uvicorn 监听 127.0.0.1:$PortPython"
    $proc = Start-Process -FilePath $venvPy `
        -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$PortPython") `
        -WorkingDirectory $PythonDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru

    $Script:Pids['python'] = $proc.Id
    Write-Note "PID $($proc.Id)，日志：$outLog"

    $headers = @{ 'X-Internal-Token' = $env:PY_INTERNAL_TOKEN }
    $pyHealth = Wait-HttpOk -Url "http://127.0.0.1:$PortPython/health" -Headers $headers `
        -TimeoutSeconds $TimeoutPython -Name 'Python 能力服务' `
        -ProcessId $proc.Id -LogHint $errLog

    Write-Ok "Python 能力服务已就绪（v$($pyHealth.version)）"
}

function Start-BackendService {
    Write-Title '启动 Spring Boot 后端'

    $mvnw = Join-Path $BackendDir 'mvnw.cmd'
    if (-not (Test-Path $mvnw)) {
        throw "未找到 Maven Wrapper：$mvnw。该文件应随工程一起提交，缺失说明工程不完整。"
    }

    $outLog = Join-Path $LogDir 'backend.out.log'
    $errLog = Join-Path $LogDir 'backend.err.log'

    Write-Info 'mvnw spring-boot:run（首次运行要下载 Maven 与依赖，可能较慢）'
    # 用 cmd.exe 显式执行 .cmd，避免不同 PowerShell 版本对 .cmd 处理的差异
    $proc = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/c', 'mvnw.cmd spring-boot:run') `
        -WorkingDirectory $BackendDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru

    $Script:Pids['backend'] = $proc.Id
    Write-Note "PID $($proc.Id)，日志：$outLog"

    $health = Wait-HttpOk -Url "http://127.0.0.1:$PortBackend/api/health" `
        -TimeoutSeconds $TimeoutBackend -Name 'Spring Boot 后端' `
        -ProcessId $proc.Id -LogHint $errLog

    Write-Ok "后端已就绪（v$($health.data.version)）"

    # 后端自己会汇报数据库与 Python 的状态，顺手把结论打出来
    $dbOk = $health.data.database.reachable
    $pyOk = $health.data.python.reachable
    if ($dbOk) {
        Write-Ok "  数据库：已连接，已执行 $($health.data.database.migrations) 个迁移"
    } else {
        Write-Warn "  数据库：不可达 — $($health.data.database.error)"
    }
    if ($pyOk) {
        Write-Ok "  Python：已连接 v$($health.data.python.version)"
    } else {
        Write-Warn "  Python：不可达 — $($health.data.python.error)"
    }
}

function Start-FrontendService {
    Write-Title '启动公开站点（Nuxt 博客前台）'

    $dir = Resolve-FrontendDir
    $pkg = Join-Path $dir 'package.json'
    if (-not (Test-Path $pkg)) {
        throw "找不到前台工程：$pkg。前台源码按约定放在部署仓库里；路径不同时请在 .env 中设置 FRONTEND_DIR。"
    }
    Write-Note "源码目录：$dir"

    $nodeModules = Join-Path $dir 'node_modules'
    if (-not (Test-Path $nodeModules)) {
        if ($SkipInstall) {
            Write-Warn '-SkipInstall：node_modules 不存在，跳过安装。前台很可能起不来。'
        } else {
            Write-Info '安装前台依赖（npm install），首次约 2～5 分钟'
            $installOut = Join-Path $LogDir 'frontend-npm-install.log'
            $installProc = Start-Process -FilePath 'cmd.exe' `
                -ArgumentList @('/c', 'npm install') `
                -WorkingDirectory $dir `
                -RedirectStandardOutput $installOut `
                -RedirectStandardError (Join-Path $LogDir 'frontend-npm-install.err.log') `
                -WindowStyle Hidden -PassThru -Wait

            if ($installProc.ExitCode -ne 0) {
                throw "npm install 失败（退出码 $($installProc.ExitCode)）。日志：$installOut"
            }
            Write-Ok '前台依赖已就绪'
        }
    } else {
        Write-Ok 'node_modules 已存在，跳过安装'
    }

    $outLog = Join-Path $LogDir 'frontend.out.log'
    $errLog = Join-Path $LogDir 'frontend.err.log'

    Write-Info "Nuxt 开发服务器监听 127.0.0.1:$PortFrontend"
    $proc = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/c', "npm run dev -- --host 127.0.0.1 --port $PortFrontend") `
        -WorkingDirectory $dir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru

    $Script:Pids['frontend'] = $proc.Id
    Write-Note "PID $($proc.Id)，日志：$outLog"

    [void](Wait-HttpOk -Url "http://127.0.0.1:$PortFrontend/" -TimeoutSeconds $TimeoutFrontend `
        -Name 'Nuxt 博客前台' -ProcessId $proc.Id -LogHint $errLog)
    Write-Ok "公开站点已就绪：http://localhost:$PortFrontend"
}

function Start-AdminService {
    Write-Title '启动写作后台（Vite）'

    $nodeModules = Join-Path $AdminDir 'node_modules'
    if (-not (Test-Path $nodeModules)) {
        if ($SkipInstall) {
            Write-Warn "-SkipInstall：node_modules 不存在，跳过安装。前台很可能起不来。"
        } else {
            Write-Info '安装前端依赖（npm install），首次约 1～3 分钟'
            $installOut = Join-Path $LogDir 'admin-npm-install.log'
            $installProc = Start-Process -FilePath 'cmd.exe' `
                -ArgumentList @('/c', 'npm install') `
                -WorkingDirectory $AdminDir `
                -RedirectStandardOutput $installOut `
                -RedirectStandardError (Join-Path $LogDir 'admin-npm-install.err.log') `
                -WindowStyle Hidden -PassThru -Wait

            if ($installProc.ExitCode -ne 0) {
                throw "npm install 失败（退出码 $($installProc.ExitCode)）。日志：$installOut"
            }
            Write-Ok '前端依赖已就绪'
        }
    } else {
        Write-Ok 'node_modules 已存在，跳过安装'
    }

    $outLog = Join-Path $LogDir 'admin.out.log'
    $errLog = Join-Path $LogDir 'admin.err.log'

    Write-Info "vite 监听 127.0.0.1:$PortAdmin"
    $proc = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/c', 'npm run dev') `
        -WorkingDirectory $AdminDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru

    $Script:Pids['admin'] = $proc.Id
    Write-Note "PID $($proc.Id)，日志：$outLog"

    [void](Wait-HttpOk -Url "http://127.0.0.1:$PortAdmin/" -TimeoutSeconds $TimeoutAdmin `
        -Name 'Vite 开发服务器' -ProcessId $proc.Id -LogHint $errLog)
    Write-Ok "写作后台已就绪：http://localhost:$PortAdmin"
}

# ── 启动前校验 ──────────────────────────────────────────────────────────
function Invoke-Preflight {
    param([string[]]$Targets)

    Write-Title '启动前校验'
    $script:PreflightFailed = $false
    $results = New-Object System.Collections.ArrayList

    function Add-Check {
        param([string]$Name, [string]$Status, [string]$Detail)
        [void]$results.Add([pscustomobject]@{ Name = $Name; Status = $Status; Detail = $Detail })
        if ($Status -eq 'FAIL') { $script:PreflightFailed = $true }
    }

    # 1. 平台
    if ($env:OS -ne 'Windows_NT') {
        Add-Check '平台' 'FAIL' "本脚本只支持 Windows，当前为 $($env:OS)"
    } else {
        Add-Check '平台' 'OK' "Windows，PowerShell $($PSVersionTable.PSVersion)"
    }

    # 2. JDK
    $needJava = ($Targets -contains 'backend')
    $java = Resolve-Java
    if (-not $java) {
        if ($needJava) { Add-Check 'JDK' 'FAIL' '未找到 java 命令。安装 JDK 21 并加入 PATH。' }
        else { Add-Check 'JDK' 'WARN' '未找到（本次不启动后端，可忽略）' }
    } elseif ($java.Major -lt 17) {
        Add-Check 'JDK' 'FAIL' "需要 17 及以上，当前为 $($java.Raw)"
    } else {
        # java -version 会输出三行，只取含版本号的第一行
        Add-Check 'JDK' 'OK' (($java.Raw -split "`n")[0].Trim())
    }

    # 3. Node
    $needNode = ($Targets -contains 'admin')
    $node = Resolve-Node
    if (-not $node) {
        if ($needNode) { Add-Check 'Node.js' 'FAIL' '未找到 node 命令。安装 Node.js 22 或更高。' }
        else { Add-Check 'Node.js' 'WARN' '未找到（本次不启动前台，可忽略）' }
    } elseif ($node.Major -lt 22) {
        Add-Check 'Node.js' 'FAIL' "需要 22 及以上，当前为 $($node.Raw)"
    } else {
        Add-Check 'Node.js' 'OK' $node.Raw
    }

    # 4. Python
    $needPy = ($Targets -contains 'python') -or ($Targets -contains 'backend')
    $py = Resolve-PythonExe
    if (-not $py) {
        if ($needPy) { Add-Check 'Python' 'FAIL' '未找到 Python 3.11+。可设环境变量 PYTHON_EXE 指向解释器。' }
        else { Add-Check 'Python' 'WARN' '未找到（本次不需要，可忽略）' }
    } else {
        Add-Check 'Python' 'OK' "$($py.Raw)  ←  $($py.Exe)"
        $Script:PythonExe = $py.Exe
    }

    # 5. Docker 与守护进程
    #    注意：本机 3306 上已经有 MySQL 时，Docker 方案压根用不到，
    #    不能因为 Docker Desktop 没开就把启动拦下来 —— 那是假失败。
    $needDb = ($Targets -contains 'db')
    $localMysqlRunning = (Test-PortInUse $PortDb) -and ((Get-ListeningProcessName $PortDb) -match 'mysql')

    if ($localMysqlRunning) {
        Add-Check 'Docker' 'OK' '本机 MySQL 已在运行，用不到 Docker，跳过检查'
    } elseif ($needDb -and -not $SkipDocker) {
        $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
        if (-not $dockerCmd) {
            Add-Check 'Docker' 'FAIL' '3306 上没有 MySQL，且未找到 docker 命令。装 Docker Desktop，或在本机安装 MySQL。'
        } else {
            $info = Invoke-Native -FilePath 'docker' -Arguments @('info', '--format', '{{.ServerVersion}}')
            if ($info.ExitCode -ne 0) {
                Add-Check 'Docker' 'FAIL' '3306 上没有 MySQL，且 Docker 守护进程未运行。请启动 Docker Desktop，或启动本机 MySQL80 服务。'
            } else {
                $dv = (Invoke-Native -FilePath 'docker' -Arguments @('--version')).Output
                Add-Check 'Docker' 'OK' "3306 空闲，将用容器提供数据库（$($dv.Trim()) / Server $($info.Output.Trim())）"
            }
        }
    } elseif (-not $needDb) {
        Add-Check 'Docker' 'OK' '本次不启动数据库，跳过检查'
    } else {
        Add-Check 'Docker' 'WARN' '-SkipDocker：已跳过容器检查，请自行确保 MySQL 可用'
    }

    # 6. .env 完整性
    #    默认宽容：占位串自动换成内置开发默认值，只提示不阻断，这样刚 clone 下来也能一键跑通。
    #    加 -Strict 则视为错误，用于"准备部署到别处"的场景。
    $problems = @()   # 空值或明显的 change-me 占位串
    $devOnly = @()    # 非空，但等于本机开发默认值
    foreach ($k in $RequiredKeys) {
        $v = [System.Environment]::GetEnvironmentVariable($k, 'Process')
        if ([string]::IsNullOrWhiteSpace($v) -or (Test-Placeholder $v)) {
            $problems += $k
            continue
        }
        if ($DevDefaults -contains $v) { $devOnly += $k }
    }

    # 分两类：有默认值可回退的，和必须由用户提供的（数据库口令就属于后者）。
    $hard = @($problems | Where-Object { -not $Defaults.ContainsKey($_) })
    $soft = @($problems | Where-Object { $Defaults.ContainsKey($_) })

    if ($hard.Count -gt 0) {
        Add-Check '.env 必需项' 'FAIL' "缺少必需凭据：$($hard -join '、')。没有默认值可回退，请填入 $EnvFile"
    } elseif ($soft.Count -gt 0 -and $Strict) {
        Add-Check '.env 必需项' 'FAIL' "以下项为空或仍是占位串：$($soft -join '、')。请编辑 $EnvFile"
    } elseif ($soft.Count -gt 0) {
        foreach ($k in $soft) { Set-Item -Path "Env:$k" -Value $Defaults[$k] }
        Add-Check '.env 必需项' 'WARN' "$($soft -join '、') 为空或是占位串，已改用开发默认值"
    } elseif ($devOnly.Count -gt 0 -and $Strict) {
        Add-Check '.env 必需项' 'FAIL' "以下项仍是开发默认值，不能用于部署：$($devOnly -join '、')"
    } elseif ($devOnly.Count -gt 0) {
        Add-Check '.env 必需项' 'OK' "$($devOnly -join '、') 仍是开发默认值（-Strict 会禁止，部署前请改）"
    } else {
        Add-Check '.env 必需项' 'OK' "$($RequiredKeys.Count) 项均已配置"
    }

    # 7. 必要文件
    $filesOk = $true
    $fileDetail = @()
    foreach ($f in @(
        @{ P = (Join-Path $BackendDir 'pom.xml'); N = 'backend/pom.xml' },
        @{ P = (Join-Path $BackendDir 'mvnw.cmd'); N = 'backend/mvnw.cmd' },
        @{ P = (Join-Path $PythonDir 'requirements.txt'); N = 'python-service/requirements.txt' },
        @{ P = (Join-Path $PythonDir 'app\main.py'); N = 'python-service/app/main.py' },
        @{ P = (Join-Path $AdminDir 'package.json'); N = 'admin/package.json' },
        @{ P = (Join-Path $Root 'docker-compose.yml'); N = 'docker-compose.yml' }
    )) {
        if (-not (Test-Path $f.P)) { $filesOk = $false; $fileDetail += $f.N }
    }
    if ($filesOk) {
        Add-Check '工程文件' 'OK' '关键文件齐全'
    } else {
        Add-Check '工程文件' 'FAIL' "缺失：$($fileDetail -join '、')"
    }

    # 7b. 前台工程（跨仓库，路径单独判断）
    if ($Targets -contains 'frontend') {
        $fdir = Resolve-FrontendDir
        if (Test-Path (Join-Path $fdir 'package.json')) {
            Add-Check '前台工程' 'OK' $fdir
        } else {
            Add-Check '前台工程' 'FAIL' "找不到 $fdir\package.json —— 前台源码在部署仓库里，路径不同请在 .env 设置 FRONTEND_DIR"
        }
    }

    # 8. 端口占用（只对本次要启动的服务检查）
    $portMap = New-Object System.Collections.ArrayList
    if ($Targets -contains 'db' -and -not $SkipDocker)      { [void]$portMap.Add(@{ P = $PortDb;      N = 'MySQL' }) }
    if ($Targets -contains 'python')                          { [void]$portMap.Add(@{ P = $PortPython;  N = 'Python' }) }
    if ($Targets -contains 'backend')                         { [void]$portMap.Add(@{ P = $PortBackend; N = '后端' }) }
    if ($Targets -contains 'frontend')                        { [void]$portMap.Add(@{ P = $PortFrontend; N = '公开站点' }) }
    if ($Targets -contains 'admin')                           { [void]$portMap.Add(@{ P = $PortAdmin;   N = '写作后台' }) }

    foreach ($item in $portMap) {
        $inUse = Test-PortInUse $item.P
        $owner = ''
        if ($inUse) { $owner = Get-ListeningProcessName $item.P }

        if ($item.N -eq 'MySQL') {
            # 3306 上已有 MySQL 是【期望状态】：本机装了 MySQL 就直接用它。
            if (-not $inUse) {
                Add-Check "端口 $($item.P)" 'OK' '空闲，将启动 Docker 容器（见 README §9.1）'
            } elseif ($owner -match 'mysql') {
                Add-Check "端口 $($item.P)" 'OK' "本机 MySQL 已在运行（$owner），将直接使用"
            } else {
                Add-Check "端口 $($item.P)" 'FAIL' "被 $owner 占用，但它看起来不是 MySQL"
            }
        } elseif ($inUse) {
            $extra = ''
            if ($owner -match 'steam') { $extra = '（Steam 客户端会常驻占用 8080）' }
            Add-Check "端口 $($item.P)" 'FAIL' "被 $owner 占用，$($item.N) 无法绑定$extra"
        } elseif (-not (Test-PortBindable $item.P)) {
            # 没有监听者却绑不上 —— 几乎都是落在 Windows 保留端口范围里。
            Add-Check "端口 $($item.P)" 'FAIL' "$($item.N) 这个端口没有进程占用，但系统不允许绑定：多半落在 Windows 保留端口范围内（Hyper-V / WSL / Docker 的排除范围）。换一个端口，用 netsh int ipv4 show excludedportrange protocol=tcp 可查保留范围。"
        } else {
            Add-Check "端口 $($item.P)" 'OK' '空闲且可绑定'
        }
    }

    # 输出结果表
    Write-Host ''
    foreach ($r in $results) {
        $pad = Format-PadRight -Text $r.Name -Width 18
        # 详情里若混入换行（java -version 就是多行输出），会把整张表撑坏，统一压成一行
        $detail = ($r.Detail -replace '\s*[\r\n]+\s*', ' ').Trim()
        switch ($r.Status) {
            'OK'   { Write-Host "  $pad" -NoNewline; Write-Host 'OK   ' -ForegroundColor Green -NoNewline;  Write-Host $detail }
            'WARN' { Write-Host "  $pad" -NoNewline; Write-Host 'WARN ' -ForegroundColor Yellow -NoNewline; Write-Host $detail }
            'FAIL' { Write-Host "  $pad" -NoNewline; Write-Host 'FAIL ' -ForegroundColor Red -NoNewline;    Write-Host $detail }
        }
    }

    Write-Host ''
    if ($script:PreflightFailed) {
        throw '启动前校验未通过。请按上面的 FAIL 项逐条修复后重试。'
    }
    Write-Ok '校验通过，未发现问题'
}

# ── 停止 ────────────────────────────────────────────────────────────────
function Invoke-StopAll {
    Write-Title '停止服务'

    if (Test-Path $PidFile) {
        $data = Get-Content -Raw $PidFile | ConvertFrom-Json
        foreach ($p in $data.PSObject.Properties) {
            $name = $p.Name
            $procId = [int]$p.Value
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) {
                # /T 连子进程一起结束：mvnw / npm 都会再派生 java / node
                [void](Invoke-Native -FilePath 'taskkill' -Arguments @('/PID', "$procId", '/T', '/F'))
                Write-Ok "已停止 $name（PID $procId）"
            } else {
                Write-Warn "$name 的进程已不在（PID $procId）"
            }
        }
        Remove-Item -Path $PidFile -Force -ErrorAction SilentlyContinue
    } else {
        Write-Warn "没有找到 $PidFile，说明没有由本脚本启动且未停止的进程。"
    }

    if (Get-Command docker -ErrorAction SilentlyContinue) {
        Push-Location $Root
        try {
            $stop = Invoke-Native -FilePath 'docker' -Arguments @('compose', 'stop', $DbService)
            if ($stop.ExitCode -eq 0) {
                Write-Ok "数据库容器已停止（数据保留在 docker volume 里）"
            } else {
                Write-Warn 'docker compose stop 未成功，可能容器本来就没在运行'
            }
        } finally {
            Pop-Location
        }
    }
}

# ══ 主流程 ══════════════════════════════════════════════════════════════
Start-Logging
$exitCode = 0
$Script:PythonExe = $null
$currentStep = '初始化'

try {
    Write-Host ''
    Write-Host '  weblog-fullstack 启动脚本' -ForegroundColor White
    Write-Host "  项目根目录：$Root" -ForegroundColor DarkGray
    if ($Script:LogFile) { Write-Host "  本次日志：$Script:LogFile" -ForegroundColor DarkGray }

    if ($Stop) {
        $currentStep = '停止服务'
        Invoke-StopAll
        Write-Host ''
        Write-Host '  已停止。' -ForegroundColor Green
        return
    }

    # 展开 -Service all
    if ($Service -contains 'all') {
        $Service = @('db', 'python', 'backend', 'frontend', 'admin')
    }

    # 依赖自动带上：要后端就必须先有库和 Python 服务
    if ($Service -contains 'backend') {
        if (-not ($Service -contains 'db'))     { $Service += 'db' }
        if (-not ($Service -contains 'python')) { $Service += 'python' }
    }
    $order = @('db', 'python', 'backend', 'frontend', 'admin')
    $Service = $order | Where-Object { $Service -contains $_ }

    # ── 环境变量 ──
    Write-Title '环境变量'
    if (-not (Test-Path $EnvFile)) {
        if (Test-Path $EnvExample) {
            Copy-Item -Path $EnvExample -Destination $EnvFile
            Write-Warn '.env 不存在，已从 .env.example 复制一份'
            Write-Note '其中是可直接用于本机开发的口令；部署到别处之前请务必改成真实值。'

        } else {
            throw "既没有 .env 也没有 .env.example，无法确定环境变量。"
        }
    }

    $envMap = Read-DotEnv -Path $EnvFile
    $fromFile = @()
    $fromShell = @()
    foreach ($k in $envMap.Keys) {
        $existing = [System.Environment]::GetEnvironmentVariable($k, 'Process')
        if ([string]::IsNullOrWhiteSpace($existing)) {
            Set-Item -Path "Env:$k" -Value $envMap[$k]
            $fromFile += $k
        } else {
            # shell 里已有的值优先，便于临时覆盖
            $fromShell += $k
        }
    }
    foreach ($k in $Defaults.Keys) {
        $existing = [System.Environment]::GetEnvironmentVariable($k, 'Process')
        if ([string]::IsNullOrWhiteSpace($existing)) {
            Set-Item -Path "Env:$k" -Value $Defaults[$k]
        }
    }
    Write-Ok "已从 .env 载入 $($fromFile.Count) 项"
    if ($fromShell.Count -gt 0) {
        Write-Warn "以下 $($fromShell.Count) 项已被当前 shell 的值覆盖（shell 优先）：$($fromShell -join '、')"
    }

    $currentStep = '启动前校验'
    Invoke-Preflight -Targets $Service

    if ($Check) {
        Write-Host ''
        Write-Host '  -Check 模式：校验通过，未启动任何服务。' -ForegroundColor Green
        return
    }

    # ── 按序启动 ──
    foreach ($svc in $Service) {
        switch ($svc) {
            'db'      { $currentStep = 'mysql';   Start-DbService }
            'python'  { $currentStep = 'python';  Start-PythonService }
            'backend' { $currentStep = 'backend'; Start-BackendService }
            'frontend' { $currentStep = 'frontend'; Start-FrontendService }
            'admin'   { $currentStep = 'admin';   Start-AdminService }
        }
        # 每起一个就落盘一次。原先只在全部成功后写一次，导致中途失败时
        # 已经跑起来的服务没有被记录，-Stop 也收不掉它们（只能手动去杀）。
        Save-Pids
    }

    # ── 收尾 ──
    Write-Title '启动完成'
    Write-Host ''
    if ($Service -contains 'db')      { Write-Host "  MySQL      127.0.0.1:$PortDb   （容器 $DbContainer）" -ForegroundColor Green }
    if ($Service -contains 'python')  { Write-Host "  Python     http://127.0.0.1:$PortPython/docs" -ForegroundColor Green }
    if ($Service -contains 'backend') { Write-Host "  API        http://127.0.0.1:$PortBackend/api/health" -ForegroundColor Green }
    if ($Service -contains 'frontend'){ Write-Host "  博客前台   http://localhost:$PortFrontend" -ForegroundColor Green }
    if ($Service -contains 'admin')   { Write-Host "  写作后台   http://localhost:$PortAdmin" -ForegroundColor Green }
    Write-Host ''
    Write-Host "  所有服务在后台运行，本窗口可以关闭。" -ForegroundColor DarkGray
    Write-Host "  停止它们：start.cmd -Stop" -ForegroundColor DarkGray
    Write-Host "  查看日志：$LogDir" -ForegroundColor DarkGray

    # 自动打开浏览器：优先展示博客前台（这才是有内容可看的页面），
    # 没起前台时退回写作后台。加 -NoBrowser 可跳过。
    if (-not $NoBrowser) {
        $openUrl = $null
        if ($Service -contains 'frontend') { $openUrl = "http://localhost:$PortFrontend" }
        elseif ($Service -contains 'admin') { $openUrl = "http://localhost:$PortAdmin" }
        if ($openUrl) {
            Write-Host "  正在打开浏览器：$openUrl" -ForegroundColor DarkGray
            try { Start-Process $openUrl | Out-Null } catch { Write-Warn "打开浏览器失败，请手动访问 $openUrl" }
        }
    }

} catch {
    $exitCode = 1
    Write-Host ''
    Write-Host '  ┌──────────────────────────────────────────────────────────┐' -ForegroundColor Red
    Write-Host '  │  启动失败                                                │' -ForegroundColor Red
    Write-Host '  └──────────────────────────────────────────────────────────┘' -ForegroundColor Red
    Write-Host ''
    Write-Host "  失败环节：$currentStep" -ForegroundColor Red
    Write-Host "  原因    ：$($_.Exception.Message)" -ForegroundColor Red

    # 按环节把对应服务的日志尾部打出来
    switch ($currentStep) {
        'python'  {
            Show-LogTail -Path (Join-Path $LogDir 'python-service.err.log')
            Show-LogTail -Path (Join-Path $LogDir 'python-service.out.log') -Lines 10
        }
        'backend' {
            Show-LogTail -Path (Join-Path $LogDir 'backend.err.log')
            Show-LogTail -Path (Join-Path $LogDir 'backend.out.log') -Lines 20
        }
        'frontend' {
            Show-LogTail -Path (Join-Path $LogDir 'frontend.err.log')
            Show-LogTail -Path (Join-Path $LogDir 'frontend.out.log') -Lines 15
        }
        'admin'   {
            Show-LogTail -Path (Join-Path $LogDir 'admin.err.log')
            Show-LogTail -Path (Join-Path $LogDir 'admin.out.log') -Lines 10
        }
        'mysql'   {
            Write-Host ''
            Write-Host "  容器状态：docker compose ps" -ForegroundColor DarkGray
            Write-Host "  容器日志：docker logs --tail 40 $DbContainer" -ForegroundColor DarkGray
        }
    }

    if ($Hints.ContainsKey($currentStep)) {
        Write-Host ''
        Write-Host '  可能的原因与处理：' -ForegroundColor Yellow
        foreach ($h in $Hints[$currentStep]) {
            Write-Host "    · $h" -ForegroundColor Yellow
        }
    }

    Write-Host ''
    if ($Script:LogFile) {
        Write-Host "  完整日志：$Script:LogFile" -ForegroundColor DarkGray
    }
    Write-Host '  单独复现某一步：start.cmd -Service <db|python|backend|frontend|admin>' -ForegroundColor DarkGray

} finally {
    if ($exitCode -eq 0) {
        # 校验模式或停止模式没有启动进程，不需要保存 PID
    }
    Stop-Logging
}

exit $exitCode
