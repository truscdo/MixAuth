// ============================================================================
// MixAuth login-chain test orchestrator (L2 offline / L3 mock online)
// Cross-platform single-file Java source launcher (JEP 330, JDK 21+, no third-party deps).
//
// Entry points (JEP 330 does NOT allow a shebang in a .java-named file):
//   Windows  : testutil/run-login-chain.bat <mc-version> [opts]   (thin forwarder)
//   POSIX    : testutil/run-login-chain <mc-version> [opts]       (shell wrapper)
//   any OS   : java --source 21 testutil/RunLoginChain.java <mc-version> [opts]
//
// This is the stage-B replacement for testutil/run-login-chain-test.bat.
// It reproduces the same flow and semantics as the .bat (including the S1-S7 /
// M2-M3 scenario matrix and assertion texts) using only the JDK standard library.
//
// CLI (mirrors the .bat):
//   java --source 21 RunLoginChain.java <mc-version> [--mock] [--trusted]
//                                             [--user NAME] [--no-build] [--scenario ID]
//
// Env overrides (stage-A compat, same fallback chain):
//   MCC_EXE, MCC_DIR, JDK_EXE, JAVA_HOME, RCON_PW, RCON_PORT, SPORT, MOCK_PORT
//
// Notes on parity with the .bat:
//   - JVM receives fml.modFolders with a DOUBLE percent separator (mixauth%%path),
//     the exact value the validated .bat template produces (verified on 1.21.5).
//   - Server is launched via ProcessBuilder directly (no .cmd hop); the generated
//     start-vanilla.cmd is kept only as a diagnostic artefact (manual re-run).
//   - PID file contract (stage-A A3) is kept: mock.pid + <id>.pid are written.
//   - Port probe (stage-A A5): Socket connect to 127.0.0.1:port.
//   - All child processes are registered and forcibly killed on JVM exit
//     (shutdown hook) to avoid stray java/JavaServer/MCC processes.
// ============================================================================

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class RunLoginChain {

    // ---- constants (mirror the .bat defaults) ----
    static final String PASSWORD = "testpass123";
    static final int TRUSTED_HOURS = 24;
    static final String RUN_DIR_NAME = "run-login";
    // placeholder UUID never used; kept for parity with MockSessionServer default

    // ---- version catalog (authoritative source: build-matrix.bat, 4 versions;
    // scenario matrix currently enables 3: 1.21.1 / 1.21.5 / 1.21.11) ----
    record Version(String neo, String parchmentMc, String parchmentMap, String mcRange) {
    }

    static final Map<String, Version> VERSIONS = Map.of(
            "1.21.1", new Version("21.1.1", "1.21.1", "2024.11.17", "[1.21.1]"),
            "1.21.5", new Version("21.5.98", "1.21.5", "2025.06.15", "[1.21.5]"),
            "1.21.8", new Version("21.8.54", "1.21.8", "2025.09.14", "[1.21.8]"),
            "1.21.11", new Version("21.11.45", "1.21.11", "2025.12.20", "[1.21.11]"));

    // ---- resolved runtime defaults (env override, then fallback) ----
    static final Path PROJECT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    static String javaBin;
    static String jcmdBin;
    static String mccBin;
    static int rconPort;
    static String rconPw;
    static int sport;
    static int mockPort;

    // ---- CLI options ----
    static final class Options {
        String mc;
        boolean mock;
        boolean trusted;
        String user = "TestPlayer";
        boolean noBuild;
        String scenario = ""; // empty = run all
    }

    // ---- process registry + shutdown hook (stray-process protection) ----
    static final List<Process> REGISTRY = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : REGISTRY) {
                try {
                    if (p.isAlive()) {
                        p.destroyForcibly();
                    }
                } catch (Exception ignored) {
                }
            }
        }));
    }

    // ---- small helpers ----
    static void log(String s) {
        System.out.println(s);
        System.out.flush();
    }

    static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String readUtf8(Path f) {
        try {
            if (!Files.exists(f))
                return "";
            // lenient decode: malformed/illegal bytes -> U+FFFD instead of throwing,
            // so a single non-UTF8 byte elsewhere in the log doesn't hide matched text
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (InputStream is = Files.newInputStream(f);
                    InputStreamReader reader = new InputStreamReader(is, decoder)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1)
                    sb.append(buf, 0, n);
                return sb.toString();
            }
        } catch (IOException e) {
            return "";
        }
    }

    static boolean contains(Path f, String pat) {
        return readUtf8(f).contains(pat);
    }

    static boolean portListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void deleteRecursive(Path dir) {
        if (!Files.exists(dir))
            return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- env resolution (stage-A A1 parity) ----
    static void resolvePaths() {
        // JDK: JDK_EXE -> JAVA_HOME -> PATH ("java"), parity with the wrapper scripts
        String jdk = System.getenv("JDK_EXE");
        if ((jdk == null || jdk.isEmpty()) && System.getenv("JAVA_HOME") != null
                && !System.getenv("JAVA_HOME").isEmpty()) {
            Path jh = Path.of(System.getenv("JAVA_HOME"));
            Path j = jh.resolve(IS_WINDOWS ? "bin\\java.exe" : "bin/java");
            if (Files.exists(j))
                jdk = j.toString();
        }
        if (jdk == null || jdk.isEmpty())
            jdk = "java"; // resolve from PATH
        javaBin = jdk;
        if ("java".equals(jdk)) {
            jcmdBin = "jcmd"; // dotless: resolve from PATH too
        } else {
            Path jdir = Path.of(jdk).getParent();
            jcmdBin = jdir.resolve(IS_WINDOWS ? "jcmd.exe" : "jcmd").toString();
        }

        // MCC: MCC_EXE -> MCC_DIR. No hardcoded default: the client must be
        // provided explicitly (main() validates mccBin before running scenarios).
        String mcc = System.getenv("MCC_EXE");
        if ((mcc == null || mcc.isEmpty()) && System.getenv("MCC_DIR") != null
                && !System.getenv("MCC_DIR").isEmpty()) {
            Path d = Path.of(System.getenv("MCC_DIR"));
            Path f = d.resolve(IS_WINDOWS ? "MinecraftClient.exe" : "MinecraftClient");
            if (Files.exists(f))
                mcc = f.toString();
        }
        mccBin = (mcc == null || mcc.isEmpty()) ? null : mcc;

        rconPw = envOr("RCON_PW", "test123");
        rconPort = Integer.parseInt(envOr("RCON_PORT", "25575"));
        sport = Integer.parseInt(envOr("SPORT", "25565"));
        mockPort = Integer.parseInt(envOr("MOCK_PORT", "18080"));
    }

    static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    // ---- process start helpers ----
    static Process startProc(List<String> cmd, Path dir, Path outLog, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null)
            pb.directory(dir.toFile());
        if (outLog != null) {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outLog.toFile()));
            pb.redirectErrorStream(true);
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectErrorStream(true);
        }
        if (env != null)
            pb.environment().putAll(env);
        Process p = pb.start();
        REGISTRY.add(p);
        return p;
    }

    // ---- wait helpers (stage-A A5 semantics: log marker + port) ----
    static boolean waitForPattern(Path file, String pat, int timeoutSec) {
        long deadline = System.nanoTime() + timeoutSec * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (contains(file, pat))
                return true;
            sleepMs(1000);
        }
        return false;
    }

    static boolean waitExit(Process p, int timeoutSec) {
        try {
            return p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void kill(Process p) {
        try {
            if (p.isAlive())
                p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    // ---- RCON (inlined from RconCli.java, same wire behaviour) ----
    static final class RconClient {
        static final int TYPE_AUTH = 3;
        static final int TYPE_AUTH_RESPONSE = 2;
        static final int TYPE_COMMAND = 2;
        static final int TYPE_RESPONSE = 0;

        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final int requestId;

        RconClient(String host, int port, String password) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(3000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            requestId = (int) (System.nanoTime() & 0x7fffffff) | 0x1000;
            if (!authenticate(password)) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                throw new IOException("RCON auth failed");
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        String sendCommand(String command) throws IOException {
            sendPacket(TYPE_COMMAND, command);
            List<String> parts = new ArrayList<>();
            long deadline = System.nanoTime() + 3_000_000_000L; // 3s total
            boolean gotResponse = false;
            while (System.nanoTime() < deadline) {
                Packet p;
                try {
                    p = readPacket();
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                if (p == null)
                    break;
                if (p.type == TYPE_RESPONSE && p.requestId == requestId) {
                    gotResponse = true;
                    if (!p.body.isEmpty())
                        parts.add(p.body);
                }
            }
            if (!gotResponse && parts.isEmpty())
                return "";
            return String.join("\n", parts) + (parts.isEmpty() ? "" : "\n");
        }

        private boolean authenticate(String password) throws IOException {
            sendPacket(TYPE_AUTH, password);
            while (true) {
                Packet p = readPacket();
                if (p == null)
                    return false;
                if (p.type == TYPE_AUTH_RESPONSE)
                    return p.requestId == requestId;
            }
        }

        private void sendPacket(int type, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            int length = 4 + 4 + payload.length + 2;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeIntLE(buf, length);
            writeIntLE(buf, requestId);
            writeIntLE(buf, type);
            buf.write(payload);
            buf.write(0);
            buf.write(0);
            // single write of the whole packet: the server RconClient reads the
            // packet in one read(); partial/telescoped writes drop the connection.
            out.write(buf.toByteArray());
            out.flush();
        }

        private void writeIntLE(ByteArrayOutputStream b, int v) {
            b.write(v & 0xFF);
            b.write((v >>> 8) & 0xFF);
            b.write((v >>> 16) & 0xFF);
            b.write((v >>> 24) & 0xFF);
        }

        private int readIntLE() throws IOException {
            return in.readUnsignedByte()
                    | (in.readUnsignedByte() << 8)
                    | (in.readUnsignedByte() << 16)
                    | (in.readUnsignedByte() << 24);
        }

        private Packet readPacket() throws IOException {
            try {
                int length = readIntLE();
                if (length < 4 || length > 65536)
                    return null;
                int id = readIntLE();
                int type = readIntLE();
                int bodyLen = length - 8 - 2;
                byte[] body = new byte[Math.max(0, bodyLen)];
                in.readFully(body);
                in.readShort(); // two null bytes
                return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
            } catch (EOFException e) {
                return null;
            }
        }

        record Packet(int requestId, int type, String body) {
        }
    }

    // ---- build (stage-A A4 parity) ----
    static boolean build(Options o, Version v) throws IOException, InterruptedException {
        log("=== build: " + o.mc + " / " + v.neo() + " ===");
        Path gradlewBat = PROJECT.resolve("gradlew.bat");
        Path gradlew = PROJECT.resolve("gradlew");
        List<String> base;
        if (Files.exists(gradlewBat)) {
            base = new ArrayList<>(List.of("cmd", "/c", gradlewBat.toString()));
        } else if (Files.exists(gradlew)) {
            if (IS_WINDOWS) {
                log("ERROR: only ./gradlew found on Windows; use gradlew.bat");
                return false;
            }
            base = new ArrayList<>(List.of(gradlew.toString()));
        } else {
            log("ERROR: gradlew.bat or gradlew not found in " + PROJECT);
            return false;
        }

        // Cross-version switch: delete compile/resource outputs to force rebuild
        // (see .bat :build comment for the config-cache up-to-date pitfall).
        deleteRecursive(PROJECT.resolve("build/classes"));
        deleteRecursive(PROJECT.resolve("build/libs"));
        deleteRecursive(PROJECT.resolve("build/resources"));
        for (String f : List.of("serverRunClasspath.txt", "serverRunVmArgs.txt", "serverRunProgramArgs.txt",
                "serverLegacyClasspath.txt", "serverLog4j2.xml", "runServer.cmd", "minecraft_assets.properties")) {
            try {
                Files.deleteIfExists(PROJECT.resolve("build/moddev/" + f));
            } catch (IOException ignored) {
            }
        }

        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of(
                "prepareServerRun", "classes", "createServerLaunchScript",
                "--no-configuration-cache", "--console=plain", "-q",
                "-Pminecraft_version=" + o.mc,
                "-Pneo_version=" + v.neo(),
                "-Pparchment_minecraft_version=" + v.parchmentMc(),
                "-Pparchment_mappings_version=" + v.parchmentMap(),
                "-Pminecraft_version_range=" + v.mcRange()));
        log("  gradle: " + commandLine(cmd));
        Process p = startProc(cmd, PROJECT, PROJECT.resolve("build/gradle-run.log"), null);
        boolean ok = waitExit(p, 600);
        return ok && p.exitValue() == 0;
    }

    static String commandLine(List<String> cmd) {
        return String.join(" ", cmd);
    }

    // ---- run-dir prep (stage-A parity) ----
    static Path runDir(Options o) {
        return PROJECT.resolve(RUN_DIR_NAME).resolve(o.mc);
    }

    static Path serverLog(Options o) {
        return runDir(o).resolve("logs/latest.log");
    }

    static Path mccBase(Options o) {
        return runDir(o).resolve("mcc-test");
    }

    static Path mockLog() {
        return PROJECT.resolve("testutil/mock-sessionserver/mock.log");
    }

    static boolean prepareRunDir(Options o) throws IOException {
        Path rd = runDir(o);
        log("=== prepare run dir: " + rd + " ===");
        Files.createDirectories(rd.resolve("config"));
        Files.createDirectories(rd.resolve("logs"));
        deleteRecursive(rd.resolve("mixauth"));
        deleteRecursive(mccBase(o));
        Files.createDirectories(mccBase(o));

        Path cfg = PROJECT.resolve("testutil/config");
        Files.copy(cfg.resolve("eula.txt"), rd.resolve("eula.txt"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cfg.resolve("server.properties"), rd.resolve("server.properties"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cfg.resolve("mixauth-server.toml"), rd.resolve("config/mixauth-server.toml"),
                StandardCopyOption.REPLACE_EXISTING);

        // server.properties: online mode + RCON (appended; later keys override earlier
        // in Properties)
        String props = String.join(System.lineSeparator(),
                "online-mode=false",
                "enforce-secure-profile=false",
                "enable-rcon=true",
                "rcon.port=" + rconPort,
                "rcon.password=" + rconPw) + System.lineSeparator();
        Files.writeString(rd.resolve("server.properties"), props, StandardCharsets.ISO_8859_1,
                StandardOpenOption.APPEND);

        // mixauth-server.toml: trusted window override (minimal regex, no TOML dep)
        int tw = o.trusted ? TRUSTED_HOURS : 0;
        Path toml = rd.resolve("config/mixauth-server.toml");
        String content = Files.readString(toml, StandardCharsets.UTF_8);
        String pat = "(?m)^trusted_login_window_hours\\s*=\\s*\\d+";
        content = content.replaceFirst(pat, "trusted_login_window_hours = " + tw);
        Files.writeString(toml, content, StandardCharsets.UTF_8);

        // server launch is done via ProcessBuilder directly (no generated .cmd);
        // the former start-vanilla.cmd diagnostic template has been removed.
        Files.deleteIfExists(serverLog(o));
        log("  run dir ready");
        return true;
    }

    static String mockArgs(Options o) {
        return "-Dmixauth.profile_lookup_url=http://127.0.0.1:" + mockPort + "/minecraft/profile/lookup/name/ "
                + "-Dmixauth.has_joined_url=http://127.0.0.1:" + mockPort + "/session/minecraft/hasJoined";
    }

    // ---- mock sessionserver ----
    static Process mockProc;

    static boolean startMock(Options o) throws IOException {
        log("=== start mock sessionserver ===");
        List<String> cmd = List.of(javaBin,
                PROJECT.resolve("testutil/mock-sessionserver/MockSessionServer.java").toString(),
                "--port", String.valueOf(mockPort),
                "--profile-mode", "online",
                "--hasjoined-mode", "online");
        mockProc = startProc(cmd, null, mockLog(), null);
        log("  mock launched");
        int i = 0;
        while (i < 10 && !portListening(mockPort)) {
            sleepMs(500);
            i++;
        }
        if (!portListening(mockPort)) {
            log("  FAIL: mock not listening on " + mockPort);
            return false;
        }
        // PID contract (A3): write mock.pid
        Path pidFile = runDir(o).resolve("mock.pid");
        try {
            Files.writeString(pidFile, String.valueOf(mockProc.pid()), StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
        }
        return true;
    }

    static void stopMock(Options o) {
        kill(mockProc);
        // PID contract fallback
        try {
            Path pidFile = runDir(o).resolve("mock.pid");
            if (Files.exists(pidFile)) {
                String pid = Files.readString(pidFile, StandardCharsets.US_ASCII).trim();
                if (!pid.isEmpty()) {
                    ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        } catch (IOException ignored) {
        }
    }

    static boolean mockMode(String profile, String hasjoined) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + mockPort + "/_mock/mode?profile=" + profile + "&hasjoined="
                            + hasjoined))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- server lifecycle ----
    static Process serverProc;

    static boolean startServer(Options o) throws IOException {
        log("=== start server ===");
        Path moddir = PROJECT.resolve("build/moddev");
        String classesDir = PROJECT.resolve("build/classes/java/main").toString();
        String resourcesDir = PROJECT.resolve("build/resources/main").toString();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        if (o.mock) {
            String ma = mockArgs(o);
            // split the two -D flags apart
            for (String token : ma.split(" (?=-D)"))
                cmd.add(token);
        }
        cmd.add("@" + moddir.resolve("serverRunClasspath.txt"));
        cmd.add("@" + moddir.resolve("serverRunVmArgs.txt"));
        cmd.add("-Dfml.modFolders=mixauth%%" + classesDir + ";mixauth%%" + resourcesDir);
        cmd.add("net.neoforged.devlaunch.Main");
        cmd.add("@" + moddir.resolve("serverRunProgramArgs.txt"));

        Path out = runDir(o).resolve("logs/" + o.mc + "-stdout.log");
        log("  server cmd: " + commandLine(cmd));
        serverProc = startProc(cmd, runDir(o), out, null);
        // PID contract (A3)
        try {
            Files.writeString(runDir(o).resolve("server.pid"), String.valueOf(serverProc.pid()),
                    StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
        }
        log("  server launched (stop via RCON)");
        return true;
    }

    static boolean waitReady(Options o) {
        log("=== wait server ready ===");
        long deadline = System.nanoTime() + 180_000_000_000L; // 180s
        int sec = 0;
        while (System.nanoTime() < deadline) {
            if (contains(serverLog(o), "Done") && portListening(sport)) {
                log("  server ready after ~" + sec + "s");
                return true;
            }
            sleepMs(1000);
            sec++;
        }
        log("  server not ready after timeout (" + serverLog(o) + ")");
        return false;
    }

    static String rcon(String command) {
        try {
            RconClient rc = new RconClient("127.0.0.1", rconPort, rconPw);
            try {
                return rc.sendCommand(command);
            } finally {
                rc.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    static boolean rconProbe() {
        log("=== RCON probe ===");
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            String out = rcon("list");
            if (!out.isEmpty()) {
                log("  RCON ready: " + out.trim());
                return true;
            }
            sleepMs(1000);
        }
        log("  RCON not ready");
        return false;
    }

    static void stopServer(Options o) {
        log("=== stop server ===");
        long serverPid = serverProc == null ? -1 : serverProc.pid();
        rcon("stop");
        // sample threads immediately during shutdown (stop-jstack.txt, A2 parity via
        // jcmd)
        Path jstack = runDir(o).resolve("logs/stop-jstack.txt");
        try {
            Files.deleteIfExists(jstack);
            if (serverPid > 0) {
                List<String> cmd = List.of(jcmdBin, String.valueOf(serverPid), "Thread.print");
                Process p = startProc(cmd, null, jstack, null);
                p.waitFor(8, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }

        int i = 0;
        while (i < 45) {
            if (!portListening(sport)) {
                log("  server stopped");
                break;
            }
            sleepMs(1000);
            i++;
        }
        if (i >= 45) {
            log("  stop timeout, killing " + (serverPid > 0 ? serverPid : "?"));
            kill(serverProc);
        }
    }

    // ---- MCC helpers ----
    static String ini(Options o, String id) {
        return mccBase(o).resolve(id + ".ini").toString();
    }

    static String inputFile(Options o, String id) {
        return mccBase(o).resolve(id + ".input.txt").toString();
    }

    static String outLog(Options o, String id) {
        return mccBase(o).resolve(id + ".out.log").toString();
    }

    static String chatLog(Options o, String id) {
        return mccBase(o).resolve(id + ".chat.log").toString();
    }

    static void writeIni(Options o, String id, String user) throws IOException {
        Path iniPath = Path.of(ini(o, id));
        String outFwd = Path.of(outLog(o, id)).toString().replace('\\', '/');
        String chatFwd = Path.of(chatLog(o, id)).toString().replace('\\', '/');
        String content = String.join("\r\n",
                "[Main]",
                "[Main.General]",
                "Account = { Login = \"" + user + "\", Password = \"-\" }",
                "Server = { Host = \"127.0.0.1\", Port = " + sport + " }",
                "AccountType = \"mojang\"",
                "MinecraftVersion = \"" + o.mc + "\"",
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
        Files.writeString(iniPath, content, StandardCharsets.UTF_8);
    }

    static void dumpLog(Path f) {
        if (!Files.exists(f))
            return;
        String content = readUtf8(f);
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        int from = Math.max(0, lines.size() - 25);
        for (int i = from; i < lines.size(); i++) {
            log("    | " + lines.get(i).stripTrailing());
        }
    }

    /**
     * One MCC run: join -> optional post-join commands -> client assert -> server
     * assert -> quit.
     */
    static boolean runMcc(Options o, String id, String user, String expectClient, int joinTimeout,
            int assertTimeout, String expectServer, String postJoinCmds, boolean nojoin) throws IOException {
        String in = inputFile(o, id);
        String out = outLog(o, id);
        Files.writeString(Path.of(in), "", StandardCharsets.UTF_8); // create empty input
        Files.deleteIfExists(Path.of(out));
        Path pidFile = mccBase(o).resolve(id + ".pid");

        writeIni(o, id, user);
        log("  MCC start: " + id + " user=" + user);

        List<String> cmd = List.of(mccBin, ini(o, id), user, "-", "127.0.0.1:" + sport,
                "--minecraftversion=" + o.mc, "--language=en");
        Map<String, String> env = Map.of("MCC_FILE_INPUT", "1", "MCC_INPUT_FILE", in);
        Process p = startProc(cmd, null, Path.of(out), env);
        // PID contract (A3)
        try {
            Files.writeString(pidFile, String.valueOf(p.pid()), StandardCharsets.US_ASCII);
        } catch (IOException ignored) {
        }

        boolean ok = true;

        if (!nojoin) {
            if (!waitForPattern(Path.of(out), "Server was successfully joined", joinTimeout)) {
                log("  FAIL: MCC did not join");
                dumpLog(Path.of(out));
                kill(p);
                return false;
            }
        }

        if (postJoinCmds != null && !postJoinCmds.isEmpty()) {
            List<String> cmds = List.of(postJoinCmds.split(";"));
            Files.writeString(Path.of(in), String.join(System.lineSeparator(), cmds), StandardCharsets.UTF_8);
        }

        if (!waitForPattern(Path.of(out), expectClient, assertTimeout)) {
            log("  FAIL: client assert: " + expectClient);
            dumpLog(Path.of(out));
            kill(p);
            return false;
        }
        log("  client assertion OK");

        if (expectServer != null && !expectServer.isEmpty()) {
            if (!waitForPattern(serverLog(o), expectServer, assertTimeout)) {
                log("  FAIL: server assert: " + expectServer);
                dumpLog(serverLog(o));
                kill(p);
                return false;
            }
            log("  server assertion OK");
        }

        try {
            Files.writeString(Path.of(in), "quit", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        if (!waitExit(p, 20)) {
            log("  MCC did not exit, killing");
            kill(p);
        }
        log("  PASS: " + id);
        return true;
    }

    // ---- scenarios ----
    static final List<String> SUMMARY = new ArrayList<>();

    static void scenarioS1(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S1"))
            return;
        log("--- S1: unknown -> register prompt ---");
        try {
            boolean ok = runMcc(o, "S1", o.user, "You are not registered. Use /register", 90, 30,
                    "auth precheck routing", "", false);
            SUMMARY.add("S1 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S1 FAIL: " + e.getMessage());
        }
    }

    static void scenarioS2(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S2"))
            return;
        log("--- S2: /register -> auto login ---");
        try {
            boolean ok = runMcc(o, "S2", o.user, "Registration successful. Logged in automatically.", 90, 30, "",
                    "/register " + PASSWORD + " " + PASSWORD, false);
            SUMMARY.add("S2 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S2 FAIL: " + e.getMessage());
        }
    }

    static void scenarioS3(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S3"))
            return;
        log("--- S3: rejoin -> login prompt ---");
        try {
            boolean ok = runMcc(o, "S3", o.user, "This account is registered. Use /login", 90, 30,
                    "routed by login mode", "", false);
            SUMMARY.add("S3 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S3 FAIL: " + e.getMessage());
        }
    }

    static void scenarioS4(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S4"))
            return;
        log("--- S4: /login correct ---");
        try {
            boolean ok = runMcc(o, "S4", o.user, "Login successful.", 90, 30, "", "/login " + PASSWORD, false);
            SUMMARY.add("S4 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S4 FAIL: " + e.getMessage());
        }
    }

    static void scenarioS5(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S5"))
            return;
        log("--- S5: wrong password x2 ---");
        try {
            boolean ok = runMcc(o, "S5", o.user, "Incorrect password.", 90, 30, "",
                    "/login wrongpass;wait 2;/login wrongpass", false);
            SUMMARY.add("S5 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S5 FAIL: " + e.getMessage());
        }
    }

    static void scenarioS6(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S6"))
            return;
        log("--- S6: 3rd wrong -> blocked kick ---");
        try {
            boolean ok = runMcc(o, "S6", o.user, "Too many incorrect password attempts.", 90, 30, "",
                    "/login wrongpass;wait 2;/login wrongpass;wait 2;/login wrongpass", false);
            SUMMARY.add("S6 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S6 FAIL: " + e.getMessage());
        }
    }

    static void scenarioS7(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("S7"))
            return;
        log("--- S7: blocked rejoin ---");
        try {
            boolean ok = runMcc(o, "S7", o.user, "temporarily blocked from joining", 90, 30, "", "", true);
            SUMMARY.add("S7 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("S7 FAIL: " + e.getMessage());
        }
    }

    static void scenarioM2(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("M2"))
            return;
        log("--- M2: profile lookup 429 ---");
        try {
            mockMode("429", "online");
            boolean ok = runMcc(o, "M2", "RateTester", "Mojang authentication servers are temporarily unavailable", 90,
                    30, "rate limited", "", true);
            SUMMARY.add("M2 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("M2 FAIL: " + e.getMessage());
        }
    }

    static void scenarioM3(Options o) {
        if (!o.scenario.isEmpty() && !o.scenario.equals("M3"))
            return;
        log("--- M3: profile lookup malformed ---");
        try {
            mockMode("malformed", "online");
            boolean ok = runMcc(o, "M3", "MalTester", "returned malformed data", 90, 30, "Failed to parse", "", true);
            SUMMARY.add("M3 " + (ok ? "PASS" : "FAIL"));
        } catch (IOException e) {
            SUMMARY.add("M3 FAIL: " + e.getMessage());
        }
    }

    static void runScenarios(Options o) {
        log("=== run scenarios (mock=" + o.mock + ") ===");
        if (o.mock) {
            scenarioM2(o);
            scenarioM3(o);
            // M1/M4 removed: offline MCC cannot pass pre-login UUID check (see
            // temp/L3-mock-M1M4-todo.md)
        } else {
            scenarioS1(o);
            scenarioS2(o);
            scenarioS3(o);
            scenarioS4(o);
            scenarioS5(o);
            scenarioS6(o);
            scenarioS7(o);
            // S8 pending-timeout -> GameTest
        }
    }

    // ---- entry ----
    public static void main(String[] args) {
        Options o = parseArgs(args);
        if (o == null) {
            usage();
            System.exit(1);
        }
        Version v = VERSIONS.get(o.mc);
        if (v == null) {
            log("unsupported version: " + o.mc);
            usage();
            System.exit(1);
        }

        System.out.println("=== MixAuth login-chain test: mc=" + o.mc + " neo=" + v.neo()
                + " mock=" + o.mock + " trusted=" + o.trusted + " user=" + o.user + " ===");

        resolvePaths();
        if (mccBin == null) {
            log("ERROR: MCC binary not found - set MCC_EXE (exact path) or MCC_DIR (directory) to run login-chain tests");
            System.exit(1);
        }

        boolean allOk = true;
        try {
            if (!o.noBuild) {
                if (!build(o, v)) {
                    log("build failed");
                    cleanupAndExit(1, o);
                }
            } else {
                log("skip build");
            }

            prepareRunDir(o);

            if (o.mock) {
                if (!startMock(o)) {
                    log("mock startup failed");
                    cleanupAndExit(1, o);
                }
            }

            startServer(o);
            if (!waitReady(o)) {
                log("server not ready, aborting");
                stopServer(o);
                cleanupAndExit(1, o);
            }
            if (!rconProbe()) {
                log("RCON not ready, aborting");
                stopServer(o);
                cleanupAndExit(1, o);
            }

            runScenarios(o);
            allOk = !SUMMARY.stream().anyMatch(s -> s.endsWith("FAIL"));
            stopServer(o);
        } catch (Exception e) {
            e.printStackTrace();
            allOk = false;
        } finally {
            if (o.mock)
                stopMock(o);
        }

        log("");
        log("=== summary: " + o.mc + " - mock=" + o.mock + ", trusted=" + o.trusted + ", user=" + o.user + " ===");
        for (String s : SUMMARY)
            log("  " + s);
        if (SUMMARY.isEmpty())
            log("  (no scenarios ran)");
        log(allOk ? "ALL PASS" : "SOME FAILED");
        System.exit(allOk ? 0 : 1);
    }

    static void cleanupAndExit(int code, Options o) {
        if (o.mock)
            stopMock(o);
        System.exit(code);
    }

    static Options parseArgs(String[] args) {
        if (args.length == 0)
            return null;
        Options o = new Options();
        o.mc = args[0];
        int i = 1;
        while (i < args.length) {
            switch (args[i]) {
                case "--mock" -> o.mock = true;
                case "--trusted" -> o.trusted = true;
                case "--no-build" -> o.noBuild = true;
                case "--user" -> {
                    if (i + 1 < args.length) {
                        o.user = args[++i];
                    } else
                        return null;
                }
                case "--scenario" -> {
                    if (i + 1 < args.length) {
                        o.scenario = args[++i];
                    } else
                        return null;
                }
                default -> {
                    log("unknown argument: " + args[i]);
                    return null;
                }
            }
            i++;
        }
        return o;
    }

    static void usage() {
        System.out.println(
                "Usage: java --source 21 RunLoginChain.java <mc-version> [--mock] [--trusted] [--user NAME] [--no-build] [--scenario ID]");
        System.out.println("  mc-version: 1.21.1 | 1.21.5 | 1.21.8 | 1.21.11");
        System.out.println("  Env: MCC_EXE, MCC_DIR, JDK_EXE, JAVA_HOME, RCON_PW, RCON_PORT, SPORT, MOCK_PORT");
    }
}