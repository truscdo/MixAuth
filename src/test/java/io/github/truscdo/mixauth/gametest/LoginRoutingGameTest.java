package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

/**
 * 进服路由测试。
 * <p>
 * 覆盖 {@code AuthServerEvents.onPlayerLoggedIn} 的分支：按登录模式（未预置 / OFFLINE /
 * ONLINE）
 * 与注册、IP 信任状态，验证玩家进服后的待认证阶段与提示消息。
 * <p>
 * 触发方式：预置登录模式（markLoginMode）→ placeNewPlayer 进服 → 真实 PlayerLoggedInEvent
 * → AuthServerEvents.onPlayerLoggedIn 路由分支。
 */
public class LoginRoutingGameTest extends AuthGameTestBase {

    public LoginRoutingGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String FAKE_IP = "203.0.113.1";
    private static final String OTHER_IP = "203.0.113.2";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "未预置登录模式进服：不触发认证路由，无待认证状态，无提示" })
    static void noPresetMode(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteNoMode");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("RouteNoMode", uuid, null, FAKE_IP);
        helper.assertNoPending(player);
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "OFFLINE 模式且未注册：进服后进入 REGISTER 待认证阶段，并提示先注册" })
    static void offlineUnregistered(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteOfflineNew");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("RouteOfflineNew", uuid, OnlineAuthService.LoginMode.OFFLINE,
                FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.assertLastMessage(player, "auth.prompt.register");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "OFFLINE 模式且已注册、同 IP 处于信任窗口内：免密直接进服，无待认证状态" })
    static void trustedBypass(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteTrusted");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "pw123456");
        helper.recordTrustedIp(uuid, FAKE_IP);
        GameTestPlayer player = helper.joinServer("RouteTrusted", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertNoPending(player);
        helper.assertLastMessage(player, "auth.message.trusted_login_bypass",
                OfflineAuthService.describeTrustedLoginWindow(AuthTranslations.resolveLanguage(player)));
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "OFFLINE 模式且已注册、IP 不在信任窗口内：进服后进入 LOGIN 待认证阶段" })
    static void offlineRegisteredNoBypass(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteOfflineReg");
        helper.resetPlayerData(uuid);
        helper.registerPassword(uuid, "pw123456");
        GameTestPlayer player = helper.joinServer("RouteOfflineReg", uuid, OnlineAuthService.LoginMode.OFFLINE,
                OTHER_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.LOGIN);
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "ONLINE 模式：直接放行进服，无待认证状态" })
    static void onlineMode(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteOnline");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("RouteOnline", uuid, OnlineAuthService.LoginMode.ONLINE, FAKE_IP);
        helper.assertNoPending(player);
        helper.assertLastMessage(player, "auth.message.current_login_mode",
                AuthTranslations.textForLanguage(AuthTranslations.resolveLanguage(player), "auth.login_mode.online"));
        helper.succeed();
    }

    // ---------------------------------------------------------------- 生产路径

    /**
     * 以上用例均经 {@code joinServer}（直接 markLoginMode）驱动，绕过生产方法
     * {@code recordKnownPlayer}。以下用例改走与 mixin 登录记录分支完全一致的生产 API
     * （joinServerViaRecordedLogin），可捕获 {@code recordKnownPlayer} 内部丢失
     * {@code markLoginMode} 调用的回归（历史回归 393e922）。
     */

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "生产路径：recordOfflineLogin（mixin OFFLINE 分支）后进服 → REGISTER 待认证 + 注册提示" })
    static void offlineRecordedViaProductionApi(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteProdOffline");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServerViaRecordedLogin(
                "RouteProdOffline", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.assertAnyMessage(player, "auth.prompt.register");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "生产路径：recordOnlineLogin（mixin ONLINE 分支）后进服 → 放行 + 当前登录模式 ONLINE 提示" })
    static void onlineRecordedViaProductionApi(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("RouteProdOnline");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServerViaRecordedLogin(
                "RouteProdOnline", uuid, OnlineAuthService.LoginMode.ONLINE, FAKE_IP);
        helper.assertNoPending(player);
        helper.assertLastMessage(player, "auth.message.current_login_mode",
                AuthTranslations.textForLanguage(AuthTranslations.resolveLanguage(player), "auth.login_mode.online"));
        helper.succeed();
    }
}
