// ============================================================================
// 登录链集成测试的集中配置。
//
// 配置值优先取 Gradle 注入的系统属性（lct.*），其次可由环境变量覆盖
// （MCC_EXE、MCC_DIR、JDK_EXE、JAVA_HOME、RCON_PW、RCON_PORT、SPORT、MOCK_PORT），
// 最后回退到各字段的内置默认值。
//
// 版本目录固定支持 4 个版本：1.21.1 / 1.21.5 / 1.21.8 / 1.21.11。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class LctConfig {

    // ---- 支持的版本目录（4 个版本）----
    public record Version(String neo, String parchmentMc, String parchmentMap, String mcRange) {
    }

    public static final Map<String, Version> VERSIONS = Map.of(
            "1.21.1", new Version("21.1.1", "1.21.1", "2024.11.17", "[1.21.1]"),
            "1.21.5", new Version("21.5.98", "1.21.5", "2025.06.15", "[1.21.5]"),
            "1.21.8", new Version("21.8.54", "1.21.8", "2025.09.14", "[1.21.8]"),
            "1.21.11", new Version("21.11.45", "1.21.11", "2025.12.20", "[1.21.11]"));

    public static final Path PROJECT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    public static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    // ---- 实际使用的版本：优先取 lct.* 系统属性，缺失时回退到目录 ----
    public static final String MC = prop("lct.mc", "1.21.1");
    public static final Version CATALOG = VERSIONS.getOrDefault(MC, VERSIONS.get("1.21.1"));
    public static final String NEO = prop("lct.neo", CATALOG.neo());
    public static final String PARCHMENT_MC = prop("lct.parchmentMc", CATALOG.parchmentMc());
    public static final String PARCHMENT_MAP = prop("lct.parchmentMap", CATALOG.parchmentMap());
    public static final String MC_RANGE = prop("lct.mcRange", CATALOG.mcRange());

    // ---- 路径 ----
    public static final Path RUN_DIR = Path.of(prop("lct.runDir",
            PROJECT.resolve("run-login").resolve(MC).toString()));
    public static final Path BUILD_DIR = Path.of(prop("lct.buildDir", PROJECT.resolve("build").toString()));
    public static final Path ARTIFACTS_DIR = BUILD_DIR.resolve("reports/integrationTest/artifacts");
    /** mock sessionserver 的日志路径（按版本放在运行目录下，已被 .gitignore 忽略）。 */
    public static final Path MOCK_LOG = RUN_DIR.resolve("logs/mock.log");

    // ---- 行为开关 ----
    /** 是否跳过嵌套的 Gradle 构建（服务器产物已提前准备好时开启）。 */
    public static final boolean NO_BUILD = Boolean.parseBoolean(prop("lct.noBuild", "false"));
    /**
     * 嵌套构建前是否删除 build/classes|libs|resources（跨版本切换的兜底清理）。
     * 外层脚本逐版本预清理后会把 L3_CLEAN_BUILD 置为 false，避免同一版本重复
     * 全量编译。
     */
    public static final boolean CLEAN_BUILD = Boolean.parseBoolean(
            propOrEnv("lct.cleanBuild", "L3_CLEAN_BUILD", "true"));
    public static final boolean TRUSTED = Boolean.parseBoolean(prop("lct.trusted", "false"));

    // ---- 端口：优先取环境变量，其次默认值 ----
    public static final int RCON_PORT = envOrInt("RCON_PORT", 25575);
    public static final String RCON_PW = envOr("RCON_PW", "test123");
    public static final int SPORT = envOrInt("SPORT", 25565);
    public static final int MOCK_PORT = envOrInt("MOCK_PORT", 18080);

    // ---- JDK 解析：JDK_EXE 优先，其次 JAVA_HOME，最后 PATH ----
    public static final String JAVA_BIN;
    public static final String JCMD_BIN;

    static {
        String jdk = System.getenv("JDK_EXE");
        if ((jdk == null || jdk.isEmpty()) && System.getenv("JAVA_HOME") != null
                && !System.getenv("JAVA_HOME").isEmpty()) {
            Path jh = Path.of(System.getenv("JAVA_HOME"));
            Path j = jh.resolve(IS_WINDOWS ? "bin\\java.exe" : "bin/java");
            if (Files.exists(j))
                jdk = j.toString();
        }
        if (jdk == null || jdk.isEmpty())
            jdk = "java";
        JAVA_BIN = jdk;
        if ("java".equals(jdk)) {
            JCMD_BIN = "jcmd";
        } else {
            Path jdir = Path.of(jdk).getParent();
            JCMD_BIN = jdir.resolve(IS_WINDOWS ? "jcmd.exe" : "jcmd").toString();
        }
    }

    /**
     * 解析 MCC 无头客户端的可执行文件路径。
     *
     * 若 MCC_EXE 与 MCC_DIR 均未配置则返回 null（此时整个集成测试层应被
     * 跳过而非失败）。既可接受精确的 exe 路径，也可接受目录——目录会被
     * 规范化为该平台的客户端可执行名 {@code MinecraftClient.exe} /
     * {@code MinecraftClient}。
     */
    public static String mccExe() {
        String mcc = propOrEnv("lct.mccExe", "MCC_EXE", null);
        if ((mcc == null || mcc.isEmpty()) && System.getenv("MCC_DIR") != null
                && !System.getenv("MCC_DIR").isEmpty()) {
            Path d = Path.of(System.getenv("MCC_DIR"));
            Path f = d.resolve(IS_WINDOWS ? "MinecraftClient.exe" : "MinecraftClient");
            if (Files.exists(f))
                mcc = f.toString();
        }
        if (mcc != null && !mcc.isEmpty()) {
            // tolerate a directory being passed (e.g. IDE runs where lct.mccExe
            // carried MCC_DIR) by resolving the platform client name.
            Path p = Path.of(mcc);
            if (Files.isDirectory(p)) {
                Path f = p.resolve(IS_WINDOWS ? "MinecraftClient.exe" : "MinecraftClient");
                if (Files.exists(f))
                    mcc = f.toString();
            }
        }
        return (mcc == null || mcc.isEmpty()) ? null : mcc;
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String propOrEnv(String propKey, String envKey, String def) {
        String v = System.getProperty(propKey);
        if (v != null && !v.isEmpty())
            return v;
        v = System.getenv(envKey);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int envOrInt(String key, int def) {
        try {
            return Integer.parseInt(envOr(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private LctConfig() {
    }
}