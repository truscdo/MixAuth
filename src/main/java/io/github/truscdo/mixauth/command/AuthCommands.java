package io.github.truscdo.mixauth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /auth 命令：管理员子命令 setpassword / setmode / remove 与玩家自助子命令 changepassword。
 */
public final class AuthCommands {
    private static final SuggestionProvider<CommandSourceStack> KNOWN_PLAYERS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (remaining.isEmpty()) {
            return Suggestions.empty();
        }
        List<KnownPlayerDao.KnownPlayerEntry> entries = KnownPlayerService.findKnownPlayersByPrefix(remaining, 20);
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

    private AuthCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> builder() {
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

    /**
     * /auth changepassword：玩家自助修改自己的离线密码。
     */
    private static int changeOwnOfflinePassword(CommandSourceStack source, String password, String confirmPassword) {
        ServerPlayer player = CommandSupport.getCommandPlayer(source);
        if (player == null) {
            return 0;
        }

        if (!CommandSupport.validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = player.getGameProfile().getId();
        if (!OfflineAuthService.isOfflineRegistered(playerUuid)) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.no_offline_password_register"));
            return 0;
        }

        // BCrypt 哈希移到后台有界执行器，落库与清理回主线程执行。
        OfflineAuthService.hashOfflinePasswordAsync(password)
                .whenComplete((hash, throwable) -> CommandSupport.executeOnServerThread(source,
                        () -> completeChangePassword(source, playerUuid, hash, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    private static void completeChangePassword(CommandSourceStack source, UUID playerUuid, String hash,
            Throwable throwable) {
        if (throwable != null) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.server_busy"));
            return;
        }
        try {
            OfflineAuthService.saveOfflinePasswordHash(playerUuid, hash);
            OfflineAuthService.clearTrustedOfflineLogins(playerUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_change.success",
                            OfflineAuthService.describeTrustedLoginWindow(language)),
                    false);
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_change.failure",
                    CommandSupport.describeCommandFailure(source, "changing an offline password", runtimeException)));
        }
    }

    /**
     * /auth setpassword：管理员为指定玩家设置离线密码。
     */
    private static int setOfflinePassword(CommandSourceStack source, String target, String password,
            String confirmPassword) {
        if (!CommandSupport.validatePasswordPair(source, password, confirmPassword)) {
            return 0;
        }

        UUID playerUuid = PlayerTargetResolver.tryParseUuid(target);
        if (playerUuid == null) {
            playerUuid = PlayerTargetResolver.resolvePlayerUuidByUsername(source, target);
        }
        if (playerUuid == null) {
            return 0;
        }
        final UUID resolvedUuid = playerUuid;

        // BCrypt 哈希移到后台有界执行器，落库与清理回主线程执行。
        OfflineAuthService.hashOfflinePasswordAsync(password)
                .whenComplete((hash, throwable) -> CommandSupport.executeOnServerThread(source,
                        () -> completeSetPassword(source, resolvedUuid, hash, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    private static void completeSetPassword(CommandSourceStack source, UUID resolvedUuid, String hash,
            Throwable throwable) {
        if (throwable != null) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.server_busy"));
            return;
        }
        try {
            OfflineAuthService.saveOfflinePasswordHash(resolvedUuid, hash);
            OfflineAuthService.clearTrustedOfflineLogins(resolvedUuid);
            String language = AuthTranslations.resolveLanguage(source);
            source.sendSuccess(
                    () -> AuthTranslations.componentForSource(
                            source,
                            "auth.command.password_set.success",
                            resolvedUuid,
                            OfflineAuthService.describeTrustedLoginWindow(language)),
                    true);
        } catch (RuntimeException runtimeException) {
            source.sendFailure(AuthTranslations.componentForSource(
                    source,
                    "auth.command.password_set.failure",
                    CommandSupport.describeCommandFailure(source, "setting an offline password", runtimeException)));
        }
    }

    /**
     * /auth setmode：管理员设置玩家的登录模式（online/offline）。
     */
    private static int setPlayerMode(CommandSourceStack source, String target, String modeRaw) {
        UUID playerUuid = PlayerTargetResolver.tryParseUuid(target);
        String username = null;
        if (playerUuid == null) {
            playerUuid = PlayerTargetResolver.resolvePlayerUuidByUsername(source, target);
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
                    CommandSupport.describeCommandFailure(source, "setting player login mode", runtimeException)));
            return 0;
        }
    }

    /**
     * /auth remove：管理员移除玩家的全部认证数据。
     */
    private static int removePlayer(CommandSourceStack source, String target) {
        UUID playerUuid = PlayerTargetResolver.tryParseUuid(target);
        if (playerUuid == null) {
            playerUuid = PlayerTargetResolver.resolvePlayerUuidByUsername(source, target);
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
                    CommandSupport.describeCommandFailure(source, "removing all player data", runtimeException)));
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
}
