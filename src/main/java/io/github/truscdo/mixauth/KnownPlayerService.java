package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.cache.AuthStore;
import io.github.truscdo.mixauth.db.OfflineClientAliasDao;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.online.OnlineAuthService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已知玩家名单服务：业务逻辑（登录模式解析、LOGIN_MODES、管理流程）。
 * <p>
 * IO 全部经 {@link AuthStore} 门面：读同步自门控；非关键写缓存先行 + write-behind。
 * </p>
 */
public final class KnownPlayerService {
    private static final Map<UUID, OnlineAuthService.LoginMode> LOGIN_MODES = new ConcurrentHashMap<>();

    private KnownPlayerService() {
    }

    /** 查询指定 clientUuid 的完整 known_players 记录；不会按用户名推导 UUID 回退查询。 */
    public static KnownPlayerDao.KnownPlayerEntry findKnownPlayer(UUID clientUuid) {
        return AuthStore.getKnownPlayer(clientUuid);
    }

    /** 查询指定离线身份与客户端 UUID 的路由 alias。 */
    public static OfflineClientAliasDao.OfflineClientAliasEntry findOfflineClientAlias(
            UUID canonicalOfflineUuid,
            UUID clientUuid) {
        return AuthStore.getOfflineClientAlias(canonicalOfflineUuid, clientUuid);
    }

    /**
     * 查询 clientUuid 对应的登录模式。
     * <p>
     * 保留该兼容 API，但只查询 clientUuid 本身；不会再查询用户名推导出的 canonical UUID。
     * </p>
     */
    public static OnlineAuthService.LoginMode resolveLoginMode(UUID clientUuid, String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        KnownPlayerDao.KnownPlayerEntry entry = findKnownPlayer(clientUuid);
        return entry == null ? null : parseLoginMode(entry.loginMode());
    }

    private static OnlineAuthService.LoginMode parseLoginMode(String mode) {
        try {
            return mode == null ? null : OnlineAuthService.LoginMode.valueOf(mode);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 记录玩家登录模式（非关键写，write-behind，缓存先行；减写收进 AuthStore）。
     * <p>
     * 同时把模式预置到内存映射（markLoginMode），供玩家进服（PlayerLoggedInEvent）
     * 消费以启动离线认证提示——否则 onPlayerLoggedIn 的 consumeLoginMode 恒为 null，
     * 离线 LOGIN/REGISTER 提示不会触发。
     */
    public static void recordKnownPlayer(UUID playerUuid, String username, OnlineAuthService.LoginMode mode) {
        AuthStore.recordKnown(playerUuid, username, mode);
        markLoginMode(playerUuid, mode);
    }

    /**
     * 彻底移除玩家的所有数据（known/password/block/trusted）。
     * 管理向低频操作：经 AuthStore 复合关键写并等待落库完成，保证命令返回时 DB 已一致。
     */
    public static boolean removeAllPlayerData(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        LOGIN_MODES.remove(playerUuid);
        return AuthStore.removePlayer(playerUuid).join();
    }

    /**
     * 消费内存中的登录模式（用于 PlayerLoggedInEvent）。
     */
    public static OnlineAuthService.LoginMode consumeLoginMode(UUID playerUuid) {
        return LOGIN_MODES.remove(playerUuid);
    }

    /**
     * 将登录模式预置到内存映射，供玩家进服（PlayerLoggedInEvent）时消费。
     * <p>
     * 公开可见性：GameTest 集成测试需要跨包预置登录模式以驱动进服路由分支。
     */
    public static void markLoginMode(UUID playerUuid, OnlineAuthService.LoginMode loginMode) {
        if (playerUuid != null) {
            LOGIN_MODES.put(playerUuid, loginMode);
        }
    }

    /** 管理命令用户名精确查找：内存索引服务（经 AuthStore，自门控保证索引完整）。 */
    public static List<KnownPlayerDao.KnownPlayerEntry> findKnownPlayersByUsername(String username) {
        return AuthStore.findKnownByUsername(username);
    }

    /** 管理命令前缀补全：内存索引服务（经 AuthStore，自门控保证索引完整）。 */
    public static List<KnownPlayerDao.KnownPlayerEntry> findKnownPlayersByPrefix(String prefix, int limit) {
        return AuthStore.findKnownByPrefix(prefix, limit);
    }
}
