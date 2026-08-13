package io.github.truscdo.mixauth.gametest;

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
 * 信任登录窗口测试。
 * <p>
 * 覆盖 {@code OfflineAuthService.canBypassOfflineLogin} 的 P0 分支：登录成功后记录的同
 * UUID 同 IP 信任记录，使玩家再次进服时免密直进。
 * <p>
 * 信任记录按 IP 统计共享 UUID（hasSharedRecentOfflineTrustedIp），故本用例使用独立 IP，
 * 避免与其他用例的信任记录互相干扰。
 */
public class TrustedLoginGameTest extends AuthGameTestBase {

    public TrustedLoginGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String TRUST_IP = "203.0.113.71";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "登录成功后同 UUID 同 IP 再次进服：信任窗口内免密直进，无待认证状态" })
    static void trustedRejoinBypassesLogin(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("TrustRejoin");
        helper.resetPlayerData(uuid);
        // 第一次进服：已注册 + 无信任记录 → LOGIN 待认证 → 登录成功记录信任窗口。
        helper.registerPassword(uuid, "pw123456");
        GameTestPlayer first = helper.joinServer("TrustRejoin", uuid, OnlineAuthService.LoginMode.OFFLINE, TRUST_IP);
        helper.assertPendingStage(first, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.runCommand(first, "/login pw123456");
        // 第一次登录为异步 BCrypt：等待完成（信任窗口落库）后再进行第二次进服，否则会误走密码登录。
        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    helper.assertNoPending(first);
                    helper.assertTrue(OfflineAuthService.canBypassOfflineLogin(uuid, TRUST_IP),
                            "expected trust window recorded after successful login");

                    // 第二次进服：同 UUID（不同用户名）+ 同 IP → 免密直进，无待认证状态。
                    GameTestPlayer second = helper.joinServer("TrustRejoinAgain", uuid,
                            OnlineAuthService.LoginMode.OFFLINE, TRUST_IP);
                    helper.assertNoPending(second);
                    // 并行测试的进服/离开广播可能混入，故断言「存在」而非「最后一条」。
                    helper.assertAnyMessage(second, "auth.message.trusted_login_bypass",
                            OfflineAuthService.describeTrustedLoginWindow());
                })
                .thenSucceed();
    }

    /**
     * 窗口外的信任记录：窗口检查时不授信（过期不授信），玩家回到密码登录。
     * 清理交由 AuthStore 节流范围删除，不在窗口检查时断言（清理时机取决于节流）。
     */
    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "窗口外的信任记录不授信，玩家回到密码登录（清理交由节流范围删除）" })
    static void expiredTrustRecordCleanedUp(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("TrustStale");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "pw123456");
        // 记录信任记录后人为把 authenticated_at 改到窗口外（模拟很久以前登录）。
        helper.recordTrustedIp(uuid, TRUST_IP);
        helper.expireTrustedIp(uuid, TRUST_IP);

        // 窗口检查：过期记录不授信。
        helper.assertTrue(!OfflineAuthService.canBypassOfflineLogin(uuid, TRUST_IP),
                "expected expired trust window to be rejected");

        // 回归：进服应回到 LOGIN 待认证（而非免密直进）。
        GameTestPlayer player = helper.joinServer("TrustStale", uuid, OnlineAuthService.LoginMode.OFFLINE, TRUST_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.succeed();
    }
}
