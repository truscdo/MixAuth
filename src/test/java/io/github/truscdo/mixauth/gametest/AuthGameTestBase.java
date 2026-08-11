package io.github.truscdo.mixauth.gametest;

import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.AuthServerConfig;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.offline.OfflineAuthSessionService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import io.github.truscdo.mixauth.db.DatabaseSupport;
import io.github.truscdo.mixauth.localization.AuthTranslations;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GameTest 集成测试基类。
 * <p>
 * 封装「自定义身份（用户名 + 离线 UUID）mock 玩家创建 + 假 IP 注入 + 登录模式预置 +
 * 认证状态/消息断言 + 命令执行」等辅助能力。
 * <p>
 * 用法：测试方法声明为 {@code static}，helper 参数类型为本类，框架通过
 * {@code (GameTestInfo)} 构造器实例化。
 * <p>
 * mock 玩家创建复刻 {@code ExtendedGameTestHelper.makeTickingMockServerPlayerInLevel}
 * 的完整流程（placeNewPlayer → 触发真实 {@code PlayerLoggedInEvent}），仅将
 * GameProfile 替换为自定义「用户名 + 离线 UUID」，并注入可覆写的假远端 IP。
 */
public class AuthGameTestBase extends ExtendedGameTestHelper {
    /** 假 IP 的固定端口，仅用于构造 InetSocketAddress。 */
    private static final int FAKE_PORT = 5000;

    public AuthGameTestBase(GameTestInfo info) {
        super(info);
    }

    // ---------------------------------------------------------------- mock 玩家

