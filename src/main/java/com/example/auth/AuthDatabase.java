package com.example.auth;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AuthDatabase {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final Object DATABASE_LOCK = new Object();
    private static final String H2_DRIVER_CLASS = "org.h2.Driver";
    private static final String H2_USERNAME = "sa";
    private static final String H2_PASSWORD = "";
    private static final String H2_URL_PREFIX = "jdbc:h2:file:";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static volatile Path databasePath;
    private static volatile String databaseUrl;
    private static volatile boolean databaseReady;

    private AuthDatabase() {
    }

    public static String findLoginMode(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        return executeQuery(
                "SELECT login_mode FROM known_players WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                rs -> rs.next() ? rs.getString("login_mode") : null,
                "Failed to read known player");
    }

    public static void saveKnownPlayer(UUID playerUuid, String username, String loginMode) {
        if (playerUuid == null || loginMode == null) {
            return;
        }

        ensureDatabase();
        mergeKnownPlayer(playerUuid, username, loginMode);
    }

    public static boolean removeKnownPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        return executeUpdate(
                "DELETE FROM known_players WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to remove known player") > 0;
    }

    public record KnownPlayerEntry(UUID playerUuid, String loginMode) {
    }

    public static List<KnownPlayerEntry> findKnownPlayersByUsername(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }

        return executeQuery(
                "SELECT player_uuid, login_mode FROM known_players WHERE username = ?",
                stmt -> stmt.setString(1, username),
                rs -> {
                    List<KnownPlayerEntry> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(new KnownPlayerEntry(
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("login_mode")));
                    }
                    return results;
                },
                "Failed to look up known players by username");
    }

    public static boolean isOfflineRegistered(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }

        return executeQuery(
                "SELECT 1 FROM offline_users WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                ResultSet::next,
                "Failed to read offline user");
    }

    public static boolean insertOfflineUser(UUID playerUuid, String passwordHash) {
        if (playerUuid == null) {
            return false;
        }

        ensureDatabase();

        long now = Instant.now().toEpochMilli();
        String sql = """
                INSERT INTO offline_users (player_uuid, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, passwordHash);
            statement.setLong(3, now);
            statement.setLong(4, now);
            return statement.executeUpdate() > 0;
        } catch (SQLException sqlException) {
            if (isUniqueConstraintViolation(sqlException)) {
                return false;
            }
            throw new IllegalStateException("Failed to create offline account", sqlException);
        }
    }

    public static String findOfflinePasswordHash(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        return executeQuery(
                "SELECT password_hash FROM offline_users WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                rs -> rs.next() ? rs.getString("password_hash") : null,
                "Failed to verify offline password");
    }

    public static void saveOfflinePassword(UUID playerUuid, String passwordHash) {
        ensureDatabase();
        mergeOfflinePassword(playerUuid, passwordHash);
    }

    public static void saveOfflineTrustedLogin(UUID playerUuid, String ipAddress, long authenticatedAt) {
        ensureDatabase();
        mergeOfflineTrustedLogin(playerUuid, ipAddress, authenticatedAt);
    }

    public static boolean hasRecentOfflineTrustedLogin(UUID playerUuid, String ipAddress, long validAfter) {
        if (playerUuid == null) {
            return false;
        }

        return executeQuery(
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
        return executeQuery(
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

        executeUpdate(
                "DELETE FROM offline_trusted_logins WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to clear offline trusted logins");
    }

    public static void saveOfflineLoginBlock(UUID playerUuid, long blockedUntil) {
        ensureDatabase();
        mergeOfflineLoginBlock(playerUuid, blockedUntil);
    }

    public static long findOfflineLoginBlockedUntil(UUID playerUuid) {
        if (playerUuid == null) {
            return 0L;
        }

        return executeQuery(
                "SELECT blocked_until FROM offline_login_blocks WHERE player_uuid = ? LIMIT 1",
                stmt -> stmt.setString(1, playerUuid.toString()),
                rs -> rs.next() ? rs.getLong("blocked_until") : 0L,
                "Failed to read offline login block");
    }

    public static void clearOfflineLoginBlock(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        executeUpdate(
                "DELETE FROM offline_login_blocks WHERE player_uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                "Failed to clear offline login block");
    }

    private static void ensureDatabase() {
        Path configuredDatabasePath = resolveConfiguredDatabasePath().toAbsolutePath();
        String configuredDatabaseUrl = buildDatabaseUrl(configuredDatabasePath);
        if (databaseReady && configuredDatabasePath.equals(databasePath)) {
            return;
        }

        synchronized (DATABASE_LOCK) {
            if (databaseReady && configuredDatabasePath.equals(databasePath)) {
                return;
            }

            try {
                Path parent = configuredDatabasePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to create auth database directory", exception);
            }

            try {
                Class.forName(H2_DRIVER_CLASS);
            } catch (ClassNotFoundException classNotFoundException) {
                throw new IllegalStateException("Failed to load H2 JDBC driver", classNotFoundException);
            }

            String offlineUsersSql = """
                    CREATE TABLE IF NOT EXISTS offline_users (
                        player_uuid VARCHAR PRIMARY KEY,
                        password_hash VARCHAR NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """;
            String offlineBlocksSql = """
                    CREATE TABLE IF NOT EXISTS offline_login_blocks (
                        player_uuid VARCHAR PRIMARY KEY,
                        blocked_until BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """;
            String offlineTrustedLoginsSql = """
                    CREATE TABLE IF NOT EXISTS offline_trusted_logins (
                        player_uuid VARCHAR NOT NULL,
                        ip_address VARCHAR NOT NULL,
                        authenticated_at BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, ip_address)
                    )
                    """;
            String offlineTrustedLoginsIndexSql = """
                    CREATE INDEX IF NOT EXISTS idx_offline_trusted_logins_ip_authenticated_at
                    ON offline_trusted_logins (ip_address, authenticated_at)
                    """;
            String knownPlayersSql = """
                    CREATE TABLE IF NOT EXISTS known_players (
                        player_uuid VARCHAR PRIMARY KEY,
                        username VARCHAR_IGNORECASE NOT NULL,
                        login_mode VARCHAR NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """;
            try (Connection connection = DriverManager.getConnection(configuredDatabaseUrl, H2_USERNAME, H2_PASSWORD);
                    Statement statement = connection.createStatement()) {
                statement.execute(offlineUsersSql);
                statement.execute(offlineBlocksSql);
                statement.execute(offlineTrustedLoginsSql);
                statement.execute(offlineTrustedLoginsIndexSql);
                statement.execute(knownPlayersSql);

            } catch (SQLException sqlException) {
                throw new IllegalStateException("Failed to initialize auth database", sqlException);
            }

            databasePath = configuredDatabasePath;
            databaseUrl = configuredDatabaseUrl;
            databaseReady = true;
            LOGGER.info("Auth H2 database ready at {}", configuredDatabasePath);
        }
    }

    private static Connection openConnection() throws SQLException {
        String currentDatabaseUrl = databaseUrl;
        if (currentDatabaseUrl == null) {
            throw new IllegalStateException("Auth database is not initialized");
        }
        return DriverManager.getConnection(currentDatabaseUrl, H2_USERNAME, H2_PASSWORD);
    }

    private static Path resolveConfiguredDatabasePath() {
        String configuredPath = AuthServerConfig.databasePath();
        try {
            return Path.of(configuredPath).normalize();
        } catch (RuntimeException runtimeException) {
            throw new IllegalStateException("Invalid auth database path: " + configuredPath, runtimeException);
        }
    }

    private static String buildDatabaseUrl(Path configuredDatabasePath) {
        return H2_URL_PREFIX + configuredDatabasePath.toString().replace('\\', '/');
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(ResultSet resultSet) throws SQLException;
    }

    private static <T> T executeQuery(String sql, SqlConsumer binder, SqlFunction<T> mapper, String errorMessage) {
        ensureDatabase();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapper.apply(resultSet);
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException(errorMessage, sqlException);
        }
    }

    private static int executeUpdate(String sql, SqlConsumer binder, String errorMessage) {
        ensureDatabase();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            return statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException(errorMessage, sqlException);
        }
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
        try (Connection connection = openConnection();
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

    private static void mergeOfflinePassword(UUID playerUuid, String passwordHash) {
        long now = Instant.now().toEpochMilli();
        executeUpdate(
                """
                        MERGE INTO offline_users AS target
                        USING (VALUES (?, ?, ?, ?)) AS incoming(player_uuid, password_hash, created_at, updated_at)
                        ON target.player_uuid = incoming.player_uuid
                        WHEN MATCHED THEN UPDATE SET
                            password_hash = incoming.password_hash,
                            updated_at = incoming.updated_at
                        WHEN NOT MATCHED THEN INSERT (player_uuid, password_hash, created_at, updated_at)
                            VALUES (incoming.player_uuid, incoming.password_hash, incoming.created_at, incoming.updated_at)
                        """,
                stmt -> {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, passwordHash);
                    stmt.setLong(3, now);
                    stmt.setLong(4, now);
                },
                "Failed to save offline password");
    }

    private static void mergeOfflineTrustedLogin(UUID playerUuid, String ipAddress, long authenticatedAt) {
        long now = Instant.now().toEpochMilli();
        executeUpdate(
                """
                        MERGE INTO offline_trusted_logins AS target
                        USING (VALUES (?, ?, ?, ?, ?)) AS incoming(player_uuid, ip_address, authenticated_at, created_at, updated_at)
                        ON target.player_uuid = incoming.player_uuid AND target.ip_address = incoming.ip_address
                        WHEN MATCHED THEN UPDATE SET
                            authenticated_at = incoming.authenticated_at,
                            updated_at = incoming.updated_at
                        WHEN NOT MATCHED THEN INSERT (player_uuid, ip_address, authenticated_at, created_at, updated_at)
                            VALUES (incoming.player_uuid, incoming.ip_address, incoming.authenticated_at, incoming.created_at, incoming.updated_at)
                        """,
                stmt -> {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, ipAddress);
                    stmt.setLong(3, authenticatedAt);
                    stmt.setLong(4, now);
                    stmt.setLong(5, now);
                },
                "Failed to save offline trusted login");
    }

    private static void mergeOfflineLoginBlock(UUID playerUuid, long blockedUntil) {
        long now = Instant.now().toEpochMilli();
        executeUpdate(
                """
                        MERGE INTO offline_login_blocks AS target
                        USING (VALUES (?, ?, ?, ?)) AS incoming(player_uuid, blocked_until, created_at, updated_at)
                        ON target.player_uuid = incoming.player_uuid
                        WHEN MATCHED THEN UPDATE SET
                            blocked_until = incoming.blocked_until,
                            updated_at = incoming.updated_at
                        WHEN NOT MATCHED THEN INSERT (player_uuid, blocked_until, created_at, updated_at)
                            VALUES (incoming.player_uuid, incoming.blocked_until, incoming.created_at, incoming.updated_at)
                        """,
                stmt -> {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setLong(2, blockedUntil);
                    stmt.setLong(3, now);
                    stmt.setLong(4, now);
                },
                "Failed to block offline login");
    }

    private static boolean isUniqueConstraintViolation(SQLException sqlException) {
        return UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState());
    }
}