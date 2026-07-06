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
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class OnlineHandshakeValidationService {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final String PROFILE_LOOKUP_BY_NAME_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final Map<String, PendingHandshake> PENDING = new ConcurrentHashMap<>();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OnlineHandshakeValidationService() {
    }

    public static boolean shouldIntercept(MinecraftServer server) {
        return !server.usesAuthentication();
    }

    public static CompletableFuture<PreLoginCheckResult> requestPreLoginCheck(ServerboundHelloPacket packet) {
        return CompletableFuture.supplyAsync(() -> doRequestPreLoginCheck(packet.name(), packet.profileId()));
    }

    public static ClientboundHelloPacket beginValidation(
            MinecraftServer server,
            Connection connection,
            ServerboundHelloPacket packet,
            String serverId
    ) throws CryptException {
        cleanupExpiredEntries();

        String key = connectionKey(connection);
        KeyPair keyPair = Crypt.generateKeyPair();
        byte[] challenge = new byte[4];
        SECURE_RANDOM.nextBytes(challenge);

        PendingHandshake pendingHandshake = new PendingHandshake(
                packet.name(),
                packet.profileId(),
                keyPair,
                challenge,
                serverId,
                System.currentTimeMillis()
        );
        PENDING.put(key, pendingHandshake);

        PublicKey publicKey = keyPair.getPublic();
        return new ClientboundHelloPacket(serverId, publicKey.getEncoded(), challenge, true);
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
            ServerboundKeyPacket packet
    ) throws CryptException {
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
                pendingHandshake.keyPair().getPrivate()
        );
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
                        secretKey
                )
        ).toString(16);

        return ValidationResult.readyForHasJoined(
                pendingHandshake.username(),
                pendingHandshake.profileId(),
                serverHash,
                pendingHandshake.createdAt()
        );
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

    @FunctionalInterface
    private interface HttpErrorHandler<T> {
        T apply(AuthLocalizedText reason);
    }

    private static <T> T httpGet(String url, Function<HttpResponse<String>, T> successHandler,
                                 String errorContext,
                                 HttpErrorHandler<T> interruptedHandler,
                                 HttpErrorHandler<T> ioFailureHandler) {
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
            return interruptedHandler.apply(AuthLocalizedText.of("auth.validation.reason.http_interrupted"));
        } catch (IOException e) {
            LOGGER.error("I/O failure while {}", errorContext, e);
            return ioFailureHandler.apply(AuthLocalizedText.of("auth.validation.reason.http_io_failure"));
        }
    }

    private static PreLoginCheckResult doRequestPreLoginCheck(String username, UUID requestedProfileId) {
        if (username == null || username.isBlank()) {
            return PreLoginCheckResult.disconnect(
                    username,
                    requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.missing_login_start_username")
            );
        }

        String url = PROFILE_LOOKUP_BY_NAME_URL + encode(username);
        return httpGet(url, response -> handlePreLoginResponse(username, requestedProfileId, response),
                "looking up Mojang profile for " + username,
                reason -> PreLoginCheckResult.disconnect(username, requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.profile_lookup_interrupted")),
                reason -> PreLoginCheckResult.disconnect(username, requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.profile_lookup_io_failure")));
    }

    private static PreLoginCheckResult handlePreLoginResponse(String username, UUID requestedProfileId,
                                                               HttpResponse<String> response) {
        int statusCode = response.statusCode();
        String body = response.body();

        if (statusCode == 404 || statusCode == 204) {
            return PreLoginCheckResult.offline(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.no_mojang_profile_for_username"));
        }
        if (statusCode == 429) {
            return PreLoginCheckResult.disconnect(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.profile_lookup_rate_limited"));
        }
        if (statusCode >= 500) {
            return PreLoginCheckResult.disconnect(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.profile_lookup_http", statusCode));
        }
        if (statusCode != 200) {
            return PreLoginCheckResult.disconnect(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.profile_lookup_unexpected_http", statusCode));
        }

        GameProfile profile = parseGameProfile(body, username);
        return verifyProfileMatch(username, requestedProfileId, profile);
    }

    private static PreLoginCheckResult verifyProfileMatch(String username, UUID requestedProfileId,
                                                           GameProfile profile) {
        if (profile == null || profile.getId() == null) {
            return PreLoginCheckResult.disconnect(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.profile_lookup_malformed_profile"));
        }
        if (!requestedProfileId.equals(profile.getId())) {
            return PreLoginCheckResult.offline(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.login_start_uuid_mismatch"));
        }
        String resolvedName = profile.getName();
        if (resolvedName == null || !resolvedName.equalsIgnoreCase(username)) {
            return PreLoginCheckResult.offline(
                    username, requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.login_start_username_mismatch"));
        }
        return PreLoginCheckResult.online(username, requestedProfileId);
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

            if (success && profile == null) {
                LOGGER.error("Session server returned malformed profile data for {}", username);
                return new HasJoinedResult(username, statusCode, body, false,
                        AuthLocalizedText.of("auth.validation.reason.session_profile_malformed"), null);
            }

            return new HasJoinedResult(username, statusCode, body, success, null, profile);
        }, "requesting session validation for " + username,
                reason -> new HasJoinedResult(username, 0, "", false,
                        AuthLocalizedText.of("auth.validation.reason.session_validation_interrupted"), null),
                reason -> new HasJoinedResult(username, 0, "", false,
                        AuthLocalizedText.of("auth.validation.reason.session_validation_io_failure"), null));
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
            long createdAt
    ) {
    }

    public record ValidationResult(
            String username,
            java.util.UUID profileId,
            String serverHash,
            boolean ready,
            AuthLocalizedText failureReason,
            long createdAt
    ) {
        public static ValidationResult readyForHasJoined(
                String username,
                java.util.UUID profileId,
                String serverHash,
                long createdAt
        ) {
            return new ValidationResult(username, profileId, serverHash, true, null, createdAt);
        }

        public static ValidationResult invalidChallenge(String username) {
            return new ValidationResult(
                    username,
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.challenge_validation_failed"),
                    0L
            );
        }

                public static ValidationResult handshakeTimedOut(String username) {
                    return new ValidationResult(
                        username,
                        null,
                        "",
                        false,
                        AuthLocalizedText.of("auth.validation.reason.handshake_timed_out"),
                        0L
                    );
                }

        public static ValidationResult missingPending() {
            return new ValidationResult(
                    "",
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.missing_pending_state"),
                    0L
            );
        }
    }

    public record HasJoinedResult(
            String username,
            int statusCode,
            String body,
            boolean success,
            AuthLocalizedText failureReason,
            GameProfile profile
    ) {
    }

    public enum PendingKeyState {
        NONE,
        PRESENT,
        EXPIRED
    }

    public record PreLoginCheckResult(
            String username,
            java.util.UUID requestedProfileId,
            Action action,
            AuthLocalizedText failureReason
    ) {
        public static PreLoginCheckResult online(String username, java.util.UUID requestedProfileId) {
            return new PreLoginCheckResult(username, requestedProfileId, Action.ONLINE, null);
        }

        public static PreLoginCheckResult offline(
                String username,
                java.util.UUID requestedProfileId,
                AuthLocalizedText failureReason
        ) {
            return new PreLoginCheckResult(username, requestedProfileId, Action.OFFLINE, failureReason);
        }

        public static PreLoginCheckResult disconnect(
                String username,
                java.util.UUID requestedProfileId,
                AuthLocalizedText failureReason
        ) {
            return new PreLoginCheckResult(username, requestedProfileId, Action.DISCONNECT, failureReason);
        }

        public enum Action {
            ONLINE,
            OFFLINE,
            DISCONNECT
        }
    }
}