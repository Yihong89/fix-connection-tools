# FIX Connection Tools

A browser-based tool for connecting to a FIX (Financial Information Exchange) acceptor and viewing live FIX messages in real time.

Since browsers cannot open raw TCP sockets, this project includes a small relay server that translates WebSocket ↔ TCP, bridging the browser to your FIX server.

## Architecture

```
Browser (HTML/JS)
      ↕ WebSocket (port 3000)
relay-server.js
      ↕ raw TCP
Your FIX Acceptor
```

## Quick Start

```bash
npm install
npm start
```

Open **http://localhost:3000** in your browser.

## Usage

### 1. Connect to a FIX server

Fill in the sidebar:
- **Host** — your FIX acceptor address (e.g. `127.0.0.1`)
- **Port** — your FIX acceptor port (e.g. `9878`)
- **SenderCompID (49)** — your sender identifier (e.g. `WEBAPP`)
- **TargetCompID (56)** — the target identifier your acceptor expects

Click **Connect**.

### 2. Send FIX messages

Use the **Quick Send** buttons to send common messages:
- **Logon (35=A)** — initiates a FIX session
- **Heartbeat (35=0)** — keeps the session alive
- **Logout (35=5)** — ends the session

Or type any FIX message into the input bar and press **Enter**. Use `|` (pipe) as the field separator — the app converts it to the SOH (`\x01`) character automatically.

### 3. Monitor messages

Inbound messages appear with a blue **IN** tag, outbound with a green **OUT** tag. Each entry is timestamped.

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `PORT` | `3000` | Port for the relay server (both WebSocket and static files) |

## Run as a background server

```bash
PORT=8080 npm start &
```

Then access the webapp at `http://localhost:8080`.

## Files

| File | Purpose |
|---|---|
| `relay-server.js` | WebSocket-to-TCP relay + static file server |
| `public/index.html` | Webapp layout |
| `public/style.css` | Dark theme styling |
| `public/app.js` | WebSocket client, FIX message handling, UI logic |
