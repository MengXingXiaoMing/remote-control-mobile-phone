const express = require('express');
const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');
const https = require('https');
const path = require('path');
const fs = require('fs');

const app = express();

const PORT = process.env.PORT || 3000;

// 读取服务器配置（部署页面可修改排队上限等）
const CONFIG_FILE = path.join(__dirname, 'server-config.json');
let serverConfig = {};
try {
    if (fs.existsSync(CONFIG_FILE)) {
        serverConfig = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    }
} catch (e) {
    console.error('读取 server-config.json 失败:', e.message);
}

// 同时活跃的屏幕传输会话上限，超出进入排队
const MAX_CONCURRENT = parseInt(serverConfig.maxConcurrent || process.env.MAX_CONCURRENT || '100', 10);

// 读取 TLS 证书（存在则启用 HTTPS/WSS 加密）
const certDir = path.join(__dirname, 'certs');
const certPath = path.join(certDir, 'server.crt');
const keyPath = path.join(certDir, 'server.key');
let server;
let protocolLabel = 'ws';
if (fs.existsSync(certPath) && fs.existsSync(keyPath)) {
    server = https.createServer({
        cert: fs.readFileSync(certPath),
        key: fs.readFileSync(keyPath)
    }, app);
    protocolLabel = 'wss';
} else {
    server = http.createServer(app);
}
const wss = new WebSocketServer({ server });

// 静态托管 Web 控制端
const webControllerDir = fs.existsSync(path.join(__dirname, 'web-controller'))
    ? path.join(__dirname, 'web-controller')
    : path.join(__dirname, '..', 'web-controller');
app.use(express.static(webControllerDir));
app.use(express.json());

app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        sessions: sessions.size,
        active: activeSessions,
        maxConcurrent: MAX_CONCURRENT,
        waiting: waitingQueue.length
    });
});

// ============================================================
// 会话管理 + 排队
// ============================================================
const sessions = new Map(); // code -> { device, controller, deviceInfo, confirmed, active, queued }
const wsToCode = new Map();  // ws -> code
let activeSessions = 0;
const waitingQueue = [];     // 排队中的 code 列表

function randomCode() {
    return String(Math.floor(100000 + Math.random() * 900000));
}

function generateUniqueCode() {
    let code;
    do {
        code = randomCode();
    } while (sessions.has(code));
    return code;
}

function updateQueuePositions() {
    waitingQueue.forEach((c, i) => {
        const s = sessions.get(c);
        if (!s) return;
        if (s.controller && s.controller.readyState === WebSocket.OPEN) {
            s.controller.send(JSON.stringify({ type: 'queued', position: i + 1 }));
        }
        if (s.device && s.device.readyState === WebSocket.OPEN) {
            s.device.send(JSON.stringify({ type: 'queued', position: i + 1 }));
        }
    });
}

function promoteNext() {
    while (waitingQueue.length > 0 && activeSessions < MAX_CONCURRENT) {
        const code = waitingQueue.shift();
        const s = sessions.get(code);
        if (!s) continue;
        if (!s.device || s.device.readyState !== WebSocket.OPEN
            || !s.controller || s.controller.readyState !== WebSocket.OPEN) {
            continue; // 会话已失效
        }
        s.queued = false;
        s.active = true;
        s.confirmed = true;
        activeSessions++;
        s.controller.send(JSON.stringify({ type: 'paired', deviceInfo: s.deviceInfo || {} }));
        s.device.send(JSON.stringify({ type: 'paired' }));
        console.log(`[${code}] 排队轮到，已连接（活跃 ${activeSessions}/${MAX_CONCURRENT}）`);
    }
    if (waitingQueue.length > 0) updateQueuePositions();
}

function releaseSessionSlot(session) {
    if (session.active) {
        session.active = false;
        session.confirmed = false;
        activeSessions = Math.max(0, activeSessions - 1);
        promoteNext();
    }
}

function removeWebSocket(ws) {
    const code = wsToCode.get(ws);
    if (code) {
        const session = sessions.get(code);
        if (session) {
            if (session.device === ws) {
                session.device = null;
                session.confirmed = false;
                releaseSessionSlot(session);
                if (session.controller && session.controller.readyState === WebSocket.OPEN) {
                    session.controller.send(JSON.stringify({ type: 'disconnected' }));
                }
            }
            if (session.controller === ws) {
                if (session.device && session.device.readyState === WebSocket.OPEN) {
                    session.device.send(JSON.stringify({ type: 'controller_disconnected' }));
                }
                session.controller = null;
                session.confirmed = false;
                releaseSessionSlot(session);
            }
            // 清理排队中已失效的会话
            const qIdx = waitingQueue.indexOf(code);
            if (qIdx >= 0 && (!session.device || !session.controller)) {
                waitingQueue.splice(qIdx, 1);
                session.queued = false;
            }
            if (!session.device && !session.controller) {
                sessions.delete(code);
            }
        }
        wsToCode.delete(ws);
    }
    if (waitingQueue.length > 0) updateQueuePositions();
}

