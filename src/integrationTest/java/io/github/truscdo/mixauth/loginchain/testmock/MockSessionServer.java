package io.github.truscdo.mixauth.loginchain.testmock;

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
 * 模拟 Mojang sessionserver（仅供测试使用，无第三方依赖）。
 *
 * <p>
 * 模拟 MixAuth 与 MCC 调用的 Mojang/Yggdrasil 端点：
 * <ul>
 * <li>{@code GET /minecraft/profile/lookup/name/{name}} — 登录前 profile 预检</li>
 * <li>{@code GET /session/minecraft/hasJoined?username=...&amp;serverId=...} —
 * 在线会话校验</li>
 * <li>{@code POST /authserver/authenticate} — 测试 Yggdrasil 登录</li>
 * <li>{@code POST /sessionserver/session/minecraft/join} — 测试 Yggdrasil 会话加入</li>
 * </ul>
 *
 * <p>
 * 通过命令行参数配置各端点的响应行为，以复现不同测试场景（正常 / 404 /
 * 429 / 5xx / 畸形 / 空 properties 等）。"online" 模式的 profile 携带非空
 * {@code properties} 数组（真实 Mojang 会返回 textures 条目），这是触发
 * 1.21.11 authlib 不可变 PropertyMap 回归所必需的。
 *
 * <p>
 * 仅依赖 JDK 标准库，可直接编译启动：
 *
 * <pre>
 *   javac -d out Mock*.java
 *   java -cp out io.github.truscdo.mixauth.loginchain.testmock.MockSessionServer \
 *       --port 8080 --profile-mode online --hasjoined-mode online
 * </pre>
 *
 * <p>
 * 参数说明：
 *
 * <pre>
 *   --port PORT            监听端口（默认 8080）
 *   --profile-mode MODE    online | 404 | 429 | 500 | malformed | empty   （默认 online）
 *   --hasjoined-mode MODE  online | 404 | 500 | malformed                （默认 online）
 * </pre>
 */
public final class MockSessionServer {
    private int port = 8080;
    private String profileMode = "online";
    private String hasJoinedMode = "online";
    private final MockYggdrasilApi yggdrasilApi = new MockYggdrasilApi();

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
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/minecraft/profile/lookup/name/", this::handleProfileLookup);
        server.createContext("/session/minecraft/hasJoined", this::handleHasJoined);
        yggdrasilApi.register(server);
        server.createContext("/_mock/", this::handleControl);
        server.createContext("/", this::handleRoot);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[mock] listening on 127.0.0.1:" + port
                + " profile-mode=" + profileMode
                + " hasjoined-mode=" + hasJoinedMode);
    }

    // ---- profile 预检：/minecraft/profile/lookup/name/{name} ----

    private void handleProfileLookup(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String name = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
        System.out.println("[mock] profile lookup name=" + name + " mode=" + profileMode);

        switch (profileMode) {
            case "404" -> send(exchange, 404, "{\"path\":\"/minecraft/profile/lookup/name/" + name + "\"}");
            case "429" -> send(exchange, 429, "{\"error\":\"TooManyRequestsException\"}");
            case "500" -> send(exchange, 500, "{\"error\":\"InternalServerError\"}");
            case "malformed" -> send(exchange, 200, "{ this is not valid json");
            case "empty" -> send(exchange, 200, profileJson(name, false));
            default -> send(exchange, 200, profileJson(name, true));
        }
    }

    // ---- 在线会话校验：/session/minecraft/hasJoined?username=...&serverId=... ----

    private void handleHasJoined(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String username = query.getOrDefault("username", "");
        String serverId = query.getOrDefault("serverId", "");
        System.out
                .println("[mock] hasJoined username=" + username + " serverId=" + serverId + " mode=" + hasJoinedMode);

        switch (hasJoinedMode) {
            case "404" -> send(exchange, 404, "{\"error\":\"NotFound\"}");
            case "500" -> send(exchange, 500, "{\"error\":\"InternalServerError\"}");
            case "malformed" -> send(exchange, 200, "{ not json");
            default -> send(exchange, 200, profileJson(username, true));
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        System.out.println("[mock] unknown path " + exchange.getRequestURI().getPath());
        send(exchange, 404, "{\"error\":\"not found\"}");
    }

    // ---- 运行期控制：/_mock/mode?profile=...&hasjoined=... ----

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

    // ---- 工具方法 ----

    /** 生成形如真实 Mojang 的 profile JSON（properties 数组非空）。 */
    private String profileJson(String name, boolean withProperties) {
        String properties = withProperties
                ? ",\"properties\":[{\"name\":\"textures\",\"value\":\""
                        + "eyJ0aW1lc3RhbXAiOjAsInByb2ZpbGVJZCI6IjAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIiwicHJvZmlsZU5hbWUiOiJUZXN0IiwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vZXhhbXBsZS5jb20vc2tpbi5wbmcifX19"
                        + "\",\"signature\":\"mock-signature\"}]"
                : ",\"properties\":[]";
        return "{\"id\":\"" + MockIdentity.noDashes(MockIdentity.yggdrasilUuid(name))
                + "\",\"name\":\"" + MockJson.escape(name) + "\""
                + properties + "}";
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
