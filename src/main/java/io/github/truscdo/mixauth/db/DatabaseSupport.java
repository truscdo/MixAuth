package io.github.truscdo.mixauth.db;

import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.LogUtil;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库基础设施：H2 连接池、建表与通用 SQL 执行。
 * <p>
 * 所有 DAO 通过 {@link #executeQuery} / {@link #executeUpdate} 访问数据库；
 * 连接来自 H2 内置连接池（{@link JdbcConnectionPool}），避免每次查询新建连接的开销。
 */
public final class DatabaseSupport {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final Object DATABASE_LOCK = new Object();
    private static final String H2_DRIVER_CLASS = "org.h2.Driver";
    private static final String H2_USERNAME = "sa";
    private static final String H2_PASSWORD = "";
    private static final String H2_URL_PREFIX = "jdbc:h2:file:";
    /** 加大 H2 页缓存：启动全量加载与写路径也命中页缓存，成本近零的免费加成。 */
    private static final String H2_URL_OPTIONS = ";CACHE_SIZE=65536";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final int MAX_CONNECTIONS = 16;

    private static final String OFFLINE_USERS_SQL = """
            CREATE TABLE IF NOT EXISTS offline_users (
                player_uuid VARCHAR PRIMARY KEY,
                password_hash VARCHAR NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    private static final String OFFLINE_BLOCKS_SQL = """
            CREATE TABLE IF NOT EXISTS offline_login_blocks (
                player_uuid VARCHAR PRIMARY KEY,
                blocked_until BIGINT NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    private static final String OFFLINE_TRUSTED_LOGINS_SQL = """
            CREATE TABLE IF NOT EXISTS offline_trusted_logins (
                player_uuid VARCHAR NOT NULL,
                ip_address VARCHAR NOT NULL,
                authenticated_at BIGINT NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (player_uuid, ip_address)
            )
            """;
    private static final String OFFLINE_TRUSTED_LOGINS_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_offline_trusted_logins_ip_authenticated_at
            ON offline_trusted_logins (ip_address, authenticated_at)
            """;
    private static final String KNOWN_PLAYERS_SQL = """
            CREATE TABLE IF NOT EXISTS known_players (
                player_uuid VARCHAR PRIMARY KEY,
                username VARCHAR_IGNORECASE NOT NULL,
                login_mode VARCHAR NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    private static final String OFFLINE_CLIENT_ALIASES_SQL = """
            CREATE TABLE IF NOT EXISTS offline_client_aliases (
                canonical_offline_uuid VARCHAR NOT NULL,
                client_uuid VARCHAR NOT NULL,
                username VARCHAR_IGNORECASE NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (canonical_offline_uuid, client_uuid)
            )
            """;

    private static volatile Path databasePath;
    private static volatile JdbcConnectionPool connectionPool;

    private DatabaseSupport() {
    }

    /**
     * 关闭连接池并释放资源（服务器停止时调用）。之后再次访问数据库会自动重新初始化。
     */
    public static void dispose() {
        JdbcConnectionPool pool = connectionPool;
        connectionPool = null;
        databasePath = null;
        if (pool != null) {
            pool.dispose();
            LOGGER.info("Auth H2 connection pool disposed");
        }
    }

    /**
     * 确保数据库已初始化：建目录、加载驱动、创建连接池并建表。
     */
    public static void ensureDatabase() {
        Path configuredDatabasePath = resolveConfiguredDatabasePath().toAbsolutePath();
        if (connectionPool != null && configuredDatabasePath.equals(databasePath)) {
            return;
        }

        synchronized (DATABASE_LOCK) {
            if (connectionPool != null && configuredDatabasePath.equals(databasePath)) {
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

            String databaseUrl = buildDatabaseUrl(configuredDatabasePath);
            JdbcConnectionPool pool = JdbcConnectionPool.create(databaseUrl, H2_USERNAME, H2_PASSWORD);
            pool.setMaxConnections(MAX_CONNECTIONS);
            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(OFFLINE_USERS_SQL);
                statement.execute(OFFLINE_BLOCKS_SQL);
                statement.execute(OFFLINE_TRUSTED_LOGINS_SQL);
                statement.execute(OFFLINE_TRUSTED_LOGINS_INDEX_SQL);
                statement.execute(KNOWN_PLAYERS_SQL);
                statement.execute(OFFLINE_CLIENT_ALIASES_SQL);
            } catch (SQLException sqlException) {
                pool.dispose();
                throw new IllegalStateException("Failed to initialize auth database", sqlException);
            }

            connectionPool = pool;
            databasePath = configuredDatabasePath;
            LOGGER.info("Auth H2 database ready at {} (connection pool, max {})", configuredDatabasePath,
                    MAX_CONNECTIONS);
        }
    }

    static Connection openConnection() throws SQLException {
        JdbcConnectionPool pool = connectionPool;
        if (pool == null) {
            throw new IllegalStateException("Auth database is not initialized");
        }
        return pool.getConnection();
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
        return H2_URL_PREFIX + configuredDatabasePath.toString().replace('\\', '/') + H2_URL_OPTIONS;
    }

    /**
     * 执行 {@code CHECKPOINT SYNC}：把已提交事务强制刷盘（fsync）。
     * <p>
     * 供关键写（密码注册/改密/设密）在落库后调用，将密码持久性从 H2 默认
     * {@code WRITE_DELAY} 的约 1s 窗口提升到 0（崩溃/断电 0 丢失）。
     * </p>
     */
    public static void checkpointSync() {
        ensureDatabase();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CHECKPOINT SYNC");
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Failed to checkpoint auth database", sqlException);
        }
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(ResultSet resultSet) throws SQLException;
    }

    public static <T> T executeQuery(String sql, SqlConsumer binder, SqlFunction<T> mapper, String errorMessage) {
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

    public static int executeUpdate(String sql, SqlConsumer binder, String errorMessage) {
        ensureDatabase();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            return statement.executeUpdate();
        } catch (SQLException sqlException) {
            throw new IllegalStateException(errorMessage, sqlException);
        }
    }

    public static boolean isUniqueConstraintViolation(SQLException sqlException) {
        return UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState());
    }
}
