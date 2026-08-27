@echo off
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Node.js not found. Please install Node.js first.
    pause
    exit /b 1
)

if not exist "admin\node_modules\ssh2" (
    echo First run: installing dependencies, please wait...
    pushd admin
    call npm install ssh2
    popd
)

echo Starting admin panel...
echo Browser will open at http://localhost:8899
start "" cmd /c "timeout /t 2 /nobreak >nul && start http://localhost:8899"
cd admin
node admin-server.js
pause
