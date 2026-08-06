package io.github.truscdo.mixauth.validation;

import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.AuthLocalizedText;
import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.LogUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Mojang API 网络层。共享单个 {@link HttpClient} 以复用 TCP/TLS 连接，
 * 提供正版 profile 预检查与 hasJoined 会话校验。
 */
public final class MojangClient {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final String PROFILE_LOOKUP_BY_NAME_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    /** 共享 HttpClient：连接复用，避免每次请求重建。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(AuthServerConfig.mojangConnectTimeout())
            .build();

    private MojangClient() {
    }

    public static CompletableFuture<HasJoinedResult> requestHasJoined(String username, String serverHash) {
        return CompletableFuture.supplyAsync(() -> doRequestHasJoined(username, serverHash));
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
            HttpResponse<String> response = HTTP_CLIENT.send(request,
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

            GameProfile profile = MojangProfileParser.parseGameProfile(body, username);
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

    private static HasJoinedResult doRequestHasJoined(String username, String serverHash) {
        String url = HAS_JOINED_URL + "?username=" + encode(username) + "&serverId=" + encode(serverHash);

        return httpGet(url, response -> {
            int statusCode = response.statusCode();
            String body = response.body();
            boolean success = statusCode == 200;
            GameProfile profile = success ? MojangProfileParser.parseGameProfile(body, username) : null;

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
            HttpResponse<String> response = HTTP_CLIENT.send(request,
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record HasJoinedResult(
            String username,
            int statusCode,
            String body,
            boolean success,
            AuthLocalizedText failureReason,
            GameProfile profile) {
    }

    public sealed interface PreLoginCheckResult {
        record Online() implements PreLoginCheckResult {
        }

        record Offline(String username) implements PreLoginCheckResult {
        }

        record Disconnect(String username, UUID requestedProfileId, AuthLocalizedText reason)
                implements PreLoginCheckResult {
        }
    }
}
