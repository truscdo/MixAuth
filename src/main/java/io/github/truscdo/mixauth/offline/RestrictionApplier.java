package io.github.truscdo.mixauth.offline;

import io.github.truscdo.mixauth.offline.OfflineAuthSessionService.PendingOfflineAuth;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 待认证玩家的限制措施：旁观者模式、持续失明、位置锁定与背包伪影。
 */
final class RestrictionApplier {
    private static final long INVENTORY_SPOOF_INTERVAL_MILLIS = 1_000L;
    private static final int AUTH_BLINDNESS_DURATION_TICKS = 15 * 20;
    private static final double LOCKED_POSITION_TOLERANCE_SQUARED = 0.0001D;

    private RestrictionApplier() {
    }

    /**
     * 重新施加待认证限制：旁观者模式、失明、位置锁定、清除移动。
     */
    static void apply(ServerPlayer player, PendingOfflineAuth pendingOfflineAuth) {
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }

        ensureAuthBlindness(player);
        if (player.distanceToSqr(pendingOfflineAuth.lockedX, pendingOfflineAuth.lockedY,
                pendingOfflineAuth.lockedZ) > LOCKED_POSITION_TOLERANCE_SQUARED) {
            player.teleportTo(player.serverLevel(), pendingOfflineAuth.lockedX, pendingOfflineAuth.lockedY,
                    pendingOfflineAuth.lockedZ, player.getYRot(), player.getXRot());
        }

        player.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * 用空背包内容覆盖客户端视图，防止待认证玩家窥探物品。
     */
    static void spoofInventoryView(ServerPlayer player, PendingOfflineAuth pendingOfflineAuth) {
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        NonNullList<ItemStack> emptyItems = NonNullList.withSize(inventoryMenu.getItems().size(), ItemStack.EMPTY);
        player.connection.send(new ClientboundContainerSetContentPacket(
                inventoryMenu.containerId,
                inventoryMenu.getStateId(),
                emptyItems,
                ItemStack.EMPTY));
        pendingOfflineAuth.nextInventorySpoofAtMillis = System.currentTimeMillis() + INVENTORY_SPOOF_INTERVAL_MILLIS;
    }

    /**
     * 恢复真实背包视图。
     */
    static void restoreInventoryView(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
    }

    private static void ensureAuthBlindness(ServerPlayer player) {
        MobEffectInstance blindness = player.getEffect(MobEffects.BLINDNESS);
        if (blindness != null && blindness.getDuration() > 40) {
            return;
        }

        player.addEffect(
                new MobEffectInstance(MobEffects.BLINDNESS, AUTH_BLINDNESS_DURATION_TICKS, 0, false, false, false));
    }
}
