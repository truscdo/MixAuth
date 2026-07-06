package com.example.auth;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AuthServerEvents {
    private static final Logger LOGGER = LogUtil.getLogger();

    private AuthServerEvents() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(registerCommand());
        event.getDispatcher().register(loginCommand());
        event.getDispatcher().register(authCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerCommand() {
        return Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                .executes(context -> registerOfflineUser(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "password"),
                                        StringArgumentType.getString(context, "confirmPassword")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> loginCommand() {
        return Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(context -> loginOfflineUser(
                                context.getSource(),
                                StringArgumentType.getString(context, "password"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> authCommand() {
        return Commands.literal("auth")
                .then(Commands.literal("changepassword")
                        .then(Commands.argument("password", StringArgumentType.word())
                                .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                        .executes(context -> changeOwnOfflinePassword(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "password"),
                                                StringArgumentType.getString(context, "confirmPassword"))))))
                .then(Commands.literal("setpassword")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.word())
                                        .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                                .executes(context -> setOfflinePassword(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "uuid"),
                                                        StringArgumentType.getString(context, "password"),
                                                        StringArgumentType.getString(context,
                                                                "confirmPassword")))))))
                .then(Commands.literal("mode")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.literal("set")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(context -> setPlayerMode(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "username"),
                                                        StringArgumentType.getString(context, "mode"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("username", StringArgumentType.word())
                                        .executes(context -> removePlayerMode(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "username"))))));
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        UUID playerUuid = player.getUUID();

        OnlineAuthService.LoginMode loginMode = OnlineAuthService.consumeLoginMode(playerUuid);
        if (loginMode == null) {
            return;
        }

        String language = AuthTranslations.resolveLanguage(player);
        player.sendSystemMessage(AuthTranslations.componentForPlayer(
                player,
                "auth.message.current_login_mode",
                AuthTranslations.textForLanguage(language, switch (loginMode) {
                    case ONLINE -> "auth.login_mode.online";
                    case OFFLINE -> "auth.login_mode.offline";
                })));
        if (loginMode != OnlineAuthService.LoginMode.OFFLINE) {
            return;
        }

        boolean offlineRegistered = OfflineAuthService.isOfflineRegistered(playerUuid);
        String remoteIp = resolveRemoteIp(player);
        if (offlineRegistered && OfflineAuthService.canBypassOfflineLogin(playerUuid, remoteIp)) {
            player.sendSystemMessage(AuthTranslations.componentForPlayer(
                    player,
                    "auth.message.trusted_login_bypass",
                    OfflineAuthService.describeTrustedLoginWindow(language)));
            return;
        }

        OfflineAuthSessionService.OfflineAuthStage stage = offlineRegistered
                ? OfflineAuthSessionService.OfflineAuthStage.LOGIN
                : OfflineAuthSessionService.OfflineAuthStage.REGISTER;
        OfflineAuthSessionService.beginPendingAuth(player, stage);
    }

    private static int setPlayerMode(CommandSourceStack source, String username, String modeRaw) {
        UUID playerUuid = resolvePlayerUuidByUsername(source, username);
        if (playerUuid == null) {
            return 0;
        }

        OnlineAuthService.LoginMode mode = parseLoginMode(source, modeRaw);
        if (mode == null) {
            return 0;
        }

        try {
            KnownPlayerService.setLoginMode(playerUuid, username, mode);
            String modeText = AuthTranslations.textForSource(source, switch (mode) {
                case ONLINE -> "auth.login_mode.online";
                case OFFLINE -> "auth.login_mode.offline";
            });
            source.sendSuccess(() -> AuthTranslations.componentForSource(
                    source,
                    "auth.command.mode.set.success",
                    username,
                    playerUuid,
                    modeText), true);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.mode.set.failure",
                    describeCommandFailure(source, "setting player login mode", runtimeException)));
            return 0;
        }
    }

    private static int removePlayerMode(CommandSourceStack source, String username) {
        UUID playerUuid = resolvePlayerUuidByUsername(source, username);
        if (playerUuid == null) {
            return 0;
        }

        try {
            boolean removed = KnownPlayerService.removeKnownPlayer(playerUuid);
            if (removed) {
                source.sendSuccess(() -> AuthTranslations.componentForSource(
                        source,
                        "auth.command.mode.remove.success",
                        username,
                        playerUuid), true);
                return Command.SINGLE_SUCCESS;
            }

            source.sendFailure(
                    AuthTranslations.componentForSource(source, "auth.command.mode.remove.not_listed", username));
            return 0;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.mode.remove.failure",
                    describeCommandFailure(source, "removing player login mode", runtimeException)));
            return 0;
        }
    }

    private static OnlineAuthService.LoginMode parseLoginMode(CommandSourceStack source, String modeRaw) {
        if (modeRaw == null) {
            return null;
        }

        String normalized = modeRaw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "online" -> OnlineAuthService.LoginMode.ONLINE;
            case "offline" -> OnlineAuthService.LoginMode.OFFLINE;
            default -> {
                source.sendFailure(
                        AuthTranslations.componentForSource(source, "auth.command.mode.invalid_mode", modeRaw));
                yield null;
            }
        };
    }

    private static UUID resolvePlayerUuidByUsername(CommandSourceStack source, String username) {
        if (username == null || username.isBlank()) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.missing_username"));
            return null;
        }

        List<AuthDatabase.KnownPlayerEntry> entries = AuthDatabase.findKnownPlayersByUsername(username);
        if (entries.isEmpty()) {
            source.sendFailure(
                    AuthTranslations.componentForSource(source, "auth.command.mode.player_not_found", username));
            return null;
        }

        if (entries.size() == 1) {
            return entries.get(0).playerUuid();
        }

        // 重复用户名：显示所有匹配项，要求使用 UUID
        Component[] details = entries.stream()
                .map(e -> {
                    String modeKey = "ONLINE".equals(e.loginMode()) ? "auth.login_mode.online"
                            : "auth.login_mode.offline";
                    String modeText = AuthTranslations.textForSource(source, modeKey);
                    return Component.literal(AuthTranslations.textForSource(
                            source, "auth.command.mode.entry_format",
                            e.playerUuid(), modeText));
                })
                .toArray(Component[]::new);
        source.sendFailure(
                AuthTranslations.componentForSource(source, "auth.command.mode.duplicate_username", username));
        for (Component detail : details) {
            source.sendFailure(detail);
        }
        return null;
    }

    private static int registerOfflineUser(CommandSourceStack source, String password, String confirmPassword) {
        ServerPlayer player = getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        if (!validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = player.getUUID();
        OfflineAuthSessionService.PendingOfflineAuth pendingOfflineAuth = OfflineAuthSessionService
                .getPendingAuth(player);

        if (pendingOfflineAuth == null) {
            if (OfflineAuthService.isOfflineRegistered(playerUuid)) {
                source.sendFailure(
                        AuthTranslations.componentForSource(source, "auth.error.account_already_has_offline_password"));
                return 0;
            }
        } else if (pendingOfflineAuth.stage != OfflineAuthSessionService.OfflineAuthStage.REGISTER) {
            source.sendFailure(
                    AuthTranslations.componentForSource(source, "auth.error.account_already_registered_login"));
            OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage);
            return 0;
        }

        try {
            OfflineAuthService.registerOfflineUser(playerUuid, password);
            if (pendingOfflineAuth != null) {
                OfflineAuthService.recordTrustedOfflineLogin(playerUuid, resolveRemoteIp(player));
                OfflineAuthSessionService.completeAuthentication(player, "auth.message.register_success_auto_login");
            } else {
                source.sendSuccess(
                        () -> AuthTranslations.componentForSource(source, "auth.command.password_create.success"),
                        false);
            }
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_create.failure",
                    describeCommandFailure(source, "creating an offline password", runtimeException)));
            return 0;
        }
    }

    private static int loginOfflineUser(CommandSourceStack source, String password) {
        ServerPlayer player = getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        OfflineAuthSessionService.PendingOfflineAuth pendingOfflineAuth = OfflineAuthSessionService
                .getPendingAuth(player);
        if (pendingOfflineAuth == null) {
            source.sendFailure(
                    AuthTranslations.componentForSource(source, "auth.error.no_registration_or_login_needed"));
            return 0;
        }

        if (pendingOfflineAuth.stage != OfflineAuthSessionService.OfflineAuthStage.LOGIN) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.not_registered_register"));
            OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage);
            return 0;
        }

        if (password.isBlank()) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.password_blank"));
            return 0;
        }

        UUID playerUuid = player.getUUID();
        if (OfflineAuthService.verifyOfflinePassword(playerUuid, password)) {
            OfflineAuthService.clearOfflineLoginBlock(playerUuid);
            OfflineAuthService.recordTrustedOfflineLogin(playerUuid, resolveRemoteIp(player));
            OfflineAuthSessionService.completeAuthentication(player, "auth.message.login_success");
            return Command.SINGLE_SUCCESS;
        }

        pendingOfflineAuth.failedLoginAttempts++;
        if (pendingOfflineAuth.failedLoginAttempts >= AuthServerConfig.maxLoginAttempts()) {
            OfflineAuthService.blockOfflineLogin(playerUuid, AuthServerConfig.tempBlockMillis());
            OfflineAuthSessionService.clearPendingAuth(player);
            String language = AuthTranslations.resolveLanguage(player);
            disconnectPlayer(
                    player,
                    AuthTranslations.componentForPlayer(
                            player,
                            "auth.error.too_many_password_failures_blocked",
                            OfflineAuthService.formatDuration(language, AuthServerConfig.tempBlockMillis())));
            return 0;
        }

        int remainingAttempts = AuthServerConfig.maxLoginAttempts() - pendingOfflineAuth.failedLoginAttempts;
        pendingOfflineAuth.nextPromptAtMillis = System.currentTimeMillis()
                + OfflineAuthSessionService.promptIntervalMillis();
        source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.password_incorrect_remaining",
                remainingAttempts));
        OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage);
        return 0;
    }

    private static int changeOwnOfflinePassword(CommandSourceStack source, String password, String confirmPassword) {
        ServerPlayer player = getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        if (!validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = player.getUUID();
        if (!OfflineAuthService.isOfflineRegistered(playerUuid)) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.no_offline_password_register"));
            return 0;
        }

        try {
            OfflineAuthService.saveOfflinePassword(playerUuid, password);
            OfflineAuthService.clearTrustedOfflineLogins(playerUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_change.success",
                            OfflineAuthService.describeTrustedLoginWindow(language)),
                    false);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_change.failure",
                    describeCommandFailure(source, "changing an offline password", runtimeException)));
            return 0;
        }
    }

    private static int setOfflinePassword(CommandSourceStack source, String rawUuid, String password,
            String confirmPassword) {
        if (!validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = parseUuid(source, rawUuid);
        if (playerUuid == null) {
            return 0;
        }

        try {
            OfflineAuthService.saveOfflinePassword(playerUuid, password);
            OfflineAuthService.clearTrustedOfflineLogins(playerUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_set.success",
                            playerUuid,
                            OfflineAuthService.describeTrustedLoginWindow(language)),
                    true);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_set.failure",
                    describeCommandFailure(source, "setting an offline password", runtimeException)));
            return 0;
        }
    }

    private static boolean validatePasswordPair(CommandSourceStack source, String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.password_mismatch"));
            return false;
        }

        PasswordPolicyValidator.ValidationResult policyResult = PasswordPolicyValidator.validate(password);
        if (!policyResult.valid()) {
            for (PasswordPolicyValidator.Error error : policyResult.errors()) {
                source.sendFailure(switch (error) {
                    case TOO_SHORT -> AuthTranslations.componentForSource(
                            source, "auth.error.password_policy.too_short", AuthServerConfig.minPasswordLength());
                    case TOO_LONG -> AuthTranslations.componentForSource(
                            source, "auth.error.password_policy.too_long", AuthServerConfig.maxPasswordLength());
                    case BLACKLISTED -> AuthTranslations.componentForSource(
                            source, "auth.error.password_policy.blacklisted");
                });
            }
            return false;
        }

        return true;
    }

    private static UUID parseUuid(CommandSourceStack source, String rawUuid) {
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException illegalArgumentException) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.command.uuid.invalid", rawUuid));
            return null;
        }
    }

    private static ServerPlayer getCommandPlayer(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.player_only_command"));
            return null;
        }
        return player;
    }

    private static void disconnectPlayer(ServerPlayer player, Component reason) {
        player.connection.disconnect(reason);
    }

    private static String describeCommandFailure(CommandSourceStack source, String operation,
            RuntimeException runtimeException) {
        LOGGER.error("Auth command failed while {}", operation, runtimeException);
        return AuthTranslations.textForSource(source, "auth.command.failure.internal");
    }

    private static String resolveRemoteIp(ServerPlayer player) {
        SocketAddress remoteAddress = player.connection.getRemoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }

        return remoteAddress == null ? null : String.valueOf(remoteAddress);
    }
}