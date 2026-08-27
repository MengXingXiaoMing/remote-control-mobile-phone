// ===== 全局状态 =====
let ws = null;
let wsWasRejected = false;

const el = (id) => document.getElementById(id);

const canvas = el('screen-canvas');
const ctx = canvas.getContext('2d');
let isPointerDown = false;
let pointerStart = { x: 0, y: 0, time: 0 };
let lastMoveTime = 0;
let frameCount = 0;
let fpsTimer = null;
let currentBlobUrl = null;

// ===== 屏幕切换 =====
function showScreen(id) {
    ['connect-screen', 'control-screen'].forEach(s => {
        el(s).classList.toggle('active', s === id);
    });
}

// ===== 自动填充服务器地址 =====
function autoFillServerUrl() {
    const host = window.location.host;
    if (host) {
        const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
        el('server-url').value = protocol + host;
    }
}

// ===== 连接 =====
el('connect-btn').addEventListener('click', () => {
    const serverUrl = el('server-url').value.trim();
    const code = el('pairing-code').value.trim();

    if (!serverUrl) {
        showStatus('请输入服务器地址', 'error');
        return;
    }
    if (!code || code.length < 4) {
        showStatus('请输入配对码', 'error');
        return;
    }

    el('connect-btn').disabled = true;
    showStatus('正在连接服务器...', '');

    try {
        ws = new WebSocket(serverUrl);
    } catch (e) {
        showStatus('地址格式错误', 'error');
        el('connect-btn').disabled = false;
        return;
    }

    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
        showStatus('正在配对...', '');
        ws.send(JSON.stringify({ type: 'register', role: 'controller', code }));
    };

    ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
            drawFrame(event.data);
        } else {
            handleMessage(JSON.parse(event.data));
        }
    };

    ws.onerror = () => {
        showStatus('连接失败，请检查地址', 'error');
        el('connect-btn').disabled = false;
    };

    ws.onclose = () => {
        if (controlScreenActive()) {
            backToConnect('连接已断开');
        } else {
            if (!wsWasRejected) {
                showStatus('连接已关闭', 'error');
            }
            wsWasRejected = false;
            el('connect-btn').disabled = false;
        }
    };
});

function controlScreenActive() {
    return el('control-screen').classList.contains('active');
}

function showStatus(msg, type) {
    const s = el('connect-status');
    s.textContent = msg;
    s.className = 'status-text ' + (type || '');
}

function handleMessage(msg) {
    switch (msg.type) {
        case 'paired':
            el('device-info').textContent = msg.deviceInfo
                ? `${msg.deviceInfo.model || 'Android设备'}`
                : '已连接';
            showScreen('control-screen');
            startFpsCounter();
            break;
        case 'waiting_for_device':
            showStatus('等待设备上线...\n请在手机上打开被控端App', '');
            break;
        case 'waiting_confirmation':
            showStatus('正在等待对方确认...\n请让对方在手机上点击"允许"', '');
            break;
        case 'rejected':
            wsWasRejected = true;
            showStatus('对方拒绝了远程控制请求', 'error');
            break;
        case 'busy':
            wsWasRejected = true;
            showStatus('该设备已被其他控制端占用，请稍后再试', 'error');
            break;
        case 'queued':
            showStatus(`当前服务器繁忙，您排在第 ${msg.position} 位，请稍候...`, '');
            break;
        case 'disconnected':
            backToConnect('设备已断开连接');
            break;
        default:
            break;
    }
}

function backToConnect(reason) {
    if (fpsTimer) clearInterval(fpsTimer);
    if (ws) { ws.close(); ws = null; }
    showScreen('connect-screen');
    showStatus(reason, 'error');
    el('connect-btn').disabled = false;
    el('loading-overlay').classList.remove('hidden');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
}

// ===== 画面渲染 =====
function drawFrame(data) {
    el('loading-overlay').classList.add('hidden');

    if (currentBlobUrl) {
        URL.revokeObjectURL(currentBlobUrl);
    }

    const blob = new Blob([data], { type: 'image/jpeg' });
    currentBlobUrl = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
        if (canvas.width !== img.width || canvas.height !== img.height) {
            canvas.width = img.width;
            canvas.height = img.height;
        }
        ctx.drawImage(img, 0, 0);
        URL.revokeObjectURL(currentBlobUrl);
        currentBlobUrl = null;
    };
    img.src = currentBlobUrl;

    frameCount++;
}

