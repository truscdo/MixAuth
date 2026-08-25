package io.github.truscdo.mixauth.offline;

import net.neoforged.bus.api.IEventBus;

/**
 * 26.2 版本特定实现：{@code PlayerInteractEvent.EntityInteractSpecific} 被移除，
 * 其场景（vanilla {@code Entity#interactAt}）合并进 {@code EntityInteract}，
 * 已由 {@code OfflineAuthSessionService#onEntityInteract} 拦截，无需单独注册。
 * 由 build.gradle 按 {@code minecraft_version} 选择本源集参与编译。
 */
final class EntityInteractCompat {
    private EntityInteractCompat() {
    }

    /** 26.2 起无独立事件，注册为空操作。 */
    static void registerSpecificInteractGuard(IEventBus bus) {
        // no-op
    }
}