package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

import java.util.UUID;

/**
 * 管理员设密（{@code /auth setpassword}）测试。
 * <p>
 * 覆盖 {@code AuthCommands.setOfflinePassword} 的 P0 分支：按 UUID 为未注册玩家设密、
 * 为已有密码玩家重置（旧密码失效 + 免密记录清除）、按用户名解析已知玩家、两次密码不一致
 * 失败。
 * <p>
 * {@code setpassword} 要求权限 3，GameTest 玩家非 OP，故用服务器控制台 source 驱动
 * （{@link AuthGameTestBase#runCommandAsConsole}），成功/失败消息发往服务器日志，测试侧
 * 只断言落库结果。改密为异步 BCrypt：哈希后回主线程落库，需等待完成再断言。
 */
public class SetPasswordGameTest extends AuthGameTestBase {

    public SetPasswordGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String TRUST_IP = "203.0.113.91";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "管理员按 UUID 为未注册玩家设置离线密码：密码落库且可验证" })
    static void setPasswordSuccessByUuid(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetPassUuid");
        helper.resetPlayerData(uuid);
        helper.runCommandAsConsole("/auth setpassword " + uuid + " newpass123 newpass123");
        // BCrypt 哈希为异步：等待完成（落库）后再断言。
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(OfflineAuthService.isOfflineRegistered(uuid),
                            "expected offline password registered after setpassword");
                    helper.assertTrue(OfflineAuthService.verifyOfflinePassword(uuid, "newpass123"),
                            "expected set password to be valid");
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "管理员为已有密码的玩家重置密码：新密码生效、旧密码失效、免密记录清除" })
    static void setPasswordResetsExisting(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetPassReset");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "oldpass123");
        helper.recordTrustedIp(uuid, TRUST_IP);
        helper.runCommandAsConsole("/auth setpassword " + uuid + " newpass123 newpass123");
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(!OfflineAuthService.verifyOfflinePassword(uuid, "oldpass123"),
                            "expected old password to be invalid after reset");
                    helper.assertTrue(OfflineAuthService.verifyOfflinePassword(uuid, "newpass123"),
                            "expected new password to be valid after reset");
                    helper.assertTrue(!helper.hasTrustedIp(uuid, TRUST_IP),
                            "expected trusted login records cleared after password reset");
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "管理员按用户名解析已知玩家并设置离线密码" })
    static void setPasswordByUsername(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetPassUser");
        helper.resetPlayerData(uuid);
        // GameTest 玩家进服不经过登录握手 mixin，故手动创建已知玩家记录供用户名解析。
        KnownPlayerService.recordKnownPlayer(uuid, "SetPassUser", OnlineAuthService.LoginMode.OFFLINE);
        helper.runCommandAsConsole("/auth setpassword SetPassUser newpass123 newpass123");
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(OfflineAuthService.isOfflineRegistered(uuid),
                            "expected offline password registered via username resolution");
                    helper.assertTrue(OfflineAuthService.verifyOfflinePassword(uuid, "newpass123"),
                            "expected set password to be valid");
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "设密两次密码不一致：提示错误，密码不变" })
    static void setPasswordMismatch(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetPassMismatch");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "oldpass123");
        helper.runCommandAsConsole("/auth setpassword " + uuid + " aaa bbb");
        // 密码对校验为同步：命令返回时错误消息已发送、数据库未动。
        helper.assertTrue(OfflineAuthService.verifyOfflinePassword(uuid, "oldpass123"),
                "expected password unchanged after mismatch");
        helper.succeed();
    }
}
