// ============================================================================
// 离线注册/登录链：
//   整个类共享一次服务器会话（基类 @BeforeAll 只启动一次服务器），因此各
//   步骤共用同一个玩家库并严格按序执行。MCC 离线客户端可能使用随机
//   client UUID，因此通过 mock 让 Mojang profile 预检稳定返回 404，再进入离线链。
//
// 四次连接顺序：
//   ① 未注册玩家 → 注册提示 → /register 注册并自动登录
//   ② 重进 → 登录提示 → 正确密码放行
//   ③ 重进 → 连续三次错误密码 → 临时封禁并断线
//   ④ 封禁期间重进 → 被拒绝（nojoin）
//
// 运行：run-login-it.bat --offline，或
//   gradlew integrationTest -Plct.layer=offline -Pminecraft_version=1.21.5 ...
// ============================================================================
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import io.github.truscdo.mixauth.loginchain.Processes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("offline")
public class OfflineChainIT extends LoginChainITBase {

    /** 测试使用的离线玩家名。 */
    private static final String USER = "TestPlayer";

    @Override
    protected boolean useMock() {
        return true;
    }

    @BeforeAll
    void configureOfflineProfileLookup() {
        assertTrue(mock.setMode("404", "online"), "mock /_mock/mode switch failed");
    }

    @Test
    @Order(1)
    @DisplayName("离线链①：注册提示 → 注册自动登录")
    public void s1RegisterPromptAndAutoLogin() throws Exception {
        try (MccDriver.MccRun r = MccDriver.launch("S1", USER, false)) {
            assertTrue(r.awaitJoin(90), () -> fail("离线链① 入服", "Server was successfully joined", r));
            assertTrue(r.awaitClientLog("You are not registered. Use /register", 30),
                    () -> fail("离线链① 客户端", "You are not registered. Use /register", r));
            assertTrue(r.awaitServerLog("auth precheck routing", 30),
                    () -> failServer("auth precheck routing", r));
            r.sendInput("/register " + PASSWORD + " " + PASSWORD);
            assertTrue(r.awaitClientLog("Registration successful. Logged in automatically.", 30),
                    () -> fail("离线链① 客户端", "Registration successful. Logged in automatically.", r));
        }
        Processes.log("  PASS: 离线链①");
    }

    @Test
    @Order(2)
    @DisplayName("离线链②：重进登录提示 → 正确密码放行")
    public void s2RejoinPromptAndLoginCorrect() throws Exception {
        try (MccDriver.MccRun r = MccDriver.launch("S2", USER, false)) {
            assertTrue(r.awaitJoin(90), () -> fail("离线链② 入服", "Server was successfully joined", r));
            assertTrue(r.awaitClientLog("This account is registered. Use /login", 30),
                    () -> fail("离线链② 客户端", "This account is registered. Use /login", r));
            assertTrue(r.awaitServerLog("auth precheck routing " + USER, 30),
                    () -> failServer("auth precheck routing " + USER, r));
            r.sendInput("/login " + PASSWORD);
            assertTrue(r.awaitClientLog("Login successful.", 30),
                    () -> fail("离线链② 客户端", "Login successful.", r));
        }
        Processes.log("  PASS: 离线链②");
    }

    @Test
    @Order(3)
    @DisplayName("离线链③：三次错误密码 → 临时封禁断线")
    public void s3ThreeWrongPasswordsBlock() throws Exception {
        try (MccDriver.MccRun r = MccDriver.launch("S3", USER, false)) {
            assertTrue(r.awaitJoin(90), () -> fail("离线链③ 入服", "Server was successfully joined", r));
            assertTrue(r.awaitClientLog("This account is registered. Use /login", 30),
                    () -> fail("离线链③ 客户端", "This account is registered. Use /login", r));
            r.sendInput("/login wrongpass;wait 2;/login wrongpass;wait 2;/login wrongpass");
            assertTrue(r.awaitClientLog("Incorrect password.", 30),
                    () -> fail("离线链③ 客户端", "Incorrect password.", r));
            assertTrue(r.awaitClientLog("Too many incorrect password attempts.", 30),
                    () -> fail("离线链③ 客户端", "Too many incorrect password attempts.", r));
        }
        Processes.log("  PASS: 离线链③");
    }

    @Test
    @Order(4)
    @DisplayName("离线链④：封禁中重进被拒")
    public void s4BlockedRejoin() throws Exception {
        try (MccDriver.MccRun r = MccDriver.launch("S4", USER, true)) {
            assertTrue(r.awaitClientLog("temporarily blocked from joining", 90),
                    () -> fail("离线链④ 客户端", "temporarily blocked from joining", r));
        }
        Processes.log("  PASS: 离线链④");
    }

    // ---- 断言失败消息构造：附带客户端/服务器日志尾部，便于定位 ----

    private static String fail(String channel, String expected, MccDriver.MccRun r) {
        StringBuilder sb = new StringBuilder();
        sb.append("offline assert failed: ").append(channel)
                .append("; expected pattern '").append(expected).append("'; client log tail:").append('\n');
        for (String line : r.clientLogTail(20))
            sb.append("    | ").append(line).append('\n');
        return sb.toString();
    }

    private static String failServer(String expected, MccDriver.MccRun r) {
        StringBuilder sb = new StringBuilder();
        sb.append("offline server assert failed; expected pattern '").append(expected)
                .append("'; server log tail:").append('\n');
        for (String line : r.serverLogTail(20))
            sb.append("    | ").append(line).append('\n');
        return sb.toString();
    }
}
