package io.github.truscdo.mixauth.cache;

import io.github.truscdo.mixauth.LogUtil;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 缓存读写层：四张表的内存镜像，热路径读写的唯一入口。
 *
 * <p>
 * 与直接读写层 {@link DirectDb} 的关系：本类只操作内存结构、不接触 JDBC；直接层的
 * 启动全量加载（回填）、未命中回填与 write-behind 写都通过本类的 put/remove 维护缓存。
 * 全部容器为无锁读的并发结构，主线程读永不阻塞。
 * </p>
 *
 * <p>
 * 启动全量加载完成后，所有读均为命中；加载完成前的短暂窗口内由 {@link DirectDb}
 * 的读穿透兜底（直接读回填），不存在逐键未命中的长期路径。
 * </p>
 *
 * <p>
 * 包内实现细节：外部（业务层/测试）一律通过 {@link AuthStore} 门面访问。
 * </p>
 */
final class AuthCache {
    private static final Logger LOGGER = LogUtil.getLogger();

    /** known_players 的内存行。 */
    public record KnownEntry(UUID playerUuid, String username, OnlineAuthService.LoginMode mode) {
    }

    // ---- known_players：byUuid 热路径 + byName/byUuidStr 管理前缀索引 ----
    private static final ConcurrentHashMap<UUID, KnownEntry> KNOWN_BY_UUID = new ConcurrentHashMap<>();
    private static final ConcurrentSkipListMap<String, Set<UUID>> KNOWN_BY_NAME = new ConcurrentSkipListMap<>();
    private static final ConcurrentSkipListMap<String, UUID> KNOWN_BY_UUID_STR = new ConcurrentSkipListMap<>();

    // ---- offline_users：uuid -> password_hash ----
    private static final ConcurrentHashMap<UUID, String> PASSWORD_HASHES = new ConcurrentHashMap<>();

    // ---- offline_login_blocks：uuid -> blocked_until ----
    private static final ConcurrentHashMap<UUID, Long> BLOCKS = new ConcurrentHashMap<>();

    // ---- offline_trusted_logins：uuid|ip -> authenticated_at + ip -> 去重 uuid 集合
    // ----
    private static final ConcurrentHashMap<String, Long> TRUSTED_BY_UUID_IP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<UUID>> TRUSTED_BY_IP = new ConcurrentHashMap<>();

    /** 启动全量加载完成信号（管理前缀索引依赖完整构建）。 */
    private static final CountDownLatch LOADED = new CountDownLatch(1);

    private AuthCache() {
    }

    // ====================================================================
    // known_players
    // ====================================================================

    public static KnownEntry getKnown(UUID playerUuid) {
        return playerUuid == null ? null : KNOWN_BY_UUID.get(playerUuid);
    }

