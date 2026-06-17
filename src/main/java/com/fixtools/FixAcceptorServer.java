package com.fixtools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Simple FIX acceptor test server.
 * Run with: ./gradlew runAcceptor
 * Listens on port 9878 by default. Set FIX_PORT env var to change.
 */
public class FixAcceptorServer {

    private static final SimpleDateFormat UTC_FMT = new SimpleDateFormat("yyyyMMdd-HH:mm:ss");
    static { UTC_FMT.setTimeZone(TimeZone.getTimeZone("UTC")); }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("FIX_PORT", "9878"));
        System.out.println("=== FIX Acceptor Test Server ===");
        System.out.println("Listening on port " + port);
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
        private final byte SOH = 0x01;

        Session(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                StringBuilder buf = new StringBuilder();

                while (running) {
                    byte[] bytes = new byte[4096];
                    int n = in.read(bytes);
                    if (n == -1) break;

                    buf.append(new String(bytes, 0, n, "UTF-8"));
                    processBuffer(buf, out);
                }
            } catch (Exception e) {
                if (running) System.out.println("[error] " + e.getMessage());
            } finally {
                running = false;
                try { socket.close(); } catch (Exception ignored) {}
                System.out.println("[disconnect] " + socket.getRemoteSocketAddress());
            }
        }

        private void processBuffer(StringBuilder buf, OutputStream out) throws Exception {
            String data = buf.toString();
            // A complete FIX message starts with "8=FIX." and ends with SOH followed by "10="
            int start = data.indexOf("8=FIX.");
            while (start != -1) {
                int end = data.indexOf("10=", start + 6);
                if (end == -1) break; // incomplete message, wait for more data

                // Find the end of the checksum value (next SOH after "10=")
                int sot = data.indexOf(SOH, end);
                if (sot == -1) break; // incomplete checksum, wait for more data

                String msg = data.substring(start, sot + 1);
                handleMessage(msg, out);

                // Move past this message
                data = data.substring(sot + 1);
                start = data.indexOf("8=FIX.");
            }
            buf.setLength(0);
            buf.append(data);
        }

        private void handleMessage(String msg, OutputStream out) throws Exception {
            Map<String, String> fields = parseFix(msg);
            String msgType = fields.get("35");
            String sender = fields.get("49");
            String target = fields.get("56");
            System.out.println("[recv] 35=" + msgType + " sender=" + sender + " target=" + target);

            if ("A".equals(msgType)) {
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
                System.out.println("[send] Logon acknowledge");

                // Send a test Execution Report
                Thread.sleep(300);
                Map<String, String> er = new HashMap<>();
                er.put("35", "8");
                er.put("49", target);
                er.put("56", sender);
                er.put("34", String.valueOf(seqNum++));
                er.put("52", utcNow());
                er.put("17", "EXEC-" + System.currentTimeMillis());
                er.put("20", "0");
                er.put("39", "0");
                er.put("37", "ORD-001");
                er.put("11", "CLIENT-REF-001");
                er.put("54", "1");
                er.put("38", "100");
                er.put("55", "AAPL");
                er.put("44", "150.25");
                er.put("32", "0");
                er.put("31", "0.00");
                er.put("150", "0");
                er.put("151", "100");
                sendFix(out, er);
                System.out.println("[send] Execution Report (35=8)");

            } else if ("0".equals(msgType)) {
                sendFix(out, Map.of(
                    "35", "0",
                    "49", sessionState.getOrDefault("target", "SERVER"),
                    "56", sessionState.getOrDefault("sender", "WEBAPP"),
                    "34", String.valueOf(seqNum++),
                    "52", utcNow()
                ));

            } else if ("5".equals(msgType)) {
                sendFix(out, Map.of(
                    "35", "5",
                    "49", sessionState.getOrDefault("target", "SERVER"),
                    "56", sessionState.getOrDefault("sender", "WEBAPP"),
                    "34", String.valueOf(seqNum++),
                    "52", utcNow(),
                    "58", "Logout acknowledged"
                ));
                System.out.println("[send] Logout acknowledge");
                running = false;

            } else {
                System.out.println("[info] Unhandled 35=" + msgType);
            }
        }

        private void sendFix(OutputStream out, Map<String, String> fields) throws Exception {
            StringBuilder sb = new StringBuilder();
            sb.append("8=FIX.4.4").append((char) SOH);
            for (Map.Entry<String, String> e : fields.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append((char) SOH);
            }
            sb.append("10=000").append((char) SOH);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        }

        private Map<String, String> parseFix(String raw) {
            Map<String, String> map = new HashMap<>();
            String[] parts = raw.split(String.valueOf((char) SOH));
            for (String p : parts) {
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq > 0) {
                    map.put(p.substring(0, eq), p.substring(eq + 1));
                }
            }
            return map;
        }
    }

    static String utcNow() {
        return UTC_FMT.format(new Date());
    }
}
