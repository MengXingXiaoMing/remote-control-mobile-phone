const http = require('http');
const fs = require('fs');
const path = require('path');
const { Client } = require('ssh2');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = process.env.PORT || 8899;
const ROOT = path.join(__dirname, '..');

function readConfigTxt() {
    const p = path.join(ROOT, 'config.txt');
    try {
        const txt = fs.readFileSync(p, 'utf8');
        for (const line of txt.split(/\r?\n/)) {
            const t = line.trim();
            if (t.startsWith('SERVER_IP=')) {
                return t.substring('SERVER_IP='.length).trim();
            }
        }
    } catch (e) { /* 忽略 */ }
    return '';
}

function sendJson(res, code, obj) {
    const body = JSON.stringify(obj);
    res.writeHead(code, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body)
    });
    res.end(body);
}

function connectSsh(params) {
    return new Promise((resolve, reject) => {
        const conn = new Client();
        conn.on('ready', () => resolve(conn));
        conn.on('error', (e) => reject(new Error(e.message)));
        conn.connect({
            host: params.host,
            port: parseInt(params.port || 22, 10),
            username: params.username || 'root',
            password: params.password || '',
            readyTimeout: 20000
        });
    });
}

function execCmd(conn, cmd) {
    return new Promise((resolve, reject) => {
        conn.exec(cmd, (err, stream) => {
            if (err) return reject(err);
            let out = '', errOut = '';
            stream.on('data', d => { out += d.toString(); });
            stream.stderr.on('data', d => { errOut += d.toString(); });
            stream.on('close', () => resolve({ out, errOut }));
            stream.on('error', (e) => reject(e));
        });
    });
}

function uploadFiles(conn, files) {
    return new Promise((resolve, reject) => {
        conn.sftp((err, sftp) => {
            if (err) return reject(err);
            let remaining = files.length;
            let firstErr = null;
            if (remaining === 0) { sftp.end(); return resolve(); }
            files.forEach(f => {
                const done = (e) => {
                    if (e && !firstErr) firstErr = e;
                    remaining--;
                    if (remaining === 0) {
                        sftp.end();
                        if (firstErr) reject(firstErr); else resolve();
                    }
                };
                if (f.data !== undefined) {
                    sftp.writeFile(f.remote, f.data, done);
                } else {
                    sftp.fastPut(f.local, f.remote, done);
                }
            });
        });
    });
}

function buildFileList(maxConcurrent) {
    const relay = path.join(ROOT, 'relay-server');
    const web = path.join(ROOT, 'web-controller');
    const files = [
        { local: path.join(relay, 'package.json'), remote: '/opt/remote-control/package.json' },
        { local: path.join(relay, 'server.js'), remote: '/opt/remote-control/server.js' },
        { data: JSON.stringify({ maxConcurrent: parseInt(maxConcurrent || 100, 10) }), remote: '/opt/remote-control/server-config.json' }
    ];
    const certDir = path.join(relay, 'certs');
    if (fs.existsSync(certDir)) {
        for (const f of fs.readdirSync(certDir)) {
            files.push({ local: path.join(certDir, f), remote: '/opt/remote-control/certs/' + f });
        }
    }
    if (fs.existsSync(web)) {
        for (const f of fs.readdirSync(web)) {
            if (/\.(html|js|css)$/.test(f)) {
                files.push({ local: path.join(web, f), remote: '/opt/remote-control/web-controller/' + f });
            }
        }
    }
    return files;
}

async function handleDeploy(body) {
    const host = (body.host || '').trim();
    const password = body.password || '';
    if (!host) return { ok: false, error: '缺少服务器地址' };
    if (!password) return { ok: false, error: '缺少 root 密码' };

    const params = { host, port: body.port, username: body.username, password };
    const conn = await connectSsh(params);
    try {
        await execCmd(conn, 'mkdir -p /opt/remote-control/certs /opt/remote-control/web-controller');
        const files = buildFileList(body.maxConcurrent);
        await uploadFiles(conn, files);
        const r = await execCmd(conn,
            'cd /opt/remote-control && npm install && pm2 delete relay 2>/dev/null; pm2 start server.js --name relay && pm2 save');
        return { ok: true, out: (r.out + r.errOut).trim() };
    } finally {
        conn.end();
    }
}

