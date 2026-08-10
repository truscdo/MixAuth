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
        helper.assertNoPending(first);
        helper.assertTrue(OfflineAuthService.canBypassOfflineLogin(uuid, TRUST_IP),
                "expected trust window recorded after successful login");

        // 第二次进服：同 UUID（不同用户名）+ 同 IP → 免密直进，无待认证状态。
        GameTestPlayer second = helper.joinServer("TrustRejoinAgain", uuid, OnlineAuthService.LoginMode.OFFLINE,
                TRUST_IP);
        helper.assertNoPending(second);
        helper.assertLastMessage(second, "auth.message.trusted_login_bypass",
                OfflineAuthService.describeTrustedLoginWindow());
        helper.succeed();
    }
}
