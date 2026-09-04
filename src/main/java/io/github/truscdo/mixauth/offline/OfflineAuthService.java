package io.github.truscdo.mixauth.offline;

import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.LogUtil;
import io.github.truscdo.mixauth.PasswordHasher;
import io.github.truscdo.mixauth.cache.AuthStore;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 离线认证服务：业务逻辑（BCrypt、登录流程、封禁策略、trusted 窗口判定、日志）。
 * <p>
 * IO 全部经 {@link AuthStore} 门面：读同步自门控；关键写（offline_users 密码哈希）落库
 * join 后再同步缓存；非关键写（block/trusted）缓存先行 + write-behind。
 * </p>
 */
public final class OfflineAuthService {
    private static final Logger LOGGER = LogUtil.getLogger();

    private OfflineAuthService() {
    }

    /**
     * OfflineGate 的身份写入：known 与 alias 均使用 canonicalOfflineProfile 的身份，
     * clientUuid 只作为 alias 路由键。
     */
    public static void recordOfflineLogin(GameProfile canonicalOfflineProfile, UUID clientUuid) {
        if (canonicalOfflineProfile == null) {
            throw new IllegalArgumentException("Missing canonical offline profile");
        }
        UUID canonicalOfflineUuid = ProfileCompat.uuid(canonicalOfflineProfile);
        String username = ProfileCompat.name(canonicalOfflineProfile);
        if (canonicalOfflineUuid == null || clientUuid == null || username == null || username.isBlank()
                || !canonicalOfflineUuid.equals(PlayerIdentityService.resolvePlayerUuid(username))) {
            throw new IllegalArgumentException("Invalid canonical offline profile");
        }

        KnownPlayerService.recordKnownPlayer(
                canonicalOfflineUuid,
                username,
                OnlineAuthService.LoginMode.OFFLINE);
        AuthStore.recordOfflineClientAlias(canonicalOfflineUuid, clientUuid, username);
    }

    public static void recordOfflineLogin(UUID playerUuid, String username) {
        recordOfflineLogin(new GameProfile(playerUuid, username), playerUuid);
    }

    public static boolean isOfflineRegistered(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        return AuthStore.getPasswordHash(playerUuid) != null;
    }

    public static boolean registerOfflineUser(UUID playerUuid, String password) {
        String passwordHash = PasswordHasher.hash(password, AuthServerConfig.bcryptCost());
        return insertOfflinePasswordHash(playerUuid, passwordHash);
    }

    public static boolean verifyOfflinePassword(UUID playerUuid, String password) {
        String passwordHash = AuthStore.getPasswordHash(playerUuid);
        return PasswordHasher.verify(password, passwordHash);
    }

    /**
     * 异步校验离线密码：主线程仅做一次轻量缓存读，BCrypt 计算提交到后台有界执行器。
     */
    public static CompletableFuture<Boolean> verifyOfflinePasswordAsync(UUID playerUuid, String password) {
        String passwordHash = AuthStore.getPasswordHash(playerUuid);
        return PasswordHasher.verifyAsync(password, passwordHash);
    }

    /** 异步计算 BCrypt 哈希（cost 取自配置），供命令层在后台完成哈希后回主线程落库。 */
    public static CompletableFuture<String> hashOfflinePasswordAsync(String password) {
        return PasswordHasher.hashAsync(password, AuthServerConfig.bcryptCost());
    }

    /**
     * 关键写（offline_users）：INSERT 语义（已存在返回 false 不覆盖），经 AuthStore
     * 落库 join 后再同步缓存（write-through + CHECKPOINT SYNC）。
     */
    public static boolean insertOfflinePasswordHash(UUID playerUuid, String passwordHash) {
        return AuthStore.insertPassword(playerUuid, passwordHash).join();
    }

    /** 关键写（offline_users）：MERGE 语义，经 AuthStore 落库 join 后再同步缓存。 */
    public static void saveOfflinePasswordHash(UUID playerUuid, String passwordHash) {
        AuthStore.savePassword(playerUuid, passwordHash).join();
    }