function startFpsCounter() {
    if (fpsTimer) clearInterval(fpsTimer);
    frameCount = 0;
    fpsTimer = setInterval(() => {
        el('fps-counter').textContent = `FPS: ${frameCount}`;
        frameCount = 0;
    }, 1000);
}

// ===== 触摸/鼠标事件 =====
function getRelativeCoords(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    return {
        x: (clientX - rect.left) * scaleX,
        y: (clientY - rect.top) * scaleY
    };
}

function sendCommand(cmd) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(cmd));
    }
}

canvas.addEventListener('mousedown', (e) => {
    isPointerDown = true;
    const p = getRelativeCoords(e.clientX, e.clientY);
    pointerStart = { x: p.x, y: p.y, time: Date.now() };
    lastMoveTime = Date.now();
});

canvas.addEventListener('mousemove', (e) => {
    if (!isPointerDown) return;
    const now = Date.now();
    if (now - lastMoveTime < 50) return;
    lastMoveTime = now;

    const p = getRelativeCoords(e.clientX, e.clientY);
    sendCommand({ type: 'swipe_move', x: p.x, y: p.y });
});

canvas.addEventListener('mouseup', (e) => {
    if (!isPointerDown) return;
    isPointerDown = false;
    const p = getRelativeCoords(e.clientX, e.clientY);
    const dx = p.x - pointerStart.x;
    const dy = p.y - pointerStart.y;
    const dist = Math.sqrt(dx * dx + dy * dy);
    const duration = Date.now() - pointerStart.time;

    if (dist < 20) {
        sendCommand({ type: 'tap', x: p.x, y: p.y });
    } else {
        sendCommand({
            type: 'swipe',
            x1: pointerStart.x, y1: pointerStart.y,
            x2: p.x, y2: p.y, duration: duration
        });
    }
});

canvas.addEventListener('mouseleave', (e) => {
    if (!isPointerDown) return;
    isPointerDown = false;
    const p = getRelativeCoords(e.clientX, e.clientY);
    const dx = p.x - pointerStart.x;
    const dy = p.y - pointerStart.y;
    const dist = Math.sqrt(dx * dx + dy * dy);
    if (dist >= 20) {
        sendCommand({
            type: 'swipe',
            x1: pointerStart.x, y1: pointerStart.y,
            x2: p.x, y2: p.y, duration: Date.now() - pointerStart.time
        });
    }
});

canvas.addEventListener('touchstart', (e) => {
    e.preventDefault();
    const touch = e.touches[0];
    isPointerDown = true;
    const p = getRelativeCoords(touch.clientX, touch.clientY);
    pointerStart = { x: p.x, y: p.y, time: Date.now() };
    lastMoveTime = Date.now();
}, { passive: false });

canvas.addEventListener('touchmove', (e) => {
    e.preventDefault();
    if (!isPointerDown) return;
    const now = Date.now();
    if (now - lastMoveTime < 50) return;
    lastMoveTime = now;

    const touch = e.touches[0];
    const p = getRelativeCoords(touch.clientX, touch.clientY);
    sendCommand({ type: 'swipe_move', x: p.x, y: p.y });
}, { passive: false });

canvas.addEventListener('touchend', (e) => {
    e.preventDefault();
    if (!isPointerDown) return;
    isPointerDown = false;

    const touch = e.changedTouches[0];
    const p = getRelativeCoords(touch.clientX, touch.clientY);
    const dx = p.x - pointerStart.x;
    const dy = p.y - pointerStart.y;
    const dist = Math.sqrt(dx * dx + dy * dy);

    if (dist < 20) {
        sendCommand({ type: 'tap', x: p.x, y: p.y });
    } else {
        sendCommand({
            type: 'swipe',
            x1: pointerStart.x, y1: pointerStart.y,
            x2: p.x, y2: p.y, duration: Date.now() - pointerStart.time
        });
    }
}, { passive: false });

// ===== 控制按钮 =====
el('btn-back').addEventListener('click', () => {
    sendCommand({ type: 'keyevent', action: 'back' });
});

el('btn-home').addEventListener('click', () => {
    sendCommand({ type: 'keyevent', action: 'home' });
});

el('btn-recents').addEventListener('click', () => {
    sendCommand({ type: 'keyevent', action: 'recents' });
});

el('btn-disconnect').addEventListener('click', () => {
    backToConnect('已手动断开');
});

// ===== 初始化 =====
autoFillServerUrl();
showScreen('connect-screen');
