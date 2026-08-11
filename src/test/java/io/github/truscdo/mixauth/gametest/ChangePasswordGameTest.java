package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.localization.AuthTranslations;
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
 * 玩家自助改密（{@code /auth changepassword}）测试。
 * <p>
 * 覆盖 {@code AuthCommands.changeOwnOfflinePassword} 的 P0 分支：已认证玩家改密成功
 * （新密码生效、旧密码失效、免密记录清除）、两次密码不一致失败、未注册离线密码（ONLINE
 * 模式玩家）被拒。
 * <p>
 * 前置说明：改密要求玩家「已认证」（无待认证），否则会被待认证动作拦截
 * （{@code only_register_or_login_commands}）。故各用例先注册 + 登录成功，再执行改密。
 * 登录成功会记录信任窗口，各用例使用独立 IP 避免共享 IP 干扰。
 */
public class ChangePasswordGameTest extends AuthGameTestBase {

    public ChangePasswordGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String CHANGE_IP = "203.0.113.81";
    private static final String MISMATCH_IP = "203.0.113.82";
    private static final String UNREGISTERED_IP = "203.0.113.83";

    /**
     * 预置注册密码后进服并登录成功，得到一个已认证（无待认证）的玩家。
     * <p>
     * 登录为异步 BCrypt：调用方需通过 startSequence 等待登录完成后再断言。
     */
    private static GameTestPlayer joinAuthenticated(AuthGameTestBase helper, String username, UUID uuid, String ip) {
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "oldpass123");
        GameTestPlayer player = helper.joinServer(username, uuid, OnlineAuthService.LoginMode.OFFLINE, ip);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.runCommand(player, "/login oldpass123");
        return player;
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "已认证玩家改密成功：新密码生效、旧密码失效、免密记录被清除" })
    static void changePasswordSuccess(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("ChangeOk");
        GameTestPlayer player = joinAuthenticated(helper, "ChangeOk", uuid, CHANGE_IP);
        // 第一次登录为异步 BCrypt：等待完成（登录 + 信任记录落库）后再改密。
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertNoPending(player);
                    helper.runCommand(player, "/auth changepassword newpass123 newpass123");
                })
                .thenExecuteAfter(20, () -> {
                    String lang = AuthTranslations.resolveLanguage(player);
                    helper.assertAnyMessage(player, "auth.command.password_change.success",
                            OfflineAuthService.describeTrustedLoginWindow(lang));
                    helper.assertTrue(!OfflineAuthService.verifyOfflinePassword(uuid, "oldpass123"),
                            "expected old password to be invalid after change");
                    helper.assertTrue(OfflineAuthService.verifyOfflinePassword(uuid, "newpass123"),
                            "expected new password to be valid after change");
                    // 登录成功记录的信任窗口应在改密后全部清除。
                    helper.assertTrue(!helper.hasTrustedIp(uuid, CHANGE_IP),
                            "expected trusted login records cleared after password change");
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "改密两次密码不一致：提示错误，密码不变" })
    static void changePasswordMismatch(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("ChangeMismatch");
        GameTestPlayer player = joinAuthenticated(helper, "ChangeMismatch", uuid, MISMATCH_IP);
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertNoPending(player);
                    helper.runCommand(player, "/auth changepassword aaa bbb");
                    // 密码对校验为同步：命令返回时错误消息已发送、数据库未动。
                    helper.assertAnyMessage(player, "auth.error.password_mismatch");
                    helper.assertTrue(OfflineAuthService.verifyOfflinePassword(uuid, "oldpass123"),
                            "expected password unchanged after mismatch");
                    helper.assertNoPending(player);
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "未注册离线密码的玩家（ONLINE 模式）改密：提示先注册，不落库" })
    static void changePasswordNotRegistered(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("ChangeUnreg");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("ChangeUnreg", uuid, OnlineAuthService.LoginMode.ONLINE,
                UNREGISTERED_IP);
        helper.assertNoPending(player);
        helper.runCommand(player, "/auth changepassword pw123456 pw123456");
        // 未注册检查为同步：命令返回时错误消息已发送。
        helper.assertAnyMessage(player, "auth.error.no_offline_password_register");
        helper.assertTrue(!OfflineAuthService.isOfflineRegistered(uuid), "expected no password to be registered");
        helper.succeed();
    }
}
