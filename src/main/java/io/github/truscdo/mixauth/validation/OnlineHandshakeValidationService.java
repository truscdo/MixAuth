package io.github.truscdo.mixauth.validation;

import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.AuthLocalizedText;
import io.github.truscdo.mixauth.LogUtil;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import org.slf4j.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class OnlineHandshakeValidationService {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 慢路径执行器：固定少量线程 + 有界队列。缓存被掏空时在后台等待生产者补货，
     * 队列满则拒绝（客户端收到"服务器繁忙"断开），避免任务无限堆积。
     */
    private static final ExecutorService HELLO_EXECUTOR = createHelloExecutor();

    private static ExecutorService createHelloExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                2,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "mixauth-keygen");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private OnlineHandshakeValidationService() {
    }

    public static boolean shouldIntercept(MinecraftServer server) {
        return !server.usesAuthentication();
    }

    /**
     * 开始正版握手。RSA 密钥对来源为 {@link KeyPairCache}，生成已剥离出服务器主线程：
     * <ul>
     * <li>快路径：缓存命中，主线程仅做非阻塞取用 + 组装报文（微秒级，无冻结）；</li>
     * <li>慢路径：缓存被掏空（突发/洪水）时，改由后台线程等待生产者补货，
     * 主线程绝不执行 RSA 密钥生成。</li>
     * </ul>
     * 调用方须在 future 完成后再发送 Hello 报文（建议回到服务器主线程发送）。
     */
    public static CompletableFuture<ClientboundHelloPacket> beginValidationAsync(
            Connection connection,
            ServerboundHelloPacket packet,
            String serverId) {
        KeyPair keyPair = KeyPairCache.poll();
        if (keyPair != null) {
            return completeOrFailed(() -> buildHello(connection, packet, serverId, keyPair));
        }

        LOGGER.warn("Key pair cache drained; deferring handshake for {} to background key generation", packet.name());
        try {
            return CompletableFuture.supplyAsync(
                    () -> buildHello(connection, packet, serverId, awaitKeyPair()),
                    HELLO_EXECUTOR);
        } catch (RejectedExecutionException rejectedExecutionException) {
            LOGGER.warn("Background key generation saturated; rejecting handshake for {}", packet.name());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Background key generation saturated", rejectedExecutionException));
        }
    }

    private static KeyPair awaitKeyPair() {
        try {
            return KeyPairCache.take();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a pre-generated key pair", interruptedException);
        }
    }

    private static ClientboundHelloPacket buildHello(
            Connection connection,
            ServerboundHelloPacket packet,
            String serverId,
            KeyPair keyPair) {
        PendingHandshakeRegistry.cleanupExpiredEntries();

        byte[] challenge = new byte[4];
        SECURE_RANDOM.nextBytes(challenge);

        PendingHandshakeRegistry.put(
                connection,
                packet.name(),
                packet.profileId(),
                keyPair,
                challenge,
                serverId);

        return new ClientboundHelloPacket(serverId, keyPair.getPublic().getEncoded(), challenge, true);
    }

    private static CompletableFuture<ClientboundHelloPacket> completeOrFailed(
            Supplier<ClientboundHelloPacket> supplier) {
        try {
            return CompletableFuture.completedFuture(supplier.get());
        } catch (RuntimeException runtimeException) {
            return CompletableFuture.failedFuture(runtimeException);
        }
    }

    public static PendingKeyState pendingKeyState(Connection connection) {
        PendingHandshakeRegistry.PendingHandshake pendingHandshake = PendingHandshakeRegistry.get(connection);
        if (pendingHandshake == null) {
            PendingHandshakeRegistry.cleanupExpiredEntries();
            return PendingKeyState.NONE;
        }

        if (PendingHandshakeRegistry.isExpired(pendingHandshake, System.currentTimeMillis())) {
            return PendingKeyState.EXPIRED;
        }

        PendingHandshakeRegistry.cleanupExpiredEntries();
        return PendingKeyState.PRESENT;
    }

    public static ValidationResult handleKey(
            Connection connection,
            ServerboundKeyPacket packet) throws CryptException {
        PendingHandshakeRegistry.PendingHandshake pendingHandshake = PendingHandshakeRegistry.get(connection);
        if (pendingHandshake == null) {
            PendingHandshakeRegistry.cleanupExpiredEntries();
            return ValidationResult.missingPending();
        }

        if (PendingHandshakeRegistry.isExpired(pendingHandshake, System.currentTimeMillis())) {
            PendingHandshakeRegistry.remove(connection);
            PendingHandshakeRegistry.cleanupExpiredEntries();
            return ValidationResult.handshakeTimedOut(pendingHandshake.username());
        }

        SecretKey secretKey = packet.getSecretKey(pendingHandshake.keyPair().getPrivate());
        boolean challengeValid = packet.isChallengeValid(
                pendingHandshake.challenge(),
                pendingHandshake.keyPair().getPrivate());
        if (!challengeValid) {
            PendingHandshakeRegistry.remove(connection);
            return ValidationResult.invalidChallenge(pendingHandshake.username());
        }

        Cipher decryptCipher = Crypt.getCipher(Cipher.DECRYPT_MODE, secretKey);
        Cipher encryptCipher = Crypt.getCipher(Cipher.ENCRYPT_MODE, secretKey);
        connection.setEncryptionKey(decryptCipher, encryptCipher);

        String serverHash = new BigInteger(
                Crypt.digestData(
                        pendingHandshake.serverId(),
                        pendingHandshake.keyPair().getPublic(),
                        secretKey))
                .toString(16);

        return ValidationResult.readyForHasJoined(
                pendingHandshake.username(),
                pendingHandshake.profileId(),
                serverHash,
                pendingHandshake.createdAt());
    }

    /** 委托网络层发起 hasJoined 会话校验。 */
    public static CompletableFuture<MojangClient.HasJoinedResult> requestHasJoined(ValidationResult result) {
        return MojangClient.requestHasJoined(result.username(), result.serverHash());
    }

    public static GameProfile createOfflineProfile(String username) {
        return UUIDUtil.createOfflineProfile(username);
    }

    public static void clear(Connection connection) {
        PendingHandshakeRegistry.clear(connection);
    }

    public record ValidationResult(
            String username,
            UUID profileId,
            String serverHash,
            boolean ready,
            AuthLocalizedText failureReason,
            long createdAt) {
        public static ValidationResult readyForHasJoined(
                String username,
                UUID profileId,
                String serverHash,
                long createdAt) {
            return new ValidationResult(username, profileId, serverHash, true, null, createdAt);
        }

        public static ValidationResult invalidChallenge(String username) {
            return new ValidationResult(
                    username,
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.client_verification_failed"),
                    0L);
        }

        public static ValidationResult handshakeTimedOut(String username) {
            return new ValidationResult(
                    username,
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.handshake_timed_out"),
                    0L);
        }

        public static ValidationResult missingPending() {
            return new ValidationResult(
                    "",
                    null,
                    "",
                    false,
                    AuthLocalizedText.of("auth.validation.reason.client_verification_failed"),
                    0L);
        }
    }

    public enum PendingKeyState {
        NONE,
        PRESENT,
        EXPIRED
    }
}
