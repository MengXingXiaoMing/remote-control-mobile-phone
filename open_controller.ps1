# 远程控制 - 一键打开管理面板（控制 + 部署 一个页面搞定）
$ErrorActionPreference = "Continue"

$root = $PSScriptRoot
$adminDir = Join-Path $root "admin"
$serverJs = Join-Path $adminDir "admin-server.js"
$url = "http://localhost:8899"

if (-not (Test-Path $serverJs)) {
    Write-Host "[错误] 找不到 admin\admin-server.js" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}

$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    Write-Host "[错误] 未检测到 Node.js，请先安装 https://nodejs.org" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}

# 检查管理面板是否已经在运行
$alreadyRunning = $false
try {
    $conn = New-Object System.Net.Sockets.TcpClient
    $conn.Connect("127.0.0.1", 8899)
    $alreadyRunning = $true
    $conn.Close()
} catch {
    $alreadyRunning = $false
}

if (-not $alreadyRunning) {
    Write-Host "正在启动管理面板..." -ForegroundColor Green
    Start-Process -FilePath "node" -ArgumentList "`"$serverJs`"" -WorkingDirectory $adminDir -WindowStyle Minimized
    Start-Sleep -Seconds 2
} else {
    Write-Host "管理面板已在运行，直接打开..." -ForegroundColor Green
}

Write-Host ""
Write-Host "管理面板地址: $url" -ForegroundColor Cyan
Write-Host "页面顶部填「服务器地址 + 配对码」即可连接手机；" -ForegroundColor Yellow
Write-Host "下方可填写 SSH 信息部署服务器、修改排队上限、查询日志。" -ForegroundColor Yellow
Write-Host ""

try {
    Start-Process $url
    Write-Host "已打开浏览器，如果没有弹出请手动复制上面的地址到浏览器" -ForegroundColor Green
} catch {
    Write-Host "无法自动打开浏览器，请手动复制上面的地址到浏览器" -ForegroundColor Yellow
}

Start-Sleep -Seconds 2
