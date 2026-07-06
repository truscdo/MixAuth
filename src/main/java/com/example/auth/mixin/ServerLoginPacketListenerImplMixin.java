package com.example.auth.mixin;

import com.example.auth.AuthLocalizedText;
import com.example.auth.KnownPlayerService;
import com.example.auth.OfflineAuthService;
import com.example.auth.OnlineAuthService;
import com.example.auth.PlayerIdentityService;
import com.example.auth.AuthServerConfig;
import com.example.auth.AuthTranslations;
import com.example.auth.validation.OnlineHandshakeValidationService;
import com.example.auth.validation.OnlineHandshakeValidationService.HasJoinedResult;
import com.example.auth.validation.OnlineHandshakeValidationService.PreLoginCheckResult;
import com.example.auth.validation.OnlineHandshakeValidationService.ValidationResult;
import com.example.auth.validation.OfflineModeDetector;
import com.mojang.authlib.GameProfile;
import com.example.auth.LogUtil;
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

import java.lang.reflect.Field;
import java.util.UUID;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginPacketListenerImplMixin {
    @Unique
    private static final Logger AUTH_LOGGER = LogUtil.getLogger();
    @Unique
    private static final Field AUTH_STATE_FIELD = resolveStateField();
    @Unique
    private volatile boolean auth$disconnected;
    @Unique
    private volatile UUID auth$requestedProfileId;

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

        // 1. 封禁检查
        UUID blockCheckUuid = PlayerIdentityService.resolvePlayerUuid(packet.name());
        long remainingBlockedMillis = OfflineAuthService.getOfflineLoginBlockRemainingMillis(blockCheckUuid);
        if (remainingBlockedMillis > 0L) {
            this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                    "auth.error.offline_temporarily_blocked",
                    OfflineAuthService.formatDuration(AuthServerConfig.defaultLanguage(), remainingBlockedMillis)
            ));
            return;
        }

        // 2. 检查已知玩家名单，跳过预检查
        String username = packet.name();
        UUID clientUuid = packet.profileId();
        OnlineAuthService.LoginMode knownMode = KnownPlayerService.resolveLoginMode(clientUuid, username);
        if (knownMode != null) {
            AUTH_LOGGER.info("Known player {} routed by login mode: {}", username, knownMode);
            if (knownMode == OnlineAuthService.LoginMode.ONLINE) {
                auth$beginOnlineHandshake(packet);
            } else {
                UUID playerUuid = PlayerIdentityService.resolvePlayerUuid(username);
                OfflineAuthService.recordOfflineLogin(playerUuid, username);
                this.auth$startClientVerification(PlayerIdentityService.createOfflineProfile(username));
            }
            return;
        }

        // 2.5 离线模式 UUID 本地检测：已确认的离线 UUID 跳过 Mojang API
        OfflineModeDetector.CheckResult detection = OfflineModeDetector.check(username, clientUuid);
        if (detection.isConfirmed()) {
            AUTH_LOGGER.info("Offline UUID confirmed locally for {} ({}), skipping Mojang pre-check",
                    username, detection.type());
            UUID playerUuid = PlayerIdentityService.resolvePlayerUuid(username);
            OfflineAuthService.recordOfflineLogin(playerUuid, username);
            this.auth$startClientVerification(PlayerIdentityService.createOfflineProfile(username));
            return;
        }

        // 3. 未知玩家，执行预检查
        OnlineHandshakeValidationService.requestPreLoginCheck(packet)
                .whenComplete((result, throwable) -> this.server.execute(() -> auth$finishPreLoginCheck(packet, result, throwable)));
    }

    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void auth$interceptKey(ServerboundKeyPacket packet, CallbackInfo callbackInfo) {
        OnlineHandshakeValidationService.PendingKeyState pendingKeyState = OnlineHandshakeValidationService.pendingKeyState(this.connection);
        if (pendingKeyState == OnlineHandshakeValidationService.PendingKeyState.NONE) {
            return;
        }

        callbackInfo.cancel();

        try {
            ValidationResult validationResult = OnlineHandshakeValidationService.handleKey(this.connection, packet);
            if (!validationResult.ready()) {
                OnlineHandshakeValidationService.clear(this.connection);
                this.disconnect(AuthTranslations.componentForConfiguredLanguage(
                        "auth.validation.failed_with_reason",
                        auth$failureText(validationResult.failureReason())
                ));
                return;
            }

            OnlineHandshakeValidationService.requestHasJoined(validationResult)
                    .whenComplete((result, throwable) -> this.server.execute(() -> auth$finishValidation(result, throwable)));
        } catch (CryptException cryptException) {
            OnlineHandshakeValidationService.clear(this.connection);
            AUTH_LOGGER.error("Failed to process online authentication key response", cryptException);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage("auth.validation.cryptographic_failure"));
        } catch (RuntimeException runtimeException) {
            OnlineHandshakeValidationService.clear(this.connection);
            AUTH_LOGGER.error("Unexpected failure while processing online authentication key response", runtimeException);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage("auth.validation.unexpected_failure"));
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void auth$cleanupOnDisconnect(DisconnectionDetails details, CallbackInfo callbackInfo) {
        this.auth$disconnected = true;
        this.auth$requestedProfileId = null;
        OnlineHandshakeValidationService.clear(this.connection);
    }

    @Unique
    private void auth$finishPreLoginCheck(ServerboundHelloPacket packet, PreLoginCheckResult result, Throwable throwable) {
        if (this.auth$disconnected) {
            return;
        }

        if (throwable != null) {
            AUTH_LOGGER.error("Mojang profile precheck completed exceptionally for {}", packet.name(), throwable);
            auth$disconnectBeforeHandshake(packet.name(), packet.profileId(), AuthLocalizedText.of("auth.validation.reason.precheck_async_failure"));
            return;
        }

        if (result == null) {
            auth$disconnectBeforeHandshake(packet.name(), packet.profileId(), AuthLocalizedText.of("auth.validation.reason.missing_precheck_result"));
            return;
        }

        switch (result.action()) {
            case ONLINE -> auth$beginOnlineHandshake(packet);
            case OFFLINE -> {
                AUTH_LOGGER.info("auth precheck routing {} to offline login: {}", result.username(), auth$failureText(result.failureReason()));
                auth$finishOfflineOrReject(result.username(), result.requestedProfileId(), result.failureReason());
            }
            case DISCONNECT -> auth$disconnectBeforeHandshake(result.username(), result.requestedProfileId(), result.failureReason());
        }
    }

    @Unique
    private void auth$finishValidation(HasJoinedResult result, Throwable throwable) {
        OnlineHandshakeValidationService.clear(this.connection);

        if (this.auth$disconnected) {
            return;
        }

        if (throwable != null) {
            AUTH_LOGGER.error("Online authentication request completed exceptionally", throwable);
            auth$disconnectAfterOnlineValidationFailure(
                    this.requestedUsername,
                    this.auth$requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.session_async_failure")
            );
            return;
        }

        if (result == null) {
            auth$disconnectAfterOnlineValidationFailure(
                    this.requestedUsername,
                    this.auth$requestedProfileId,
                    AuthLocalizedText.of("auth.validation.reason.missing_session_result")
            );
            return;
        }

        if (result.success()) {
            GameProfile profile = result.profile();
            if (profile == null) {
                auth$disconnectAfterOnlineValidationFailure(
                        result.username(),
                        this.auth$requestedProfileId,
                        AuthLocalizedText.of("auth.validation.reason.missing_authenticated_profile")
                );
                return;
            }

            AUTH_LOGGER.info("auth validation continuing online login for {}", profile.getName());
            OnlineAuthService.recordOnlineLogin(profile);
            this.auth$startClientVerification(profile);
            return;
        }

        auth$disconnectAfterOnlineValidationFailure(result.username(), this.auth$requestedProfileId, result.failureReason());
    }

    @Unique
    private void auth$finishOfflineOrReject(String username, UUID requestedProfileId, AuthLocalizedText failureReason) {
        if (username == null || username.isBlank()) {
            this.disconnect(AuthTranslations.componentForConfiguredLanguage("auth.validation.failed_before_username"));
            return;
        }

        AUTH_LOGGER.info("auth validation falling back to offline login for {}", username);
        UUID playerUuid = PlayerIdentityService.resolvePlayerUuid(username);
        OfflineAuthService.recordOfflineLogin(playerUuid, username);
        this.auth$startClientVerification(PlayerIdentityService.createOfflineProfile(username));
    }

    @Unique
    private void auth$disconnectAfterOnlineValidationFailure(String username, UUID requestedProfileId, AuthLocalizedText failureReason) {
        String suffix = auth$failureText(failureReason);
        AUTH_LOGGER.warn("auth validation failed after online handshake for {}. Disconnecting client instead of offline fallback: {}", username, suffix);
        auth$disconnectBeforeHandshake(username, requestedProfileId, suffix);
    }

    @Unique
    private void auth$beginOnlineHandshake(ServerboundHelloPacket packet) {
        try {
            auth$setState("KEY");

            ClientboundHelloPacket helloPacket = OnlineHandshakeValidationService.beginValidation(
                    this.server,
                    this.connection,
                    packet,
                    this.serverId
            );
            this.connection.send(helloPacket);
        } catch (CryptException cryptException) {
            AUTH_LOGGER.error("Failed to start online authentication handshake for {}", packet.name(), cryptException);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage("auth.validation.failed_send_encryption_request"));
        } catch (RuntimeException runtimeException) {
            AUTH_LOGGER.error("Unexpected failure before online authentication key exchange for {}", packet.name(), runtimeException);
            this.disconnect(AuthTranslations.componentForConfiguredLanguage("auth.validation.failed_before_key_exchange"));
        }
    }

    @Unique
    private void auth$disconnectBeforeHandshake(String username, UUID requestedProfileId, AuthLocalizedText failureReason) {
        auth$disconnectBeforeHandshake(username, requestedProfileId, auth$failureText(failureReason));
    }

    @Unique
    private void auth$disconnectBeforeHandshake(String username, UUID requestedProfileId, String failureReason) {
        this.disconnect(AuthTranslations.componentForConfiguredLanguage("auth.validation.failed_with_reason", failureReason));
    }

    @Unique
    private static String auth$failureText(AuthLocalizedText failureReason) {
        if (failureReason == null || failureReason.isMissing()) {
            return AuthTranslations.textForConfiguredLanguage("auth.validation.online_validation_failed");
        }

        return failureReason.textForConfiguredLanguage();
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void auth$setState(String stateName) {
        try {
            Class<? extends Enum> enumClass = (Class<? extends Enum>) AUTH_STATE_FIELD.getType().asSubclass(Enum.class);
            Object enumValue = Enum.valueOf(enumClass, stateName);
            AUTH_STATE_FIELD.set(this, enumValue);
        } catch (IllegalAccessException illegalAccessException) {
            throw new IllegalStateException("Failed to set login state to " + stateName, illegalAccessException);
        }
    }

    @Unique
    private static Field resolveStateField() {
        try {
            Field field = ServerLoginPacketListenerImpl.class.getDeclaredField("state");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException noSuchFieldException) {
            throw new IllegalStateException("Failed to locate ServerLoginPacketListenerImpl.state", noSuchFieldException);
        }
    }
}