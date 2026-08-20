package io.github.truscdo.mixauth.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

/**
 * 1.21.11 版本特定实现：权限系统重构为三层对象模型
 * （{@code Permission} / {@code PermissionSet} / {@code PermissionCheck}），
 * {@code CommandSourceStack.hasPermission(int)} 被移除。
 *
 * <p>
 * 旧 {@code hasPermission(3)}（命令权限等级 &gt;= ADMINS）等价映射为
 * {@code source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)}——
 * 原版 {@code LevelBasedPermissionSet} 语义为 "equal or higher command permission
 * level"，
 * 等级概念保留。由 build.gradle 按 {@code minecraft_version} 选择本源集参与编译。
 */
final class PermissionCompat {
    private PermissionCompat() {
    }

    /**
     * 检查命令源是否具备管理员权限（等价于旧语义：命令权限等级 &gt;= 3）。
     */
    static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }
}
