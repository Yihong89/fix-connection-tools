const http = require('http');
const net = require('net');
const { WebSocketServer } = require('ws');
const path = require('path');
const fs = require('fs');

const PORT = process.env.PORT || 3000;
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const server = http.createServer((req, res) => {
  const file = req.url === '/' ? '/index.html' : req.url;
  const filepath = path.join(__dirname, 'public', file);
  const ext = path.extname(filepath);
  if (!fs.existsSync(filepath)) { res.writeHead(404); res.end('Not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
  fs.createReadStream(filepath).pipe(res);
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  console.log(`[relay] WebSocket connected: ${req.socket.remoteAddress}`);
  let tcpSocket = null;
  let connecting = false;
  let destroyed = false;

  const send = (type, data) => {
    if (ws.readyState === 1) ws.send(JSON.stringify({ type, ...data }));
  };

  const cleanup = () => {
    destroyed = true;
    if (tcpSocket) { tcpSocket.destroy(); tcpSocket = null; }
  };

  ws.on('message', (raw) => {
    if (destroyed) return;
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    if (msg.type === 'connect') {
      if (tcpSocket || connecting) return;
      connecting = true;
      const { host, port, senderCompID, targetCompID } = msg;
      if (!host || !port) {
        send('error', { message: 'Host and port are required' });
        connecting = false;
        return;
      }

      tcpSocket = new net.Socket();
      tcpSocket.setNoDelay(true);

      tcpSocket.connect(port, host, () => {
        connecting = false;
        console.log(`[relay] TCP connected to ${host}:${port}`);
        send('connected', { host, port, senderCompID, targetCompID });
      });

      tcpSocket.on('data', (data) => {
        const text = data.toString('utf-8');
        send('message', { direction: 'inbound', content: text, raw: text });
      });

      tcpSocket.on('close', (hadError) => {
        console.log(`[relay] TCP closed (error=${hadError})`);
        if (!destroyed) send('disconnected', { reason: hadError ? 'connection error' : 'closed' });
        tcpSocket = null;
        connecting = false;
      });

      tcpSocket.on('error', (err) => {
        console.log(`[relay] TCP error: ${err.message}`);
        if (!destroyed) send('error', { message: `TCP error: ${err.message}` });
        connecting = false;
        tcpSocket = null;
      });

      // connect timeout
      tcpSocket.setTimeout(10000, () => {
        tcpSocket.destroy();
        connecting = false;
        send('error', { message: 'Connection timed out' });
      });

    } else if (msg.type === 'send') {
      if (!tcpSocket || tcpSocket.destroyed) {
        send('error', { message: 'Not connected to FIX server' });
        return;
      }
      tcpSocket.write(msg.content + '\n', (err) => {
        if (err) send('error', { message: `Send failed: ${err.message}` });
        else send('message', { direction: 'outbound', content: msg.content });
      });

    } else if (msg.type === 'disconnect') {
      cleanup();
      send('disconnected', { reason: 'user requested' });
    }
  });

  ws.on('close', cleanup);
  ws.on('error', cleanup);
});

server.listen(PORT, () => {
  console.log(`[relay] Server running at http://localhost:${PORT}`);
});
