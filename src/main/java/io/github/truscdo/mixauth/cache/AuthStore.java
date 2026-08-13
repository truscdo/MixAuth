package io.github.truscdo.mixauth.cache;

import io.github.truscdo.mixauth.db.DatabaseSupport;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.db.OfflineLoginBlockDao;
import io.github.truscdo.mixauth.db.OfflineTrustedLoginDao;
import io.github.truscdo.mixauth.db.OfflineUserDao;
import io.github.truscdo.mixauth.online.OnlineAuthService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 唯一对外数据层门面（纯数据层）。
 *
 * <p>
 * 业务层（{@code OfflineAuthService} / {@code KnownPlayerService}）只通过本类获取/删除/写入
 * 数据；「读缓存还是读库、加载门控、write-through / write-behind、一致性同步、清理节流」
 * 全部在本门面内决策。内部聚合 {@link AuthCache}（内存镜像）+ {@link DirectDb}（单 worker
 * JDBC）+ 4 个 DAO，后两者为包内实现细节，业务层不可见。
 * </p>
 *
 * <p>
 * 不变量：
 * <ol>
 * <li>读方法同步、无副作用，内部自门控 {@code awaitLoaded}（业务层无感知加载生命周期）。</li>
 * <li>写/回填方法永不 {@code awaitLoaded}：{@code putPassword} / {@code putKnown} 会在
 * {@link DirectDb} worker 线程的 write-through 任务内被调用，自门控会导致 worker 自等
 * {@code LOADED} 死锁；{@code backfill*} 本身是加载的一部分。</li>
 * <li>关键写顺序 = 落库 join → 缓存同步（崩溃一致性：DB 为准，缓存重启自动对齐）。</li>
 * <li>非关键写顺序 = 缓存先行 → write-behind（可重建）。</li>
 * </ol>
 * </p>
 *
 * <p>
 * 本类不包含任何业务策略（窗口判定、共享 IP 检测、何时清理、业务日志都在业务层）。
 * </p>
 */
public final class AuthStore {
    /** trusted 全表过期清理节流间隔：从「每次进服」降为周期清理。 */
    private static final long TRUSTED_SWEEP_INTERVAL_MILLIS = 60_000L;
    private static final AtomicLong LAST_TRUSTED_SWEEP_MILLIS = new AtomicLong();

    private AuthStore() {
    }

    // ====================================================================
    // 读（同步、无副作用、内部自门控 awaitLoaded）
    // ====================================================================

    /** 读 offline_users：密码哈希，无则 null。 */
    public static String getPasswordHash(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        AuthCache.awaitLoaded();
        return AuthCache.getPasswordHash(uuid);
    }

    /** 读 offline_login_blocks：封禁截止时间戳，无则 null。 */
    public static Long getBlockedUntil(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        AuthCache.awaitLoaded();
        return AuthCache.getBlockedUntil(uuid);
    }

    /** 读 known_players：登录模式（包装 KnownEntry，业务层不再依赖 KnownEntry 类型）。 */
    public static OnlineAuthService.LoginMode getLoginMode(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        AuthCache.awaitLoaded();
        AuthCache.KnownEntry entry = AuthCache.getKnown(uuid);
        return entry == null ? null : entry.mode();
    }

    /** 读 offline_trusted_logins：指定 (uuid, ip) 的认证时间戳，纯读不清理。 */
    public static Long getTrustedAt(UUID uuid, String ip) {
        AuthCache.awaitLoaded();
        return AuthCache.getTrustedAt(uuid, ip);
    }

    /** 读 offline_trusted_logins：共享 IP 检测的数据原料（ip → 去重 uuid 集合）。 */
    public static Set<UUID> getTrustedUuidsByIp(String ip) {
        AuthCache.awaitLoaded();
        return AuthCache.getTrustedUuidsByIp(ip);
    }

    /** 管理查询：按用户名精确查找（VARCHAR_IGNORECASE 语义，保留重复用户名多结果）。 */
    public static List<KnownPlayerDao.KnownPlayerEntry> findKnownByUsername(String username) {
        AuthCache.awaitLoaded();
        return AuthCache.findKnownByUsername(username);
    }

    /** 管理查询：按用户名/UUID 前缀补全。 */
    public static List<KnownPlayerDao.KnownPlayerEntry> findKnownByPrefix(String prefix, int limit) {
        AuthCache.awaitLoaded();
        return AuthCache.findKnownByPrefix(prefix, limit);
    }

    // ====================================================================
    // 关键写（落库 join → 缓存同步）
    // ====================================================================

    /**
     * 关键写（offline_users）：INSERT 语义（已存在返回 false 不覆盖），write-through +
     * {@code CHECKPOINT SYNC}。由单 worker 执行，调用方 join；崩溃/断电时密码哈希 0 丢失。
     */
    public static CompletableFuture<Boolean> insertPassword(UUID uuid, String hash) {
        return DirectDb.submit(() -> {
            boolean inserted = OfflineUserDao.insertOfflineUser(uuid, hash);
            if (inserted) {
                AuthCache.putPassword(uuid, hash);
                DatabaseSupport.checkpointSync();
            }
            return inserted;
        });
    }

    /**
     * 关键写（offline_users）：MERGE 语义（原 {@code saveOfflinePassword}），write-through +
     * {@code CHECKPOINT SYNC}。由单 worker 执行，调用方 join。
     */
    public static CompletableFuture<Void> savePassword(UUID uuid, String hash) {
        return DirectDb.<Void>submit(() -> {
            OfflineUserDao.saveOfflinePassword(uuid, hash);
            AuthCache.putPassword(uuid, hash);
            DatabaseSupport.checkpointSync();
            return null;
        });
    }

