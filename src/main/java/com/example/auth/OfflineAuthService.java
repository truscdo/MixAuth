package com.example.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
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
        return AuthDatabase.isOfflineRegistered(playerUuid);
    }

    public static boolean registerOfflineUser(UUID playerUuid, String password) {
        String passwordHash = hashPassword(password);
        return AuthDatabase.insertOfflineUser(playerUuid, passwordHash);
    }

    public static void saveOfflinePassword(UUID playerUuid, String password) {
        AuthDatabase.saveOfflinePassword(playerUuid, hashPassword(password));
    }

    public static boolean verifyOfflinePassword(UUID playerUuid, String password) {
        String passwordHash = AuthDatabase.findOfflinePasswordHash(playerUuid);
        if (passwordHash == null) {
            return false;
        }

        return BCrypt.verifyer().verify(password.toCharArray(), passwordHash).verified;
    }

    public static void blockOfflineLogin(UUID playerUuid, long durationMillis) {
        long blockedUntil = Instant.now().toEpochMilli() + durationMillis;
        AuthDatabase.saveOfflineLoginBlock(playerUuid, blockedUntil);
    }

    public static long getOfflineLoginBlockRemainingMillis(UUID playerUuid) {
        long blockedUntil = AuthDatabase.findOfflineLoginBlockedUntil(playerUuid);
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
        AuthDatabase.clearOfflineLoginBlock(playerUuid);
    }

    public static void recordTrustedOfflineLogin(UUID playerUuid, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        AuthDatabase.saveOfflineTrustedLogin(playerUuid, ipAddress, Instant.now().toEpochMilli());
    }

    public static boolean canBypassOfflineLogin(UUID playerUuid, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        long validAfter = Instant.now().toEpochMilli() - AuthServerConfig.trustedLoginWindowMillis();
        if (!AuthDatabase.hasRecentOfflineTrustedLogin(playerUuid, ipAddress, validAfter)) {
            return false;
        }

        if (AuthDatabase.hasSharedRecentOfflineTrustedIp(ipAddress, validAfter)) {
            LOGGER.info(
                    "Skipped offline passwordless login for {} because IP {} matched multiple UUIDs within {}",
                    playerUuid,
                    ipAddress,
                    describeTrustedLoginWindow()
            );
            return false;
        }

        return true;
    }

    public static void clearTrustedOfflineLogins(UUID playerUuid) {
        AuthDatabase.clearOfflineTrustedLogins(playerUuid);
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

    private static String hashPassword(String password) {
        return BCrypt.withDefaults().hashToString(AuthServerConfig.bcryptCost(), password.toCharArray());
    }
}