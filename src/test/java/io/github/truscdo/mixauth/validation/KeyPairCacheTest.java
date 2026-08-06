package io.github.truscdo.mixauth.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyPairCache} 的单元测试。
 *
 * <p>
 * 生产者线程为后台 daemon，从类加载开始持续补货（约每 8ms 一个 RSA-1024 密钥对），
 * 因此断言都留有 5 秒的宽松窗口，避免与生产者节奏偶合。
 * </p>
 */
@DisplayName("KeyPairCache — 密钥对预生成缓存")
class KeyPairCacheTest {

    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    @Test
    @DisplayName("生产者持续补货：poll 最终返回非空 RSA-1024 密钥对")
    void pollEventuallyReturnsKeyPair() throws Exception {
        KeyPair keyPair = awaitNonEmptyPoll();
        assertNotNull(keyPair, "缓存应在 5 秒内被生产者补满");
        assertEquals("RSA", keyPair.getPublic().getAlgorithm(), "应为 RSA 密钥对");
        assertTrue(keyPair.getPublic() instanceof RSAPublicKey, "公钥应为 RSAPublicKey");
        assertEquals(1024, ((RSAPublicKey) keyPair.getPublic()).getModulus().bitLength(), "应为 1024 位");
    }

    @Test
    @DisplayName("take 阻塞等待并返回一个可用密钥对")
    void takeReturnsKeyPair() throws Exception {
        KeyPair keyPair = KeyPairCache.take();
        assertNotNull(keyPair);
        assertEquals("RSA", keyPair.getPrivate().getAlgorithm(), "私钥算法应为 RSA");
    }

    @Test
    @DisplayName("连续取出的密钥对互不相同")
    void consecutivePollsReturnDistinctKeyPairs() throws Exception {
        Set<byte[]> encoded = new HashSet<>();
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (encoded.size() < 5 && System.nanoTime() < deadline) {
            KeyPair keyPair = KeyPairCache.poll();
            if (keyPair != null) {
                encoded.add(keyPair.getPublic().getEncoded());
            } else {
                Thread.sleep(10);
            }
        }
        assertEquals(5, encoded.size(), "应能连续取出 5 个互不相同的密钥对");
    }

    private static KeyPair awaitNonEmptyPoll() throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_NANOS;
        KeyPair keyPair;
        while ((keyPair = KeyPairCache.poll()) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return keyPair;
    }
}
