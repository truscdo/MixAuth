package io.github.truscdo.mixauth.gametest;

import io.github.truscdo.mixauth.cache.AuthStore;
import io.github.truscdo.mixauth.db.DatabaseSupport;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.db.OfflineLoginBlockDao;
import io.github.truscdo.mixauth.db.OfflineTrustedLoginDao;
import io.github.truscdo.mixauth.db.OfflineUserDao;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestInfo;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 缓存与 DB 一致性测试（数据层双通道比对）。
 * <p>
 * AuthStore 门面重构后的一致性语义：
 * <ul>
 * <li>关键写（insertPassword/savePassword/removePlayer）= 落库 join → 缓存同步（DB
 * 为准）；</li>
 * <li>非关键写（recordBlock/clearBlock/recordKnown）= 缓存先行 →
 * write-behind（可重建）。</li>
 * </ul>
 * 本测试在真实服务器进程内直接比对「缓存通道（AuthStore.get*）」与「DB 通道（4 个 DAO
 * findAll 按 uuid 过滤）」。非关键写落库异步，经 {@link AuthGameTestBase#awaitDb} 轮询
 * 最终一致。
 * <p>
 * 各用例使用独立 uuid（offlineUuid("SyncXxx")）与独立 IP，避免跨用例干扰；用例开头/结尾
 * 调用 {@code resetPlayerData} 清场（DB 跨运行持久化）。
 */
public class CacheSyncGameTest extends AuthGameTestBase {

    public CacheSyncGameTest(GameTestInfo info) {
        super(info);
    }

    /** 本类专属信任记录 IP（独立，避免共享 IP 干扰）。 */
    private static final String SYNC_TRUST_IP = "203.0.113.111";

    // ====================================================================
    // 用例 1：关键写 = 落库 join → 缓存同步
    // ====================================================================

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "关键写：insertPassword（INSERT 不覆盖）/savePassword（MERGE 覆盖）join 后缓存与 DB 双通道一致" })
    static void criticalWriteSyncsCacheThenDb(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SyncCrit");
        helper.resetPlayerData(uuid);
        String hashA = "HASH_A";
        String hashB = "HASH_B";

        // insertPassword：INSERT 语义，join 后缓存 + DB 双通道立即可见。
        helper.assertTrue(AuthStore.insertPassword(uuid, hashA).join(),
                "expected insertPassword to insert new row");
        helper.assertTrue(hashA.equals(AuthStore.getPasswordHash(uuid)),
                "expected cache password hash == HASH_A after insert");
        helper.assertTrue(OfflineUserDao.findAll().stream()
                .anyMatch(row -> row.playerUuid().equals(uuid) && hashA.equals(row.passwordHash())),
                "expected DB password hash == HASH_A after insert");

        // 重复 insertPassword：INSERT 语义不覆盖，返回 false，缓存/DB 均保持 HASH_A。
        helper.assertTrue(!AuthStore.insertPassword(uuid, hashB).join(),
                "expected duplicate insert to return false (no overwrite)");
        helper.assertTrue(hashA.equals(AuthStore.getPasswordHash(uuid)),
                "expected cache password hash unchanged after duplicate insert");
        helper.assertTrue(OfflineUserDao.findAll().stream()
                .anyMatch(row -> row.playerUuid().equals(uuid) && hashA.equals(row.passwordHash())),
                "expected DB password hash unchanged after duplicate insert");

        // savePassword：MERGE 语义覆盖，join 后缓存/DB 均为 HASH_B。
        AuthStore.savePassword(uuid, hashB).join();
        helper.assertTrue(hashB.equals(AuthStore.getPasswordHash(uuid)),
                "expected cache password hash == HASH_B after save");
        helper.assertTrue(OfflineUserDao.findAll().stream()
                .anyMatch(row -> row.playerUuid().equals(uuid) && hashB.equals(row.passwordHash())),
                "expected DB password hash == HASH_B after save");

        helper.resetPlayerData(uuid);
        helper.succeed();
    }

    // ====================================================================
    // 用例 2：非关键写 = 缓存先行 + write-behind 最终一致
    // ====================================================================

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "非关键写：recordBlock/clearBlock/recordKnown 缓存先行、DB 轮询最终一致（含减写无冗余 MERGE）" })
    static void nonCriticalWriteCacheFirstThenDb(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SyncNonCrit");
        helper.resetPlayerData(uuid);
        long futureTs = Instant.now().toEpochMilli() + 60_000L;

        // recordBlock（void）：缓存立即可见，DB 轮询最终一致。
        AuthStore.recordBlock(uuid, futureTs);
        helper.assertTrue(futureTs == AuthStore.getBlockedUntil(uuid),
                "expected cache blockedUntil visible immediately after recordBlock");
        helper.assertTrue(helper.awaitDb(5_000L, () -> OfflineLoginBlockDao.findAll().stream()
                .anyMatch(row -> row.playerUuid().equals(uuid) && row.blockedUntil() == futureTs)),
                "expected DB block record eventually written");

        // clearBlock（void）：缓存立即 null，DB 轮询最终消失。
        AuthStore.clearBlock(uuid);
        helper.assertTrue(AuthStore.getBlockedUntil(uuid) == null,
                "expected cache blockedUntil null immediately after clearBlock");
        helper.assertTrue(helper.awaitDb(5_000L, () -> OfflineLoginBlockDao.findAll().stream()
                .noneMatch(row -> row.playerUuid().equals(uuid))),
                "expected DB block record eventually removed");

        // recordKnown（void，含减写）：缓存立即可见，DB 轮询最终一致。
        AuthStore.recordKnown(uuid, "SyncNonCrit", OnlineAuthService.LoginMode.OFFLINE);
        helper.assertTrue(AuthStore.getLoginMode(uuid) == OnlineAuthService.LoginMode.OFFLINE,
                "expected cache loginMode visible immediately after recordKnown");
        helper.assertTrue(helper.awaitDb(5_000L, () -> KnownPlayerDao.findAll().stream()
                .anyMatch(entry -> entry.playerUuid().equals(uuid) && "OFFLINE".equals(entry.loginMode()))),
                "expected DB known record eventually written");

        // 减写验证：同名同模式重复 recordKnown 应跳过冗余 DB MERGE。
        // MERGE 本身不增行（行数断言无法区分减写失效），故以 updated_at 时间戳是否被刷新为准。
        long rowsBefore = KnownPlayerDao.findAll().stream().filter(entry -> entry.playerUuid().equals(uuid)).count();
        long updatedAtBefore = readKnownUpdatedAt(uuid);
        AuthStore.recordKnown(uuid, "SyncNonCrit", OnlineAuthService.LoginMode.OFFLINE);
        // 给潜在的冗余 write-behind 任务一个执行窗口（减写失效才会刷新 updated_at）。
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200L));
        long rowsAfter = KnownPlayerDao.findAll().stream().filter(entry -> entry.playerUuid().equals(uuid)).count();
        long updatedAtAfter = readKnownUpdatedAt(uuid);
        helper.assertTrue(rowsBefore == rowsAfter,
                "expected no extra known row after dedup recordKnown");
        helper.assertTrue(updatedAtBefore == updatedAtAfter,
                "expected dedup recordKnown to skip redundant DB MERGE (updated_at unchanged)");

        helper.resetPlayerData(uuid);
        helper.succeed();
    }

    // ====================================================================
    // 用例 3：复合关键写 removePlayer = 四表全清 + 缓存同步
    // ====================================================================

    @GameTest
    @EmptyTemplate
    @TestHolder(description = { "复合关键写：removePlayer 清空 known/password/block/trusted 四表且缓存四读全 null" })
    static void removePlayerClearsAllFourTablesAndCache(AuthGameTestBase helper) {
        UUID uuid = offlineUuid("SyncRemove");
        helper.resetPlayerData(uuid);
        long futureTs = Instant.now().toEpochMilli() + 60_000L;

        // 制造四表数据：password/trusted 为 join 写，known/block 为 write-behind。
        helper.assertTrue(AuthStore.insertPassword(uuid, "HASH").join(),
                "expected insertPassword to seed offline_users");
        AuthStore.recordKnown(uuid, "SyncRemove", OnlineAuthService.LoginMode.OFFLINE);
        AuthStore.recordBlock(uuid, futureTs);
        AuthStore.recordTrusted(uuid, SYNC_TRUST_IP, Instant.now().toEpochMilli()).join();

        // 等 write-behind 的 known/block 落库，确保 removePlayer 前四表数据齐备。
        helper.assertTrue(helper.awaitDb(5_000L, () -> KnownPlayerDao.findAll().stream()
                .anyMatch(entry -> entry.playerUuid().equals(uuid))
                && OfflineLoginBlockDao.findAll().stream()
                        .anyMatch(row -> row.playerUuid().equals(uuid))),
                "expected known+block write-behind flushed before removePlayer");

        // removePlayer：复合关键写，返回 known 是否删除。
        helper.assertTrue(AuthStore.removePlayer(uuid).join(),
                "expected removePlayer to remove known player");

        // 缓存四读全 null。
        helper.assertTrue(AuthStore.getPasswordHash(uuid) == null,
                "expected cache password hash null after removePlayer");
        helper.assertTrue(AuthStore.getLoginMode(uuid) == null,
                "expected cache loginMode null after removePlayer");
        helper.assertTrue(AuthStore.getBlockedUntil(uuid) == null,
                "expected cache blockedUntil null after removePlayer");
        helper.assertTrue(AuthStore.getTrustedAt(uuid, SYNC_TRUST_IP) == null,
                "expected cache trustedAt null after removePlayer");

        // DB 四表均不含该 uuid（join 后立即可断言）。
        helper.assertTrue(OfflineUserDao.findAll().stream().noneMatch(row -> row.playerUuid().equals(uuid)),
                "expected offline_users row removed");
        helper.assertTrue(KnownPlayerDao.findAll().stream().noneMatch(entry -> entry.playerUuid().equals(uuid)),
                "expected known_players row removed");
        helper.assertTrue(OfflineLoginBlockDao.findAll().stream().noneMatch(row -> row.playerUuid().equals(uuid)),
                "expected offline_login_blocks row removed");
        helper.assertTrue(OfflineTrustedLoginDao.findAll().stream().noneMatch(row -> row.playerUuid().equals(uuid)),
                "expected offline_trusted_logins row removed");

        helper.succeed();
    }

    // ---------------------------------------------------------------- 内部辅助

    /** 直读 known_players 指定 uuid 的 updated_at（减写验证用），无行返回 -1。 */
    private static long readKnownUpdatedAt(UUID uuid) {
        return DatabaseSupport.executeQuery(
                "SELECT updated_at FROM known_players WHERE player_uuid = ?",
                stmt -> stmt.setString(1, uuid.toString()),
                rs -> rs.next() ? rs.getLong("updated_at") : -1L,
                "Failed to read known player updated_at");
    }
}
