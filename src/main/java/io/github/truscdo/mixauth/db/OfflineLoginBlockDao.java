package io.github.truscdo.mixauth.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * offline_login_blocks 表的数据访问。
 */
public final class OfflineLoginBlockDao {
    private OfflineLoginBlockDao() {
    }

    public static void saveOfflineLoginBlock(UUID playerUuid, long blockedUntil) {
        DatabaseSupport.ensureDatabase();
        mergeOfflineLoginBlock(playerUuid, blockedUntil);
    }

    public static long findOfflineLoginBlockedUntil(UUID playerUuid) {
        if (playerUuid == null) {
            return 0L;
        }

        return DatabaseSupport.executeQuery(
                "SELECT blocked_until FROM offline_login_blocks WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                rs -> rs.next() ? rs.getLong("blocked_until") : 0L,
                "Failed to read offline login block");
    }

    public static void clearOfflineLoginBlock(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        DatabaseSupport.executeUpdate(
                "DELETE FROM offline_login_blocks WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to clear offline login block");
    }

    private static void mergeOfflineLoginBlock(UUID playerUuid, long blockedUntil) {
        long now = Instant.now().toEpochMilli();
        String sql = """
                MERGE INTO offline_login_blocks AS target
                USING (VALUES (?, ?, ?, ?)) AS incoming(player_uuid, blocked_until, created_at, updated_at)
                ON target.player_uuid = incoming.player_uuid
                WHEN MATCHED THEN UPDATE SET
                    blocked_until = incoming.blocked_until,
                    updated_at = incoming.updated_at
                WHEN NOT MATCHED THEN INSERT (player_uuid, blocked_until, created_at, updated_at)
                    VALUES (incoming.player_uuid, incoming.blocked_until, incoming.created_at, incoming.updated_at)
                """;
        try (Connection connection = DatabaseSupport.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setLong(2, blockedUntil);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed to block offline login", sqlException);
        }
    }
}
