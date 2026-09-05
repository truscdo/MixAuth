// 在线预检场景共享一套 mock 与真实服务器，只在测试方法间切换 mock 响应。
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import io.github.truscdo.mixauth.loginchain.Processes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("online")
public class OnlinePrefetchIT extends LoginChainITBase {

    @Test
    @DisplayName("路由回归：同名玩家更换 client UUID 后仍执行登录前预检")
    public void canonicalUuidIsNotUsedAsClientUuidFallback() throws Exception {
        String username = "AliasRouteTest";
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
                assertTrue(second.awaitServerLog("auth precheck routing " + username, 30),
                        () -> failMsgServer("auth precheck routing " + username, second));
            }
        } finally {
            server.rcon("/auth remove " + canonicalUuid);
        }
    }

    @Test
    @DisplayName("在线预检：profile 查询返回 429 限流 → 拒绝，不回退离线")
    public void prefetch429RateLimited() throws Exception {
        assertTrue(mock.setMode("429", "online"), "mock /_mock/mode switch failed");
        try (MccDriver.MccRun r = MccDriver.launch("M2", "RateTester", true)) {
            assertTrue(r.awaitClientLog("Mojang authentication servers are temporarily unavailable", 90),
                    () -> failMsg("429 client", "Mojang authentication servers are temporarily unavailable", r));
            assertTrue(r.awaitServerLog("rate limited", 30),
                    () -> failMsgServer("rate limited", r));
        }
        Processes.log("  PASS: 在线预检 429 限流");
    }

    @Test
    @DisplayName("在线预检：profile 查询返回畸形数据 → 客户端/服务器双通道报解析失败")
    public void prefetchMalformedDisconnectsCleanly() throws Exception {
        assertTrue(mock.setMode("malformed", "online"), "mock /_mock/mode switch failed");
        try (MccDriver.MccRun r = MccDriver.launch("M3", "MalTester", true)) {
            assertTrue(r.awaitClientLog("returned malformed data", 90),
                    () -> failMsg("malformed client", "returned malformed data", r));
            assertTrue(r.awaitServerLog("Failed to parse", 30),
                    () -> failMsgServer("Failed to parse", r));
        }
        Processes.log("  PASS: 在线预检 畸形数据");
    }
}
