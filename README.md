# 远程控制手机

通过互联网远程控制 Android 手机/平板的完整系统，专为帮助家中长辈远程操作手机而设计。控制端和部署端合并为一个网页管理面板，打开即用。

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│               中继服务器 (relay-server)                       │
│           Node.js + WebSocket + Express (端口 3000)           │
│                                                              │
│    ┌──────────┐            ┌──────────────┐                  │
│    │ 被控设备  │ ←──WSS──→  │  管理面板     │                  │
│    │ Android  │  二进制帧/  │  (控制+部署)  │                  │
│    │          │  JSON 指令  │              │                  │
│    └──────────┘            └──────────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

- **被控端（Android App）**：捕获屏幕画面（JPEG）发送到服务器，接收触摸/按键指令。
- **控制端（管理面板）**：浏览器显示远程画面，鼠标/触摸操作；同时内置部署功能。
- **中继服务器**：负责配对（6 位配对码）和画面/指令转发，支持排队。

## 目录结构

```
├── admin/               管理面板（控制 + 部署一个页面）
│   ├── admin-server.js  本地服务（端口 8899），含 WebSocket 代理和 SSH 部署接口
│   └── admin.html       管理面板页面
├── relay-server/        中继服务器（部署到云服务器，端口 3000）
│   ├── server.js        配对与转发逻辑
│   └── certs/           证书目录（仓库内仅有 server.crt.example 模板，真实证书需自行生成）
├── android-app/         Android 被控端 App
├── 启动管理面板.bat    一键启动管理面板（推荐，双击即用，自动装依赖并打开浏览器）
├── open_controller.ps1  一键启动管理面板（PowerShell 版，等效）
├── config.txt           服务器公网 IP 配置（已 git 忽略，模板见 config.txt.example）
└── config.txt.example   配置模板
```

## 快速开始

### 环境要求

