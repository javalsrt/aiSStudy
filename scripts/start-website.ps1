# ========================================================
# 智学职达官网启动脚本 (Windows / PowerShell)
# ========================================================
# 功能：
#   1. 启动 website/ 静态站点（默认 http://127.0.0.1:5500/）
#   2. 自动选择可用端口（默认 5500，被占用则顺延）
#   3. 优先使用 Python http.server，未安装则降级为 npx serve
#   4. 自动打开默认浏览器
#
# 用法：
#   .\start-website.ps1                 # 启动（默认 5500）
#   .\start-website.ps1 -Port 8080      # 指定端口
#   .\start-website.ps1 -Stop          # 停止当前服务
#   .\start-website.ps1 -Restart        # 重启
#   .\start-website.ps1 -OpenBrowser   $false  # 不自动打开浏览器
# ========================================================

[CmdletBinding()]
param(
    [int]$Port = 5500,
    [switch]$Stop,
    [switch]$Restart,
    [bool]$OpenBrowser = $true
)

$ScriptRoot   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ScriptName   = Split-Path -Leaf $MyInvocation.MyCommand.Path
$WebsiteDir   = Join-Path $ScriptRoot "..\website"
$WebsiteDir   = (Resolve-Path $WebsiteDir).Path
$LogDir       = Join-Path $ScriptRoot "..\logs"
$LogFile      = Join-Path $LogDir "website.log"
$ErrLogFile   = Join-Path $LogDir "website.err.log"
$PidFile      = Join-Path $LogDir "website.pid"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

function Write-Step($msg)  { Write-Host "[*] $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "[+] $msg" -ForegroundColor Green }
function Write-Warn($msg)  { Write-Host "[!] $msg" -ForegroundColor Yellow }
function Write-Err($msg)   { Write-Host "[X] $msg" -ForegroundColor Red }

function Test-PortInUse([int]$p) {
    $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    return [bool]$conn
}

function Get-FreePort([int]$start) {
    $p = $start
    while ($p -lt 65535) {
        if (-not (Test-PortInUse $p)) { return $p }
        $p++
    }
    throw "未找到可用端口（从 $start 起）"
}

function Read-StoredPid {
    if (Test-Path $PidFile) {
        $raw = Get-Content $PidFile -ErrorAction SilentlyContinue
        if ($raw -and ($raw | Select-Object -First 1) -match '^\d+$') {
            return [int]$Matches[0]
        }
    }
    return $null
}

function Stop-Service {
    $svcPid = Read-StoredPid
    if ($svcPid) {
        $proc = Get-Process -Id $svcPid -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Step "停止官网服务 PID=$svcPid ..."
            Stop-Process -Id $svcPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 500
        }
        Remove-Item $PidFile -ErrorAction SilentlyContinue
    }
    # 兜底：根据端口杀进程
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        Write-Step "清理端口 $Port 上残留进程 PID=$($conn.OwningProcess)"
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Write-Ok "官网服务已停止"
}

function Start-Service {
    if (-not (Test-Path $WebsiteDir)) {
        Write-Err "找不到网站目录：$WebsiteDir"
        exit 1
    }

    # 若已运行则提示
    $oldPid = Read-StoredPid
    if ($oldPid) {
        $old = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
        if ($old) {
            Write-Warn "检测到已有进程 PID=$oldPid，请先执行 -Stop 或 -Restart"
            exit 1
        }
    }

    # 选择可用端口
    $usePort = Get-FreePort $Port
    if ($usePort -ne $Port) {
        Write-Warn "端口 $Port 被占用，改用 $usePort"
    }

    # 选运行时
    $python = (Get-Command python -ErrorAction SilentlyContinue)
    $pyExe  = if ($python) { $python.Source } else { $null }
    $npx    = Get-Command npx -ErrorAction SilentlyContinue

    if (-not $pyExe -and -not $npx) {
        Write-Err "未检测到 Python 或 Node.js/npx，无法启动静态服务器"
        exit 1
    }

    $args = @()
    $cmd  = $null
    if ($pyExe) {
        $cmd  = $pyExe
        $args = @("-m", "http.server", "$usePort", "--bind", "127.0.0.1")
        $engine = "python http.server"
    } else {
        $cmd  = $npx.Source
        $args = @("serve", "-l", "$usePort", "--no-clipboard")
        $engine = "npx serve"
    }

    Write-Step "启动官网：$engine  目录：$WebsiteDir  端口：$usePort"
    $proc = Start-Process -FilePath $cmd `
                         -ArgumentList $args `
                         -WorkingDirectory $WebsiteDir `
                         -RedirectStandardOutput $LogFile `
                         -RedirectStandardError  $ErrLogFile `
                         -PassThru `
                         -WindowStyle Hidden

    # 等待就绪
    $ready = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$usePort/" -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
    }
    if (-not $ready) {
        Write-Err "服务启动超时，请查看日志：$LogFile"
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        exit 1
    }

    # 写 PID
    Set-Content -Path $PidFile -Value $proc.Id -Encoding UTF8

    $url = "http://127.0.0.1:$usePort/"
    Write-Ok  "官网已启动  $url  (PID=$($proc.Id))"
    Write-Host "    日志：$LogFile"
    Write-Host "    停止：$ScriptName -Stop"

    if ($OpenBrowser) {
        try {
            Start-Process $url | Out-Null
        } catch {
            Write-Warn "自动打开浏览器失败：$($_.Exception.Message)"
        }
    }
}

# ============= 主流程 =============
if ($Stop)    { Stop-Service; exit 0 }
if ($Restart) { Stop-Service; Start-Sleep -Seconds 1 }
Start-Service
