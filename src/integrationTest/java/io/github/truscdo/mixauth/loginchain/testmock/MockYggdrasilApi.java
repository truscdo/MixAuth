package io.github.truscdo.mixauth.loginchain.testmock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MockYggdrasilApi {
    private static final String ACCESS_TOKEN = "test-access-token";
    private final Map<String, String> authenticatedProfiles = new ConcurrentHashMap<>();

    void register(HttpServer server) {
        server.createContext("/authserver/authenticate", this::handleAuthenticate);
        server.createContext("/sessionserver/session/minecraft/join", this::handleJoin);
    }

    private void handleAuthenticate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        Map<String, String> fields;
        try {
            fields = MockJson.parseStringFields(readBody(exchange));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        String username = fields.get("username");
        String clientToken = fields.get("clientToken");
        if (username == null || username.isBlank() || clientToken == null) {
            send(exchange, 400, "{\"error\":\"Invalid credentials\"}");
            return;
        }

        UUID profileUuid = MockIdentity.yggdrasilUuid(username);
        authenticatedProfiles.put(MockIdentity.noDashes(profileUuid), username);
        String profile = "{\"id\":\"" + MockIdentity.noDashes(profileUuid)
                + "\",\"name\":\"" + MockJson.escape(username) + "\"}";
        send(exchange, 200, "{\"accessToken\":\"" + ACCESS_TOKEN
                + "\",\"clientToken\":\"" + MockJson.escape(clientToken)
                + "\",\"selectedProfile\":" + profile
                + ",\"availableProfiles\":[" + profile + "]}");
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        Map<String, String> fields;
        try {
            fields = MockJson.parseStringFields(readBody(exchange));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        if (!fields.containsKey("accessToken")
                || !fields.containsKey("selectedProfile")
                || !fields.containsKey("serverId")) {
            send(exchange, 400, "{\"error\":\"Missing required field\"}");
            return;
        }

        String normalizedProfile = MockIdentity.normalizeUuid(fields.get("selectedProfile"));
        String username = normalizedProfile == null ? null : authenticatedProfiles.get(normalizedProfile);
        if (username == null || !normalizedProfile.equals(MockIdentity.noDashes(
                MockIdentity.yggdrasilUuid(username)))) {
            send(exchange, 400, "{\"error\":\"Invalid selectedProfile\"}");
            return;
        }

        System.out.println("[mock] join username=" + username + " serverId=" + fields.get("serverId"));
        sendNoContent(exchange);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
