package io.github.truscdo.mixauth.command;

import net.minecraft.commands.CommandSourceStack;

/**
 * 版本无关的权限检查适配器（主目录基础版，覆盖 1.21.0 / 1.21.1 ~ 1.21.10）。
 *
 * <p>
 * 1.21.11 起 {@code CommandSourceStack.hasPermission(int)} 被移除，权限系统重构为
 * 三层对象模型（{@code Permission} / {@code PermissionSet} / {@code PermissionCheck}），
 * 由 {@code src/neo-1.21.11} 的覆盖实现承载（build.gradle 按 {@code minecraft_version}
 * 选择源集，并在 1.21.11 分支排除本文件以避免重复类）。
 */
final class PermissionCompat {
    private PermissionCompat() {
    }

    /**
     * 检查命令源是否具备管理员权限（旧语义：命令权限等级 &gt;= 3）。
     */
    static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(3);
    }
}
