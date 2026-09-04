// 在线加密握手 与 hasJoined 返回 500 两个场景。
//
// 路由机制：MCC 离线客户端每次启动随机生成 profileId，与 mock profile lookup
// 返回的固定 UUID 必然不匹配，登录前预检会永远判定离线、在线握手分支不可达。
// 因此本类不依赖预检，而是通过 RCON 执行 /auth setmode <离线UUID> online 预置
// 已知玩家，利用 KnownPlayerService.resolveLoginMode 的「服务器生成 UUID 回退」
// 分支，使 handleHello 拦截后直接进入在线握手分支（跳过 profile 预检）。
// 离线 UUID 用 Minecraft 标准算法计算：UUID.nameUUIDFromBytes("OfflinePlayer:" + 用户名)。
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("online")
public class OnlineHandshakePendingIT extends LoginChainITBase {

    /**
     * 通过 RCON 预置已知玩家并指定 Online 登录模式。
     * <p>
     * 目标使用服务器生成的离线 UUID（Minecraft 标准算法），使
     * {@code resolveLoginMode} 的「服务器生成 UUID 回退」分支能命中，
     * 从而让 handleHello 拦截后直接进入在线握手分支，跳过 profile 预检。
     * {@code setLoginMode} 缓存先行，命令返回后内存立即生效。
     */
    private void presetKnownPlayer(String username) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        String out = server.rcon("/auth setmode " + offlineUuid + " online");
        // 成功消息（auth.command.mode.set.success）包含目标 UUID，语言无关
        assertTrue(out != null && out.contains(offlineUuid.toString()),
                () -> "preset known player failed for " + username + "; rcon out: " + out);
    }

    @Test
    @Disabled("等待补充 Online known_players 测试夹具")
    @DisplayName("在线握手：正常在线握手 → 以 Online 模式入服")
    public void onlineHandshakeM1() throws Exception {
        presetKnownPlayer("OnlineTester");
        assertTrue(mock.setMode("online", "online"), "mock /_mock/mode switch failed");
        try (MccDriver.MccRun r = MccDriver.launch("M1", "OnlineTester", false)) {
            // 期望：hasJoined 返回 200 → 正常进服
            assertTrue(r.awaitJoin(90),
                    () -> failMsg("online handshake client", "Server was successfully joined", r));
            // 服务器侧：auth$finishValidation 成功路径
            assertTrue(r.awaitServerLog("auth validation continuing online login", 30),
                    () -> failMsgServer("auth validation continuing online login", r));
        }
    }

    @Test
    @Disabled("等待补充 Online known_players 测试夹具")
    @DisplayName("在线握手：hasJoined 返回 500 → 断线且不回退离线")
    public void hasJoined500Disconnects() throws Exception {
        presetKnownPlayer("HasJoinedTester");
        assertTrue(mock.setMode("online", "500"), "mock /_mock/mode switch failed");
        // nojoin=true：预期登录前失败，不等待成功入服
        try (MccDriver.MccRun r = MccDriver.launch("M4", "HasJoinedTester", true)) {
            // 客户端：断开原因 = failed_with_reason + server_internal_error
            // （doRequestHasJoined 对 HTTP 500 返回 failureReason=null，
            // auth$failureText(null) 回退到 server_internal_error 文本）
            assertTrue(r.awaitClientLog("Online authentication failed: Internal server authentication error.", 90),
                    () -> failMsg("hasJoined500 client",
                            "Online authentication failed: Internal server authentication error.", r));
            // 服务器：mixin warn；且无 "falling back to offline login"（安全语义）
            assertTrue(r.awaitServerLog("auth validation failed after online handshake", 30),
                    () -> failMsgServer("auth validation failed after online handshake", r));
        }
    }
}
