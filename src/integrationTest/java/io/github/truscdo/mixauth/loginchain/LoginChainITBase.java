// ============================================================================
// 登录链集成测试基类（PER_CLASS）：统一管理服务器、mock sessionserver 与
// MCC 客户端子进程的完整生命周期。
//
//   @BeforeAll  构建/准备服务器运行目录（可跳过构建）
//               → 启动 mock（在线预检层）→ 启动服务器 → 等待就绪 → RCON 探活
//   每个 @Test  切换 mock 响应模式 → 启动 MCC → 客户端/服务器双通道断言
//   @AfterAll   RCON 停服 → jstack 采样 → 兜底强杀 → 停止 mock
//               → 归档日志到 build/reports/integrationTest/artifacts/<类名>/
//
// 约定：
//   - 未配置 MCC 二进制时整层跳过（抛 TestAbortedException，不判失败）。
//   - 某类所有 @Test 均 @Disabled 时，不启动服务器，直接整体跳过。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class LoginChainITBase {

    /** 测试使用的默认密码。 */
    protected static final String PASSWORD = "testpass123";

    /**
     * 是否需要启动 mock sessionserver（模拟 Mojang）。在线预检层默认启动；
     * 离线注册/登录链覆写为 false —— 离线登录从不查询 Mojang。
     */
    protected boolean useMock() {
        return true;
    }

    protected IntegrationServerManager server;
    protected MockServerManager mock;
    protected Path serverLog;
    private String scenarioName;
    private boolean skipArchive;

    @BeforeAll
    void setupL3() throws IOException, InterruptedException {
        scenarioName = getClass().getSimpleName();
        if (allTestsDisabled()) {
            skipArchive = true;
            Processes.log("=== " + scenarioName + ": all tests @Disabled - skipping server startup ===");
            return;
        }
        mccAvailabilityOrAbort();
        try {
            server = new IntegrationServerManager();
            if (!LctConfig.NO_BUILD) {
                if (!server.build()) {
                    throw new RuntimeException(
                            "gradle prepareServerRun/classes failed (see " + LctConfig.BUILD_DIR + "/gradle-run.log)");
                }
            } else {
                Processes.log("skip build");
            }
            LoginChainRunDir.prepare(LctConfig.TRUSTED);
            if (useMock()) {
                mock = new MockServerManager();
                if (!mock.start())
                    throw new RuntimeException("mock startup failed");
            }
            if (!server.start(useMock()))
                throw new RuntimeException("server startup failed");
            if (!server.waitReady())
                throw new RuntimeException("server not ready within 180s");
            if (!server.rconProbe())
                throw new RuntimeException("RCON not ready within 15s");
            serverLog = LoginChainRunDir.serverLog();
            Processes.log("=== " + scenarioName + " ready ===");
        } catch (IOException | RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    @AfterAll
    void teardownL3() {
        shutdown();
    }

    // ---- lifecycle helpers ----

    private void shutdown() {
        try {
            if (server != null)
                server.stop();
        } catch (Exception e) {
            Processes.log("  stop server error: " + e);
        }
        try {
            if (mock != null)
                mock.stop();
        } catch (Exception e) {
            Processes.log("  stop mock error: " + e);
        }
        if (!skipArchive)
            archiveCurrentLogs();
    }

    private void mccAvailabilityOrAbort() {
        if (LctConfig.mccExe() == null) {
            throw new TestAbortedException(
                    "MCC binary not found - set MCC_EXE or MCC_DIR; integration-test layer skipped");
        }
    }

    private boolean allTestsDisabled() {
        // getMethods() 只返回 public 方法；改用 getDeclaredMethods() 沿继承链
        // 扫描，避免把包级（非 public）的 @Test 方法误判为「全部禁用」。
        Class<?> c = getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class) && !m.isAnnotationPresent(Disabled.class))
                    return false;
            }
            c = c.getSuperclass();
        }
        return true;
    }

    /** 场景结束后把服务器与 MCC 日志归档到报告目录，作为失败时的现场证据。 */
    private void archiveCurrentLogs() {
        try {
            Path dest = LctConfig.ARTIFACTS_DIR.resolve(scenarioName);
            Files.createDirectories(dest);
            Path latest = LoginChainRunDir.serverLog();
            if (Files.exists(latest)) {
                Files.copy(latest, dest.resolve("latest.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path outStd = LoginChainRunDir.serverOut();
            if (Files.exists(outStd)) {
                Files.copy(outStd, dest.resolve("server-stdout.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path mcc = LoginChainRunDir.mccBase();
            if (Files.exists(mcc)) {
                try (var walk = Files.walk(mcc)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".out.log"))
                            .forEach(p -> {
                                try {
                                    Files.copy(p, dest.resolve(p.getFileName().toString()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
            Processes.log("  artifacts archived: " + dest);
        } catch (IOException e) {
            Processes.log("  artifact archive skipped: " + e);
        }
    }

    // ---- 断言失败消息构造：附带客户端/服务器日志尾部，便于定位 ----

    protected static String failMsg(String channel, String expected, MccDriver.MccRun r) {
        StringBuilder sb = new StringBuilder();
        sb.append("登录链断言失败: ").append(channel)
                .append("; expected pattern '").append(expected).append("'; client log tail:").append('\n');
        for (String line : r.clientLogTail(20))
            sb.append("    | ").append(line).append('\n');
        return sb.toString();
    }

    protected static String failMsgServer(String expected, MccDriver.MccRun r) {
        StringBuilder sb = new StringBuilder();
        sb.append("登录链服务器断言失败；期望模式 '").append(expected)
                .append("'; server log tail:").append('\n');
        for (String line : r.serverLogTail(20))
            sb.append("    | ").append(line).append('\n');
        return sb.toString();
    }

    protected static List<String> tail(Path f, int n) {
        return LogMatcher.tail(f, n);
    }
}