    /**
     * 关键写（offline_trusted_logins）：按玩家删除全部信任记录（改密/设密用）。
     * DB 先删（join）→ 缓存同步，崩溃后缓存重启自动对齐。
     */
    public static CompletableFuture<Void> clearTrustedByUuidCritical(UUID uuid) {
        return DirectDb.<Void>submit(() -> {
            OfflineTrustedLoginDao.clearOfflineTrustedLogins(uuid);
            AuthCache.removeTrustedByUuid(uuid);
            return null;
        });
    }

    /**
     * 关键写：四表全清（known + password + block + trusted）。一个 worker 任务内依次删除
     * （join），成功后同步清理缓存四项；返回 known 是否删除。
     */
    public static CompletableFuture<Boolean> removePlayer(UUID uuid) {
        return DirectDb.submit(() -> {
            boolean knownRemoved = KnownPlayerDao.removeKnownPlayer(uuid);
            OfflineTrustedLoginDao.clearOfflineTrustedLogins(uuid);
            OfflineLoginBlockDao.clearOfflineLoginBlock(uuid);
            OfflineUserDao.deleteOfflineUser(uuid);
            AuthCache.removeKnown(uuid);
            AuthCache.removePassword(uuid);
            AuthCache.removeBlock(uuid);
            AuthCache.removeTrustedByUuid(uuid);
            return knownRemoved;
        });
    }

    // ====================================================================
    // 非关键写（缓存先行 + write-behind，可重建）
    // ====================================================================

    /**
     * 非关键写（known_players）：记录登录模式。
     * 减写：缓存中已是同名同模式时跳过冗余 DB MERGE。
     */
    public static void recordKnown(UUID uuid, String name, OnlineAuthService.LoginMode mode) {
        if (uuid == null || mode == null) {
            return;
        }
        AuthCache.KnownEntry existing = AuthCache.getKnown(uuid);
        if (existing != null && existing.mode() == mode
                && (existing.username() == null || existing.username().equals(name))) {
            return;
        }
        AuthCache.putKnown(uuid, name, mode);
        DirectDb.submitWrite(() -> KnownPlayerDao.saveKnownPlayer(uuid, name, mode.name()));
    }

    /** 非关键写（known_players）：管理员强制设置登录模式（无减写）。 */
    public static void setLoginMode(UUID uuid, String name, OnlineAuthService.LoginMode mode) {
        if (uuid == null || mode == null) {
            return;
        }
        AuthCache.putKnown(uuid, name, mode);
        DirectDb.submitWrite(() -> KnownPlayerDao.saveKnownPlayer(uuid, name, mode.name()));
    }

    /** 非关键写（offline_login_blocks）：记录封禁，缓存先行。 */
    public static void recordBlock(UUID uuid, long blockedUntil) {
        if (uuid == null) {
            return;
        }
        AuthCache.putBlock(uuid, blockedUntil);
        DirectDb.submitWrite(() -> OfflineLoginBlockDao.saveOfflineLoginBlock(uuid, blockedUntil));
    }

    /** 非关键写（offline_login_blocks）：清除封禁，缓存先行。 */
    public static void clearBlock(UUID uuid) {
        if (uuid == null) {
            return;
        }
        AuthCache.removeBlock(uuid);
        DirectDb.submitWrite(() -> OfflineLoginBlockDao.clearOfflineLoginBlock(uuid));
    }

    /**
     * 非关键写（offline_trusted_logins）：记录一条免密信任记录，缓存先行 + write-behind
     * MERGE；返回 future 供测试 join（确认 DB 已写入）。
     */
    public static CompletableFuture<Void> recordTrusted(UUID uuid, String ip, long authenticatedAt) {
        if (uuid == null || ip == null || ip.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        AuthCache.putTrusted(uuid, ip, authenticatedAt);
        return DirectDb.<Void>submit(() -> {
            OfflineTrustedLoginDao.saveOfflineTrustedLogin(uuid, ip, authenticatedAt);
            return null;
        });
    }

    /**
     * 唯一范围清理 API（offline_trusted_logins）：内部 60s 节流 + 内存惰性过期 +
     * write-behind 范围删除。何时触发由业务层决定，是否执行由本方法节流。
     * {@code removeTrusted} 仅在本方法内部使用（单条惰性删不在业务层暴露）。
     */
    public static void sweepExpiredTrusted(long validAfter) {
        long now = Instant.now().toEpochMilli();
        long last = LAST_TRUSTED_SWEEP_MILLIS.get();
        if (now - last < TRUSTED_SWEEP_INTERVAL_MILLIS) {
            return;
        }
        if (!LAST_TRUSTED_SWEEP_MILLIS.compareAndSet(last, now)) {
            return;
        }
        AuthCache.sweepExpiredTrusted(validAfter);
        DirectDb.submitWrite(() -> OfflineTrustedLoginDao.deleteExpiredOfflineTrustedLogins(validAfter));
    }

    // ====================================================================
    // 生命周期
    // ====================================================================

    /**
     * 启动全量加载：收编 {@code DirectDb.loadAllAsync()}，无论成败都放行加载锁存器
     * （成功后读可用；失败后防停机窗口内线程悬挂）。fail-closed 处理由调用方（AuthMod）负责。
     */
    public static CompletableFuture<Void> loadAllAsync() {
        return DirectDb.loadAllAsync().whenComplete((unused, throwable) -> AuthCache.markLoaded());
    }

    /** 关服排空 write-behind 队列并关闭单 worker（之后若服务器重启会自动重新创建）。 */
    public static void drainAndShutdown() {
        DirectDb.drainAndShutdown();
    }
}
