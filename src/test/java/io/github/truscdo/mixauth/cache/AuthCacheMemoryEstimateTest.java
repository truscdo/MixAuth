package io.github.truscdo.mixauth.cache;

import io.github.truscdo.mixauth.online.OnlineAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthCache} 内存占用估算（10 万级玩家场景）。
 *
 * <p>
 * 测量方法：本测试与 {@link AuthCache} 同包，直接调用其 public static 写入方法，把真实
 * 尺寸的数据填进真实的静态镜像结构（ConcurrentHashMap / ConcurrentSkipListMap）；
 * 每填完一组结构后做多轮强制 GC 并取堆用量最小值（best-of-N），相邻测量点之差即该组
 * 结构的可达堆内存占用。全部结构填满后与空基线之差即为「十万级玩家全量缓存」的总占用。
 * </p>
 *
 * <p>
 * 场景假设（报表输出中会原样打印，可按需调整常量）：
 * <ul>
 * <li>known_players：{@value #PLAYERS} 名玩家；用户名 3-16 字符随机（平均约 7-8 字符），
 * 登录模式 70% OFFLINE / 30% ONLINE（离线服主流形态）；</li>
 * <li>offline_users：{@value #PASSWORD_PLAYERS} 条密码（100% 玩家已设离线密码），
 * BCrypt cost=12 标准 60 字符哈希；</li>
 * <li>offline_login_blocks：{@value #BLOCKED_PLAYERS} 条（10% 玩家处于 5 分钟临时封禁）；</li>
 * <li>offline_trusted_logins：{@value #TRUSTED_RECORDS} 条 (uuid|ip → 时间戳)（24h
 * 免密窗口内
 * 平均 2 条/玩家），去重 IP 约 {@value #TRUSTED_DISTINCT_IPS} 个（含 NAT 共享）。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 说明：
 * <ul>
 * <li>结果在 64 位 JVM 默认压缩指针（-XX:+UseCompressedOops）下测得，与典型服务端配置一致；
 * 若改用 32GB+ 大堆（压缩指针关闭）约上浮 10%~15%。</li>
 * <li>分结构组数值为增量测量（相邻测量点相减，含极小 GC 噪声，已用 max(0, ·) 钳制）；
 * 「合计」与空基线的差值最可靠。</li>
 * <li>末尾附宽松的合理性上限断言（防结构膨胀回归，非精确断言）。</li>
 * </ul>
 * </p>
 */
@DisplayName("AuthCache 内存占用估算（10 万级玩家）")
class AuthCacheMemoryEstimateTest {

    // ---- 场景假设（按需调整后重跑即可得到新场景的估算） ----
    private static final int PLAYERS = 100_000;
    private static final int PASSWORD_PLAYERS = 100_000;
    private static final int BLOCKED_PLAYERS = 10_000;
    private static final int TRUSTED_RECORDS = 200_000;
    private static final int TRUSTED_DISTINCT_IPS = 20_000;

    /** 每个测量点强制 GC 的轮数（取最小值收敛到可达集）。 */
    private static final int GC_ROUNDS = 3;

    /** BCrypt 哈希字符集（$2a$12$ 前缀 + 53 位随机体，共 60 字符）。 */
    private static final char[] BCRYPT_CHARS = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            .toCharArray();
    private static final String BCRYPT_PREFIX = "$2a$12$";

    private static final long MB = 1024L * 1024L;

    @Test
    @DisplayName("10 万玩家全量缓存：分结构组 + 合计内存报表")
    void reportFullCacheFootprint() throws Exception {
        // 1) 清空全部静态镜像结构（含前次运行残留），得到干净基线
        resetCache();
        long baseline = measureHeapBestOf(GC_ROUNDS);

        // 2) 依次填充各结构组，每步测量（增量 = 该组可达内存）
        fillKnownPlayers();
        long afterKnown = measureHeapBestOf(GC_ROUNDS);
        long knownBytes = Math.max(0L, afterKnown - baseline);

        fillPasswords();
        long afterPasswords = measureHeapBestOf(GC_ROUNDS);
        long passwordBytes = Math.max(0L, afterPasswords - afterKnown);

        fillBlocks();
        long afterBlocks = measureHeapBestOf(GC_ROUNDS);
        long blockBytes = Math.max(0L, afterBlocks - afterPasswords);

        fillTrusted();
        long afterTrusted = measureHeapBestOf(GC_ROUNDS);
        long trustedBytes = Math.max(0L, afterTrusted - afterBlocks);

        long totalBytes = Math.max(0L, afterTrusted - baseline);

        printReport(knownBytes, passwordBytes, blockBytes, trustedBytes, totalBytes);

        // 3) 宽松合理性上限（防结构膨胀回归；量级判断，非精确断言）
        assertTrue(knownBytes < 256L * MB, "known_players 组异常过大: " + knownBytes + " B");
        assertTrue(passwordBytes < 128L * MB, "offline_users 组异常过大: " + passwordBytes + " B");
        assertTrue(blockBytes < 32L * MB, "offline_login_blocks 组异常过大: " + blockBytes + " B");
        assertTrue(trustedBytes < 256L * MB, "offline_trusted_logins 组异常过大: " + trustedBytes + " B");
        assertTrue(totalBytes < 640L * MB, "全量缓存异常过大: " + totalBytes + " B");
    }

    // ====================================================================
    // 数据填充（每个 fill* 内部局部变量随方法返回全部不可达，不污染测量）
    // ====================================================================

    private static void fillKnownPlayers() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < PLAYERS; i++) {
            UUID uuid = new UUID(rng.nextLong(), rng.nextLong());
            String name = uniqueUsername(rng, usedNames);
            OnlineAuthService.LoginMode mode = rng.nextInt(10) < 7
                    ? OnlineAuthService.LoginMode.OFFLINE
                    : OnlineAuthService.LoginMode.ONLINE;
            AuthCache.putKnown(uuid, name, mode);
        }
    }

    private static void fillPasswords() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < PASSWORD_PLAYERS; i++) {
            AuthCache.putPassword(new UUID(rng.nextLong(), rng.nextLong()), randomBcryptHash(rng));
        }
    }

    private static void fillBlocks() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();
        for (int i = 0; i < BLOCKED_PLAYERS; i++) {
            AuthCache.putBlock(new UUID(rng.nextLong(), rng.nextLong()), now + rng.nextLong(300_000L));
        }
    }

    private static void fillTrusted() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();
        String[] ips = new String[TRUSTED_DISTINCT_IPS];
        for (int i = 0; i < ips.length; i++) {
            ips[i] = randomIpv4(rng);
        }
        for (int i = 0; i < TRUSTED_RECORDS; i++) {
            UUID uuid = new UUID(rng.nextLong(), rng.nextLong());
            String ip = ips[rng.nextInt(ips.length)];
            AuthCache.putTrusted(uuid, ip, now - rng.nextLong(24L * 3_600_000L));
        }
    }

    /** 生成 3-16 字符、全小写字母数字下划线的合法 MC 用户名，并保证本次填充内唯一。 */
    private static String uniqueUsername(ThreadLocalRandom rng, Set<String> used) {
        // 长度偏向中短（E[r^2]=1/3 → 平均约 7.3 字符），贴合真实用户名分布
        String candidate;
        do {
            int length = 3 + (int) (rng.nextDouble() * rng.nextDouble() * 13);
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = (char) ('a' + rng.nextInt(26));
            }
            candidate = new String(chars);
        } while (!used.add(candidate));
        return candidate;
    }

    private static String randomBcryptHash(ThreadLocalRandom rng) {
        char[] chars = new char[60];
        BCRYPT_PREFIX.getChars(0, BCRYPT_PREFIX.length(), chars, 0);
        for (int i = BCRYPT_PREFIX.length(); i < chars.length; i++) {
            chars[i] = BCRYPT_CHARS[rng.nextInt(BCRYPT_CHARS.length)];
        }
        return new String(chars);
    }

    private static String randomIpv4(ThreadLocalRandom rng) {
        return (rng.nextInt(256)) + "." + (rng.nextInt(256)) + "."
                + (rng.nextInt(256)) + "." + (rng.nextInt(256));
    }

    // ====================================================================
    // 测量工具
    // ====================================================================

    /** 反射清空 AuthCache 全部静态镜像结构（ConcurrentHashMap/SkipListMap 均实现 clear）。 */
    private static void resetCache() throws Exception {
        String[] fieldNames = {
                "KNOWN_BY_UUID", "KNOWN_BY_NAME", "KNOWN_BY_UUID_STR",
                "PASSWORD_HASHES", "BLOCKS", "TRUSTED_BY_UUID_IP", "TRUSTED_BY_IP"
        };
        for (String name : fieldNames) {
            Field field = AuthCache.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> map) {
                map.clear();
            }
        }
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void forceGc() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** best-of-N：多轮强制 GC 后取堆用量最小值，收敛到当前可达集。 */
    private static long measureHeapBestOf(int rounds) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            forceGc();
            best = Math.min(best, usedHeap());
        }
        return best;
    }

    // ====================================================================
    // 报表
    // ====================================================================

    private static void printReport(long knownBytes, long passwordBytes, long blockBytes,
            long trustedBytes, long totalBytes) {
        System.out.println();
        System.out.println("====================================================================");
        System.out.println("AuthCache 内存占用估算报表（10 万级玩家）");
        System.out.println("--------------------------------------------------------------------");
        System.out.println("场景假设:");
        System.out.printf("  known_players 玩家数          : %,d%n", PLAYERS);
        System.out.printf("  offline_users 密码数          : %,d (bcrypt cost=12, 60 字符)%n",
                PASSWORD_PLAYERS);
        System.out.printf("  offline_login_blocks 封禁数    : %,d (占玩家 %.0f%%)%n",
                BLOCKED_PLAYERS, 100.0 * BLOCKED_PLAYERS / PLAYERS);
        System.out.printf("  offline_trusted_logins 记录数  : %,d (平均 %.1f 条/玩家, 24h 窗口)%n",
                TRUSTED_RECORDS, (double) TRUSTED_RECORDS / PLAYERS);
        System.out.printf("  trusted 去重 IP 数            : %,d%n", TRUSTED_DISTINCT_IPS);
        System.out.println("--------------------------------------------------------------------");
        System.out.println("测量（Java " + System.getProperty("java.version")
                + ", 默认压缩指针, best-of-" + GC_ROUNDS + " 次 GC 取最小）:");
        System.out.printf("  %-42s %12s %12s%n", "结构组", "占用", "每行");
        printRow("known_players（UUID 行 + 用户名/UUID 前缀索引）", knownBytes, PLAYERS);
        printRow("offline_users（密码哈希）", passwordBytes, PASSWORD_PLAYERS);
        printRow("offline_login_blocks（封禁截止时间）", blockBytes, BLOCKED_PLAYERS);
        printRow("offline_trusted_logins（uuid|ip + ip→uuid 双结构）", trustedBytes, TRUSTED_RECORDS);
        System.out.println("--------------------------------------------------------------------");
        printRow("合计（全量缓存）", totalBytes, PLAYERS);
        System.out.println("--------------------------------------------------------------------");
        System.out.printf("按玩家数线性外推：20 万玩家 ≈ %.1f MB | 50 万玩家 ≈ %.1f MB%n",
                totalBytes * 2.0 / MB, totalBytes * 5.0 / MB);
        System.out.println("====================================================================");
        System.out.flush();
    }

    private static void printRow(String label, long bytes, long rows) {
        System.out.printf("  %-42s %10.1f MB %10.0f B%n",
                label, bytes / (double) MB, bytes / (double) rows);
    }
}
