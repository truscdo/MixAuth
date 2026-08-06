package io.github.truscdo.mixauth.validation;

import io.github.truscdo.mixauth.LogUtil;
import org.slf4j.Logger;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * RSA-1024 密钥对预生成缓存。
 *
 * <p>
 * 目标：把在线握手所需的 RSA 密钥对生成彻底移出服务器主线程，并用一个
 * 有界缓冲池平滑峰值。单条后台生产者线程持续生成密钥对并放入有界队列：
 * <ul>
 * <li>队列满时 {@code put} 自然阻塞，空闲时零 CPU 开销；</li>
 * <li>主线程快速路径 {@link #poll()} 非阻塞取用（微秒级）；</li>
 * <li>缓存被掏空时 {@link #take()} 由非主线程阻塞等待（通常一个生成周期内补上）。</li>
 * </ul>
 *
 * <p>
 * 在洪水场景下，握手吞吐天然被生产者速率（约每 8ms 一个密钥对）封顶，
 * 且所有生成成本都在后台线程，主线程对此类 CPU-DoS 免疫。
 *
 * <p>
 * 密钥生成逻辑与 {@code net.minecraft.util.Crypt#generateKeyPair()} 完全一致
 * （RSA / 1024 位），但只依赖标准 JCA，因此本类可在纯 JUnit 环境中直接测试。
 */
final class KeyPairCache {
    private static final Logger LOGGER = LogUtil.getLogger();

    /** 与 Minecraft {@code Crypt} 一致的 RSA 密钥长度。 */
    private static final int RSA_BITS = 1024;
    /** 预生成缓冲容量：足够覆盖正常突发，内存占用可忽略（约 0.5 KB/个）。 */
    private static final int CAPACITY = 32;
    /** 生产者线程失败退避，避免异常时忙循环。 */
    private static final long RETRY_BACKOFF_MILLIS = 100L;

    private static final BlockingQueue<KeyPair> CACHE = new ArrayBlockingQueue<>(CAPACITY);

    private KeyPairCache() {
    }

    static {
        Thread producer = new Thread(KeyPairCache::produce, "mixauth-keypair-producer");
        producer.setDaemon(true);
        producer.start();
        LOGGER.debug("Key pair pre-generation cache started (capacity {})", CAPACITY);
    }

    private static void produce() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 队列满时阻塞，自动暂停生产；消费一个后立刻补一个。
                CACHE.put(generateKeyPair());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException runtimeException) {
                // 兜底：任何意外运行时异常都不能杀死生产者线程，否则缓存将永久枯竭。
                LOGGER.error("Unexpected error while pre-generating RSA key pair, retrying in {}ms",
                        RETRY_BACKOFF_MILLIS, runtimeException);
                sleepBackoff();
            }
        }
    }

    /**
     * 主线程快速路径：非阻塞取用。缓存为空时返回 {@code null}，
     * 调用方应切换到异步慢路径，而不是在主线程生成。
     */
    static KeyPair poll() {
        return CACHE.poll();
    }

    /**
     * 慢速路径：阻塞等待一个密钥对。仅供非主线程使用；
     * 生产者通常在一个生成周期内补上，等待时间以毫秒计。
     */
    static KeyPair take() throws InterruptedException {
        return CACHE.take();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(RSA_BITS);
            return keyPairGenerator.generateKeyPair();
        } catch (GeneralSecurityException generalSecurityException) {
            throw new IllegalStateException("Unable to generate RSA key pair", generalSecurityException);
        }
    }

    private static void sleepBackoff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