- 一台有公网 IP 的云服务器（阿里云/腾讯云均可），开放 **3000** 端口
- 云服务器已安装 Node.js 和 pm2
- 电脑已安装 [Node.js](https://nodejs.org)（运行管理面板）
- [Android Studio](https://developer.android.com/studio)（构建 App，可选）

### 第一步：配置服务器地址

复制 `config.txt.example` 为 `config.txt`，把里面的 IP 改成你的云服务器公网 IP：

```
SERVER_IP=你的公网IP
```

### 第二步：生成 SSL 证书

中继服务器使用自签名证书实现 WSS 加密。首次部署前，在**本地**生成一对证书（`server.crt` / `server.key`），放到 `relay-server/certs/` 目录下——部署时管理面板会自动上传到服务器，无需手动登录服务器。

在 Git Bash（或 Linux/Mac 终端）里执行：

```bash
mkdir -p relay-server/certs
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout relay-server/certs/server.key \
  -out relay-server/certs/server.crt \
  -days 3650 \
  -subj "/CN=remote-control" \
  -addext "subjectAltName=IP:你的公网IP,DNS:localhost"
```

生成后，把 `server.crt` 的内容同步到安卓端：复制 `android-app/app/src/main/res/raw/server_cert.crt.example` 为 `server_cert.crt`，并用你生成的证书内容替换。

> 说明：仓库内只提供不含 IP 的 `server.crt.example` / `server_cert.crt.example` 占位模板，仅作格式参考。若证书缺失，服务器会退化为明文 HTTP，此时需把 App 和配置里的 `wss://` 改为 `ws://`。

### 第三步：部署中继服务器

**方式 A：用管理面板部署（推荐）**

1. 双击 `启动管理面板.bat`（或运行 `open_controller.ps1`），自动打开 `http://localhost:8899`
2. 页面下方「服务器配置（部署）」填写：IP、端口（22）、用户名（root）、root 密码、排队上限
3. 点击「部署到服务器」，等待 1-3 分钟

**方式 B：手动部署**

```bash
cd relay-server
npm install
pm2 start server.js --name relay
pm2 save
```

### 第四步：构建并安装 Android App

1. 用 Android Studio 打开 `android-app/` 文件夹，等待 Gradle 同步
2. 把默认服务器地址改成你的服务器：可直接在 App 首页的「服务器地址」输入框里填写，或修改 `app/src/main/java/com/remotecontrol/MainActivity.kt` 里的 `DEFAULT_SERVER_URL`（默认值 `wss://你的服务器IP:3000`）
3. Build → Build APK(s)
4. 生成的 APK（`app/build/outputs/apk/debug/app-debug.apk`）安装到被控手机/平板

## 使用说明

### 被控端（长辈的手机上）

1. 打开「远程控制」App，点「开启无障碍服务」，在系统设置中打开「远程控制」
2. 确认服务器地址正确（默认已填，一般无需修改）
3. 点「开始远程控制」，授予屏幕录制权限
4. 屏幕会显示一个 **6 位配对码**（例如 393574），把码告诉控制端

### 控制端（你的电脑上）

1. 双击 `启动管理面板.bat` 打开管理面板
2. 页面顶部「远程控制」：填服务器地址（自动读取 `config.txt`）和 6 位配对码
3. 点「连接设备」
4. 对方手机弹出「允许」对话框，点「允许」
5. 即可看到远程画面，鼠标点击/拖拽操作；底栏有「返回 / 主页 / 任务 / 断开」按钮

### 页面结构

| 区块 | 用途 |
|------|------|
| 顶部 · 远程控制 | 填服务器地址 + 配对码，连接并操作手机 |
| 中部 · 服务器配置 | SSH 部署服务器、修改排队上限、查询日志 |
| 底部 · 输出 | 显示部署/日志结果 |

## 技术原理

### 屏幕捕获
`MediaProjection` API 捕获屏幕，`ImageReader` + `VirtualDisplay` 逐帧取图，压缩为 JPEG 后经 WebSocket 发送。

### 触摸注入
`AccessibilityService.dispatchGesture()` 模拟点击/滑动，`performGlobalAction()` 模拟系统按键，无需 Root。

### 通信与证书
- 被控端/控制端 → 服务器：WebSocket（WSS 加密）
- 中继服务器使用**自签名证书**，浏览器无法直接信任，因此管理面板内置了本地 WebSocket 代理：浏览器连 `ws://localhost:8899/ws`，由本机 Node 信任自签名证书后转发到 `wss://服务器IP:3000`，从而规避浏览器证书报错。

### SSL 证书

为避免把真实服务器 IP 和证书提交到公开仓库，仓库内**不包含真实证书**，仅提供不含 IP 的占位模板（`relay-server/certs/server.crt.example`、`android-app/app/src/main/res/raw/server_cert.crt.example`）。证书生成步骤见上文「快速开始 → 第二步：生成 SSL 证书」。

## 性能参数

| 参数 | 默认值 | 位置 |
|------|--------|------|
| 帧率 | 10 FPS | `ScreenCaptureService.kt` 的 `TARGET_FPS` |
| JPEG 质量 | 50 | `ScreenCaptureService.kt` 的 `JPEG_QUALITY` |
| 最大分辨率 | 1280px | `ScreenCaptureService.kt` 的 `MAX_DIMENSION` |
| 并发上限 | 100 路 | 管理面板部署时设置，或 `server-config.json` |

## 安全说明

- `server.key`（私钥）、`config.txt`（含真实 IP）以及含真实 IP 的 `server.crt`/`server_cert.crt` 均已加入 `.gitignore`，仓库内只保留不含真实 IP 的 `.example` 模板，可安全公开
- 配对码一次性生成，断开后需重新连接；被控端每次连接需手动确认
- 一机一控：同一设备同时只允许一个控制端接入

## 限制说明

- 仅支持 Android 8.0+（`dispatchGesture` 需 API 24+，`minSdk` 26）
- 不支持 iOS
- 部分系统级界面（锁屏、权限弹窗）可能无法注入触摸
- Android 10+ 每次开始屏幕捕获需用户确认
