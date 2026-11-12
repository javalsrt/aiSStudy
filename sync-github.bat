@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem 双击即可：自动导出数据库 + 提交 + 推送到 GitHub
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-github.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-github.ps1" %*
)

echo.
pause
