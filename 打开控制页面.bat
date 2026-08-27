@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "SERVER_IP="
for /f "tokens=1,2 delims==" %%a in (config.txt) do (
    if "%%a"=="SERVER_IP" set "SERVER_IP=%%b"
)

if "%SERVER_IP%"=="" (
    echo [Error] SERVER_IP not found in config.txt
    echo Please add: SERVER_IP=your_public_ip
    pause
    exit /b 1
)

set "URL=http://%SERVER_IP%:3000"
echo.
echo Opening control page...
echo URL: %URL%
echo.

start "" "%URL%"

timeout /t 2 /nobreak >nul
pause