    /** 非关键写（offline_login_blocks）：缓存先行 + write-behind（经 AuthStore）。 */
    public static void blockOfflineLogin(UUID playerUuid, long durationMillis) {
        if (playerUuid == null) {
            return;
        }
        long blockedUntil = Instant.now().toEpochMilli() + durationMillis;
        AuthStore.recordBlock(playerUuid, blockedUntil);
    }

    public static long getOfflineLoginBlockRemainingMillis(UUID playerUuid) {
        if (playerUuid == null) {
            return 0L;
        }
        Long blockedUntil = AuthStore.getBlockedUntil(playerUuid);
        if (blockedUntil == null) {
            return 0L;
        }

        long remainingMillis = blockedUntil - Instant.now().toEpochMilli();
        if (remainingMillis > 0L) {
            return remainingMillis;
        }

        // 惰性过期：清理缓存 + write-behind 写回 DB（行为与现状一致）
        clearOfflineLoginBlock(playerUuid);
        return 0L;
    }

    /** 非关键写（offline_login_blocks）：缓存先行 + write-behind（经 AuthStore）。 */
    public static void clearOfflineLoginBlock(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        AuthStore.clearBlock(playerUuid);
    }

    /** 非关键写（offline_trusted_logins）：缓存先行 + write-behind（经 AuthStore）。 */
    public static void recordTrustedOfflineLogin(UUID playerUuid, String ipAddress) {
        if (playerUuid == null || ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        AuthStore.recordTrusted(playerUuid, ipAddress, Instant.now().toEpochMilli());
    }

    public static boolean canBypassOfflineLogin(UUID playerUuid, String ipAddress) {
        if (playerUuid == null || ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        long now = Instant.now().toEpochMilli();
        long validAfter = now - AuthServerConfig.trustedLoginWindowMillis();

        // 何时触发=业务（每次窗口检查），是否执行=AuthStore 节流
        AuthStore.sweepExpiredTrusted(validAfter);

        Long authenticatedAt = AuthStore.getTrustedAt(playerUuid, ipAddress);
        if (authenticatedAt == null) {
            return false;
        }
        if (authenticatedAt < validAfter) {
            // 过期不授信；清理交给 AuthStore 节流范围删除，不做单条惰性删
            return false;
        }

        if (hasSharedRecentOfflineTrustedIp(ipAddress, validAfter)) {
            LOGGER.info(
                    "Skipped offline passwordless login for {} because IP {} matched multiple UUIDs within {}",
                    playerUuid,
                    ipAddress,
                    describeTrustedLoginWindow());
            return false;
        }

        return true;
    }

    private static boolean hasSharedRecentOfflineTrustedIp(String ipAddress, long validAfter) {
        Set<UUID> uuids = AuthStore.getTrustedUuidsByIp(ipAddress);
        if (uuids == null || uuids.isEmpty()) {
            return false;
        }
        int recent = 0;
        for (UUID uuid : uuids) {
            Long authenticatedAt = AuthStore.getTrustedAt(uuid, ipAddress);
            if (authenticatedAt != null && authenticatedAt >= validAfter) {
                recent++;
                if (recent > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 关键写（offline_trusted_logins）：改密/设密后清空该玩家的全部免密记录（落库 join）。 */
    public static void clearTrustedOfflineLogins(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        AuthStore.clearTrustedByUuidCritical(playerUuid).join();
    }

    public static String describeTrustedLoginWindow(String language) {
        return formatDuration(language, AuthServerConfig.trustedLoginWindowMillis());
    }

    public static String describeTrustedLoginWindow() {
        return describeTrustedLoginWindow(AuthServerConfig.defaultLanguage());
    }

    public static String formatDuration(String language, long durationMillis) {
        return AuthTranslations.formatDuration(language, durationMillis);
    }
}
