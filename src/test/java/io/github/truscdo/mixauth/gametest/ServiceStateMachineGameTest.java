package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.OfflineAuthService;
import io.github.truscdo.mixauth.OfflineAuthSessionService;
import io.github.truscdo.mixauth.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

/**
 * 服务层状态机集成断言。
 * <p>
 * 从 GameTest 玩家视角验证认证状态机的三个 P0 迁移：REGISTER→注册→进游戏、
 * LOGIN→登录成功→进游戏、连续失败→封禁（服务层重进检查）。
 * <p>
 * 登录/注册成功会记录信任窗口，各用例使用独立 IP 避免共享 IP 干扰。
 */
public class ServiceStateMachineGameTest extends AuthGameTestBase {

    public ServiceStateMachineGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String SM_REGISTER_IP = "203.0.113.61";
    private static final String SM_LOGIN_IP = "203.0.113.62";
    private static final String SM_BLOCKED_IP = "203.0.113.63";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "状态机：REGISTER 阶段注册后自动登录进入游戏（无待认证 + 密码已落库）" })
    static void registerAutoLogin(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SmRegister");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("SmRegister", uuid, OnlineAuthService.LoginMode.OFFLINE,
                SM_REGISTER_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.runCommand(player, "/register pw123456 pw123456");
        helper.assertNoPending(player);
        helper.assertTrue(OfflineAuthService.isOfflineRegistered(uuid), "expected offline password registered");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "状态机：LOGIN 阶段登录成功后进入游戏（无待认证 + 信任窗口已记录）" })
    static void loginEnterGame(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SmLogin");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "pw123456");
        GameTestPlayer player = helper.joinServer("SmLogin", uuid, OnlineAuthService.LoginMode.OFFLINE, SM_LOGIN_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.runCommand(player, "/login pw123456");
        helper.assertNoPending(player);
        helper.assertTrue(OfflineAuthService.canBypassOfflineLogin(uuid, SM_LOGIN_IP),
                "expected trust window recorded after login");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "状态机：连续错误登录达上限触发封禁，服务层剩余封禁时间大于 0" })
    static void blockAfterFailures(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SmBlocked");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "pw123456");
        GameTestPlayer player = helper.joinServer("SmBlocked", uuid, OnlineAuthService.LoginMode.OFFLINE,
                SM_BLOCKED_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        int max = AuthServerConfig.maxLoginAttempts();
        for (int i = 0; i < max; i++) {
            helper.runCommand(player, "/login wrongpassword");
        }
        helper.assertTrue(OfflineAuthService.getOfflineLoginBlockRemainingMillis(uuid) > 0,
                "expected temporary block remaining");
        helper.assertTrue(!player.connection.getConnection().isConnected(),
                "expected player to be disconnected");
        helper.succeed();
    }
}
