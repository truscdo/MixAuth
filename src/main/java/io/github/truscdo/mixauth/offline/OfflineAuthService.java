package io.github.truscdo.mixauth.offline;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.LogUtil;
import io.github.truscdo.mixauth.PasswordHasher;
import io.github.truscdo.mixauth.db.OfflineLoginBlockDao;
import io.github.truscdo.mixauth.db.OfflineTrustedLoginDao;
import io.github.truscdo.mixauth.db.OfflineUserDao;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.UUID;

public final class OfflineAuthService {
    private static final Logger LOGGER = LogUtil.getLogger();

    private OfflineAuthService() {
    }

    public static void recordOfflineLogin(UUID playerUuid, String username) {
        KnownPlayerService.recordKnownPlayer(playerUuid, username, OnlineAuthService.LoginMode.OFFLINE);
    }

    public static boolean isOfflineRegistered(UUID playerUuid) {
        return OfflineUserDao.isOfflineRegistered(playerUuid);
    }

    public static boolean registerOfflineUser(UUID playerUuid, String password) {
        String passwordHash = PasswordHasher.hash(password, AuthServerConfig.bcryptCost());
        return OfflineUserDao.insertOfflineUser(playerUuid, passwordHash);
    }

    public static void saveOfflinePassword(UUID playerUuid, String password) {
        OfflineUserDao.saveOfflinePassword(playerUuid, PasswordHasher.hash(password, AuthServerConfig.bcryptCost()));
    }

    public static boolean verifyOfflinePassword(UUID playerUuid, String password) {
        String passwordHash = OfflineUserDao.findOfflinePasswordHash(playerUuid);
        return PasswordHasher.verify(password, passwordHash);
    }

    public static void blockOfflineLogin(UUID playerUuid, long durationMillis) {
        long blockedUntil = Instant.now().toEpochMilli() + durationMillis;
        OfflineLoginBlockDao.saveOfflineLoginBlock(playerUuid, blockedUntil);
    }

    public static long getOfflineLoginBlockRemainingMillis(UUID playerUuid) {
        long blockedUntil = OfflineLoginBlockDao.findOfflineLoginBlockedUntil(playerUuid);
        if (blockedUntil <= 0L) {
            return 0L;
        }

        long remainingMillis = blockedUntil - Instant.now().toEpochMilli();
        if (remainingMillis > 0L) {
            return remainingMillis;
        }

        clearOfflineLoginBlock(playerUuid);
        return 0L;
    }

    public static void clearOfflineLoginBlock(UUID playerUuid) {
        OfflineLoginBlockDao.clearOfflineLoginBlock(playerUuid);
    }

    public static void recordTrustedOfflineLogin(UUID playerUuid, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        OfflineTrustedLoginDao.saveOfflineTrustedLogin(playerUuid, ipAddress, Instant.now().toEpochMilli());
    }

    public static boolean canBypassOfflineLogin(UUID playerUuid, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        long validAfter = Instant.now().toEpochMilli() - AuthServerConfig.trustedLoginWindowMillis();
        // 每次窗口检查顺带清理已过期的免密记录，避免 offline_trusted_logins 表无限增长
        OfflineTrustedLoginDao.deleteExpiredOfflineTrustedLogins(validAfter);
        if (!OfflineTrustedLoginDao.hasRecentOfflineTrustedLogin(playerUuid, ipAddress, validAfter)) {
            return false;
        }

        if (OfflineTrustedLoginDao.hasSharedRecentOfflineTrustedIp(ipAddress, validAfter)) {
            LOGGER.info(
                    "Skipped offline passwordless login for {} because IP {} matched multiple UUIDs within {}",
                    playerUuid,
                    ipAddress,
                    describeTrustedLoginWindow());
            return false;
        }

        return true;
    }

    public static void clearTrustedOfflineLogins(UUID playerUuid) {
        OfflineTrustedLoginDao.clearOfflineTrustedLogins(playerUuid);
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