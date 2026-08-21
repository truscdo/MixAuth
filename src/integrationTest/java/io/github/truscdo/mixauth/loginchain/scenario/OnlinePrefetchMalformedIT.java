// 在线预检：登录前查询 profile 返回畸形（非 JSON）响应时，解析失败必须在
// 客户端与服务器两侧同时体现。
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import io.github.truscdo.mixauth.loginchain.Processes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("online")
public class OnlinePrefetchMalformedIT extends LoginChainITBase {

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