    /** 覆盖式写入并同步维护管理索引（热路径/直接层写）。 */
    public static void putKnown(UUID playerUuid, String username, OnlineAuthService.LoginMode mode) {
        if (playerUuid == null || mode == null) {
            return;
        }
        KnownEntry previous = KNOWN_BY_UUID.put(playerUuid, new KnownEntry(playerUuid, username, mode));
        if (previous != null && previous.username() != null && !previous.username().equals(username)) {
            removeFromNameIndex(previous.username(), playerUuid);
        }
        addToNameIndex(username, playerUuid);
        KNOWN_BY_UUID_STR.putIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), playerUuid);
    }

    /** 启动全量加载回填：仅写入缓存中尚不存在的键，避免覆盖并发新写。 */
    public static void backfillKnown(UUID playerUuid, String username, String loginMode) {
        if (playerUuid == null || loginMode == null) {
            return;
        }
        OnlineAuthService.LoginMode mode = parseMode(loginMode);
        if (mode == null) {
            return;
        }
        KnownEntry entry = new KnownEntry(playerUuid, username, mode);
        if (KNOWN_BY_UUID.putIfAbsent(playerUuid, entry) != null) {
            return;
        }
        addToNameIndex(username, playerUuid);
        KNOWN_BY_UUID_STR.putIfAbsent(playerUuid.toString().toLowerCase(Locale.ROOT), playerUuid);
    }

    public static void removeKnown(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        KnownEntry removed = KNOWN_BY_UUID.remove(playerUuid);
        if (removed != null) {
            removeFromNameIndex(removed.username(), playerUuid);
        }
        KNOWN_BY_UUID_STR.remove(playerUuid.toString().toLowerCase(Locale.ROOT));
    }

    private static void addToNameIndex(String username, UUID playerUuid) {
        if (username == null) {
            return;
        }
        String lower = username.toLowerCase(Locale.ROOT);
        KNOWN_BY_NAME.computeIfAbsent(lower, key -> ConcurrentHashMap.newKeySet()).add(playerUuid);
    }

    private static void removeFromNameIndex(String username, UUID playerUuid) {
        if (username == null) {
            return;
        }
        String lower = username.toLowerCase(Locale.ROOT);
        Set<UUID> set = KNOWN_BY_NAME.get(lower);
        if (set != null) {
            set.remove(playerUuid);
            if (set.isEmpty()) {
                KNOWN_BY_NAME.remove(lower, set);
            }
        }
    }

    /** 管理命令：按用户名精确查找（VARCHAR_IGNORECASE 语义，保留重复用户名多结果）。 */
    public static List<KnownPlayerDao.KnownPlayerEntry> findKnownByUsername(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        Set<UUID> uuids = KNOWN_BY_NAME.get(username.toLowerCase(Locale.ROOT));
        if (uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        List<KnownPlayerDao.KnownPlayerEntry> result = new ArrayList<>();
        for (UUID uuid : uuids) {
            KnownEntry entry = KNOWN_BY_UUID.get(uuid);
            if (entry != null) {
                result.add(toDaoEntry(entry));
            }
        }
        result.sort(Comparator.comparing(KnownPlayerDao.KnownPlayerEntry::playerUuid));
        return result;
    }

    /** 管理命令：按用户名/UUID 前缀补全（subMap 天然字典序，复刻 ORDER BY + LIMIT）。 */
    public static List<KnownPlayerDao.KnownPlayerEntry> findKnownByPrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        String upperBound = lower + Character.MAX_VALUE;
        List<KnownPlayerDao.KnownPlayerEntry> result = new ArrayList<>();
        for (Map.Entry<String, Set<UUID>> entry : KNOWN_BY_NAME.subMap(lower, true, upperBound, true).entrySet()) {
            for (UUID uuid : entry.getValue()) {
                KnownEntry known = KNOWN_BY_UUID.get(uuid);
                if (known != null) {
                    result.add(toDaoEntry(known));
                    if (result.size() >= limit) {
                        return result;
                    }
                }
            }
        }
        for (Map.Entry<String, UUID> entry : KNOWN_BY_UUID_STR.subMap(lower, true, upperBound, true).entrySet()) {
            if (result.size() >= limit) {
                break;
            }
            KnownEntry known = KNOWN_BY_UUID.get(entry.getValue());
            if (known != null) {
                result.add(toDaoEntry(known));
            }
        }
        return result;
    }

    private static KnownPlayerDao.KnownPlayerEntry toDaoEntry(KnownEntry entry) {
        return new KnownPlayerDao.KnownPlayerEntry(entry.playerUuid(), entry.username(), entry.mode().name());
    }

    // ====================================================================
    // offline_users
    // ====================================================================

    public static String getPasswordHash(UUID playerUuid) {
        return playerUuid == null ? null : PASSWORD_HASHES.get(playerUuid);
    }

    public static void putPassword(UUID playerUuid, String passwordHash) {
        if (playerUuid == null || passwordHash == null) {
            return;
        }
        PASSWORD_HASHES.put(playerUuid, passwordHash);
    }

    public static void backfillPassword(UUID playerUuid, String passwordHash) {
        if (playerUuid != null && passwordHash != null) {
            PASSWORD_HASHES.putIfAbsent(playerUuid, passwordHash);
        }
    }

    public static void removePassword(UUID playerUuid) {
        if (playerUuid != null) {
            PASSWORD_HASHES.remove(playerUuid);
        }
    }

    // ====================================================================
    // offline_login_blocks
    // ====================================================================

    public static Long getBlockedUntil(UUID playerUuid) {
        return playerUuid == null ? null : BLOCKS.get(playerUuid);
    }

    public static void putBlock(UUID playerUuid, long blockedUntil) {
        if (playerUuid != null) {
            BLOCKS.put(playerUuid, blockedUntil);
        }
    }

    public static void backfillBlock(UUID playerUuid, long blockedUntil) {
        if (playerUuid != null && blockedUntil > 0L) {
            BLOCKS.putIfAbsent(playerUuid, blockedUntil);
        }
    }

    public static void removeBlock(UUID playerUuid) {
        if (playerUuid != null) {
            BLOCKS.remove(playerUuid);
        }
    }

    // ====================================================================
    // offline_trusted_logins
    // ====================================================================

    public static Long getTrustedAt(UUID playerUuid, String ipAddress) {
        if (playerUuid == null || ipAddress == null) {
            return null;
        }
        return TRUSTED_BY_UUID_IP.get(trustedKey(playerUuid, ipAddress));
    }

    public static Set<UUID> getTrustedUuidsByIp(String ipAddress) {
        return ipAddress == null ? null : TRUSTED_BY_IP.get(ipAddress);
    }

    public static void putTrusted(UUID playerUuid, String ipAddress, long authenticatedAt) {
        if (playerUuid == null || ipAddress == null) {
            return;
        }
        TRUSTED_BY_UUID_IP.put(trustedKey(playerUuid, ipAddress), authenticatedAt);
        TRUSTED_BY_IP.computeIfAbsent(ipAddress, key -> ConcurrentHashMap.newKeySet()).add(playerUuid);
    }

    /** 未命中/加载回填：putIfAbsent，避免覆盖并发新写入。 */
    public static void backfillTrusted(UUID playerUuid, String ipAddress, long authenticatedAt) {
        if (playerUuid == null || ipAddress == null) {
            return;
        }
        if (TRUSTED_BY_UUID_IP.putIfAbsent(trustedKey(playerUuid, ipAddress), authenticatedAt) == null) {
            TRUSTED_BY_IP.computeIfAbsent(ipAddress, key -> ConcurrentHashMap.newKeySet()).add(playerUuid);
        }
    }

    public static void removeTrusted(UUID playerUuid, String ipAddress) {
        if (playerUuid == null || ipAddress == null) {
            return;
        }
        TRUSTED_BY_UUID_IP.remove(trustedKey(playerUuid, ipAddress));
        removeFromIpIndex(playerUuid, ipAddress);
    }

    /** 改密/设密/管理员删除：移除该玩家的全部免密记录。 */
    public static void removeTrustedByUuid(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        String prefix = playerUuid.toString() + "|";
        List<String> keys = new ArrayList<>();
        for (String key : TRUSTED_BY_UUID_IP.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            TRUSTED_BY_UUID_IP.remove(key);
        }
        for (Set<UUID> set : TRUSTED_BY_IP.values()) {
            if (set != null) {
                set.remove(playerUuid);
            }
        }
    }

    /** 惰性过期：删除早于阈值的已加载记录，返回删除条数。 */
    public static int sweepExpiredTrusted(long validAfter) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : TRUSTED_BY_UUID_IP.entrySet()) {
            if (entry.getValue() < validAfter) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            int separator = key.indexOf('|');
            if (separator <= 0) {
                TRUSTED_BY_UUID_IP.remove(key);
                continue;
            }
            UUID uuid = UUID.fromString(key.substring(0, separator));
            String ip = key.substring(separator + 1);
            removeTrusted(uuid, ip);
        }
        return expired.size();
    }

    private static void removeFromIpIndex(UUID playerUuid, String ipAddress) {
        Set<UUID> set = TRUSTED_BY_IP.get(ipAddress);
        if (set != null) {
            set.remove(playerUuid);
            if (set.isEmpty()) {
                TRUSTED_BY_IP.remove(ipAddress, set);
            }
        }
    }

    private static String trustedKey(UUID playerUuid, String ipAddress) {
        return playerUuid.toString() + "|" + ipAddress;
    }

    // ====================================================================
    // 加载状态
    // ====================================================================

    public static void markLoaded() {
        LOADED.countDown();
    }

    /** 等待启动全量加载完成（管理前缀索引需完整构建）。 */
    public static void awaitLoaded() {
        while (LOADED.getCount() > 0L) {
            try {
                if (LOADED.await(1L, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static OnlineAuthService.LoginMode parseMode(String mode) {
        try {
            return OnlineAuthService.LoginMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException exception) {
            LOGGER.warn("Unknown login mode in database: {}", mode);
            return null;
        }
    }
}
