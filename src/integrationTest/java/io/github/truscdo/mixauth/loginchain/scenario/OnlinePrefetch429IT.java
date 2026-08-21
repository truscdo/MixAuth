// 在线预检：登录前查询 profile 返回 HTTP 429（限流）时，客户端应被干净拒绝，
// 不能回退为离线成功入服。
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import io.github.truscdo.mixauth.loginchain.Processes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("online")
public class OnlinePrefetch429IT extends LoginChainITBase {

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
}