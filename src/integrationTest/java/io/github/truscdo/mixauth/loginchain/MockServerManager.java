// ============================================================================
// mock sessionserver 的子进程管理：负责启动/停止本次测试自带的模拟 Mojang
// 服务，并支持在运行中通过 /_mock/mode 切换响应模式。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class MockServerManager {

    private Process mockProc;
    private boolean started;

    public boolean start() throws IOException {
        Processes.log("=== start mock sessionserver ===");
        // mock 位于 integrationTest 源集，直接用本测试 JVM 的 classpath
        // 在子 JVM 中启动其主类。
        List<String> cmd = List.of(LctConfig.JAVA_BIN,
                "-cp", System.getProperty("java.class.path"),
                "io.github.truscdo.mixauth.loginchain.testmock.MockSessionServer",
                "--port", String.valueOf(LctConfig.MOCK_PORT),
                "--profile-mode", "online",
                "--hasjoined-mode", "online");
        mockProc = Processes.startProc(cmd, null, LoginChainRunDir.mockLog(), null);
        Processes.log("  mock launched");
        int i = 0;
        while (i < 10 && !Processes.portListening(LctConfig.MOCK_PORT)) {
            Processes.sleepMs(500);
            i++;
        }
        if (!Processes.portListening(LctConfig.MOCK_PORT)) {
            Processes.log("  FAIL: mock not listening on " + LctConfig.MOCK_PORT);
            return false;
        }
        try {
            Files.writeString(LoginChainRunDir.runDir().resolve("mock.pid"),
                    String.valueOf(mockProc.pid()), StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
        }
        started = true;
        return true;
    }

    public void stop() {
        if (!started) {
            Processes.log("  stop mock: not started, skip");
            return;
        }
        Processes.kill(mockProc);
        try {
            Path pidFile = LoginChainRunDir.runDir().resolve("mock.pid");
            if (Files.exists(pidFile)) {
                String pid = Files.readString(pidFile, StandardCharsets.US_ASCII).trim();
                if (!pid.isEmpty())
                    ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (IOException ignored) {
        }
        started = false;
    }

    /** 运行中切换 mock 的响应模式：GET /_mock/mode?profile=..&hasjoined=.. */
    public boolean setMode(String profile, String hasjoined) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + LctConfig.MOCK_PORT
                            + "/_mock/mode?profile=" + profile + "&hasjoined=" + hasjoined))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}