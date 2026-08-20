package io.github.truscdo.mixauth.offline;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 版本无关的传送适配器（主目录基础版，覆盖 1.21.0 / 1.21.1）。
 *
 * <p>
 * {@code ServerPlayer.teleportTo} 的 6 参重载
 * {@code (ServerLevel, x, y, z, yaw, pitch)} 自 1.21.2 起被移除，改为 8 参
 * {@code (ServerLevel, x, y, z, Set<Relative>, yaw, pitch, boolean)}，
 * 由 {@code src/neo-1.21.2} 的覆盖实现承载（build.gradle 按 {@code minecraft_version}
 * 选择源集）。
 */
final class TeleportCompat {
    private TeleportCompat() {
    }

    /** 将玩家传送到锁定坐标（保持当前朝向）。 */
    static void teleportBack(ServerPlayer player, double x, double y, double z) {
        player.teleportTo((ServerLevel) player.level(), x, y, z, player.getYRot(), player.getXRot());
    }
}
