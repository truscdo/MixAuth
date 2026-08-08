package io.github.truscdo.mixauth.command;

import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 命令参数中玩家目标的解析：优先按 UUID，其次按用户名查询已知玩家名单。
 */
public final class PlayerTargetResolver {
    private PlayerTargetResolver() {
    }

    /**
     * 静默尝试将字符串解析为 UUID，不输出任何消息。
     */
    public static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 按用户名在已知玩家名单中解析玩家 UUID。未找到或存在重复用户名时向执行者输出提示。
     */
    public static UUID resolvePlayerUuidByUsername(CommandSourceStack source, String username) {
        if (username == null || username.isBlank()) {
            source.sendFailure(AuthTranslations.componentForSource(source, "auth.error.missing_username"));
            return null;
        }

        List<KnownPlayerDao.KnownPlayerEntry> entries = KnownPlayerDao.findKnownPlayersByUsername(username);
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
}
