package io.github.truscdo.mixauth.mixin;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import io.github.truscdo.mixauth.offline.PlayerIdentityService;
import io.github.truscdo.mixauth.db.OfflineClientAliasDao;
import io.github.truscdo.mixauth.localization.AuthLocalizedText;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import io.github.truscdo.mixauth.login.LoginContexts;
import io.github.truscdo.mixauth.login.LoginHandoffService;
import io.github.truscdo.mixauth.validation.MojangClient;
import io.github.truscdo.mixauth.validation.MojangClient.HasJoinedResult;
import io.github.truscdo.mixauth.validation.MojangClient.PreLoginCheckResult;
import io.github.truscdo.mixauth.validation.OnlineHandshakeValidationService;
import io.github.truscdo.mixauth.validation.OnlineHandshakeValidationService.ValidationResult;
import io.github.truscdo.mixauth.validation.OfflineModeDetector;
import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.LogUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.CryptException;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginPacketListenerImplMixin {
    @Unique
    private static final Logger AUTH_LOGGER = LogUtil.getLogger();
    @Unique
    private volatile boolean auth$disconnected;
    @Unique
    private volatile UUID auth$requestedProfileId;
    @Unique
    private volatile GameProfile auth$canonicalOfflineProfile;

    @Shadow
    @Final
    Connection connection;

    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    private String requestedUsername;

    @Shadow
    @Final
    private String serverId;

    @Shadow
    public abstract void disconnect(Component reason);

    @Invoker("startClientVerification")
    abstract void auth$startClientVerification(GameProfile profile);

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void auth$interceptHello(ServerboundHelloPacket packet, CallbackInfo callbackInfo) {
        if (!OnlineHandshakeValidationService.shouldIntercept(this.server)) {
            return;
        }

        this.requestedUsername = packet.name();
        this.auth$requestedProfileId = packet.profileId();
        callbackInfo.cancel();

        String username = packet.name();
        UUID clientUuid = packet.profileId();
        if (username == null || username.isBlank() || clientUuid == null) {
            AUTH_LOGGER.warn("Invalid Login Start identity, disconnecting client: username={}, clientUuid={}",
                    username, clientUuid);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.missing_username"));
            return;
        }

        try {
            // 一个 Login Start 只创建一次 canonical profile，所有 OfflineGate 路径复用它。
            this.auth$canonicalOfflineProfile = PlayerIdentityService.createOfflineProfile(username);
        } catch (RuntimeException runtimeException) {
            AUTH_LOGGER.warn("Failed to create canonical offline profile for {}", username, runtimeException);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.mojang_data_error"));
            return;
        }
        UUID canonicalOfflineUuid = ProfileCompat.uuid(this.auth$canonicalOfflineProfile);

        // 1. 只查询 clientUuid 对应的完整 known_players 记录。
        var knownPlayer = KnownPlayerService.findKnownPlayer(clientUuid);
        if (knownPlayer != null) {
            OnlineAuthService.LoginMode knownMode = OnlineAuthService.LoginMode.valueOf(knownPlayer.loginMode());
            AUTH_LOGGER.info("Known player {} routed by client UUID login mode: {}", username, knownMode);
            if (knownMode == OnlineAuthService.LoginMode.ONLINE) {
                // ONLINE 优先级最高；后续握手/hasJoined 失败时只拒绝，不降级。
                auth$beginOnlineHandshake(packet);
                return;
            }
            if (clientUuid.equals(canonicalOfflineUuid)) {
                auth$enterOfflineGate();
                return;
            }
        }

        // 2. 查询精确的 canonicalOfflineUuid + clientUuid alias。
        OfflineClientAliasDao.OfflineClientAliasEntry alias = KnownPlayerService.findOfflineClientAlias(
                canonicalOfflineUuid, clientUuid);
        if (alias != null && username.equalsIgnoreCase(alias.username())) {
            AUTH_LOGGER.info("Offline player {} routed by client UUID alias", username);
            auth$enterOfflineGate();
            return;
        }

        // 3. 离线模式 UUID 本地检测：已确认的离线 UUID 跳过 Mojang API。
        OfflineModeDetector.CheckResult detection = OfflineModeDetector.check(username, clientUuid);
        if (detection.isConfirmed()) {
            AUTH_LOGGER.info("Offline UUID confirmed locally for {} ({}), skipping Mojang pre-check",
                    username, detection.type());
            auth$enterOfflineGate();
            return;
        }

        // 4. 未知身份执行 Mojang 预检查；API 异常由回调路径直接拒绝。
        MojangClient.asyncPreLoginCheck(packet.name(), packet.profileId())
                .whenComplete((result, throwable) -> this.server.execute(() -> {
                    if (throwable != null) {
                        AUTH_LOGGER.warn("auth precheck rejected for {}: {}",
                                packet.name(), throwable.toString());
                        if (!auth$disconnected) {
                            auth$disconnectBeforeHandshake(
                                    packet.name(),
                                    packet.profileId(),
                                    AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
                        }
                        return;
                    }

                    if (auth$disconnected) {
                        AUTH_LOGGER.debug("auth precheck result ignored — client already disconnected");
                        return;
                    }

                    switch (result) {
                        case PreLoginCheckResult.Online r -> auth$beginOnlineHandshake(packet);
                        case PreLoginCheckResult.Offline r -> {
                            AUTH_LOGGER.info("auth precheck routing {} to offline login", r.username());
                            auth$enterOfflineGate();
                        }
                        case PreLoginCheckResult.Disconnect r -> {
                            AUTH_LOGGER.warn("auth precheck disconnect for {}: {}", r.username(), r.reason());
                            auth$disconnectBeforeHandshake(
                                    r.username(), r.requestedProfileId(), r.reason());
                        }
                    }
                }));
    }

    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void auth$interceptKey(ServerboundKeyPacket packet, CallbackInfo callbackInfo) {
        // 正版服：vanilla 全程自管，必须放行，否则会破坏 vanilla 在线验证
        if (!OnlineHandshakeValidationService.shouldIntercept(this.server)) {
            return;
        }

        // 离线服：key 包一律由本 Mod 接管，vanilla handleKey 永不执行
        callbackInfo.cancel();

        OnlineHandshakeValidationService.PendingKeyState pendingKeyState = OnlineHandshakeValidationService
                .pendingKeyState(this.connection);
        if (pendingKeyState == OnlineHandshakeValidationService.PendingKeyState.NONE) {
            // 本 Mod 未发起本次握手却收到 key 包：协议违规，显式断开（fail-closed）
            OnlineHandshakeValidationService.clear(this.connection);
            AUTH_LOGGER.warn("auth unexpected key packet from {}, no pending handshake", this.requestedUsername);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.unexpected_key_packet"));
            return;
        }

        try {
            ValidationResult validationResult = OnlineHandshakeValidationService.handleKey(this.connection, packet);
            if (!validationResult.ready()) {
                OnlineHandshakeValidationService.clear(this.connection);
                AUTH_LOGGER.warn("auth key validation failed for {}: {}",
                        this.requestedUsername, auth$failureText(validationResult.failureReason()));
                this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                        "auth.validation.failed_with_reason",
                        auth$failureText(validationResult.failureReason())));
                return;
            }

            OnlineHandshakeValidationService.requestHasJoined(validationResult)
                    .whenComplete(
                            (result, throwable) -> this.server.execute(() -> auth$finishValidation(result, throwable)));
        } catch (CryptException cryptException) {
            OnlineHandshakeValidationService.clear(this.connection);
            AUTH_LOGGER.error("Failed to process online authentication key response", cryptException);
            this.disconnect(AuthTranslations
                    .componentForConfiguredLanguage("auth.validation.reason.client_verification_failed"));
        } catch (RuntimeException runtimeException) {
            OnlineHandshakeValidationService.clear(this.connection);
            AUTH_LOGGER.error("Unexpected failure while processing online authentication key response",
                    runtimeException);
            this.disconnect(
                    AuthTranslations.componentForConfiguredLanguage("auth.validation.reason.server_internal_error"));
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void auth$cleanupOnDisconnect(DisconnectionDetails details, CallbackInfo callbackInfo) {
        this.auth$disconnected = true;
        this.auth$requestedProfileId = null;
        this.auth$canonicalOfflineProfile = null;
        OnlineHandshakeValidationService.clear(this.connection);
        LoginContexts.clear(this.connection);
    }

    @Unique
    private void auth$finishValidation(HasJoinedResult result, Throwable throwable) {
        OnlineHandshakeValidationService.clear(this.connection);

        if (this.auth$disconnected) {
            AUTH_LOGGER.debug("auth finishValidation skipped — client already disconnected");
            return;
        }

        if (throwable != null) {
            AUTH_LOGGER.error("Online authentication request completed exceptionally", throwable);
            auth$disconnectAfterOnlineValidationFailure(
                    this.requestedUsername,
                    this.auth$requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
            return;
        }

        if (result == null) {
            auth$disconnectAfterOnlineValidationFailure(
                    this.requestedUsername,
                    this.auth$requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.mojang_api_unavailable"));
            return;
        }

        if (result.success()) {
            GameProfile profile = result.profile();
            if (profile == null) {
                auth$disconnectAfterOnlineValidationFailure(
                        result.username(),
                        this.auth$requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.mojang_data_error"));
                return;
            }

            AUTH_LOGGER.info("auth validation continuing online login for {}", ProfileCompat.name(profile));
            if (!auth$completeLoginHandoff(OnlineAuthService.LoginMode.ONLINE, profile)) {
                return;
            }
            this.auth$startClientVerification(profile);
            return;
        }

        auth$disconnectAfterOnlineValidationFailure(result.username(), this.auth$requestedProfileId,
                result.failureReason());
    }

    @Unique
    private void auth$enterOfflineGate() {
        GameProfile canonicalOfflineProfile = this.auth$canonicalOfflineProfile;
        UUID clientUuid = this.auth$requestedProfileId;
        if (canonicalOfflineProfile == null || clientUuid == null) {
            AUTH_LOGGER.warn("auth OfflineGate called without canonical profile, disconnecting");
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.server_internal_error"));
            return;
        }

        UUID canonicalOfflineUuid = ProfileCompat.uuid(canonicalOfflineProfile);
        if (canonicalOfflineUuid == null) {
            AUTH_LOGGER.warn("Canonical offline profile has no UUID, disconnecting {}",
                    ProfileCompat.name(canonicalOfflineProfile));
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.validation.reason.server_internal_error"));
            return;
        }
        long remainingBlockedMillis = OfflineAuthService.getOfflineLoginBlockRemainingMillis(canonicalOfflineUuid);
        if (remainingBlockedMillis > 0L) {
            AUTH_LOGGER.info("OfflineGate rejected temporarily blocked player {}",
                    ProfileCompat.name(canonicalOfflineProfile));
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.error.offline_temporarily_blocked",
                    OfflineAuthService.formatDuration(AuthServerConfig.defaultLanguage(), remainingBlockedMillis)));
            return;
        }

        AUTH_LOGGER.info("auth OfflineGate accepted {}", ProfileCompat.name(canonicalOfflineProfile));
        if (!auth$completeLoginHandoff(OnlineAuthService.LoginMode.OFFLINE, canonicalOfflineProfile)) {
            return;
        }
        this.auth$startClientVerification(canonicalOfflineProfile);
    }

    @Unique
    private boolean auth$completeLoginHandoff(
            OnlineAuthService.LoginMode mode,
            GameProfile authenticatedProfile) {
        try {
            boolean published = mode == OnlineAuthService.LoginMode.ONLINE
                    ? LoginHandoffService.completeOnlineLogin(
                            this.connection, this.auth$requestedProfileId, authenticatedProfile)
                    : LoginHandoffService.completeOfflineLogin(
                            this.connection, this.auth$requestedProfileId, authenticatedProfile);
            if (published) {
                return true;
            }
            AUTH_LOGGER.error(
                    "Refused duplicate login context for {} from {}",
                    ProfileCompat.name(authenticatedProfile),
                    this.connection.getRemoteAddress());
        } catch (RuntimeException runtimeException) {
            AUTH_LOGGER.error(
                    "Failed to complete login handoff for {} from {}",
                    ProfileCompat.name(authenticatedProfile),
                    this.connection.getRemoteAddress(),
                    runtimeException);
        }
        this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                "auth.validation.reason.server_internal_error"));
        return false;
    }

    @Unique
    private void auth$disconnectAfterOnlineValidationFailure(String username, UUID requestedProfileId,
            AuthLocalizedText failureReason) {
        String suffix = auth$failureText(failureReason);
        AUTH_LOGGER.warn(
                "auth validation failed after online handshake for {}. Disconnecting client instead of offline fallback: {}",
                username, suffix);
        auth$disconnectBeforeHandshake(username, requestedProfileId, suffix);
    }

    @Unique
    private void auth$beginOnlineHandshake(ServerboundHelloPacket packet) {
        // 密钥对由后台预生成/生成，绝不阻塞主线程；报文就绪后再回主线程发送。
        OnlineHandshakeValidationService.beginValidationAsync(this.connection, packet, this.serverId)
                .whenComplete(
                        (helloPacket, throwable) -> this.auth$scheduleDeferredHello(packet, helloPacket, throwable));
    }

    @Unique
    private void auth$scheduleDeferredHello(ServerboundHelloPacket packet, ClientboundHelloPacket helloPacket,
            Throwable throwable) {
        try {
            this.server.execute(() -> {
                if (this.auth$disconnected) {
                    AUTH_LOGGER.debug("auth deferred online handshake skipped — client already disconnected");
                    return;
                }
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                            && completionException.getCause() != null
                                    ? completionException.getCause()
                                    : throwable;
                    AUTH_LOGGER.error("Failed to start online authentication handshake for {}", packet.name(), cause);
                    this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                            "auth.validation.reason.server_internal_error"));
                    return;
                }
                this.connection.send(helloPacket);
            });
        } catch (RejectedExecutionException rejectedExecutionException) {
            AUTH_LOGGER.debug("Server executor rejected deferred online handshake for {} (server shutting down?)",
                    packet.name());
        }
    }

    @Unique
    private void auth$disconnectBeforeHandshake(String username, UUID requestedProfileId,
            AuthLocalizedText failureReason) {
        auth$disconnectBeforeHandshake(username, requestedProfileId, auth$failureText(failureReason));
    }

    @Unique
    private void auth$disconnectBeforeHandshake(String username, UUID requestedProfileId, String failureReason) {
        this.disconnect(
                AuthTranslations.componentForConfiguredLanguage("auth.validation.failed_with_reason", failureReason));
    }

    @Unique
    private static String auth$failureText(AuthLocalizedText failureReason) {
        if (failureReason == null || failureReason.isMissing()) {
            return AuthTranslations.textForConfiguredLanguage("auth.validation.reason.server_internal_error");
        }

        return failureReason.textForConfiguredLanguage();
    }

}
