package io.github.truscdo.mixauth.gametest.mixauth_tests;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.testframework.conf.Feature;
import net.neoforged.testframework.conf.FrameworkConfiguration;
import net.neoforged.testframework.impl.MutableTestFramework;

/**
 * 测试专用 mod：注册 NeoForge testframework，承载 GameTest 集成测试。
 * <p>
 * 仅在 test 源集内编译，不污染生产代码（AuthMod）。依赖通过 mavenLocal 解析
 * （当前网络环境下 JVM 直连 maven.neoforged.net 被 CDN 拦截）。
 */
@Mod("mixauth_tests")
public final class TestFrameworkMod {
    public TestFrameworkMod(IEventBus modBus, ModContainer container) {
        // 框架 id 为 mixauth:tests（namespace=mixauth，
        // 与 gameTestServer 的 neoforge.enabledGameTestNamespaces 一致）。
        final MutableTestFramework framework = FrameworkConfiguration
                .builder(ResourceLocation.fromNamespaceAndPath("mixauth", "tests"))
                .enable(Feature.GAMETEST, Feature.MAGIC_ANNOTATIONS, Feature.SUMMARY_DUMP, Feature.TEST_STORE)
                .disable(Feature.CLIENT_SYNC, Feature.CLIENT_MODIFICATIONS)
                .build()
                .create();

        framework.init(modBus, container);

        // 在 `tests` 顶级命令下注册框架命令（/tests enable|disable|status|set …），
        // 便于服内启停/查看测试状态。
        NeoForge.EVENT_BUS.addListener((final RegisterCommandsEvent event) -> {
            final LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("tests");
            framework.registerCommands(node);
            event.getDispatcher().register(node);
        });
    }
}
