package io.github.truscdo.mixauth.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * offline_trusted_logins 表的数据访问。
 */
public final class OfflineTrustedLoginDao {
    private OfflineTrustedLoginDao() {
    }

    public static void saveOfflineTrustedLogin(UUID playerUuid, String ipAddress, long authenticatedAt) {
        DatabaseSupport.ensureDatabase();
        mergeOfflineTrustedLogin(playerUuid, ipAddress, authenticatedAt);
    }

    public static boolean hasRecentOfflineTrustedLogin(UUID playerUuid, String ipAddress, long validAfter) {
        if (playerUuid == null) {
            return false;
        }

        return DatabaseSupport.executeQuery(
                """
                        SELECT 1
                        FROM offline_trusted_logins
                        WHERE player_uuid = ? AND ip_address = ? AND authenticated_at >= ?
                        LIMIT 1
                        """,
                stmt -> {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, ipAddress);
                    stmt.setLong(3, validAfter);
                },
                ResultSet::next,
                "Failed to read offline trusted login");
    }

    public static boolean hasSharedRecentOfflineTrustedIp(String ipAddress, long validAfter) {
        return DatabaseSupport.executeQuery(
                """
                        SELECT COUNT(DISTINCT player_uuid) AS player_count
                        FROM offline_trusted_logins
                        WHERE ip_address = ? AND authenticated_at >= ?
                        """,
                stmt -> {
                    stmt.setString(1, ipAddress);
                    stmt.setLong(2, validAfter);
                },
                rs -> rs.next() && rs.getLong("player_count") > 1L,
                "Failed to read shared offline trusted IP");
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
