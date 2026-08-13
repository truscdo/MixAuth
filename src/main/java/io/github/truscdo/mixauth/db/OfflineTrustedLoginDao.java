package io.github.truscdo.mixauth.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * offline_trusted_logins 表的数据访问。
 */
public final class OfflineTrustedLoginDao {
    private OfflineTrustedLoginDao() {
    }

    /** offline_trusted_logins 全表行（启动加载用）。 */
    public record OfflineTrustedLoginRow(UUID playerUuid, String ipAddress, long authenticatedAt) {
    }

    public static void saveOfflineTrustedLogin(UUID playerUuid, String ipAddress, long authenticatedAt) {
        DatabaseSupport.ensureDatabase();
        mergeOfflineTrustedLogin(playerUuid, ipAddress, authenticatedAt);
    }

    /** 启动全量加载：读取 offline_trusted_logins 全表。 */
    public static List<OfflineTrustedLoginRow> findAll() {
        return DatabaseSupport.executeQuery(
                "SELECT player_uuid, ip_address, authenticated_at FROM offline_trusted_logins",
                stmt -> {
                },
                rs -> {
                    List<OfflineTrustedLoginRow> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(new OfflineTrustedLoginRow(
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("ip_address"),
                                rs.getLong("authenticated_at")));
                    }
                    return results;
                },
                "Failed to load offline trusted logins");
    }

    public static void clearOfflineTrustedLogins(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        DatabaseSupport.executeUpdate(
                "DELETE FROM offline_trusted_logins WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to clear offline trusted logins");
    }

    /** 删除指定 (uuid, ip) 单条记录（免密窗口惰性过期用）。 */
    public static void clearOfflineTrustedLogin(UUID playerUuid, String ipAddress) {
        if (playerUuid == null || ipAddress == null) {
            return;
        }

        DatabaseSupport.executeUpdate(
                "DELETE FROM offline_trusted_logins WHERE player_uuid = ? AND ip_address = ?",
                stmt -> {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, ipAddress);
                },
                "Failed to clear offline trusted login");
    }

    /**
     * 删除所有 {@code authenticated_at} 早于指定时间戳的过期记录（免密窗口已失效的行）。
     *
     * @param validBefore 时间戳阈值，早于该值的记录视为过期
     */
    public static void deleteExpiredOfflineTrustedLogins(long validBefore) {
        DatabaseSupport.executeUpdate(
                "DELETE FROM offline_trusted_logins WHERE authenticated_at < ?",
                stmt -> stmt.setLong(1, validBefore),
                "Failed to delete expired offline trusted logins");
    }

    private static void mergeOfflineTrustedLogin(UUID playerUuid, String ipAddress, long authenticatedAt) {
        long now = Instant.now().toEpochMilli();
        String sql = """
                MERGE INTO offline_trusted_logins AS target
                USING (VALUES (?, ?, ?, ?, ?)) AS incoming(player_uuid, ip_address, authenticated_at, created_at, updated_at)
                ON target.player_uuid = incoming.player_uuid AND target.ip_address = incoming.ip_address
                WHEN MATCHED THEN UPDATE SET
                    authenticated_at = incoming.authenticated_at,
                    updated_at = incoming.updated_at
                WHEN NOT MATCHED THEN INSERT (player_uuid, ip_address, authenticated_at, created_at, updated_at)
                    VALUES (incoming.player_uuid, incoming.ip_address, incoming.authenticated_at, incoming.created_at, incoming.updated_at)
                """;
        try (Connection connection = DatabaseSupport.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, ipAddress);
            statement.setLong(3, authenticatedAt);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed to save offline trusted login", sqlException);
        }
    }
}
