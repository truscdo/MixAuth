package io.github.truscdo.mixauth.offline;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemInteractionResult;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

/**
 * 1.21.0 / 1.21.1 版本特定实现：{@link UseItemOnBlockEvent#cancelWithResult} 的参数
 * 类型为 {@link ItemInteractionResult}（1.21.2 起变为
 * {@link net.minecraft.world.InteractionResult}）。
 * 由 build.gradle 按 {@code minecraft_version} 选择本源集参与编译。
 */
final class UseItemOnBlockCompat {
    private UseItemOnBlockCompat() {
    }

    static void handle(UseItemOnBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;
        PendingActionGuard.denyIfPending(player,
                pending -> event.cancelWithResult(ItemInteractionResult.FAIL),
                "auth.error.cannot_interact_blocks_items_before_login");
    }
}
