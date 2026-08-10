package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

/**
 * 登录流程测试。
 * <p>
 * 覆盖 {@code LoginCommand} 的 P0 分支：正确密码登录成功（清除待认证 + 记录信任窗口）、
 * 错误密码提示剩余次数、连续错误达上限触发临时封禁并断开连接。
 */
public class LoginFlowGameTest extends AuthGameTestBase {

    public LoginFlowGameTest(GameTestInfo info) {
        super(info);
    }

    /** 各用例独立 IP：登录成功会记录信任窗口，共享 IP 会触发跨 UUID 干扰（trusted IP 共享判定）。 */
    private static final String LOGIN_IP = "203.0.113.51";
    private static final String WRONG_IP = "203.0.113.52";
    private static final String BLOCKED_IP = "203.0.113.53";

    /** 预置注册密码后以 OFFLINE 模式进服（无信任记录 → LOGIN 待认证阶段）。 */
    private static GameTestPlayer joinRegisteredPending(AuthGameTestBase helper, String username, UUID uuid,
            String ip) {
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "pw123456");
        return helper.joinServer(username, uuid, OnlineAuthService.LoginMode.OFFLINE, ip);
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "LOGIN 阶段输入正确密码：待认证清除、记录信任窗口、登录成功文案" })
    static void loginSuccess(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("LoginOk");
        GameTestPlayer player = joinRegisteredPending(helper, "LoginOk", uuid, LOGIN_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.runCommand(player, "/login pw123456");
        helper.assertNoPending(player);
        helper.assertTrue(OfflineAuthService.canBypassOfflineLogin(uuid, LOGIN_IP),
                "expected trusted login window recorded for the login IP");
        helper.assertLastMessage(player, "auth.message.login_success");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "LOGIN 阶段输入错误密码（未达上限）：提示剩余次数，保持待认证" })
    static void wrongPasswordRemaining(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("LoginWrong");
        GameTestPlayer player = joinRegisteredPending(helper, "LoginWrong", uuid, WRONG_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.runCommand(player, "/login wrongpassword");
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        int remaining = AuthServerConfig.maxLoginAttempts() - 1;
        helper.assertAnyMessage(player, "auth.error.password_incorrect_remaining", remaining);
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "连续错误达上限：临时封禁落库、玩家被断开、待认证清除" })
    static void tooManyFailuresBlocked(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("LoginBlocked");
        GameTestPlayer player = joinRegisteredPending(helper, "LoginBlocked", uuid, BLOCKED_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        int max = AuthServerConfig.maxLoginAttempts();
        for (int i = 0; i < max; i++) {
            helper.runCommand(player, "/login wrongpassword");
        }
        helper.assertTrue(OfflineAuthService.getOfflineLoginBlockRemainingMillis(uuid) > 0,
                "expected temporary login block recorded");
        helper.assertTrue(!player.connection.getConnection().isConnected(),
                "expected player to be disconnected");
        helper.assertNoPending(player);
        helper.succeed();
    }
}
