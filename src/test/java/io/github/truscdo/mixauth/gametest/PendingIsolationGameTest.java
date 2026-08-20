package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.UUID;

/**
 * 待认证隔离状态测试。
 * <p>
 * 覆盖 {@code OfflineAuthSessionService} 在玩家待认证期间施加的隔离：旁观者模式、持续失明、
 * 位置锁定（均由 {@code onServerTick} 每个 tick 重新施加），以及登出时清理待认证状态
 * （{@code onPlayerLoggedOut}）。
 * <p>
 * tick 相关断言用 {@code startSequence().thenExecuteAfter(...)} 等待服务器 tick 推进：
 * mock 玩家在 placeNewPlayer 进服后先被基类复位为生存模式，隔离由进服后的服务器 tick
 * 重新施加。
 */
public class PendingIsolationGameTest extends AuthGameTestBase {

    public PendingIsolationGameTest(GameTestInfo info) {
        super(info);
    }

    private static final String FAKE_IP = "203.0.113.1";

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "待认证玩家进服后处于旁观者模式，且服务器 tick 持续保持" })
    static void spectatorMode(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("IsolSpectator");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("IsolSpectator", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertGameMode(player, GameType.SPECTATOR))
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "待认证玩家持续处于失明状态，短效失明会被服务器 tick 刷新回完整时长" })
    static void persistentBlindness(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("IsolBlind");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("IsolBlind", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        helper.assertBlindness(player);
        // 覆盖为短效失明（10 tick），验证 onServerTick 的失明刷新逻辑（时长 > 40 才保留）。
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 10, 0, false, false, false));
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    var blindness = player.getEffect(MobEffects.BLINDNESS);
                    helper.assertTrue(blindness != null && blindness.getDuration() > 40,
                            "expected blindness refreshed to full duration, but was " + blindness);
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "待认证玩家位置被锁定：传送到任意点后会被服务器 tick 拉回锁定点" })
    static void positionLock(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("IsolPosLock");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("IsolPosLock", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        Vec3 locked = player.position();
        // 版本无关写法：ServerPlayer.teleportTo 的 6 参重载 1.21.2 起被移除，
        // 且 Entity.moveTo 1.21.5 起被移除；setPos 全版本稳定。仅需把玩家挪远即可。
        player.setPos(locked.x + 100.0, locked.y, locked.z + 100.0);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    Vec3 now = player.position();
                    helper.assertTrue(now.distanceToSqr(locked) < 1.0,
                            "expected player pulled back to locked position " + locked + " but was " + now);
                })
                .thenSucceed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "玩家登出事件后清理待认证状态" })
    static void logoutClearsPending(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("IsolLogout");
        helper.resetPlayerData(uuid);
        GameTestPlayer player = helper.joinServer("IsolLogout", uuid, OnlineAuthService.LoginMode.OFFLINE, FAKE_IP);
        helper.assertPendingStage(player, OfflineAuthSessionService.OfflineAuthStage.REGISTER);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertNoPending(player);
        helper.succeed();
    }
}
