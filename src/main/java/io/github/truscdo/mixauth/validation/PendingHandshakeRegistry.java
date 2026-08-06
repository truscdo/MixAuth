package io.github.truscdo.mixauth.validation;

import io.github.truscdo.mixauth.AuthServerConfig;
import net.minecraft.network.Connection;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正版握手进行中的会话状态存储。
 * 以连接（IP:端口）为键保存待处理握手，并负责过期清理。
 */
public final class PendingHandshakeRegistry {
    private static final Map<String, PendingHandshake> PENDING = new ConcurrentHashMap<>();

    private PendingHandshakeRegistry() {
    }

    public static void put(Connection connection, String username, UUID profileId, KeyPair keyPair,
            byte[] challenge, String serverId) {
        PENDING.put(connectionKey(connection), new PendingHandshake(
                username, profileId, keyPair, challenge, serverId, System.currentTimeMillis()));
    }

    public static PendingHandshake get(Connection connection) {
        return PENDING.get(connectionKey(connection));
    }

    public static PendingHandshake remove(Connection connection) {
        return PENDING.remove(connectionKey(connection));
    }

    public static void clear(Connection connection) {
        PENDING.remove(connectionKey(connection));
    }

    public static void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    public static boolean isExpired(PendingHandshake pendingHandshake, long now) {
        return now - pendingHandshake.createdAt() > AuthServerConfig.pendingHandshakeTtl().toMillis();
    }

    private static String connectionKey(Connection connection) {
        SocketAddress remoteAddress = connection.getRemoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            String hostAddress = address == null ? inetSocketAddress.getHostString() : address.getHostAddress();
            return hostAddress + ":" + inetSocketAddress.getPort();
        }
        return String.valueOf(remoteAddress);
    }

    public record PendingHandshake(
            String username,
            UUID profileId,
            KeyPair keyPair,
            byte[] challenge,
            String serverId,
            long createdAt) {
    }
}
