// ============================================================================
// 运行目录（run-login/<mc>/）的准备与常用路径访问器。
// 每个版本使用独立的运行目录，由 LctConfig.RUN_DIR 保证隔离。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class LoginChainRunDir {

    public static final int TRUSTED_HOURS = 24;

    private LoginChainRunDir() {
    }

    public static Path runDir() {
        return LctConfig.RUN_DIR;
    }

    public static Path serverLog() {
        return runDir().resolve("logs/latest.log");
    }

    public static Path serverOut() {
        return runDir().resolve("logs/" + LctConfig.MC + "-stdout.log");
    }

    public static Path mccBase() {
        return runDir().resolve("mcc-test");
    }

    public static Path mockLog() {
        return LctConfig.MOCK_LOG;
    }

    public static void prepare(boolean trusted) throws IOException {
        Path rd = runDir();
        Processes.log("=== prepare run dir: " + rd + " ===");
        Files.createDirectories(rd.resolve("config"));
        Files.createDirectories(rd.resolve("logs"));
        Processes.deleteRecursive(rd.resolve("mixauth"));
        Processes.deleteRecursive(mccBase());
        Files.createDirectories(mccBase());

        Path cfg = LctConfig.PROJECT.resolve("templates");
        Files.copy(cfg.resolve("eula.txt"), rd.resolve("eula.txt"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cfg.resolve("server.properties"), rd.resolve("server.properties"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cfg.resolve("mixauth-server.toml"), rd.resolve("config/mixauth-server.toml"),
                StandardCopyOption.REPLACE_EXISTING);

        // 追加在线模式 + RCON 配置到 server.properties（追加在后，键重复时
        // 后写入的值生效）。
        String props = String.join(System.lineSeparator(),
                "online-mode=false",
                "enforce-secure-profile=false",
                "enable-rcon=true",
                "rcon.port=" + LctConfig.RCON_PORT,
                "rcon.password=" + LctConfig.RCON_PW) + System.lineSeparator();
        Files.writeString(rd.resolve("server.properties"), props, StandardCharsets.ISO_8859_1,
                StandardOpenOption.APPEND);

        // 按需改写 mixauth-server.toml 中的信任窗口（用最小正则替换，避免引入 TOML 依赖）。
        int tw = trusted ? TRUSTED_HOURS : 0;
        Path toml = rd.resolve("config/mixauth-server.toml");
        String content = Files.readString(toml, StandardCharsets.UTF_8);
        String pat = "(?m)^trusted_login_window_hours\\s*=\\s*\\d+";
        content = content.replaceFirst(pat, "trusted_login_window_hours = " + tw);
        Files.writeString(toml, content, StandardCharsets.UTF_8);

        Files.deleteIfExists(serverLog());
        Processes.log("  run dir ready");
    }
}