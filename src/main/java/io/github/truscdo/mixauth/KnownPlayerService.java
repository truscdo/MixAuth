package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.db.OfflineLoginBlockDao;
import io.github.truscdo.mixauth.db.OfflineTrustedLoginDao;
import io.github.truscdo.mixauth.db.OfflineUserDao;
import net.minecraft.core.UUIDUtil;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KnownPlayerService {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final Map<UUID, OnlineAuthService.LoginMode> LOGIN_MODES = new ConcurrentHashMap<>();

    private KnownPlayerService() {
    }

    /**
     * 查询已知玩家名单中的登录模式。
     * 先查 clientUuid，未命中则查 server-generated UUID（基于用户名）。
     *
     * @param clientUuid 客户端在 Login Start 中发送的 UUID
     * @param username   玩家用户名
     * @return LoginMode（ONLINE/OFFLINE）或 null（不在已知名单中）
     */
    public static OnlineAuthService.LoginMode resolveLoginMode(UUID clientUuid, String username) {
        if (username == null || username.isBlank()) {
            return null;
        }

        // 先尝试 clientUuid
        if (clientUuid != null) {
            String mode = KnownPlayerDao.findLoginMode(clientUuid);
            if (mode != null) {
                return parseLoginMode(mode);
            }
        }

        // 再尝试 server-generated UUID
        UUID serverUuid = UUIDUtil.createOfflineProfile(username).getId();
        if (!serverUuid.equals(clientUuid)) {
            String mode = KnownPlayerDao.findLoginMode(serverUuid);
            if (mode != null) {
                return parseLoginMode(mode);
            }
        }

        return null;
    }

    /**
     * 记录玩家登录模式到已知名单和内存映射。
     */
    public static void recordKnownPlayer(UUID playerUuid, String username, OnlineAuthService.LoginMode mode) {
        if (playerUuid == null || mode == null) {
            return;
        }

        KnownPlayerDao.saveKnownPlayer(playerUuid, username, mode.name());
        markLoginMode(playerUuid, mode);
    }

    /**
     * 管理员设置玩家登录模式。
     */
    public static void setLoginMode(UUID playerUuid, String username, OnlineAuthService.LoginMode mode) {
        if (playerUuid == null || mode == null) {
            return;
        }

        KnownPlayerDao.saveKnownPlayer(playerUuid, username, mode.name());
        LOGGER.info("Admin set login mode for {} ({}) to {}", username, playerUuid, mode);
    }

    /**
     * 从已知名单中移除玩家。
     */
    public static boolean removeKnownPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        LOGIN_MODES.remove(playerUuid);
        return KnownPlayerDao.removeKnownPlayer(playerUuid);
    }

    /**
     * 彻底移除玩家的所有数据（已知名单、离线密码、封禁记录、免密登录记录）。
     */
    public static boolean removeAllPlayerData(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        LOGIN_MODES.remove(playerUuid);
        boolean removedFromKnown = KnownPlayerDao.removeKnownPlayer(playerUuid);
        OfflineTrustedLoginDao.clearOfflineTrustedLogins(playerUuid);
        OfflineLoginBlockDao.clearOfflineLoginBlock(playerUuid);
        OfflineUserDao.deleteOfflineUser(playerUuid);
        return removedFromKnown;
    }

    /**
     * 消费内存中的登录模式（用于 PlayerLoggedInEvent）。
     */
    public static OnlineAuthService.LoginMode consumeLoginMode(UUID playerUuid) {
        return LOGIN_MODES.remove(playerUuid);
    }

    static void markLoginMode(UUID playerUuid, OnlineAuthService.LoginMode loginMode) {
        if (playerUuid != null) {
            LOGIN_MODES.put(playerUuid, loginMode);
        }
    }

    private static OnlineAuthService.LoginMode parseLoginMode(String mode) {
        try {
            return OnlineAuthService.LoginMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException e) {
            LOGGER.warn("Unknown login mode in database: {}", mode);
            return null;
        }
    }
}
