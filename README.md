# FIX Connection Tools

A browser-based tool for connecting to a FIX (Financial Information Exchange) acceptor and viewing live FIX messages in real time.

Since browsers cannot open raw TCP sockets, this project includes a small relay server (Spring Boot) that translates WebSocket ↔ TCP, bridging the browser to your FIX server.

## Architecture

```
Browser (HTML/JS)
      ↕ WebSocket (port 3000)
Spring Boot Relay Server
      ↕ raw TCP
Your FIX Acceptor
```

## Requirements

- Java 11+
- Gradle (optional — the included `gradlew` wrapper handles it)

## Quick Start

```bash
./gradlew bootRun
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

## Build a standalone JAR

```bash
./gradlew bootJar
java -jar build/libs/fix-connection-tools-1.0.0.jar
```

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `PORT` | `3000` | Port for the relay server (both WebSocket and static files) |

## Project Structure

```
src/main/java/com/fixtools/
├── FixToolsApplication.java       # Spring Boot entry point
├── config/WebSocketConfig.java    # WebSocket endpoint registration
└── handler/RelayWebSocketHandler.java  # WebSocket ↔ TCP relay logic

src/main/resources/
├── application.yml                # Server configuration
└── static/                        # Frontend files
    ├── index.html                 # Webapp layout
    ├── style.css                  # Dark theme styling
    └── app.js                     # WebSocket client & FIX message handling
```
