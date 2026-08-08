package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.OfflineAuthSessionService;
import io.github.truscdo.mixauth.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

/**
 * 待认证期间玩家行为拦截测试。
 * <p>
 * 覆盖 {@code OfflineAuthSessionService} 的拦截监听器：命令（仅放行 register/login）、
 * 聊天（cancel + 文案）。事件驱动用例用 {@code NeoForge.EVENT_BUS.post(...)} 构造事件，
 * 与真实客户端走同一监听器。
 */
public class PendingInterceptionGameTest extends AuthGameTestBase {

    public PendingInterceptionGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String FAKE_IP = "203.0.113.1";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "待认证玩家执行非 register/login 命令被拦截：命令不生效并收到拦截文案" })
    static void commandBlocked(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("InterceptCmd");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("InterceptCmd", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.runCommand(player, "/gamemode creative");
        helper.assertTrue(player.gameMode.getGameModeForPlayer() != GameType.CREATIVE,
                "expected gamemode command to be blocked, but gamemode is "
                        + player.gameMode.getGameModeForPlayer());
        helper.assertAnyMessage(player, "auth.error.only_register_or_login_commands");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "待认证玩家执行 register/login 命令不被拦截：命令进入自身逻辑执行" })
    static void registerAndLoginAllowed(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("InterceptAllow");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("InterceptAllow", uuid, OnlineAuthService.LoginMode.OFFLINE,
                FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        // REGISTER 阶段执行 /login：拦截放行，命令自身以「尚未注册」拒绝。
        helper.runCommand(player, "/login somepassword");
        helper.assertAnyMessage(player, "auth.error.not_registered_register");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "待认证玩家发送聊天消息被拦截：事件被取消并收到拦截文案" })
    static void chatBlocked(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("InterceptChat");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("InterceptChat", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        ServerChatEvent event = new ServerChatEvent(player, "hello", Component.literal("hello"));
        NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.isCanceled(), "expected chat event to be cancelled");
        helper.assertAnyMessage(player, "auth.error.cannot_chat_before_login");
        helper.succeed();
    }
}
