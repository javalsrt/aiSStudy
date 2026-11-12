<#
.SYNOPSIS
    一键同步代码 + 数据库到 GitHub。

.DESCRIPTION
    每次运行会自动完成：
      1) 用 mysqldump 导出当前数据库到 init-db/znxsgltest.sql
      2) git add -A
      3) git commit（自动生成提交信息，也可手动指定）
      4) git push origin 当前分支

.EXAMPLE
    ./sync-github.ps1
    ./sync-github.ps1 -Message "feat: 课程封面"

.NOTES
    数据库连接可用环境变量覆盖：DB_HOST / DB_USER / DB_PASSWORD / DB_NAME
    mysqldump 路径可用环境变量 MYSQLDUMP 指定。
#>
param(
    [string]$Message = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

# ---- 数据库配置（默认与本机开发环境一致，可用环境变量覆盖）----
$DbHost = if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }
$DbUser = if ($env:DB_USER) { $env:DB_USER } else { 'root' }
$DbPassword = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { '123456' }
$DbName = if ($env:DB_NAME) { $env:DB_NAME } else { 'znxsglTest' }

# ---- 定位 mysqldump ----
$dumpExe = $env:MYSQLDUMP
if (-not $dumpExe -or -not (Test-Path $dumpExe)) {
    $candidates = @(
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe',
        'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe',
        'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysqldump.exe'
    )
    $dumpExe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $dumpExe) {
    $cmd = Get-Command mysqldump -ErrorAction SilentlyContinue
    if ($cmd) { $dumpExe = $cmd.Source }
}
if (-not $dumpExe) {
    throw '未找到 mysqldump，请安装 MySQL 客户端，或设置 MYSQLDUMP 环境变量'
}

$dumpFile = Join-Path $repoRoot 'init-db\znxsgltest.sql'
$tempFile = Join-Path $repoRoot 'init-db\znxsgltest.sql.tmp'
$dumpDir = Split-Path -Parent $dumpFile
if (-not (Test-Path $dumpDir)) { New-Item -ItemType Directory -Path $dumpDir | Out-Null }

# ---- 导出数据库（先写临时文件，成功后再替换，避免导出失败破坏旧备份）----
Write-Host "正在导出数据库 $DbName ..." -ForegroundColor Cyan
if (Test-Path $tempFile) { Remove-Item $tempFile -Force }
& $dumpExe "--host=$DbHost" "--user=$DbUser" "--password=$DbPassword" `
    '--default-character-set=utf8mb4' '--single-transaction' '--no-tablespaces' `
    '--routines' '--events' '--triggers' '--set-gtid-purged=OFF' `
    "--result-file=$tempFile" $DbName

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tempFile)) {
    if (Test-Path $tempFile) { Remove-Item $tempFile -Force }
    throw "数据库导出失败（退出码 $LASTEXITCODE），已中止同步"
}
if ((Get-Item $tempFile).Length -lt 1KB) {
    Remove-Item $tempFile -Force
    throw '导出文件异常（小于 1KB），已中止同步'
}
Move-Item -Path $tempFile -Destination $dumpFile -Force
Write-Host ("数据库已导出：{0:N1} MB" -f ((Get-Item $dumpFile).Length / 1MB)) -ForegroundColor Green

# ---- Git 提交与推送 ----
git add -A
if (-not (git status --porcelain)) {
    Write-Host '没有需要同步的改动。' -ForegroundColor Yellow
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Message)) {
    $Message = "chore: 同步代码与数据库 $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
}
git commit -m $Message
if ($LASTEXITCODE -ne 0) { throw '提交失败，已中止同步' }

git push origin HEAD
if ($LASTEXITCODE -ne 0) {
    throw '推送失败：请检查网络，或先执行 git pull --rebase 后再运行本脚本'
}

Write-Host '同步完成：代码与数据库均已推送到 GitHub。' -ForegroundColor Green