async function handleLogs(body) {
    const host = (body.host || '').trim();
    const password = body.password || '';
    if (!host) return { ok: false, error: '缺少服务器地址' };
    if (!password) return { ok: false, error: '缺少 root 密码' };
    const lines = parseInt(body.lines || 100, 10);

    const params = { host, port: body.port, username: body.username, password };
    const conn = await connectSsh(params);
    try {
        const cmd = 'echo "===== 服务状态 ====="; pm2 status; echo ""; echo "===== 健康状态 ====="; curl -sk https://localhost:3000/health 2>/dev/null || curl -s http://localhost:3000/health; echo ""; echo ""; echo "===== 最近日志(' + lines + ' 行) ====="; pm2 logs relay --lines ' + lines + ' --nostream 2>&1';
        const r = await execCmd(conn, cmd);
        return { ok: true, out: (r.out + r.errOut).trim() };
    } finally {
        conn.end();
    }
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        let data = '';
        req.on('data', c => {
            data += c;
            if (data.length > 1e6) { reject(new Error('请求过大')); req.destroy(); }
        });
        req.on('end', () => {
            try { resolve(data ? JSON.parse(data) : {}); }
            catch (e) { reject(new Error('JSON 解析失败')); }
        });
        req.on('error', reject);
    });
}

const server = http.createServer(async (req, res) => {
    const url = (req.url || '').split('?')[0];

    if (req.method === 'GET' && (url === '/' || url === '/admin.html')) {
        const html = fs.readFileSync(path.join(__dirname, 'admin.html'));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(html);
        return;
    }

    if (req.method === 'GET' && url === '/api/config') {
        sendJson(res, 200, { serverIp: readConfigTxt() });
        return;
    }

    if (req.method === 'POST' && url === '/api/deploy') {
        try {
            const body = await readBody(req);
            const result = await handleDeploy(body);
            sendJson(res, result.ok ? 200 : 400, result);
        } catch (e) {
            sendJson(res, 500, { ok: false, error: e.message });
        }
        return;
    }

    if (req.method === 'POST' && url === '/api/logs') {
        try {
            const body = await readBody(req);
            const result = await handleLogs(body);
            sendJson(res, result.ok ? 200 : 400, result);
        } catch (e) {
            sendJson(res, 500, { ok: false, error: e.message });
        }
        return;
    }

    sendJson(res, 404, { ok: false, error: '未找到接口' });
});

// ============================================================
// WebSocket 代理：浏览器 -> 本机(ws) -> 中继服务器(wss)
// 目的：中继服务器使用自签名证书，浏览器直接连 wss 会被拒绝；
// 由本机 Node 信任自签名证书并转发，浏览器只需连本地 ws，无证书报错。
// ============================================================
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
    const pathname = (req.url || '').split('?')[0];
    if (pathname !== '/ws') {
        socket.destroy();
        return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
        wss.emit('connection', ws, req);
    });
});

wss.on('connection', (browserWs, req) => {
    const url = new URL(req.url, 'http://localhost');
    const target = url.searchParams.get('target') || '';
    if (!target || (!target.startsWith('ws://') && !target.startsWith('wss://'))) {
        try { browserWs.send(JSON.stringify({ type: 'rejected', reason: '无效的服务器地址' })); } catch (e) {}
        try { browserWs.close(4000, 'invalid target'); } catch (e) {}
        return;
    }

    // 连接中继服务器，信任自签名证书
    const upstream = new WebSocket(target, { rejectUnauthorized: false });
    let upstreamReady = false;
    const pending = [];

    browserWs.on('message', (data, isBinary) => {
        if (upstreamReady && upstream.readyState === WebSocket.OPEN) {
            upstream.send(data, { binary: isBinary });
        } else {
            pending.push({ data, isBinary });
        }
    });

    upstream.on('open', () => {
        upstreamReady = true;
        for (const p of pending) upstream.send(p.data, { binary: p.isBinary });
        pending.length = 0;
    });

    upstream.on('message', (data, isBinary) => {
        if (browserWs.readyState === WebSocket.OPEN) {
            browserWs.send(data, { binary: isBinary });
        }
    });

    upstream.on('error', (e) => {
        if (browserWs.readyState === WebSocket.OPEN) {
            try { browserWs.send(JSON.stringify({ type: 'connect_error', message: e.message || '' })); } catch (err) {}
        }
        try { browserWs.close(); } catch (err) {}
    });

    browserWs.on('close', () => { try { upstream.close(); } catch (e) {} });
    browserWs.on('error', () => { try { upstream.close(); } catch (e) {} });
    upstream.on('close', () => { try { browserWs.close(); } catch (e) {} });
});

server.listen(PORT, () => {
    console.log('');
    console.log('========================================');
    console.log('  远程控制 - 管理页面已启动');
    console.log(`  请打开浏览器访问: http://localhost:${PORT}`);
    console.log('========================================');
    console.log('');
});
