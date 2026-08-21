// 在线加密握手 与 hasJoined 返回 500 两个场景暂挂起（@Disabled）。
//
// 恢复条件（实现后移除类级 @Disabled 并补齐断言文本，框架无需改动）：
//   1. 先确认离线 MCC 能走通 MixAuth 的在线加密握手流程
//      （拦截握手传球 → OnlineHandshakeValidationService.handleKey → requestHasJoined）
//   2. mock 需按请求的玩家名返回 UUID 匹配的 profile
//      （--profile-mode online 动态 UUID，或 --profile-uuid 直接指定），
//      使登录前预检能进入在线握手分支
//   3. 握手场景：切换 mock 模式前需先 recordKnownPlayer 建立玩家索引
//   4. hasJoined 500 场景：UUID 匹配使 hasJoined 可达后，验证
//      「hasJoined 500 不回退离线」的安全语义未被破坏
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("online")
@Disabled("恢复条件")
public class OnlineHandshakePendingIT extends LoginChainITBase {

    @Test
    @DisplayName("在线预检：正常在线握手 → 以 Online 模式入服（挂起中）")
    public void onlineHandshakeM1() throws Exception {
        // 前提：切换 mock 为正常在线响应，且返回的 UUID 与客户端一致
        assertTrue(mock.setMode("online", "online"), "mock /_mock/mode switch failed");
        try (MccDriver.MccRun r = MccDriver.launch("M1", "OnlineTester", false)) {
            // 期望：hasJoined 返回 200 → 正常进服并处于 Online 登录模式
            assertTrue(r.awaitServerLog("Current login mode: Online", 90),
                    () -> failMsgServer("Current login mode: Online", r));
        }
    }

    @Test
    @DisplayName("在线预检：hasJoined 返回 500 → 断线且不回退离线（挂起中）")
    public void hasJoined500Disconnects() throws Exception {
        // 只有 UUID 匹配、hasJoined 请求可达时，500 才有机会被触发
        assertTrue(mock.setMode("online", "500"), "mock /_mock/mode switch failed");
        try (MccDriver.MccRun r = MccDriver.launch("M4", "HasJoinedTester", false)) {
            assertTrue(r.awaitClientLog("InternalServerError", 90),
                    () -> failMsg("hasJoined500 client", "InternalServerError", r));
            assertTrue(r.awaitServerLog("hasJoined", 30),
                    () -> failMsgServer("hasJoined", r));
        }
    }
}