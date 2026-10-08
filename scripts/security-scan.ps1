<#
.SYNOPSIS
  安全扫描与权限越权测试入口。
.DESCRIPTION
  1. 后端依赖漏洞扫描（OWASP dependency-check）
  2. 前端依赖漏洞扫描（npm audit）
  3. 后端权限越权测试（scripts/test-perm.ps1）
  4. 前端权限状态测试（scripts/test-frontend-perm.ps1）

  说明：dependency-check 首次运行会下载 NVD 数据库，耗时可长达数分钟；
  权限测试需要后端服务已启动。可通过参数跳过对应步骤。
#>
param(
  [switch]$SkipMaven,
  [switch]$SkipNpm,
  [switch]$SkipPerm,
  [double]$FailCvss = 7.0
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$failed = @()

function Run-Step([string]$name, [scriptblock]$action) {
  Write-Host "`n========== $name ==========" -ForegroundColor Cyan
  & $action
  if ($LASTEXITCODE -ne 0) {
    $script:failed += $name
    Write-Host "[FAIL] $name (exit=$LASTEXITCODE)" -ForegroundColor Red
  } else {
    Write-Host "[PASS] $name" -ForegroundColor Green
  }
}

if (-not $SkipMaven) {
  Run-Step '后端依赖漏洞扫描 dependency-check' {
    Push-Location (Join-Path $root 'backend')
    try {
      mvn -q org.owasp:dependency-check-maven:check `
        "-DskipTests" `
        "-DfailBuildOnCVSS=$FailCvss"
    } finally {
      Pop-Location
    }
  }
}

if (-not $SkipNpm) {
  Run-Step '前端依赖漏洞扫描 npm audit' {
    Push-Location (Join-Path $root 'web-admin-react')
    try {
      npm audit --audit-level=high
    } finally {
      Pop-Location
    }
  }
}

if (-not $SkipPerm) {
  Run-Step '后端接口权限越权测试' {
    & (Join-Path $root 'scripts\test-perm.ps1')
  }
  Run-Step '前端权限状态与页面访问测试' {
    & (Join-Path $root 'scripts\test-frontend-perm.ps1')
  }
}

Write-Host "`n========== 安全扫描汇总 ==========" -ForegroundColor Cyan
if ($failed.Count -eq 0) {
  Write-Host '全部通过' -ForegroundColor Green
  exit 0
}
Write-Host ("未通过: " + ($failed -join ', ')) -ForegroundColor Red
exit 1