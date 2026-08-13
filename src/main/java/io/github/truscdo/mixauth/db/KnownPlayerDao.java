package io.github.truscdo.mixauth.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * known_players 表的数据访问。
 */
public final class KnownPlayerDao {
    private KnownPlayerDao() {
    }

    public record KnownPlayerEntry(UUID playerUuid, String username, String loginMode) {
    }

    /** 启动全量加载：读取 known_players 全表。 */
    public static List<KnownPlayerEntry> findAll() {
        return DatabaseSupport.executeQuery(
                "SELECT player_uuid, username, login_mode FROM known_players",
                stmt -> {
                },
                rs -> {
                    List<KnownPlayerEntry> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(new KnownPlayerEntry(
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("username"),
                                rs.getString("login_mode")));
                    }
                    return results;
                },
                "Failed to load known players");
    }

    public static void saveKnownPlayer(UUID playerUuid, String username, String loginMode) {
        if (playerUuid == null || loginMode == null) {
            return;
        }

        DatabaseSupport.ensureDatabase();
        mergeKnownPlayer(playerUuid, username, loginMode);
    }

    public static boolean removeKnownPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        return DatabaseSupport.executeUpdate(
                "DELETE FROM known_players WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to remove known player") > 0;
    }

    private static void mergeKnownPlayer(UUID playerUuid, String username, String loginMode) {
        long now = Instant.now().toEpochMilli();
        String safeUsername = username == null ? "" : username;
        String sql = """
                MERGE INTO known_players AS target
                USING (VALUES (?, ?, ?, ?, ?)) AS incoming(player_uuid, username, login_mode, created_at, updated_at)
                ON target.player_uuid = incoming.player_uuid
                WHEN MATCHED THEN UPDATE SET
                    username = incoming.username,
                    login_mode = incoming.login_mode,
                    updated_at = incoming.updated_at
                WHEN NOT MATCHED THEN INSERT (player_uuid, username, login_mode, created_at, updated_at)
                    VALUES (incoming.player_uuid, incoming.username, incoming.login_mode, incoming.created_at, incoming.updated_at)
                """;
        try (Connection connection = DatabaseSupport.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, safeUsername);
            statement.setString(3, loginMode);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed to save known player", sqlException);
        }
    }
}
