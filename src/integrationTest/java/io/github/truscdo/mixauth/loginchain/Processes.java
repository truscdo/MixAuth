// ============================================================================
// 服务器 / mock / MCC 管理器共用的子进程工具：启动、等待、强杀、端口探测，
// 并维护进程注册表；注册表配合 shutdown hook 保证 JVM 退出时不残留子进程。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class Processes {

    private static final List<Process> REGISTRY = new CopyOnWriteArrayList<>();

    static {
        // 残留进程防护：本 JVM 退出时，强杀所有由我们派生的子进程。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : REGISTRY) {
                try {
                    if (p.isAlive())
                        p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
        }));
    }

    private Processes() {
    }

    public static void log(String s) {
        System.out.println(s);
        System.out.flush();
    }

    public static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static Process startProc(List<String> cmd, Path dir, Path outLog, Map<String, String> env)
            throws IOException {
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

    public static boolean waitExit(Process p, int timeoutSec) {
        try {
            return p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void kill(Process p) {
        try {
            if (p != null && p.isAlive())
                p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    public static boolean portListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void deleteRecursive(Path dir) {
        if (!Files.exists(dir))
            return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
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

    public static String commandLine(List<String> cmd) {
        return String.join(" ", cmd);
    }
}