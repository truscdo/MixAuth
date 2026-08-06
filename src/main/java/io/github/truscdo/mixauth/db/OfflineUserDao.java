package io.github.truscdo.mixauth.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * offline_users 表的数据访问。
 */
public final class OfflineUserDao {
    private OfflineUserDao() {
    }

    public static boolean isOfflineRegistered(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        return DatabaseSupport.executeQuery(
                "SELECT 1 FROM offline_users WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                ResultSet::next,
                "Failed to read offline user");
    }

    public static boolean insertOfflineUser(UUID playerUuid, String passwordHash) {
        if (playerUuid == null) {
            return false;
        }

        DatabaseSupport.ensureDatabase();

        long now = Instant.now().toEpochMilli();
        String sql = """
                INSERT INTO offline_users (player_uuid, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = DatabaseSupport.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, passwordHash);
            statement.setLong(3, now);
            statement.setLong(4, now);
            return statement.executeUpdate() > 0;
        } catch (SQLException sqlException) {
            if (DatabaseSupport.isUniqueConstraintViolation(sqlException)) {
                return false;
            }
            throw new IllegalStateException("Failed to create offline account", sqlException);
        }
    }

    public static String findOfflinePasswordHash(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        return DatabaseSupport.executeQuery(
                "SELECT password_hash FROM offline_users WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                rs -> rs.next() ? rs.getString("password_hash") : null,
                "Failed to verify offline password");
    }

    public static boolean deleteOfflineUser(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        return DatabaseSupport.executeUpdate(
                "DELETE FROM offline_users WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to delete offline user") > 0;
    }

    public static void saveOfflinePassword(UUID playerUuid, String passwordHash) {
        DatabaseSupport.ensureDatabase();
        mergeOfflinePassword(playerUuid, passwordHash);
    }

    private static void mergeOfflinePassword(UUID playerUuid, String passwordHash) {
        long now = Instant.now().toEpochMilli();
        String sql = """
                MERGE INTO offline_users AS target
                USING (VALUES (?, ?, ?, ?)) AS incoming(player_uuid, password_hash, created_at, updated_at)
                ON target.player_uuid = incoming.player_uuid
                WHEN MATCHED THEN UPDATE SET
                    password_hash = incoming.password_hash,
                    updated_at = incoming.updated_at
                WHEN NOT MATCHED THEN INSERT (player_uuid, password_hash, created_at, updated_at)
                    VALUES (incoming.player_uuid, incoming.password_hash, incoming.created_at, incoming.updated_at)
                """;
        try (Connection connection = DatabaseSupport.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, passwordHash);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed to save offline password", sqlException);
        }
    }
}
