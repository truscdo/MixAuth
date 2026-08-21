// ============================================================================
// 容错日志扫描工具，供服务器/MCC 双通道断言使用。读取时把非法或畸形
// UTF-8 字节替换为 U+FFFD，避免日志中的脏字节掩盖要匹配的文本。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LogMatcher {

    private LogMatcher() {
    }

    public static String readUtf8(Path f) {
        try {
            if (!Files.exists(f))
                return "";
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

    public static boolean contains(Path f, String pat) {
        return readUtf8(f).contains(pat);
    }

    /** 轮询日志文件直到出现指定模式，超时返回 false（每秒探测一次）。 */
    public static boolean waitForPattern(Path file, String pat, int timeoutSec) {
        long deadline = System.nanoTime() + timeoutSec * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (contains(file, pat))
                return true;
            Processes.sleepMs(1000);
        }
        return false;
    }

    /** 返回文件末尾至多 n 行（去除行尾空白），用于失败消息与日志转储。 */
    public static List<String> tail(Path f, int n) {
        if (!Files.exists(f))
            return List.of();
        String content = readUtf8(f);
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        int from = Math.max(0, lines.size() - n);
        List<String> out = new ArrayList<>();
        for (int i = from; i < lines.size(); i++)
            out.add(lines.get(i).stripTrailing());
        return out;
    }

    public static void dumpLog(Path f) {
        for (String line : tail(f, 25))
            Processes.log("    | " + line);
    }
}