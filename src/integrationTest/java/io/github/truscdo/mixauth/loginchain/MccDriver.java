// ============================================================================
// MCC（无头 Minecraft 客户端）子进程驱动。每次 launch() 对应一个 @Test 场景：
//   生成 .ini 配置与输入文件，通过 MCC_FILE_INPUT/MCC_INPUT_FILE 环境变量注入，
//   记录 PID，并在客户端 *.out.log 与服务器 logs/latest.log 上进行双通道断言。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class MccDriver {
    private static final String YGGDRASIL_PASSWORD = "test-password";

    private MccDriver() {
    }

    /**
     * 启动一个 MCC 子进程并返回用于断言的句柄。
     *
     * @param id     场景标识（也用于文件名，如 .ini/.pid/.out.log 前缀）
     * @param user   游戏内玩家名
     * @param nojoin 为 true 时不等待成功入服（客户端预期在登录前失败时使用）
     */
    public static MccRun launch(String id, String user, boolean nojoin) throws IOException {
        return launchProcess(id, user, nojoin, false);
    }

    /** Start MCC against the local Yggdrasil test service. */
    public static MccRun launchYggdrasil(String id, String user, boolean nojoin) throws IOException {
        return launchProcess(id, user, nojoin, true);
    }

    private static MccRun launchProcess(String id, String user, boolean nojoin, boolean yggdrasil)
            throws IOException {
        String mcc = LctConfig.mccExe();
        if (mcc == null) {
            throw new IllegalStateException(
                    "MCC binary not found - set MCC_EXE (exact path) or MCC_DIR (directory)");
        }
        Processes.log("  MCC start: " + id + " user=" + user + (nojoin ? " (nojoin)" : ""));
        Path in = inputFile(id);
        Path out = outLog(id);
        Files.writeString(in, "", StandardCharsets.UTF_8); // create empty input
        Files.deleteIfExists(out);
        if (yggdrasil) {
            writeYggdrasilIni(id, user);
        } else {
            writeIni(id, user);
        }

        String password = yggdrasil ? YGGDRASIL_PASSWORD : "-";
        List<String> cmd = List.of(mcc, ini(id).toString(), user, password,
                "127.0.0.1:" + LctConfig.SPORT,
                "--minecraftversion=" + LctConfig.MC, "--language=en");
        Map<String, String> env = Map.of("MCC_FILE_INPUT", "1", "MCC_INPUT_FILE", in.toString());
        Process p = Processes.startProc(cmd, null, out, env);
        try {
            Files.writeString(pidFile(id), String.valueOf(p.pid()), StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
        }
        return new MccRun(p, in, out, id, nojoin);
    }

    // ---- 每个场景各自的临时文件路径 ----
    public static Path ini(String id) {
        return LoginChainRunDir.mccBase().resolve(id + ".ini");
    }

    public static Path inputFile(String id) {
        return LoginChainRunDir.mccBase().resolve(id + ".input.txt");
    }

    public static Path outLog(String id) {
        return LoginChainRunDir.mccBase().resolve(id + ".out.log");
    }

    public static Path chatLog(String id) {
        return LoginChainRunDir.mccBase().resolve(id + ".chat.log");
    }

    public static Path pidFile(String id) {
        return LoginChainRunDir.mccBase().resolve(id + ".pid");
    }

    static void writeIni(String id, String user) throws IOException {
        String chatFwd = chatLog(id).toString().replace('\\', '/');
        String content = String.join("\r\n",
                "[Main]",
                "[Main.General]",
                "Account = { Login = \"" + user + "\", Password = \"-\" }",
                "Server = { Host = \"127.0.0.1\", Port = " + LctConfig.SPORT + " }",
                "AccountType = \"mojang\"",
                "MinecraftVersion = \"" + LctConfig.MC + "\"",
                "[Main.Advanced]",
                "ChatbotLogFile = \"" + chatFwd + "\"",
                "Language = \"en\"",
                "LoadMccTranslation = false",
                "LoadResourcePackTranslations = false",
                "EnableSentry = false",
                "ExitOnFailure = true",
                "InternalCmdChar = \"slash\"",
                "SessionCache = \"none\"",
                "ProfileKeyCache = \"none\"",
                "[Console.General]",
                "ConsoleColorMode = \"disable\"") + "\r\n";
        Files.writeString(ini(id), content, StandardCharsets.UTF_8);
    }

    static void writeYggdrasilIni(String id, String user) throws IOException {
        String chatFwd = chatLog(id).toString().replace('\\', '/');
        String content = String.join("\r\n",
                "[Main]",
                "[Main.General]",
                "Account = { Login = \"" + user + "\", Password = \"" + YGGDRASIL_PASSWORD + "\" }",
                "Server = { Host = \"127.0.0.1\", Port = " + LctConfig.SPORT + " }",
                "AccountType = \"yggdrasil\"",
                "AuthServerUrl = \"http://127.0.0.1:" + LctConfig.MOCK_PORT + "/\"",
                "MinecraftVersion = \"" + LctConfig.MC + "\"",
                "[Main.Advanced]",
                "ChatbotLogFile = \"" + chatFwd + "\"",
                "Language = \"en\"",
                "LoadMccTranslation = false",
                "LoadResourcePackTranslations = false",
                "EnableSentry = false",
                "ExitOnFailure = true",
                "InternalCmdChar = \"slash\"",
                "SessionCache = \"none\"",
                "ProfileKeyCache = \"none\"",
                "[Console.General]",
                "ConsoleColorMode = \"disable\"") + "\r\n";
        Files.writeString(ini(id), content, StandardCharsets.UTF_8);
    }

    /** 一个运行中的 MCC 子进程的句柄，附带客户端/服务器双通道断言方法。 */
    public static final class MccRun implements AutoCloseable {

        private final Process proc;
        private final Path in;
        private final Path out;
        private final String id;
        private final boolean nojoin;

        MccRun(Process proc, Path in, Path out, String id, boolean nojoin) {
            this.proc = proc;
            this.in = in;
            this.out = out;
            this.id = id;
            this.nojoin = nojoin;
        }

        public String id() {
            return id;
        }

        public Path out() {
            return out;
        }

        public boolean nojoin() {
            return nojoin;
        }

        /** 等待客户端成功入服（仅用于预期正常入服的场景）。 */
        public boolean awaitJoin(int timeoutSec) {
            return awaitClientLog("Server was successfully joined", timeoutSec);
        }

        /** 等待 MCC 客户端自身日志中出现指定模式。 */
        public boolean awaitClientLog(String pat, int timeoutSec) {
            return LogMatcher.waitForPattern(out, pat, timeoutSec);
        }

        /** 在服务器 latest.log 中等待指定模式。 */
        public boolean awaitServerLog(String pat, int timeoutSec) {
            return LogMatcher.waitForPattern(LoginChainRunDir.serverLog(), pat, timeoutSec);
        }

        /**
         * 向 MCC 输入文件写入命令。{@code ;} 分隔的命令串会被拆成每行一条 ——
         * 这也是让 MCC 的 `wait N` 指令生效的关键（例如
         * "/login wrongpass;wait 2;/login wrongpass"）。
         */
        public void sendInput(String commands) {
            try {
                if (!commands.isEmpty()) {
                    Files.writeString(in, String.join(System.lineSeparator(), commands.split(";")),
                            StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
            }
        }

        public List<String> clientLogTail(int n) {
            return LogMatcher.tail(out, n);
        }

        public List<String> serverLogTail(int n) {
            return LogMatcher.tail(LoginChainRunDir.serverLog(), n);
        }

        public void dumpClientLog() {
            LogMatcher.dumpLog(out);
        }

        @Override
        public void close() {
            try {
                Files.writeString(in, "quit", StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
            if (!Processes.waitExit(proc, 20)) {
                Processes.log("  MCC did not exit, killing: " + id);
                Processes.kill(proc);
            }
            // 清理临时文件：pid/input 可丢弃；*.out.log 保留用于失败归档
            try {
                Files.deleteIfExists(pidFile(id));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(inputFile(id));
            } catch (IOException ignored) {
            }
        }
    }
}
