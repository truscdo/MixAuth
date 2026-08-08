package io.github.truscdo.mixauth.command;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.LogUtil;
import io.github.truscdo.mixauth.PasswordPolicyValidator;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 各命令执行器共享的辅助方法。
 */
public final class CommandSupport {
    private static final Logger LOGGER = LogUtil.getLogger();

    private CommandSupport() {
    }

    /**
     * 获取命令执行者对应的玩家，非玩家执行时发送错误消息并返回 null。
     */
    public static ServerPlayer getCommandPlayer(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.player_only_command"));
            return null;
        }
        return player;
    }

    public static void disconnectPlayer(ServerPlayer player, Component reason) {
        player.connection.disconnect(reason);
    }

    /**
     * 记录命令执行失败日志，并返回面向玩家的通用错误文案。
     */
    public static String describeCommandFailure(CommandSourceStack source, String operation,
            RuntimeException runtimeException) {
        LOGGER.error("Auth command failed while {}", operation, runtimeException);
        return AuthTranslations.textForSource(source, "auth.command.failure.internal");
    }

    /**
     * 解析玩家连接的远端 IP。
     */
    public static String resolveRemoteIp(ServerPlayer player) {
        SocketAddress remoteAddress = player.connection.getRemoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }

        return remoteAddress == null ? null : String.valueOf(remoteAddress);
    }

    /**
     * 校验两次输入的密码一致且满足密码策略，失败时向执行者输出错误消息。
     */
    public static boolean validatePasswordPair(CommandSourceStack source, String password, String confirmPassword) {
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
}
