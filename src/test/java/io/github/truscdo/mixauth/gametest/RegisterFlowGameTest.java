package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

/**
 * 注册流程测试。
 * <p>
 * 覆盖 {@code RegisterCommand} 的 P0 分支：REGISTER 阶段注册成功（自动登录 + 状态恢复），
 * 以及两次密码不一致的失败分支。
 */
public class RegisterFlowGameTest extends AuthGameTestBase {

    public RegisterFlowGameTest(GameTestInfo info) {
        super(info);
    }

    /** RG-01 专用 IP：注册成功会记录信任窗口，须与其他用例隔离避免共享 IP 干扰。 */
    private static final String REGISTER_IP = "203.0.113.41";
    private static final String FAKE_IP = "203.0.113.1";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "REGISTER 阶段注册成功：密码落库、待认证清除、恢复原游戏模式与失明、自动登录" })
    static void registerSuccess(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RegisterOk");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("RegisterOk", uuid, OnlineAuthService.LoginMode.OFFLINE,
                REGISTER_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.runCommand(player, "/register pw123456 pw123456");
        // BCrypt 哈希为异步：等待完成（落库 + 自动登录）后再断言。
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(OfflineAuthService.isOfflineRegistered(uuid),
                            "expected offline password to be registered");
                    helper.assertNoPending(player);
                    // 隔离解除后恢复为进服时的原游戏模式（gameTestServer 默认为创造模式），即不再处于旁观者。
                    helper.assertTrue(player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR,
                            "expected original game mode restored after login, but still spectator");
                    helper.assertTrue(!player.hasEffect(MobEffects.BLINDNESS),
                            "expected blindness removed after login");
                    // 等待期间并行测试的进服/离开广播可能混入，故断言「存在」而非「最后一条」。
                    helper.assertAnyMessage(player, "auth.message.register_success_auto_login");
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "两次密码不一致：注册失败提示，密码不落库，保持 REGISTER 待认证" })
    static void passwordMismatch(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RegisterMismatch");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("RegisterMismatch", uuid, OnlineAuthService.LoginMode.OFFLINE,
                FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.runCommand(player, "/register pw123456 pw654321");
        helper.assertTrue(!OfflineAuthService.isOfflineRegistered(uuid), "expected password NOT to be registered");
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.assertLastMessage(player, "auth.error.password_mismatch");
        helper.succeed();
    }
}
