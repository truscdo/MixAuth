package io.github.truscdo.mixauth;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * BCrypt 密码哈希/校验的单一入口。
 *
 * <p>
 * 模式中立工具（当前仅被离线密码流程消费），与 {@link PasswordPolicyValidator} /
 * {@link PasswordBlacklistLoader} 同属根包约定，避免把通用加密原语耦合进离线包。
 * </p>
 *
 * <p>
 * bcrypt 库实例无状态且线程安全，故以 static final 单例复用，避免每次调用重建。
 * 本类不依赖任何 Minecraft 类，可在纯 JUnit 环境直接测试。
 * </p>
 */
public final class PasswordHasher {

    /**
     * BCrypt 的硬性输入上限（UTF-8 字节）。超过该字节数的输入会被 BCrypt 静默截断，
     * 导致不同密码可能产生相同哈希，因此任何超限密码都必须在策略层拒绝。
     */
    public static final int MAX_INPUT_BYTES = 72;

    private static final BCrypt.Hasher HASHER = BCrypt.withDefaults();
    private static final BCrypt.Verifyer VERIFIER = BCrypt.verifyer();

    /**
     * 异步执行器：固定少量线程 + 有界队列。BCrypt（cost=12 约 200–400ms）由命令主线程
     * 异步提交到此处执行，队列满则拒绝（AbortPolicy），既不让主线程等待，也不无限堆积。
     */
    private static final ExecutorService HASH_EXECUTOR = createHashExecutor();

    private static ExecutorService createHashExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "mixauth-bcrypt");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private PasswordHasher() {
    }

    /**
     * 生成 BCrypt 哈希。
     *
     * @param password 明文密码（非空）
     * @param cost     BCrypt work factor，通常来自 {@link AuthServerConfig#bcryptCost()}
     * @return BCrypt 哈希字符串（格式 {@code $2a$<cost>$...}）
     */
    public static String hash(String password, int cost) {
        return HASHER.hashToString(cost, password.toCharArray());
    }

    /**
     * 校验密码与哈希是否匹配。
     *
     * <p>
     * 哈希为 null/空白或格式非法时一律视为不匹配（fail-closed），不向上抛异常：
     * 存储的哈希可能因数据损坏/篡改而无法解析，此时绝不能放行。
     * </p>
     *
     * @param password     明文密码（非空）
     * @param passwordHash BCrypt 哈希
     * @return 匹配返回 {@code true}；哈希缺失/非法或密码不匹配返回 {@code false}
     */
    public static boolean verify(String password, String passwordHash) {
        if (password == null || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        try {
            return VERIFIER.verify(password.toCharArray(), passwordHash).verified;
        } catch (RuntimeException runtimeException) {
            // 哈希格式损坏/非法：fail-closed，视为不匹配
            return false;
        }
    }

    /**
     * 异步生成 BCrypt 哈希（提交到有界执行器，主线程不参与计算）。
     * 执行器饱和（AbortPolicy 拒绝）时返回失败的 future，由调用方给出可读错误。
     */
    public static CompletableFuture<String> hashAsync(String password, int cost) {
        try {
            return CompletableFuture.supplyAsync(() -> hash(password, cost), HASH_EXECUTOR);
        } catch (RejectedExecutionException rejectedExecutionException) {
            return CompletableFuture.failedFuture(rejectedExecutionException);
        }
    }

    /**
     * 异步校验密码（提交到有界执行器）。语义与 {@link #verify(String, String)} 一致。
     */
    public static CompletableFuture<Boolean> verifyAsync(String password, String passwordHash) {
        try {
            return CompletableFuture.supplyAsync(() -> verify(password, passwordHash), HASH_EXECUTOR);
        } catch (RejectedExecutionException rejectedExecutionException) {
            return CompletableFuture.failedFuture(rejectedExecutionException);
        }
    }
}
