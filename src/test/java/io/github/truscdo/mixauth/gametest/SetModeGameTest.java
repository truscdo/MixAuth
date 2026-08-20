package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.cache.AuthStore;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

import java.util.UUID;

/**
 * 管理员设登录模式（{@code /auth setmode}）测试。
 * <p>
 * 覆盖 {@code AuthCommands.setPlayerMode} 的 P0 分支：按 UUID 设置 online/offline、
 * 按用户名解析已知玩家、非法 mode 参数失败（模式不变）。
 * <p>
 * {@code setmode} 要求权限 3（isAdmin），GameTest 玩家非 OP，故用服务器控制台 source 驱动
 * （{@link AuthGameTestBase#runCommandAsConsole}），成功/失败消息发往服务器日志，测试侧
 * 只断言落库结果。与 {@code setpassword}（BCrypt 异步）不同，setmode 是同步写：
 * {@code AuthStore.setLoginMode} 缓存先行、同步可见，命令返回后可直接断言。
 */
public class SetModeGameTest extends AuthGameTestBase {

    public SetModeGameTest(GameTestInfo info) {
        super(info);
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "管理员按 UUID 将玩家登录模式设为 online" })
    static void setModeOnlineByUuid(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetModeOnline");
        helper.resetPlayerData(uuid);
        helper.runCommandAsConsole("/auth setmode " + uuid + " online");
        helper.assertTrue(AuthStore.getLoginMode(uuid) == OnlineAuthService.LoginMode.ONLINE,
                "expected login mode ONLINE after setmode online");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "管理员按 UUID 将玩家登录模式设为 offline" })
    static void setModeOfflineByUuid(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetModeOffline");
        helper.resetPlayerData(uuid);
        helper.runCommandAsConsole("/auth setmode " + uuid + " offline");
        helper.assertTrue(AuthStore.getLoginMode(uuid) == OnlineAuthService.LoginMode.OFFLINE,
                "expected login mode OFFLINE after setmode offline");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "管理员按用户名解析已知玩家并设置登录模式为 online" })
    static void setModeByUsername(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetModeUser");
        helper.resetPlayerData(uuid);
        // 按用户名解析依赖 known_players 索引（findKnownPlayersByUsername），先落一条记录。
        KnownPlayerService.recordKnownPlayer(uuid, "SetModeUser", OnlineAuthService.LoginMode.OFFLINE);
        helper.runCommandAsConsole("/auth setmode SetModeUser online");
        helper.assertTrue(AuthStore.getLoginMode(uuid) == OnlineAuthService.LoginMode.ONLINE,
                "expected login mode ONLINE after setmode by username");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "非法 mode 参数：命令失败，登录模式不变" })
    static void setModeInvalidMode(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SetModeBad");
        helper.resetPlayerData(uuid);
        KnownPlayerService.recordKnownPlayer(uuid, "SetModeBad", OnlineAuthService.LoginMode.OFFLINE);
        helper.runCommandAsConsole("/auth setmode SetModeBad bogus");
        helper.assertTrue(AuthStore.getLoginMode(uuid) == OnlineAuthService.LoginMode.OFFLINE,
                "expected login mode unchanged after invalid mode");
        helper.succeed();
    }
}
