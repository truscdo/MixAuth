package io.github.truscdo.mixauth.offline;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * 1.21.2+ 版本特定实现：{@code ServerPlayer.teleportTo} 的 6 参重载被移除，改为
 * 8 参 {@code (ServerLevel, x, y, z, Set<Relative>, yaw, pitch, boolean)}；
 * 绝对坐标传 {@code Set.of()}，末位 {@code false} 表示不重置相机
 * （与 1.21.1 的 6 参行为一致；客户端位置同步由内部
 * {@code connection.teleport} 无条件执行，与 boolean 无关）。
 * 由 build.gradle 按 {@code minecraft_version} 选择本源集参与编译。
 */
final class TeleportCompat {
    private TeleportCompat() {
    }

    /** 将玩家传送到锁定坐标（保持当前朝向）。 */
    static void teleportBack(ServerPlayer player, double x, double y, double z) {
        player.teleportTo((ServerLevel) player.level(), x, y, z, Set.of(), player.getYRot(), player.getXRot(), false);
    }
}
