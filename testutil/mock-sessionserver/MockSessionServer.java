import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Mock Mojang sessionserver (test-only, no third-party deps).
 *
 * <p>
 * Simulates the two endpoints MixAuth calls:
 * <ul>
 * <li>{@code GET /minecraft/profile/lookup/name/{name}} — pre-login profile
 * check</li>
 * <li>{@code GET /session/minecraft/hasJoined?username=...&amp;serverId=...} —
 * online session validation</li>
 * </ul>
 *
 * <p>
 * Response behaviour is configured with command-line flags so each test
 * scenario
 * (online handshake / 404 / 429 / 5xx / malformed / empty-properties) can be
 * reproduced.
 * The "online" profile includes a NON-EMPTY {@code properties} array (real
 * Mojang returns
 * a textures entry), which is required to reproduce the authlib 7.0.61
 * immutable-PropertyMap
 * regression on 1.21.11.
 *
 * <p>
 * Run with JDK single-file source launcher (no compile step):
 * 
 * <pre>
 *   java MockSessionServer.java --port 8080 --profile-mode online --hasjoined-mode online
 * </pre>
 *
 * <p>
 * Flag reference:
 * 
 * <pre>
 *   --port PORT             listen port (default 8080)
 *   --profile-mode MODE     online | 404 | 429 | 500 | malformed | empty   (default online)
 *   --hasjoined-mode MODE   online | 404 | 500 | malformed                (default online)
 *   --profile-uuid UUID     profile UUID to return (default 00000000-0000-0000-0000-000000000001)
 * </pre>
 */
public final class MockSessionServer {
    private static final String DEFAULT_UUID = "00000000-0000-0000-0000-000000000001";

    private int port = 8080;
    private String profileMode = "online";
    private String hasJoinedMode = "online";
    private String profileUuid = DEFAULT_UUID;

    public static void main(String[] args) throws IOException {
        MockSessionServer server = new MockSessionServer();
        server.parseArgs(args);
        server.start();
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--profile-mode" -> profileMode = args[++i];
                case "--hasjoined-mode" -> hasJoinedMode = args[++i];
                case "--profile-uuid" -> profileUuid = args[++i];
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/minecraft/profile/lookup/name/", this::handleProfileLookup);
        server.createContext("/session/minecraft/hasJoined", this::handleHasJoined);
        server.createContext("/_mock/", this::handleControl);
        server.createContext("/", this::handleRoot);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[mock] listening on 127.0.0.1:" + port
                + " profile-mode=" + profileMode
                + " hasjoined-mode=" + hasJoinedMode
                + " uuid=" + profileUuid);
    }

    // ---- profile lookup: /minecraft/profile/lookup/name/{name} ----

    private void handleProfileLookup(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        System.out.println("[mock] profile lookup name=" + name + " mode=" + profileMode);

        switch (profileMode) {
            case "404" -> send(exchange, 404, "{\"path\":\"/minecraft/profile/lookup/name/" + name + "\"}");
            case "429" -> send(exchange, 429, "{\"error\":\"TooManyRequestsException\"}");
            case "500" -> send(exchange, 500, "{\"error\":\"InternalServerError\"}");
            case "malformed" -> send(exchange, 200, "{ this is not valid json");
            case "empty" -> send(exchange, 200, "{\"id\":\"" + noDashes(profileUuid)
                    + "\",\"name\":\"" + name + "\",\"properties\":[]}");
            default -> send(exchange, 200, profileJson(profileUuid, name));
        }
    }

    // ---- hasJoined: /session/minecraft/hasJoined?username=...&serverId=... ----

    private void handleHasJoined(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String username = query.getOrDefault("username", "");
        String serverId = query.getOrDefault("serverId", "");
        System.out
                .println("[mock] hasJoined username=" + username + " serverId=" + serverId + " mode=" + hasJoinedMode);

        switch (hasJoinedMode) {
            case "404" -> send(exchange, 404, "{\"error\":\"NotFound\"}");
            case "500" -> send(exchange, 500, "{\"error\":\"InternalServerError\"}");
            case "malformed" -> send(exchange, 200, "{ not json");
            default -> send(exchange, 200, profileJson(profileUuid, username));
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        System.out.println("[mock] unknown path " + exchange.getRequestURI().getPath());
        send(exchange, 404, "{\"error\":\"not found\"}");
    }

    // ---- runtime control: /_mock/mode?profile=...&hasjoined=... ----

    private void handleControl(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.endsWith("/mode")) {
            send(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (query.containsKey("profile")) {
            profileMode = query.get("profile");
        }
        if (query.containsKey("hasjoined")) {
            hasJoinedMode = query.get("hasjoined");
        }
        System.out.println("[mock] control mode set: profile=" + profileMode
                + " hasjoined=" + hasJoinedMode);
        send(exchange, 200, "{\"profile\":\"" + profileMode
                + "\",\"hasjoined\":\"" + hasJoinedMode + "\"}");
    }

    // ---- helpers ----

    /** Real-Mojang-style profile JSON with a non-empty properties array. */
    private String profileJson(String uuid, String name) {
        return "{\"id\":\"" + noDashes(uuid)
                + "\",\"name\":\"" + name
                + "\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + "eyJ0aW1lc3RhbXAiOjAsInByb2ZpbGVJZCI6IjAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIiwicHJvZmlsZU5hbWUiOiJUZXN0IiwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vZXhhbXBsZS5jb20vc2tpbi5wbmcifX19"
                + "\",\"signature\":\"mock-signature\"}]}";
    }

    private static String noDashes(String uuid) {
        return uuid.replace("-", "");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
