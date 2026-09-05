// ============================================================================
// 服务器生命周期管理：直接用 devlaunch 启动服务器（不走 `gradlew runServer`）。
//   在线预检层：通过 -Dmixauth.profile_lookup_url / -Dmixauth.has_joined_url
//   注入 mock sessionserver 的地址；离线层不注入。统一使用
//   -Dfml.modFolders=mixauth%%... 指定 mod 的编译产物与资源目录。
//
// build() 为当前选择的版本重新运行 gradlew prepareServerRun classes
// createServerLaunchScript（默认会先删除编译产物；可通过 CLEAN_BUILD=false 关闭，
// 由外层脚本逐版本预先清理时使用）。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class IntegrationServerManager {

    private Process serverProc;
    private boolean started;

    /** 为当前选中的版本运行 Gradle 准备任务，生成服务器启动所需的 moddev 产物。 */
    public boolean build() throws IOException, InterruptedException {
        Processes.log("=== build: " + LctConfig.MC + " / " + LctConfig.NEO + " ===");
        Path gradlewBat = LctConfig.PROJECT.resolve("gradlew.bat");
        Path gradlew = LctConfig.PROJECT.resolve("gradlew");
        List<String> base;
        if (Files.exists(gradlewBat)) {
            base = new ArrayList<>(List.of("cmd", "/c", gradlewBat.toString()));
        } else if (Files.exists(gradlew)) {
            if (LctConfig.IS_WINDOWS) {
                Processes.log("ERROR: only ./gradlew found on Windows; use gradlew.bat");
                return false;
            }
            base = new ArrayList<>(List.of(gradlew.toString()));
        } else {
            Processes.log("ERROR: gradlew.bat or gradlew not found in " + LctConfig.PROJECT);
            return false;
        }

        // 跨版本切换时需要删除编译/资源产物强制重建（Gradle 配置缓存按属性
        // 「名称」而非「值」判断 up-to-date，版本切换可能误判为无需重建）。
        // 外层脚本已逐版本预清理并关闭 CLEAN_BUILD 时，此处不再重复清理。
        if (LctConfig.CLEAN_BUILD) {
            Processes.deleteRecursive(LctConfig.BUILD_DIR.resolve("classes"));
            Processes.deleteRecursive(LctConfig.BUILD_DIR.resolve("libs"));
            Processes.deleteRecursive(LctConfig.BUILD_DIR.resolve("resources"));
        }
        for (String f : List.of("serverRunClasspath.txt", "serverRunVmArgs.txt",
                "serverRunProgramArgs.txt", "serverLegacyClasspath.txt", "serverLog4j2.xml",
                "runServer.cmd", "minecraft_assets.properties")) {
            try {
                Files.deleteIfExists(LctConfig.BUILD_DIR.resolve("moddev/" + f));
            } catch (IOException ignored) {
            }
        }

        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of(
                "prepareServerRun", "classes", "createServerLaunchScript",
                "--no-configuration-cache", "--console=plain", "-q",
                "-Pminecraft_version=" + LctConfig.MC,
                "-Pneo_version=" + LctConfig.NEO,
                "-Pparchment_minecraft_version=" + LctConfig.PARCHMENT_MC,
                "-Pparchment_mappings_version=" + LctConfig.PARCHMENT_MAP,
                "-Pminecraft_version_range=" + LctConfig.MC_RANGE));
        Processes.log("  gradle: " + Processes.commandLine(cmd));
        Process p = Processes.startProc(cmd, LctConfig.PROJECT,
                LctConfig.BUILD_DIR.resolve("gradle-run.log"), null);
        boolean ok = Processes.waitExit(p, 600);
        return ok && p.exitValue() == 0;
    }

    /**
     * 以 devlaunch 方式启动服务器（携带 -Dfml.modFolders）。仅当
     * {@code withMock} 为 true 时注入 mock sessionserver 的地址；离线
     * 注册/登录链不查询 Mojang，因此不注入。
     */
    public boolean start(boolean withMock) throws IOException {
        Processes.log("=== start server ===");
        Path moddir = LctConfig.BUILD_DIR.resolve("moddev");
        String classesDir = LctConfig.BUILD_DIR.resolve("classes/java/main").toString();
        String resourcesDir = LctConfig.BUILD_DIR.resolve("resources/main").toString();

        List<String> cmd = new ArrayList<>();
        cmd.add(LctConfig.JAVA_BIN);
        if (withMock) {
            // 注入 mock 地址参数（mockArgs() 里的两个 -D 参数需拆分后逐个添加）
            for (String token : mockArgs().split(" (?=-D)"))
                cmd.add(token);
        }
        cmd.add("@" + moddir.resolve("serverRunClasspath.txt"));
        cmd.add("@" + moddir.resolve("serverRunVmArgs.txt"));
        cmd.add("-Dfml.modFolders=mixauth%%" + classesDir + ";mixauth%%" + resourcesDir);
        cmd.add("net.neoforged.devlaunch.Main");
        cmd.add("@" + moddir.resolve("serverRunProgramArgs.txt"));

        Path out = LoginChainRunDir.serverOut();
        Processes.log("  server cmd: " + Processes.commandLine(cmd));
        serverProc = Processes.startProc(cmd, LoginChainRunDir.runDir(), out, null);
        try {
            Files.writeString(LoginChainRunDir.runDir().resolve("server.pid"),
                    String.valueOf(serverProc.pid()), StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
        }
        Processes.log("  server launched (stop via RCON)");
        started = true;
        return true;
    }

    /** 等待服务器就绪：日志出现 "Done" 且游戏端口可连接（最长 180 秒）。 */
    public boolean waitReady() {
        Processes.log("=== wait server ready ===");
        long deadline = System.nanoTime() + 180_000_000_000L; // 180s
        int sec = 0;
        while (System.nanoTime() < deadline) {
            if (LogMatcher.contains(LoginChainRunDir.serverLog(), "Done")
                    && Processes.portListening(LctConfig.SPORT)) {
                Processes.log("  server ready after ~" + sec + "s");
                return true;
            }
            Processes.sleepMs(1000);
            sec++;
        }
        Processes.log("  server not ready after timeout (" + LoginChainRunDir.serverLog() + ")");
        return false;
    }

    /** 通过 RCON 执行 list 探测服务器可用性（最长 15 秒）。 */
    public boolean rconProbe() {
        Processes.log("=== RCON probe ===");
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            String out = rcon("list");
            if (!out.isEmpty()) {
                Processes.log("  RCON ready: " + out.trim());
                return true;
            }
            Processes.sleepMs(1000);
        }
        Processes.log("  RCON not ready");
        return false;
    }

    public String rcon(String command) {
        try {
            RconClient rc = new RconClient("127.0.0.1", LctConfig.RCON_PORT, LctConfig.RCON_PW);
            try {
                return rc.sendCommand(command);
            } finally {
                rc.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** 停止服务器：RCON 停服 → 等待端口释放 → 超时时采集 jstack 并强杀兜底。 */
    public void stop() {
        if (!started) {
            Processes.log("  stop server: not started, skip");
            return;
        }
        Processes.log("=== stop server ===");
        long serverPid = serverProc == null ? -1 : serverProc.pid();
        rcon("stop");
        int i = 0;
        while (i < 45) {
            if (!Processes.portListening(LctConfig.SPORT)) {
                break;
            }
            Processes.sleepMs(1000);
            i++;
        }
        boolean exited = serverProc == null || Processes.waitExit(serverProc, i < 45 ? 15 : 1);
        if (i >= 45 || !exited) {
            captureThreadDump(serverPid);
            Processes.log("  stop timeout, killing " + (serverPid > 0 ? serverPid : "?"));
            Processes.kill(serverProc);
        } else {
            Processes.log("  server stopped");
        }
        started = false;
    }

    private void captureThreadDump(long serverPid) {
        if (serverPid <= 0)
            return;
        Path jstack = LoginChainRunDir.runDir().resolve("logs/stop-jstack.txt");
        try {
            Files.deleteIfExists(jstack);
            List<String> cmd = List.of(LctConfig.JCMD_BIN, String.valueOf(serverPid), "Thread.print");
            Process p = Processes.startProc(cmd, null, jstack, null);
            p.waitFor(8, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    static String mockArgs() {
        return "-Dmixauth.profile_lookup_url=http://127.0.0.1:" + LctConfig.MOCK_PORT
                + "/minecraft/profile/lookup/name/ "
                + "-Dmixauth.has_joined_url=http://127.0.0.1:" + LctConfig.MOCK_PORT
                + "/session/minecraft/hasJoined";
    }
}
