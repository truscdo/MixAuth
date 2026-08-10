package io.github.truscdo.mixauth.offline;

import io.github.truscdo.mixauth.offline.OfflineAuthSessionService.PendingOfflineAuth;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * 待认证玩家的动作拦截辅助：统一"取玩家 + guard + 取消 + 拒绝"三步模板，
 * 并负责节流的拒绝提示发送。
 */
final class PendingActionGuard {
    private static final long ACTION_DENY_MESSAGE_INTERVAL_MILLIS = 1_000L;

    private PendingActionGuard() {
    }

    /**
     * 获取玩家的待处理认证状态。如果没有待处理认证，返回 null。
     */
    static PendingOfflineAuth guard(ServerPlayer player) {
        return OfflineAuthSessionService.getPendingAuth(player);
    }

    /**
     * 若玩家处于待认证状态，则执行给定的取消动作并发送节流的拒绝提示。
     *
     * @return true 表示玩家被拦截（存在待认证状态）
     */
    static boolean denyIfPending(ServerPlayer player, Consumer<PendingOfflineAuth> cancelAction,
            String translationKey) {
        PendingOfflineAuth pendingOfflineAuth = guard(player);
        if (pendingOfflineAuth == null) {
            return false;
        }

        cancelAction.accept(pendingOfflineAuth);
        deny(player, pendingOfflineAuth, translationKey);
        return true;
    }

    /**
     * 发送节流的拒绝提示（并顺带重发认证提示）。
     */
    static void deny(ServerPlayer player, PendingOfflineAuth pendingOfflineAuth, String translationKey,
            Object... args) {
        long now = System.currentTimeMillis();
        if (now < pendingOfflineAuth.nextActionDeniedMessageAtMillis) {
            return;
        }

        player.sendSystemMessage(AuthTranslations.componentForPlayer(player, translationKey, args));
        OfflineAuthSessionService.sendAuthPrompt(player, pendingOfflineAuth.stage);
        pendingOfflineAuth.nextActionDeniedMessageAtMillis = now + ACTION_DENY_MESSAGE_INTERVAL_MILLIS;
    }
}
