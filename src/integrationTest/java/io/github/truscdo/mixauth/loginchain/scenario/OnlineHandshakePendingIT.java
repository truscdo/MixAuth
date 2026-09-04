// 在线加密握手 与 hasJoined 返回 500 两个场景，以及 canonical UUID 路由回归场景。
//
// MCC 离线客户端每次启动可能使用不同的 client UUID，因此回归场景先让同名玩家
// 完成一次离线登录，再用新的 client UUID 重进；第二次必须继续执行登录前预检，
// 不能仅因为 canonical 离线 UUID 已知就直接命中离线路由。
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

    /** 通过 RCON 预置一个 ONLINE 记录；实际 client UUID 夹具补齐后再启用。 */
    private void presetKnownPlayer(String username) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        String out = server.rcon("/auth setmode " + offlineUuid + " online");
        // 成功消息（auth.command.mode.set.success）包含目标 UUID，语言无关
        assertTrue(out != null && out.contains(offlineUuid.toString()),
                () -> "preset known player failed for " + username + "; rcon out: " + out);
    }

    @Test
    @DisplayName("路由回归：同名玩家更换 client UUID 后仍执行登录前预检")
    public void canonicalUuidIsNotUsedAsClientUuidFallback() throws Exception {
        String username = "CanonicalAliasRoute";
        UUID canonicalUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        assertTrue(mock.setMode("404", "online"), "mock /_mock/mode switch failed");

        try {
            try (MccDriver.MccRun first = MccDriver.launch("R-CANONICAL-1", username, false)) {
                assertTrue(first.awaitJoin(90),
                        () -> failMsg("first canonical collision login", "Server was successfully joined", first));
                assertTrue(first.awaitServerLog("auth precheck routing", 30),
                        () -> failMsgServer("auth precheck routing", first));
            }

            try (MccDriver.MccRun second = MccDriver.launch("R-CANONICAL-2", username, false)) {
                assertTrue(second.awaitJoin(90),
                        () -> failMsg("second canonical collision login", "Server was successfully joined", second));
                // If routing falls back to the canonical UUID, this precheck log is absent.
                assertTrue(second.awaitServerLog("auth precheck routing " + username, 30),
                        () -> failMsgServer("auth precheck routing " + username, second));
            }
        } finally {
            server.rcon("/auth remove " + canonicalUuid);
        }
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
