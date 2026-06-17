package com.fixtools.handler;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RelayWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, TcpRelay> relays = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // session ready
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
        String type = (String) msg.get("type");
        if (type == null) return;

        if ("connect".equals(type)) {
            handleConnect(session, msg);
        } else if ("send".equals(type)) {
            handleSend(session, msg);
        } else if ("disconnect".equals(type)) {
            handleDisconnect(session);
        }
    }

    private void handleConnect(WebSocketSession session, Map<String, Object> msg) {
        String host = (String) msg.get("host");
        Object portObj = msg.get("port");
        int port = portObj instanceof Number ? ((Number) portObj).intValue() : 0;

        if (host == null || host.trim().isEmpty() || port <= 0) {
            sendJson(session, Map.of("type", "error", "message", "Invalid host or port"));
            return;
        }

        TcpRelay relay = new TcpRelay(session, host, port);
        relays.put(session.getId(), relay);
        executor.submit(relay::start);
    }

    private void handleSend(WebSocketSession session, Map<String, Object> msg) {
        TcpRelay relay = relays.get(session.getId());
        if (relay == null) {
            sendJson(session, Map.of("type", "error", "message", "Not connected to FIX server"));
            return;
        }
        String content = (String) msg.get("content");
        if (content != null) {
            relay.send(content);
            sendJson(session, Map.of("type", "message", "direction", "outbound", "content", content));
        }
    }

    private void handleDisconnect(WebSocketSession session) {
        TcpRelay relay = relays.remove(session.getId());
        if (relay != null) relay.close();
        sendJson(session, Map.of("type", "disconnected", "reason", "user requested"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        TcpRelay relay = relays.remove(session.getId());
        if (relay != null) relay.close();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        TcpRelay relay = relays.remove(session.getId());
        if (relay != null) relay.close();
    }

    private void sendJson(WebSocketSession session, Map<?, ?> data) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(data)));
            }
        } catch (Exception ignored) {}
    }

    private class TcpRelay {
        private final WebSocketSession session;
        private final String host;
        private final int port;
        private Socket socket;
        private volatile boolean running;

        TcpRelay(WebSocketSession session, String host, int port) {
            this.session = session;
            this.host = host;
            this.port = port;
        }

        void start() {
            running = true;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 10000);
                socket.setTcpNoDelay(true);
                sendJson(session, Map.of("type", "connected", "host", host, "port", port));

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                char[] buf = new char[4096];
                int n;
                while (running && (n = reader.read(buf)) != -1) {
                    String chunk = new String(buf, 0, n);
                    sendJson(session, Map.of("type", "message", "direction", "inbound", "content", chunk));
                }
            } catch (Exception e) {
                if (running) {
                    sendJson(session, Map.of("type", "error", "message", "TCP: " + e.getMessage()));
                }
            } finally {
                close();
                if (running) {
                    sendJson(session, Map.of("type", "disconnected", "reason", "TCP connection closed"));
                }
                relays.remove(session.getId());
            }
        }

        void send(String content) {
            try {
                if (socket != null && !socket.isClosed()) {
                    OutputStream out = socket.getOutputStream();
                    out.write(content.getBytes("UTF-8"));
                    out.flush();
                }
            } catch (Exception e) {
                sendJson(session, Map.of("type", "error", "message", "Send failed: " + e.getMessage()));
            }
        }

        void close() {
            running = false;
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
