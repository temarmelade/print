@echo off
rem PrintKiosk: kiosk mode remove. Double-click - Windows will ask for admin rights.
net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kiosk-mode-remove.ps1"
echo.
pause
