package com.fixtools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple FIX acceptor test server.
 * Run with: ./gradlew runAcceptor
 * Listens on port 9878 by default. Set PORT env var to change.
 */
public class FixAcceptorServer {

    private static final AtomicInteger msgSeqNum = new AtomicInteger(1);
    private static final SimpleDateFormat UTC_FMT = new SimpleDateFormat("yyyyMMdd-HH:mm:ss");
    static { UTC_FMT.setTimeZone(TimeZone.getTimeZone("UTC")); }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("FIX_PORT", "9878"));
        System.out.println("=== FIX Acceptor Test Server ===");
        System.out.println("Listening on port " + port);
        System.out.println("Connect with the webapp and send a Logon (35=A)");
        System.out.println();

        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                Socket client = server.accept();
                System.out.println("[connect] " + client.getRemoteSocketAddress());
                new Thread(new Session(client)).start();
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    static class Session implements Runnable {
        private final Socket socket;
        private final Map<String, String> sessionState = new HashMap<>();
        private int seqNum = 1;
        private volatile boolean running = true;

        Session(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream out = socket.getOutputStream();

                while (running) {
                    char[] buf = new char[4096];
                    int n = reader.read(buf);
                    if (n == -1) break;

                    String data = new String(buf, 0, n);
                    String[] msgs = data.split("", -1);

                    StringBuilder partial = new StringBuilder();
                    for (String raw : msgs) {
                        if (raw.isEmpty()) continue;
                        // reassemble if last chunk was incomplete
                        if (partial.length() > 0) {
                            partial.append('').append(raw);
                            raw = partial.toString();
                            partial.setLength(0);
                        }
                        if (!raw.contains("35=")) {
                            partial.append(raw);
                            continue;
                        }
                        handleMessage(raw, out);
                    }
                }
            } catch (Exception e) {
                if (running) System.out.println("[error] " + e.getMessage());
            } finally {
                running = false;
                try { socket.close(); } catch (Exception ignored) {}
                System.out.println("[disconnect] " + socket.getRemoteSocketAddress());
            }
        }

        private void handleMessage(String msg, OutputStream out) throws Exception {
            Map<String, String> fields = parseFix(msg);
            String msgType = fields.get("35");
            String sender = fields.get("49");
            String target = fields.get("56");
            System.out.println("[recv] 35=" + msgType + " " + summarize(fields));

            if ("A".equals(msgType)) {
                // Logon response
                sessionState.put("sender", sender);
                sessionState.put("target", target);
                sendFix(out, Map.of(
                    "35", "A",
                    "49", target,
                    "56", sender,
                    "34", String.valueOf(seqNum++),
                    "52", utcNow(),
                    "98", "0",
                    "108", "30"
                ));
                System.out.println("[send] Logon response (35=A)");

                // Send a test Execution Report after a short delay
                Thread.sleep(500);
                Map<String, String> execReport = new HashMap<>();
                execReport.put("35", "8");
                execReport.put("49", target);
                execReport.put("56", sender);
                execReport.put("34", String.valueOf(seqNum++));
                execReport.put("52", utcNow());
                execReport.put("17", "EXEC-" + System.currentTimeMillis());
                execReport.put("20", "0");
                execReport.put("39", "0");
                execReport.put("37", "ORD-001");
                execReport.put("11", "CLIENT-REF-001");
                execReport.put("54", "1");
                execReport.put("38", "100");
                execReport.put("55", "AAPL");
                execReport.put("44", "150.25");
                execReport.put("32", "0");
                execReport.put("31", "0.00");
                execReport.put("150", "0");
                execReport.put("151", "100");
                sendFix(out, execReport);
                System.out.println("[send] Execution Report (35=8)");

            } else if ("0".equals(msgType)) {
                // Heartbeat response
                sendFix(out, Map.of(
                    "35", "0",
                    "49", sessionState.getOrDefault("target", "SERVER"),
                    "56", sessionState.getOrDefault("sender", "WEBAPP"),
                    "34", String.valueOf(seqNum++),
                    "52", utcNow()
                ));

            } else if ("5".equals(msgType)) {
                // Logout response
                sendFix(out, Map.of(
                    "35", "5",
                    "49", sessionState.getOrDefault("target", "SERVER"),
                    "56", sessionState.getOrDefault("sender", "WEBAPP"),
                    "34", String.valueOf(seqNum++),
                    "52", utcNow(),
                    "58", "Logout acknowledged"
                ));
                System.out.println("[send] Logout response (35=5)");
                running = false;

            } else {
                System.out.println("[info] Unhandled message type: " + msgType);
            }
        }

        private void sendFix(OutputStream out, Map<String, String> fields) throws Exception {
            StringBuilder sb = new StringBuilder();
            sb.append("8=FIX.4.4");
            for (Map.Entry<String, String> e : fields.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append('');
            }
            // BodyLength (9) and Checksum (10) would be calculated in real FIX
            sb.append("10=000");
            String raw = sb.toString();
            out.write(raw.getBytes("UTF-8"));
            out.flush();
        }

        private Map<String, String> parseFix(String raw) {
            Map<String, String> map = new HashMap<>();
            // Strip BeginString (8=...) and checksum (10=...) for simplicity
            String[] parts = raw.split("");
            int bodyLen = parts.length - 1; // skip trailing empty from split
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq > 0) {
                    map.put(p.substring(0, eq), p.substring(eq + 1));
                }
            }
            return map;
        }

        private String summarize(Map<String, String> f) {
            if ("8".equals(f.get("35"))) {
                return "ExecReport ord=" + f.get("11") + " sym=" + f.get("55");
            }
            if ("A".equals(f.get("35"))) {
                return "sender=" + f.get("49") + " target=" + f.get("56");
            }
            return "";
        }
    }

    static String utcNow() {
        return UTC_FMT.format(new Date());
    }
}
