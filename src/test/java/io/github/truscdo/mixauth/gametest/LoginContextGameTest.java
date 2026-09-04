package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.login.LoginContext;
import io.github.truscdo.mixauth.login.LoginContexts;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

public class LoginContextGameTest extends AuthGameTestBase {
    private static final String FAKE_IP = "203.0.113.20";

    public LoginContextGameTest(GameTestInfo info) {
        super(info);
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "同一连接上下文只能发布一次、消费一次，且两个连接互不覆盖" })
    static void atomicPublishAndTake(AuthGameTestBase helper) {
        UUID firstUuid = AuthGameTestBase.offlineUuid("ContextAtomicA");
        UUID secondUuid = AuthGameTestBase.offlineUuid("ContextAtomicB");
        helper.resetPlayerData(firstUuid);
        helper.resetPlayerData(secondUuid);
        GameTestPlayer firstPlayer = helper.joinServer("ContextAtomicA", firstUuid,
                OnlineAuthService.LoginMode.ONLINE, FAKE_IP);
        GameTestPlayer secondPlayer = helper.joinServer("ContextAtomicB", secondUuid,
                OnlineAuthService.LoginMode.ONLINE, FAKE_IP);

        LoginContext first = new LoginContext(
                OnlineAuthService.LoginMode.OFFLINE,
                firstUuid,
                firstUuid,
                "SharedConcurrentName",
                1L);
        LoginContext second = new LoginContext(
                OnlineAuthService.LoginMode.ONLINE,
                secondUuid,
                firstUuid,
                "SharedConcurrentName",
                2L);

        helper.assertTrue(LoginContexts.publish(firstPlayer.connection.getConnection(), first),
                "first context should publish");
        helper.assertTrue(LoginContexts.publish(secondPlayer.connection.getConnection(), second),
                "second connection should publish independently");
        helper.assertTrue(!LoginContexts.publish(firstPlayer.connection.getConnection(), second),
                "duplicate publish must not overwrite the first context");
        helper.assertTrue(LoginContexts.peek(firstPlayer.connection.getConnection()) == first,
                "peek should return the original context");
        helper.assertTrue(LoginContexts.take(firstPlayer.connection.getConnection()) == first,
                "take should return the original context");
        helper.assertTrue(LoginContexts.take(firstPlayer.connection.getConnection()) == null,
                "a context must be consumable only once");
        helper.assertTrue(LoginContexts.take(secondPlayer.connection.getConnection()) == second,
                "the second connection must retain its own context");
        LoginContexts.clear(firstPlayer.connection.getConnection());
        LoginContexts.clear(firstPlayer.connection.getConnection());
        helper.assertTrue(LoginContexts.peek(firstPlayer.connection.getConnection()) == null,
                "clear must be idempotent");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "登录上下文 UUID 与最终玩家 UUID 不匹配时断开" })
    static void mismatchedUuidDisconnects(AuthGameTestBase helper) {
        UUID playerUuid = AuthGameTestBase.offlineUuid("ContextUuidPlayer");
        UUID contextUuid = AuthGameTestBase.offlineUuid("ContextUuidOther");
        helper.resetPlayerData(playerUuid);
        helper.resetPlayerData(contextUuid);
        LoginContext context = new LoginContext(
                OnlineAuthService.LoginMode.OFFLINE,
                playerUuid,
                contextUuid,
                "ContextUuidOther",
                System.currentTimeMillis());
        GameTestPlayer player = helper.joinServerWithContext(
                "ContextUuidPlayer", playerUuid, context, FAKE_IP);
        helper.startSequence()
                .thenExecuteAfter(1, () -> helper.assertTrue(!player.connection.getConnection().isConnected(),
                        "UUID mismatch must disconnect"))
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "requested UUID 与 canonical offline UUID 不同仍能沿连接正确交接" })
    static void requestedUuidDiffersFromCanonicalOfflineUuid(AuthGameTestBase helper) {
        String username = "ContextAliasPlayer";
        UUID canonicalUuid = AuthGameTestBase.offlineUuid(username);
        UUID requestedUuid = UUID.randomUUID();
        helper.resetPlayerData(canonicalUuid);
        GameTestPlayer player = helper.joinServerViaRecordedLogin(
                username,
                canonicalUuid,
                requestedUuid,
                OnlineAuthService.LoginMode.OFFLINE,
                FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "登录上下文用户名与最终玩家用户名不匹配时断开" })
    static void mismatchedUsernameDisconnects(AuthGameTestBase helper) {
        UUID playerUuid = AuthGameTestBase.offlineUuid("ContextNamePlayer");
        helper.resetPlayerData(playerUuid);
        LoginContext context = new LoginContext(
                OnlineAuthService.LoginMode.OFFLINE,
                playerUuid,
                playerUuid,
                "ContextNameOther",
                System.currentTimeMillis());
        GameTestPlayer player = helper.joinServerWithContext(
                "ContextNamePlayer", playerUuid, context, FAKE_IP);
        helper.startSequence()
                .thenExecuteAfter(1, () -> helper.assertTrue(!player.connection.getConnection().isConnected(),
                        "username mismatch must disconnect"))
                .thenSucceed();
    }
}
