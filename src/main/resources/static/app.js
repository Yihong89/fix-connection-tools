const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
const relayUrl = `${wsProto}//${location.host}/relay`;

let ws = null;
let connected = false;
let msgCount = 0;
let seqNum = 1;

// DOM refs
const $ = (id) => document.getElementById(id);
const host = $('host'), port = $('port'), sender = $('sender'), target = $('target');
const connectBtn = $('connectBtn'), sendBtn = $('sendBtn'), clearBtn = $('clearBtn');
const msgInput = $('msgInput'), logEl = $('log'), statusEl = $('status'), statusDetail = $('statusDetail');
const msgCountEl = $('msgCount');

/* ---- WebSocket connection to relay ---- */
function connectRelay() {
  ws = new WebSocket(relayUrl);

  ws.onopen = () => {
    setStatus('connecting', 'Connecting to FIX server...');
    seqNum = 1;
    ws.send(JSON.stringify({
      type: 'connect',
      host: host.value.trim(),
      port: parseInt(port.value, 10),
      senderCompID: sender.value.trim(),
      targetCompID: target.value.trim(),
    }));
  };

  ws.onmessage = (e) => {
    let msg;
    try { msg = JSON.parse(e.data); } catch { return; }

    switch (msg.type) {
      case 'connected':
        connected = true;
        setStatus('connected', `${msg.host}:${msg.port}`);
        enableControls(true);
        addLog('system', `Connected to ${msg.host}:${msg.port}`);
        break;

      case 'disconnected':
        connected = false;
        setStatus('disconnected', msg.reason || '');
        enableControls(false);
        addLog('system', `Disconnected: ${msg.reason}`);
        break;

      case 'message':
        if (msg.direction === 'inbound') {
          addLog('inbound', msg.content);
        } else {
          addLog('outbound', msg.content);
          seqNum++;
        }
        break;

      case 'error':
        addLog('error', msg.message);
        if (!connected) {
          setStatus('disconnected', msg.message);
          enableControls(false);
        }
        break;
    }
  };

  ws.onclose = () => {
    if (connected) {
      connected = false;
      enableControls(false);
      setStatus('disconnected', 'relay closed');
    }
  };

  ws.onerror = () => {
    addLog('error', 'WebSocket error — is the relay server running?');
    setStatus('disconnected', 'relay error');
  };
}

function disconnectRelay() {
  if (ws) {
    ws.send(JSON.stringify({ type: 'disconnect' }));
    ws.close();
    ws = null;
  }
  connected = false;
  enableControls(false);
  setStatus('disconnected', 'disconnected');
}

/* ---- Controls ---- */
function enableControls(enabled) {
  sendBtn.disabled = !enabled;
  msgInput.disabled = !enabled;
  connectBtn.textContent = enabled ? 'Disconnect' : 'Connect';
  connectBtn.className = enabled ? 'btn btn-danger' : 'btn btn-primary';
}

function setStatus(state, detail) {
  statusEl.textContent = { connected: 'Connected', disconnected: 'Disconnected', connecting: 'Connecting...' }[state] || state;
  statusEl.className = `status status-${state}`;
  statusDetail.textContent = detail || '';
}

function addLog(type, content) {
  const entry = document.createElement('div');
  entry.className = `log-entry ${type}`;
  const time = new Date().toLocaleTimeString();
  if (type === 'inbound') entry.innerHTML = `<span class="time">${time}</span><span class="tag">IN</span>${escapeHtml(content)}`;
  else if (type === 'outbound') entry.innerHTML = `<span class="time">${time}</span><span class="tag">OUT</span>${escapeHtml(content)}`;
  else if (type === 'error') entry.innerHTML = `<span class="time">${time}</span>${escapeHtml(content)}`;
  else entry.innerHTML = `<span class="time">${time}</span>${escapeHtml(content)}`;
  logEl.appendChild(entry);
  logEl.scrollTop = logEl.scrollHeight;
  if (type === 'inbound' || type === 'outbound') {
    msgCount++;
    msgCountEl.textContent = `${msgCount} messages`;
  }
}

function escapeHtml(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

/* ---- FIX helpers ---- */
function fillTemplate(tpl) {
  const now = new Date();
  const utc = now.getUTCFullYear() +
    String(now.getUTCMonth() + 1).padStart(2, '0') +
    String(now.getUTCDate()).padStart(2, '0') +
    '-' +
    String(now.getUTCHours()).padStart(2, '0') +
    String(now.getUTCMinutes()).padStart(2, '0') +
    String(now.getUTCSeconds()).padStart(2, '0');
  return tpl
    .replace(/YYYYMMDD-HH:MM:SS/g, utc)
    .replace(/NN/g, seqNum)
    .replace(/XXX/g, '000001')
    .replace(/\|/g, '\x01');
}

/* ---- Event listeners ---- */
connectBtn.addEventListener('click', () => {
  if (connected) {
    disconnectRelay();
  } else {
    connectRelay();
  }
});

sendBtn.addEventListener('click', sendMessage);
msgInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') sendMessage(); });

function sendMessage() {
  const text = msgInput.value.trim();
  if (!text || !connected || !ws) return;
  const sep = text.includes('\x01') ? text : text.replace(/\|/g, '\x01');
  ws.send(JSON.stringify({ type: 'send', content: sep }));
  msgInput.value = '';
}

// Quick send buttons
document.querySelectorAll('[data-msg]').forEach(btn => {
  btn.addEventListener('click', () => {
    msgInput.value = fillTemplate(btn.dataset.msg);
    sendMessage();
  });
});

clearBtn.addEventListener('click', () => {
  logEl.innerHTML = '';
  msgCount = 0;
  msgCountEl.textContent = '0 messages';
});

setStatus('disconnected', 'configure FIX server details and connect');
