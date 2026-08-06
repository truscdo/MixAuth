package io.github.truscdo.mixauth.validation;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.AuthLocalizedText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.github.truscdo.mixauth.LogUtil;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import org.slf4j.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

public final class OnlineHandshakeValidationService {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final String PROFILE_LOOKUP_BY_NAME_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final Map<String, PendingHandshake> PENDING = new ConcurrentHashMap<>();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 慢路径执行器：固定少量线程 + 有界队列。缓存被掏空时在后台等待生产者补货，
     * 队列满则拒绝（客户端收到"服务器繁忙"断开），避免任务无限堆积。
     */
    private static final ExecutorService HELLO_EXECUTOR = createHelloExecutor();

    private static ExecutorService createHelloExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                2,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "mixauth-keygen");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private OnlineHandshakeValidationService() {
    }

    public static boolean shouldIntercept(MinecraftServer server) {
        return !server.usesAuthentication();
    }

    /**
     * 开始正版握手。RSA 密钥对来源为 {@link KeyPairCache}，生成已剥离出服务器主线程：
     * <ul>
     * <li>快路径：缓存命中，主线程仅做非阻塞取用 + 组装报文（微秒级，无冻结）；</li>
     * <li>慢路径：缓存被掏空（突发/洪水）时，改由后台线程等待生产者补货，
     * 主线程绝不执行 RSA 密钥生成。</li>
     * </ul>
     * 调用方须在 future 完成后再发送 Hello 报文（建议回到服务器主线程发送）。
     */
    public static CompletableFuture<ClientboundHelloPacket> beginValidationAsync(
            Connection connection,
            ServerboundHelloPacket packet,
            String serverId) {
        KeyPair keyPair = KeyPairCache.poll();
        if (keyPair != null) {
            return completeOrFailed(() -> buildHello(connection, packet, serverId, keyPair));
        }

        LOGGER.warn("Key pair cache drained; deferring handshake for {} to background key generation", packet.name());
        try {
            return CompletableFuture.supplyAsync(
                    () -> buildHello(connection, packet, serverId, awaitKeyPair()),
                    HELLO_EXECUTOR);
        } catch (RejectedExecutionException rejectedExecutionException) {
            LOGGER.warn("Background key generation saturated; rejecting handshake for {}", packet.name());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Background key generation saturated", rejectedExecutionException));
        }
    }

    private static KeyPair awaitKeyPair() {
        try {
            return KeyPairCache.take();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a pre-generated key pair", interruptedException);
        }
    }

    private static ClientboundHelloPacket buildHello(
            Connection connection,
            ServerboundHelloPacket packet,
            String serverId,
            KeyPair keyPair) {
        cleanupExpiredEntries();

        String key = connectionKey(connection);
        byte[] challenge = new byte[4];
        SECURE_RANDOM.nextBytes(challenge);

        PendingHandshake pendingHandshake = new PendingHandshake(
                packet.name(),
                packet.profileId(),
                keyPair,
                challenge,
                serverId,
                System.currentTimeMillis());
        PENDING.put(key, pendingHandshake);

        return new ClientboundHelloPacket(serverId, keyPair.getPublic().getEncoded(), challenge, true);
    }

    private static CompletableFuture<ClientboundHelloPacket> completeOrFailed(
            Supplier<ClientboundHelloPacket> supplier) {
        try {
            return CompletableFuture.completedFuture(supplier.get());
        } catch (RuntimeException runtimeException) {
            return CompletableFuture.failedFuture(runtimeException);
        }
    }

    public static PendingKeyState pendingKeyState(Connection connection) {
        String key = connectionKey(connection);
        PendingHandshake pendingHandshake = PENDING.get(key);
        if (pendingHandshake == null) {
            cleanupExpiredEntries();
            return PendingKeyState.NONE;
        }

        if (isExpired(pendingHandshake, System.currentTimeMillis())) {
            return PendingKeyState.EXPIRED;
        }

        cleanupExpiredEntries();
        return PendingKeyState.PRESENT;
    }

    public static ValidationResult handleKey(
            Connection connection,
            ServerboundKeyPacket packet) throws CryptException {
        String key = connectionKey(connection);
        PendingHandshake pendingHandshake = PENDING.get(key);
        if (pendingHandshake == null) {
            cleanupExpiredEntries();
            return ValidationResult.missingPending();
        }

        if (isExpired(pendingHandshake, System.currentTimeMillis())) {
            PENDING.remove(key);
            cleanupExpiredEntries();
            return ValidationResult.handshakeTimedOut(pendingHandshake.username());
        }

        SecretKey secretKey = packet.getSecretKey(pendingHandshake.keyPair().getPrivate());
        boolean challengeValid = packet.isChallengeValid(
                pendingHandshake.challenge(),
                pendingHandshake.keyPair().getPrivate());
        if (!challengeValid) {
            PENDING.remove(connectionKey(connection));
            return ValidationResult.invalidChallenge(pendingHandshake.username());
        }

        Cipher decryptCipher = Crypt.getCipher(Cipher.DECRYPT_MODE, secretKey);
        Cipher encryptCipher = Crypt.getCipher(Cipher.ENCRYPT_MODE, secretKey);
        connection.setEncryptionKey(decryptCipher, encryptCipher);

        String serverHash = new BigInteger(
                Crypt.digestData(
                        pendingHandshake.serverId(),
                        pendingHandshake.keyPair().getPublic(),
                        secretKey))
                .toString(16);

        return ValidationResult.readyForHasJoined(
                pendingHandshake.username(),
                pendingHandshake.profileId(),
                serverHash,
                pendingHandshake.createdAt());
    }

    public static CompletableFuture<HasJoinedResult> requestHasJoined(ValidationResult result) {
        return CompletableFuture.supplyAsync(() -> doRequestHasJoined(result));
    }

    public static GameProfile createOfflineProfile(String username) {
        return UUIDUtil.createOfflineProfile(username);
    }

    public static void clear(Connection connection) {
        PENDING.remove(connectionKey(connection));
    }

    private static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(AuthServerConfig.mojangConnectTimeout())
                .build();
    }

    /** httpGet 传输层错误类型 */
    enum HttpErrorType {
        INTERRUPTED, IO_FAILURE
    }

    @FunctionalInterface
    private interface HttpErrorHandler<T> {
        T apply(HttpErrorType type);
    }

    private static <T> T httpGet(String url, Function<HttpResponse<String>, T> successHandler,
            String errorContext,
            HttpErrorHandler<T> errorHandler) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(AuthServerConfig.mojangRequestTimeout())
                    .build();
            HttpResponse<String> response = createHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return successHandler.apply(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while {}", errorContext, e);
            return errorHandler.apply(HttpErrorType.INTERRUPTED);
        } catch (IOException e) {
            LOGGER.error("I/O failure while {}", errorContext, e);
            return errorHandler.apply(HttpErrorType.IO_FAILURE);
        }
    }

    public static PreLoginCheckResult syncPreLoginCheck(String username, UUID requestedProfileId) {
        if (username == null || username.isBlank()) {
            LOGGER.warn("syncPreLoginCheck called with empty username, disconnecting");
            return new PreLoginCheckResult.Disconnect(
                    username,
                    requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.missing_username"));
        }

        try {
            String url = PROFILE_LOOKUP_BY_NAME_URL + encode(username);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(AuthServerConfig.mojangRequestTimeout())
                    .build();
            HttpResponse<String> response = createHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode == 404 || statusCode == 204) {
                return new PreLoginCheckResult.Offline(username);
            }
            if (statusCode == 429) {
                LOGGER.warn("Mojang profile lookup rate limited for {} (HTTP 429)", username);
                return new PreLoginCheckResult.Disconnect(
                        username, requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
            }
            if (statusCode >= 500) {
                LOGGER.warn("Mojang profile lookup returned server error {} for {}", statusCode, username);
                return new PreLoginCheckResult.Disconnect(
                        username, requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
            }
            if (statusCode != 200) {
                LOGGER.warn("Mojang profile lookup returned unexpected status {} for {}", statusCode, username);
                return new PreLoginCheckResult.Disconnect(
                        username, requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
            }

            GameProfile profile = parseGameProfile(body, username);
            if (profile == null || profile.getId() == null) {
                LOGGER.warn("Mojang profile lookup returned malformed profile data for {} (HTTP 200, parse failed)",
                        username);
                return new PreLoginCheckResult.Disconnect(
                        username, requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.mojang_data_error"));
            }
            if (!requestedProfileId.equals(profile.getId())) {
                return new PreLoginCheckResult.Offline(username);
            }
            String resolvedName = profile.getName();
            if (resolvedName == null || !resolvedName.equalsIgnoreCase(username)) {
                return new PreLoginCheckResult.Offline(username);
            }
            return new PreLoginCheckResult.Online();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while looking up Mojang profile for {}", username, e);
            return new PreLoginCheckResult.Disconnect(username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
        } catch (IOException e) {
            LOGGER.error("I/O failure while looking up Mojang profile for {}", username, e);
            return new PreLoginCheckResult.Disconnect(username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
        } catch (RuntimeException e) {
            LOGGER.error("Unexpected error while looking up Mojang profile for {}", username, e);
            return new PreLoginCheckResult.Disconnect(username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
        }
    }

    private static HasJoinedResult doRequestHasJoined(ValidationResult result) {
        String username = result.username();
        String url = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                + encode(username) + "&serverId=" + encode(result.serverHash());

        return httpGet(url, response -> {
            int statusCode = response.statusCode();
            String body = response.body();
            boolean success = statusCode == 200;
            GameProfile profile = success ? parseGameProfile(body, username) : null;

            if (!success) {
                LOGGER.warn("Session server returned HTTP {} for {} (expected 200)", statusCode, username);
            }

            if (success && profile == null) {
                LOGGER.error("Session server returned malformed profile data for {}", username);
                return new HasJoinedResult(username, statusCode, body, false,
                        AuthLocalizedText.of("auth.validation.reason.mojang_data_error"), null);
            }

            return new HasJoinedResult(username, statusCode, body, success, null, profile);
        }, "requesting session validation for " + username,
                type -> switch (type) {
                    case INTERRUPTED -> new HasJoinedResult(username, 0, "", false,
                            AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"), null);
                    case IO_FAILURE -> new HasJoinedResult(username, 0, "", false,
                            AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"), null);
                });
    }

    private static GameProfile parseGameProfile(String body, String fallbackUsername) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement idElement = root.get("id");
            if (idElement == null) {
                return null;
            }

            UUID uuid = parseUndashedUuid(idElement.getAsString());
            String profileName = Optional.ofNullable(root.get("name"))
                    .map(JsonElement::getAsString)
                    .filter(name -> !name.isBlank())
                    .orElse(fallbackUsername);

            GameProfile profile = new GameProfile(uuid, profileName);
            JsonArray properties = root.getAsJsonArray("properties");
            if (properties != null) {
                for (JsonElement propertyElement : properties) {
                    JsonObject propertyObject = propertyElement.getAsJsonObject();
                    String name = propertyObject.get("name").getAsString();
                    String value = propertyObject.get("value").getAsString();
                    JsonElement signatureElement = propertyObject.get("signature");
                    Property property = signatureElement == null || signatureElement.isJsonNull()
                            ? new Property(name, value)
                            : new Property(name, value, signatureElement.getAsString());
                    profile.getProperties().put(name, property);
                }
            }

            return profile;
        } catch (RuntimeException runtimeException) {
            LOGGER.error("Failed to parse authenticated profile for {}", fallbackUsername, runtimeException);
            return null;
        }
    }

    private static UUID parseUndashedUuid(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "");
        if (normalized.length() != 32) {
            throw new IllegalArgumentException("Unexpected UUID length: " + value);
        }

        String dashed = normalized.substring(0, 8)
                + "-" + normalized.substring(8, 12)
                + "-" + normalized.substring(12, 16)
                + "-" + normalized.substring(16, 20)
                + "-" + normalized.substring(20);
        return UUID.fromString(dashed);
    }

    private static void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private static boolean isExpired(PendingHandshake pendingHandshake, long now) {
        return now - pendingHandshake.createdAt() > AuthServerConfig.pendingHandshakeTtl().toMillis();
    }

    private static String connectionKey(Connection connection) {
        SocketAddress remoteAddress = connection.getRemoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            String hostAddress = address == null ? inetSocketAddress.getHostString() : address.getHostAddress();
            return hostAddress + ":" + inetSocketAddress.getPort();
        }
        return String.valueOf(remoteAddress);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record PendingHandshake(
            String username,
            java.util.UUID profileId,
            KeyPair keyPair,
            byte[] challenge,
            String serverId,
            long createdAt) {
    }

    public record ValidationResult(
            String username,
            java.util.UUID profileId,
            String serverHash,
            boolean ready,
            AuthLocalizedText failureReason,
            long createdAt) {
        public static ValidationResult readyForHasJoined(
                String username,
                java.util.UUID profileId,
                String serverHash,
                long createdAt) {
            return new ValidationResult(username, profileId, serverHash, true, null, createdAt);
        }

        public static ValidationResult invalidChallenge(String username) {
            return new ValidationResult(
                    username,
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.client_verification_failed"),
                    0L);
        }

        public static ValidationResult handshakeTimedOut(String username) {
            return new ValidationResult(
                    username,
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.handshake_timed_out"),
                    0L);
        }

        public static ValidationResult missingPending() {
            return new ValidationResult(
                    "",
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.client_verification_failed"),
                    0L);
        }
    }

    public record HasJoinedResult(
            String username,
            int statusCode,
            String body,
            boolean success,
            AuthLocalizedText failureReason,
            GameProfile profile) {
    }

    public enum PendingKeyState {
        NONE,
        PRESENT,
        EXPIRED
    }

    public sealed interface PreLoginCheckResult {
        record Online() implements PreLoginCheckResult {
        }

        record Offline(String username) implements PreLoginCheckResult {
        }

        record Disconnect(String username, java.util.UUID requestedProfileId, AuthLocalizedText reason)
                implements PreLoginCheckResult {
        }
    }
}