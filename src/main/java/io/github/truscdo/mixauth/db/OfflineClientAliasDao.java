package io.github.truscdo.mixauth.db;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@code offline_client_aliases} 表的数据访问。
 */
public final class OfflineClientAliasDao {
    private OfflineClientAliasDao() {
    }

    public record OfflineClientAliasEntry(
            UUID canonicalOfflineUuid,
            UUID clientUuid,
            String username,
            long createdAt,
            long updatedAt) {
    }

    /** 启动全量加载：读取 offline_client_aliases 全表。 */
    public static List<OfflineClientAliasEntry> findAll() {
        return DatabaseSupport.executeQuery(
                "SELECT canonical_offline_uuid, client_uuid, username, created_at, updated_at "
                        + "FROM offline_client_aliases",
                stmt -> {
                },
                rs -> {
                    List<OfflineClientAliasEntry> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(new OfflineClientAliasEntry(
                                UUID.fromString(rs.getString("canonical_offline_uuid")),
                                UUID.fromString(rs.getString("client_uuid")),
                                rs.getString("username"),
                                rs.getLong("created_at"),
                                rs.getLong("updated_at")));
                    }
                    return results;
                },
                "Failed to load offline client aliases");
    }

    /** 查询单个 (canonicalOfflineUuid, clientUuid) alias。 */
    public static OfflineClientAliasEntry find(UUID canonicalOfflineUuid, UUID clientUuid) {
        if (canonicalOfflineUuid == null || clientUuid == null) {
            return null;
        }
        return DatabaseSupport.executeQuery(
                "SELECT canonical_offline_uuid, client_uuid, username, created_at, updated_at "
                        + "FROM offline_client_aliases "
                        + "WHERE canonical_offline_uuid = ? AND client_uuid = ?",
                stmt -> {
                    stmt.setString(1, canonicalOfflineUuid.toString());
                    stmt.setString(2, clientUuid.toString());
                },
                rs -> rs.next() ? readEntry(rs) : null,
                "Failed to load offline client alias");
    }

    /**
     * 写入或刷新 alias。已存在的行保留 {@code created_at}，只更新用户名和
     * {@code updated_at}。
     */
    public static void save(
            UUID canonicalOfflineUuid,
            UUID clientUuid,
            String username,
            long createdAt,
            long updatedAt) {
        validate(canonicalOfflineUuid, clientUuid, username);
        DatabaseSupport.ensureDatabase();

        String sql = """
                MERGE INTO offline_client_aliases AS target
                USING (VALUES (?, ?, ?, ?, ?)) AS incoming(
                    canonical_offline_uuid, client_uuid, username, created_at, updated_at)
                ON target.canonical_offline_uuid = incoming.canonical_offline_uuid
                    AND target.client_uuid = incoming.client_uuid
                WHEN MATCHED THEN UPDATE SET
                    username = incoming.username,
                    updated_at = incoming.updated_at
                WHEN NOT MATCHED THEN INSERT (
                    canonical_offline_uuid, client_uuid, username, created_at, updated_at)
                    VALUES (incoming.canonical_offline_uuid, incoming.client_uuid,
                        incoming.username, incoming.created_at, incoming.updated_at)
                """;
        try (Connection connection = DatabaseSupport.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, canonicalOfflineUuid.toString());
            statement.setString(2, clientUuid.toString());
            statement.setString(3, username);
            statement.setLong(4, createdAt);
            statement.setLong(5, updatedAt);
            statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed to save offline client alias", sqlException);
        }
    }

    /** 删除单个 alias。 */
    public static boolean remove(UUID canonicalOfflineUuid, UUID clientUuid) {
        if (canonicalOfflineUuid == null || clientUuid == null) {
            return false;
        }
        return DatabaseSupport.executeUpdate(
                "DELETE FROM offline_client_aliases "
                        + "WHERE canonical_offline_uuid = ? AND client_uuid = ?",
                stmt -> {
                    stmt.setString(1, canonicalOfflineUuid.toString());
                    stmt.setString(2, clientUuid.toString());
                },
                "Failed to remove offline client alias") > 0;
    }

    /** 删除一个 canonical 离线身份的全部 alias。 */
    public static int removeAll(UUID canonicalOfflineUuid) {
        if (canonicalOfflineUuid == null) {
            return 0;
        }
        return DatabaseSupport.executeUpdate(
                "DELETE FROM offline_client_aliases WHERE canonical_offline_uuid = ?",
                stmt -> stmt.setString(1, canonicalOfflineUuid.toString()),
                "Failed to remove offline client aliases");
    }

    /**
     * 执行单个 canonical 身份的容量淘汰。调用方应在当前 DB worker 内串行调用。
     * 本次使用的 clientUuid 会被保留；即使数据库中已有超限旧数据也会被收敛到上限。
     */
    public static void trimToCapacity(UUID canonicalOfflineUuid, UUID keepClientUuid, int capacity) {
        if (canonicalOfflineUuid == null || capacity < 1) {
            return;
        }

        List<OfflineClientAliasEntry> aliases = findByCanonical(canonicalOfflineUuid);
        if (aliases.size() <= capacity) {
            return;
        }

        aliases.sort(Comparator
                .comparingLong(OfflineClientAliasEntry::updatedAt)
                .thenComparingLong(OfflineClientAliasEntry::createdAt)
                .thenComparing(entry -> entry.clientUuid().toString()));
        int removeCount = aliases.size() - capacity;
        for (OfflineClientAliasEntry alias : aliases) {
            if (removeCount == 0) {
                break;
            }
            if (alias.clientUuid().equals(keepClientUuid)) {
                continue;
            }
            remove(canonicalOfflineUuid, alias.clientUuid());
            removeCount--;
        }
    }

    private static List<OfflineClientAliasEntry> findByCanonical(UUID canonicalOfflineUuid) {
        return DatabaseSupport.executeQuery(
                "SELECT canonical_offline_uuid, client_uuid, username, created_at, updated_at "
                        + "FROM offline_client_aliases WHERE canonical_offline_uuid = ?",
                stmt -> stmt.setString(1, canonicalOfflineUuid.toString()),
                rs -> {
                    List<OfflineClientAliasEntry> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(readEntry(rs));
                    }
                    return results;
                },
                "Failed to load offline client aliases for capacity trim");
    }

    private static OfflineClientAliasEntry readEntry(java.sql.ResultSet resultSet) throws SQLException {
        return new OfflineClientAliasEntry(
                UUID.fromString(resultSet.getString("canonical_offline_uuid")),
                UUID.fromString(resultSet.getString("client_uuid")),
                resultSet.getString("username"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private static void validate(UUID canonicalOfflineUuid, UUID clientUuid, String username) {
        if (canonicalOfflineUuid == null || clientUuid == null || username == null || username.isBlank()
                || !canonicalOfflineUuid.equals(offlineUuid(username))) {
            throw new IllegalArgumentException("Invalid offline client alias identity");
        }
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