    /**
     * 创建并让 mock 玩家进服（触发真实 {@code PlayerLoggedInEvent} 进服路由）。
     *
     * @param username 玩家用户名
     * @param uuid     玩家 UUID（离线服建议用 {@link #offlineUuid(String)}）
     * @param fakeIp   注入的假远端 IP（null 表示不注入）
     * @return 已在服务器中的 GameTestPlayer
     */
    protected GameTestPlayer createMockPlayer(String username, UUID uuid, String fakeIp) {
        GameProfile profile = new GameProfile(uuid, username);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerLevel level = getLevel();
        GameTestPlayer player = new GameTestPlayer(
                level.getServer(), level, cookie.gameProfile(), cookie.clientInformation(), this);

        MockPlayerConnection connection = new MockPlayerConnection(PacketFlow.SERVERBOUND, player, fakeIp);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);

        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        level.getServer().getConnection().getConnections().add(connection);
        testInfo.addListener(player);

        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setYRot(180.0f);
        player.connection.chunkSender.sendNextChunks(player);
        player.connection.chunkSender.onChunkBatchReceivedByClient(64.0f);
        return player;
    }

    /**
     * 预置登录模式后让玩家进服（进服路由测试入口）。
     *
     * @param mode 预置的登录模式（null 表示不预置 → 无路由）
     */
    protected GameTestPlayer joinServer(String username, UUID uuid, OnlineAuthService.LoginMode mode, String fakeIp) {
        if (mode != null) {
            KnownPlayerService.markLoginMode(uuid, mode);
        }
        return createMockPlayer(username, uuid, fakeIp);
    }

    /** 基于用户名计算离线模式 UUID（与服务器 UUIDUtil.createOfflineProfile 一致）。 */
    protected static UUID offlineUuid(String username) {
        return UUIDUtil.createOfflineProfile(username).getId();
    }

    // ---------------------------------------------------------------- 状态断言

    protected OfflineAuthSessionService.PendingOfflineAuth pendingOf(GameTestPlayer player) {
        return OfflineAuthSessionService.getPendingAuth(player);
    }

    protected void assertNoPending(GameTestPlayer player) {
        assertTrue(pendingOf(player) == null, "expected no pending auth, but found " + pendingOf(player));
    }

    protected void assertPendingStage(GameTestPlayer player, OfflineAuthSessionService.OfflineAuthStage stage) {
        OfflineAuthSessionService.PendingOfflineAuth pending = pendingOf(player);
        assertTrue(pending != null, "expected pending auth but was null");
        if (pending != null) {
            assertTrue(pending.stage() == stage, "expected stage " + stage + " but was " + pending.stage());
        }
    }

    protected void assertGameMode(GameTestPlayer player, GameType gameType) {
        assertTrue(player.gameMode.getGameModeForPlayer() == gameType,
                "expected game mode " + gameType + " but was " + player.gameMode.getGameModeForPlayer());
    }

    protected void assertBlindness(GameTestPlayer player) {
        assertTrue(player.hasEffect(MobEffects.BLINDNESS), "expected blindness effect");
    }

    // ---------------------------------------------------------------- 消息断言

    /**
     * 玩家收到的全部系统消息（ClientboundSystemChatPacket.content）。
     * <p>
     * 由 {@link MockPlayerConnection} 在 {@code send()} 时同步记录，避免服务器 tick
     * flush EmbeddedChannel 出站缓冲导致消息被消费（读取时机竞态）。
     */
    protected List<Component> systemMessages(GameTestPlayer player) {
        return ((MockPlayerConnection) player.connection.getConnection()).capturedSystemMessages();
    }

    /**
     * 玩家收到的最后一条系统消息的文本。
     * <p>
     * 生产代码 componentForPlayer 用 Component.literal 在发送前把翻译 key 解析为纯文本
     * （LiteralContents），故此处断言文本而非翻译 key。
     */
    protected String lastMessageText(GameTestPlayer player) {
        List<Component> messages = systemMessages(player);
        if (messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1).getString();
    }

    /**
     * 断言玩家收到的最后一条系统消息 == key 对应翻译文本。
     * <p>
     * 期望值用生产 API（AuthTranslations.textForLanguage + resolveLanguage）生成，
     * 不硬编码文案、不依赖具体语言。
     */
    protected void assertLastMessage(GameTestPlayer player, String key, Object... args) {
        String expected = AuthTranslations.textForLanguage(AuthTranslations.resolveLanguage(player), key, args);
        String actual = lastMessageText(player);
        assertTrue(expected.equals(actual),
                "expected last message text '" + expected + "' but was '" + actual + "'");
    }

    /**
     * 断言玩家收到的系统消息中存在一条 == key 对应翻译文本。
     * <p>
     * 拦截类消息（denyPendingAction）发送拦截文案后会紧接重发登录提示，故此类断言用
     * 「存在」而非「最后一条」。
     */
    protected void assertAnyMessage(GameTestPlayer player, String key, Object... args) {
        String expected = AuthTranslations.textForLanguage(AuthTranslations.resolveLanguage(player), key, args);
        boolean found = systemMessages(player).stream().anyMatch(message -> expected.equals(message.getString()));
        assertTrue(found, "expected any message with text '" + expected + "' but captured " + systemMessages(player));
    }

    // ---------------------------------------------------------------- 命令 / DB 辅助

    /** 以玩家身份执行命令（触发真实 CommandEvent 拦截链路）。 */
    protected void runCommand(GameTestPlayer player, String command) {
        getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    /**
     * 以服务器控制台身份（权限 4）执行管理员子命令（setpassword/setmode/remove 等）。
     * <p>
     * GameTest 玩家不是服务器 OP，无法通过 {@code hasPermission(3)} 校验，故管理员子命令
     * 用控制台 source 驱动（成功/失败消息发往服务器日志，测试侧只断言落库结果）。
     */
    protected void runCommandAsConsole(String command) {
        getLevel().getServer().getCommands().performPrefixedCommand(
                getLevel().getServer().createCommandSourceStack(), command);
    }

    /** 直接落库：注册离线密码。 */
    protected void registerPassword(UUID uuid, String password) {
        OfflineAuthService.registerOfflineUser(uuid, password);
    }

    /** 直接落库：记录一条免密登录信任记录。 */
    protected void recordTrustedIp(UUID uuid, String ip) {
        OfflineAuthService.recordTrustedOfflineLogin(uuid, ip);
    }

    /**
     * 直接落库：把指定信任记录的 {@code authenticated_at} 改到免密窗口之外
     * （模拟很久以前的登录留下的过期记录）。
     */
    protected void expireTrustedIp(UUID uuid, String ip) {
        long beforeWindow = Instant.now().toEpochMilli() - AuthServerConfig.trustedLoginWindowMillis() - 60_000L;
        DatabaseSupport.executeUpdate(
                "UPDATE offline_trusted_logins SET authenticated_at = ? WHERE player_uuid = ? AND ip_address = ?",
                stmt -> {
                    stmt.setLong(1, beforeWindow);
                    stmt.setString(2, uuid.toString());
                    stmt.setString(3, ip);
                },
                "test setup: expire trusted ip");
    }

    /** 断言某条信任记录是否仍存在于表中（不论窗口内外）。 */
    protected boolean hasTrustedIp(UUID uuid, String ip) {
        return DatabaseSupport.executeQuery(
                "SELECT 1 FROM offline_trusted_logins WHERE player_uuid = ? AND ip_address = ? LIMIT 1",
                stmt -> {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, ip);
                },
                ResultSet::next,
                "test setup: check trusted ip exists");
    }

    /**
     * 清空玩家的全部数据（已知名单/离线密码/封禁/信任记录）。
     * <p>
     * 测试数据库跨运行持久化，上一轮成功登录/注册留下的信任记录会让本轮进服走免密直进分支，
     * 故每个依赖「未注册/未信任」前提的用例须先重置，保证用例幂等、互不干扰。
     */
    protected void resetPlayerData(UUID uuid) {
        KnownPlayerService.removeAllPlayerData(uuid);
    }

    // ---------------------------------------------------------------- 内部连接

    /**
     * 模拟玩家连接：内存连接 + 可覆写的假远端 IP。
     * 复刻官方 testframework 的 Connection 匿名子类（tick 复位 lastActionTime、内存连接），
     * 额外覆写 {@code getRemoteAddress()} 以注入假 IP（CommandSupport.resolveRemoteIp 依赖它）。
     */
    static final class MockPlayerConnection extends Connection {
        private final GameTestPlayer player;
        private final List<Component> capturedSystemMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private SocketAddress remoteAddress;

        MockPlayerConnection(PacketFlow flow, GameTestPlayer player, String fakeIp) {
            super(flow);
            this.player = player;
            setFakeIp(fakeIp);
        }

        void setFakeIp(String ip) {
            this.remoteAddress = ip == null ? null : new InetSocketAddress(ip, FAKE_PORT);
        }

        /** 系统消息捕获列表（send 时同步记录，tick 冲刷出站缓冲不影响）。 */
        List<Component> capturedSystemMessages() {
            return List.copyOf(capturedSystemMessages);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundSystemChatPacket chat) {
                capturedSystemMessages.add(chat.content());
            }
            super.send(packet, listener, flush);
        }

        @Override
        public void tick() {
            super.tick();
            player.resetLastActionTime();
        }

        @Override
        public boolean isMemoryConnection() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return remoteAddress != null ? remoteAddress : super.getRemoteAddress();
        }
    }
}