wss.on('connection', (ws, req) => {
    ws.isAlive = true;

    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (data, isBinary) => {
        if (isBinary) {
            const code = wsToCode.get(ws);
            if (code) {
                const session = sessions.get(code);
                if (session && session.confirmed
                    && session.controller && session.controller.readyState === WebSocket.OPEN) {
                    session.controller.send(data, { binary: true });
                }
            }
            return;
        }

        let msg;
        try {
            msg = JSON.parse(data.toString());
        } catch (e) {
            console.error('JSON 解析错误:', e.message);
            return;
        }

        if (msg.type === 'register') {
            if (msg.role === 'device') {
                let code = String(msg.code || '');
                const existing = sessions.get(code);
                if (!code || (existing && existing.device
                    && existing.device !== ws
                    && existing.device.readyState === WebSocket.OPEN)) {
                    code = generateUniqueCode();
                }

                if (!sessions.has(code)) {
                    sessions.set(code, {
                        device: null, controller: null,
                        deviceInfo: null, confirmed: false, active: false, queued: false
                    });
                }
                const session = sessions.get(code);
                session.device = ws;
                session.deviceInfo = msg.deviceInfo || {};
                session.confirmed = false;
                wsToCode.set(ws, code);

                ws.send(JSON.stringify({ type: 'registered', role: 'device', code }));

                if (session.controller && session.controller.readyState === WebSocket.OPEN) {
                    session.controller.send(JSON.stringify({ type: 'waiting_confirmation' }));
                    ws.send(JSON.stringify({ type: 'controller_request' }));
                }

                console.log(`[设备] 配对码 ${code} 已注册: ${JSON.stringify(session.deviceInfo || {})}`);

            } else if (msg.role === 'controller') {
                const code = String(msg.code || '');
                if (!sessions.has(code)) {
                    sessions.set(code, {
                        device: null, controller: null,
                        deviceInfo: null, confirmed: false, active: false, queued: false
                    });
                }
                const session = sessions.get(code);

                // 一机一控：若已有人连接/控制，则阻拦后来者
                if (session.controller && session.controller !== ws
                    && session.controller.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({ type: 'busy' }));
                    try { ws.close(4002, 'busy'); } catch (e) { /* 忽略 */ }
                    console.log(`[控制器] 被阻拦（${code} 已被占用）`);
                    return;
                }
                session.controller = ws;
                wsToCode.set(ws, code);
                session.confirmed = false;

                if (session.device && session.device.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({ type: 'waiting_confirmation' }));
                    session.device.send(JSON.stringify({ type: 'controller_request' }));
                } else {
                    ws.send(JSON.stringify({ type: 'waiting_for_device' }));
                }

                console.log(`[控制器] 连接配对码 ${code}`);
            }
            return;
        }

        const code = wsToCode.get(ws);
        if (!code) return;
        const session = sessions.get(code);
        if (!session) return;

        if (ws === session.device) {
            if (msg.type === 'confirm_connection') {
                if (activeSessions < MAX_CONCURRENT) {
                    session.confirmed = true;
                    session.active = true;
                    activeSessions++;
                    if (session.controller && session.controller.readyState === WebSocket.OPEN) {
                        session.controller.send(JSON.stringify({
                            type: 'paired', deviceInfo: session.deviceInfo || {}
                        }));
                    }
                    ws.send(JSON.stringify({ type: 'paired' }));
                    console.log(`[${code}] 设备已确认连接（活跃 ${activeSessions}/${MAX_CONCURRENT}）`);
                } else {
                    session.confirmed = false;
                    session.queued = true;
                    waitingQueue.push(code);
                    const position = waitingQueue.length;
                    if (session.controller && session.controller.readyState === WebSocket.OPEN) {
                        session.controller.send(JSON.stringify({ type: 'queued', position }));
                    }
                    ws.send(JSON.stringify({ type: 'queued', position }));
                    console.log(`[${code}] 会话已排队（第 ${position} 位）`);
                }
                return;
            }
            if (msg.type === 'reject_connection') {
                session.confirmed = false;
                if (session.controller && session.controller.readyState === WebSocket.OPEN) {
                    session.controller.send(JSON.stringify({ type: 'rejected' }));
                    try { session.controller.close(); } catch (e) { /* 忽略 */ }
                }
                session.controller = null;
                console.log(`[${code}] 设备已拒绝连接`);
                return;
            }

            if (session.confirmed
                && session.controller && session.controller.readyState === WebSocket.OPEN) {
                session.controller.send(data.toString());
            }
            return;
        }

        if (ws === session.controller && session.confirmed
            && session.device && session.device.readyState === WebSocket.OPEN) {
            session.device.send(data.toString());
        }
    });

    ws.on('close', () => { removeWebSocket(ws); });
    ws.on('error', () => { removeWebSocket(ws); });
});

// 心跳检测
const heartbeat = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (!ws.isAlive) {
            ws.terminate();
            removeWebSocket(ws);
            return;
        }
        ws.isAlive = false;
        ws.ping();
    });
}, 30000);

wss.on('close', () => {
    clearInterval(heartbeat);
});

server.listen(PORT, '0.0.0.0', () => {
    console.log('');
    console.log('========================================');
    console.log('  远程控制中继服务器已启动');
    console.log('========================================');
    console.log(`  协议:        ${protocolLabel.toUpperCase()}（${protocolLabel === 'wss' ? '已加密' : '明文，未配置证书'}）`);
    console.log(`  Web 控制端:  http${protocolLabel === 'wss' ? 's' : ''}://localhost:${PORT}`);
    console.log(`  WebSocket:   ${protocolLabel}://localhost:${PORT}`);
    console.log(`  健康检查:    http${protocolLabel === 'wss' ? 's' : ''}://localhost:${PORT}/health`);
    console.log('----------------------------------------');
    console.log(`  并发上限:    ${MAX_CONCURRENT} 路（超出排队）`);
    console.log('  安全: 一机一控 + 被控端需确认');
    console.log('========================================');
    console.log('');
});
