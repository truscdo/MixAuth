package io.github.truscdo.mixauth.cache;

import io.github.truscdo.mixauth.LogUtil;
import io.github.truscdo.mixauth.db.KnownPlayerDao;
import io.github.truscdo.mixauth.db.OfflineLoginBlockDao;
import io.github.truscdo.mixauth.db.OfflineTrustedLoginDao;
import io.github.truscdo.mixauth.db.OfflineUserDao;
import org.slf4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 直接读写层：唯一接触 JDBC 的单 worker 平台线程（FIFO 有序）。
 *
 * <p>
 * 承载：启动全量加载（直接读）、write-behind 非关键写、write-through 关键写
 * （调用方 join 等待）。单线程天然保持登录状态机的写顺序（记失败→封禁；成功→清封禁→记免密）。
 * </p>
 *
 * <p>
 * 关键纪律：本层之外的任何代码（含虚拟线程/主线程）都不得直接执行 JDBC；
 * 直接读写全部经此单线程。
 * </p>
 *
 * <p>
 * 包内实现细节：外部（业务层/测试）一律通过 {@link AuthStore} 门面访问。
 * </p>
 */
final class DirectDb {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final int QUEUE_CAPACITY = 16384;

    private static volatile ExecutorService worker;

    private DirectDb() {
    }

    private static ExecutorService worker() {
        ExecutorService current = worker;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (DirectDb.class) {
            current = worker;
            if (current == null || current.isShutdown()) {
                current = createWorker();
                worker = current;
            }
            return current;
        }
    }

    private static ExecutorService createWorker() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "mixauth-db-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    /** 提交直接读/写任务到单 worker，返回可等待的 future（队列满返回失败 future）。 */
    public static <T> CompletableFuture<T> submit(Callable<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (RuntimeException runtimeException) {
                    throw runtimeException;
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, worker());
        } catch (RejectedExecutionException rejectedExecutionException) {
            LOGGER.warn("Auth DB worker queue full, rejected direct task: {}", rejectedExecutionException.toString());
            return CompletableFuture.failedFuture(rejectedExecutionException);
        }
    }

    /** 提交 write-behind 非关键写（fire-and-forget；队列满时降级丢弃，数据可重建）。 */
    public static void submitWrite(Runnable task) {
        try {
            worker().execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException runtimeException) {
                    LOGGER.warn("Auth DB write-behind task failed", runtimeException);
                }
            });
        } catch (RejectedExecutionException rejectedExecutionException) {
            LOGGER.warn("Auth DB worker queue full, dropped write-behind task (recoverable): {}",
                    rejectedExecutionException.toString());
        }
    }

    /**
     * 启动时在单 worker 上异步全量加载四表到缓存。
     * <p>
     * 成败策略由调用方（AuthMod）决定：成功后放行加载锁存器；失败则停机（fail-closed）。
     * </p>
     */
    public static CompletableFuture<Void> loadAllAsync() {
        return DirectDb.<Void>submit(() -> {
            loadKnownPlayers();
            loadOfflineUsers();
            loadBlocks();
            loadTrusted();
            return null;
        });
    }

    private static void loadKnownPlayers() {
        for (KnownPlayerDao.KnownPlayerEntry entry : KnownPlayerDao.findAll()) {
            AuthCache.backfillKnown(entry.playerUuid(), entry.username(), entry.loginMode());
        }
    }

    private static void loadOfflineUsers() {
        for (OfflineUserDao.OfflineUserRow row : OfflineUserDao.findAll()) {
            AuthCache.backfillPassword(row.playerUuid(), row.passwordHash());
        }
    }

    private static void loadBlocks() {
        for (OfflineLoginBlockDao.OfflineLoginBlockRow row : OfflineLoginBlockDao.findAll()) {
            AuthCache.backfillBlock(row.playerUuid(), row.blockedUntil());
        }
    }

    private static void loadTrusted() {
        for (OfflineTrustedLoginDao.OfflineTrustedLoginRow row : OfflineTrustedLoginDao.findAll()) {
            AuthCache.backfillTrusted(row.playerUuid(), row.ipAddress(), row.authenticatedAt());
        }
    }

    /** 阻塞等待已提交任务全部完成（关服排空 / 测试同步）。 */
    public static void drain() {
        ExecutorService current = worker;
        if (current == null || current.isShutdown()) {
            return;
        }
        try {
            submit(() -> null).get(30L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            LOGGER.warn("Auth DB worker drain failed", exception);
        }
    }

    /** 关服排空并关闭单 worker（之后若服务器重启会自动重新创建）。 */
    public static void drainAndShutdown() {
        drain();
        ExecutorService current = worker;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(10L, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        } finally {
            worker = null;
        }
    }
}
