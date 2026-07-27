package io.github.truscdo.mixauth;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
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

    private static final SuggestionProvider<CommandSourceStack> KNOWN_PLAYERS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (remaining.isEmpty()) {
            return Suggestions.empty();
        }
        List<AuthDatabase.KnownPlayerEntry> entries = AuthDatabase.findKnownPlayersByPrefix(remaining, 20);
        if (entries == null || entries.isEmpty()) {
            return Suggestions.empty();
        }
        for (var entry : entries) {
            String tooltip = entry.username() + " (" + entry.playerUuid() + ")";
            Component tooltipComponent = Component.literal(tooltip);
            String usernameLower = entry.username().toLowerCase(Locale.ROOT);
            String uuidStr = entry.playerUuid().toString().toLowerCase(Locale.ROOT);
            if (usernameLower.startsWith(remaining)) {
                builder.suggest(entry.username(), tooltipComponent);
            } else if (uuidStr.startsWith(remaining)) {
                builder.suggest(uuidStr, tooltipComponent);
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> LOGIN_MODES = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        if ("online".startsWith(remaining))
            builder.suggest("online");
        if ("offline".startsWith(remaining))
            builder.suggest("offline");
        return builder.buildFuture();
    };

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
                        .then(Commands.argument("target", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.word())
                                        .then(Commands.argument("confirmPassword", StringArgumentType.word())
                                                .executes(context -> setOfflinePassword(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "password"),
                                                        StringArgumentType.getString(context,
                                                                "confirmPassword")))))))
                .then(Commands.literal("setmode")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(KNOWN_PLAYERS)
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(LOGIN_MODES)
                                        .executes(context -> setPlayerMode(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "target"),
                                                StringArgumentType.getString(context, "mode"))))))
                .then(Commands.literal("remove")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(KNOWN_PLAYERS)
                                .executes(context -> removePlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "target")))));
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        UUID playerUuid = player.getGameProfile().getId();

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

    private static int setPlayerMode(CommandSourceStack source, String target, String modeRaw) {
        UUID playerUuid = tryParseUuid(target);
        String username = null;
        if (playerUuid == null) {
            playerUuid = resolvePlayerUuidByUsername(source, target);
            if (playerUuid == null) {
                return 0;
            }
            username = target; // 通过用户名查到的，保留真实用户名
        }
        final UUID resolvedUuid = playerUuid;

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
                    target,
                    resolvedUuid,
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

    private static int removePlayer(CommandSourceStack source, String target) {
        UUID playerUuid = tryParseUuid(target);
        if (playerUuid == null) {
            playerUuid = resolvePlayerUuidByUsername(source, target);
        }
        if (playerUuid == null) {
            return 0;
        }
        final UUID resolvedUuid = playerUuid;

        try {
            KnownPlayerService.removeAllPlayerData(resolvedUuid);
            source.sendSuccess(() -> AuthTranslations.componentForSource(
                    source,
                    "auth.command.remove.success",
                    target,
                    resolvedUuid), true);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.remove.failure",
                    describeCommandFailure(source, "removing all player data", runtimeException)));
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

        UUID playerUuid = player.getGameProfile().getId();
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

        UUID playerUuid = player.getGameProfile().getId();
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

        UUID playerUuid = player.getGameProfile().getId();
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

    private static int setOfflinePassword(CommandSourceStack source, String target, String password,
            String confirmPassword) {
        if (!validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = tryParseUuid(target);
        if (playerUuid == null) {
            playerUuid = resolvePlayerUuidByUsername(source, target);
        }
        if (playerUuid == null) {
            return 0;
        }
        final UUID resolvedUuid = playerUuid;

        try {
            OfflineAuthService.saveOfflinePassword(resolvedUuid, password);
            OfflineAuthService.clearTrustedOfflineLogins(resolvedUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_set.success",
                            resolvedUuid,
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

    /**
     * 静默尝试将字符串解析为 UUID，不输出任何消息。
     */
    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
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