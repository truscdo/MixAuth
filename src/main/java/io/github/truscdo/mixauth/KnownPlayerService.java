package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.cache.AuthStore;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.core.UUIDUtil;
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final Map<UUID, OnlineAuthService.LoginMode> LOGIN_MODES = new ConcurrentHashMap<>();

    private KnownPlayerService() {
    }

    /**
     * 查询已知玩家名单中的登录模式。
     * 先查 clientUuid，未命中则查 server-generated UUID（基于用户名）；两次查找命中同一份
     * 缓存（AuthStore 内部 byUuid），无需两份缓存。
     *
     * @param clientUuid 客户端在 Login Start 中发送的 UUID
     * @param username   玩家用户名
     * @return LoginMode（ONLINE/OFFLINE）或 null（不在已知名单中）
     */
    public static OnlineAuthService.LoginMode resolveLoginMode(UUID clientUuid, String username) {
        if (username == null || username.isBlank()) {
            return null;
        }

        if (clientUuid != null) {
            OnlineAuthService.LoginMode mode = AuthStore.getLoginMode(clientUuid);
            if (mode != null) {
                return mode;
            }
        }

        UUID serverUuid = ProfileCompat.uuid(UUIDUtil.createOfflineProfile(username));
        if (!serverUuid.equals(clientUuid)) {
            OnlineAuthService.LoginMode mode = AuthStore.getLoginMode(serverUuid);
            if (mode != null) {
                return mode;
            }
        }

        return null;
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
     * 管理员设置玩家登录模式（非关键写，write-behind）。
     */
    public static void setLoginMode(UUID playerUuid, String username, OnlineAuthService.LoginMode mode) {
        if (playerUuid == null || mode == null) {
            return;
        }
        AuthStore.setLoginMode(playerUuid, username, mode);
        LOGGER.info("Admin set login mode for {} ({}) to {}", username, playerUuid, mode);
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
