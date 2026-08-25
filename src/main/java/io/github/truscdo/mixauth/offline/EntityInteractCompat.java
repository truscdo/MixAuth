package io.github.truscdo.mixauth.offline;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 版本无关的"右键实体特定部位"交互拦截适配器（主目录基础版，覆盖 1.21.x / 26.1）。
 *
 * <p>
 * {@code PlayerInteractEvent.EntityInteractSpecific}（对应 vanilla
 * {@code Entity#interactAt}，如剪羊毛、挤奶等）自 26.2 起被 NeoForge 移除，
 * 其场景合并进 {@code EntityInteract}（26.2 的 {@code EntityInteract} javadoc
 * 明确 "responsible for all entity interactions"）。因此 26.2 覆盖实现由
 * {@code src/neo-26.2} 承载（build.gradle 按 {@code minecraft_version} 选择源集，
 * 并在 26.2 分支排除本文件以避免重复类），注册为空操作。
 */
final class EntityInteractCompat {
    private EntityInteractCompat() {
    }

    /** 注册"右键实体特定部位"（interactAt）的未登录拦截。 */
    static void registerSpecificInteractGuard(IEventBus bus) {
        bus.addListener(EntityInteractCompat::onEntityInteractSpecific);
    }

    private static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingActionGuard.denyIfPending(player,
                pending -> {
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                },
                "auth.error.cannot_attack_or_interact_before_login");
    }
}