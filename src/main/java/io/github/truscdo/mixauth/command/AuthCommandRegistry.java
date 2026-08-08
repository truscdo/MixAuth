package io.github.truscdo.mixauth.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * 认证相关命令的注册门面。
 */
public final class AuthCommandRegistry {
    private AuthCommandRegistry() {
    }

    public static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(RegisterCommand.builder());
        dispatcher.register(LoginCommand.builder());
        dispatcher.register(AuthCommands.builder());
    }
}
