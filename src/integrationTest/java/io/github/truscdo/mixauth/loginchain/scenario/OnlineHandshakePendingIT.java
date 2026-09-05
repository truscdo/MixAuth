// 在线加密握手与 hasJoined 返回 500 场景。
package io.github.truscdo.mixauth.loginchain.scenario;

import io.github.truscdo.mixauth.loginchain.LoginChainITBase;
import io.github.truscdo.mixauth.loginchain.MccDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("online")
public class OnlineHandshakePendingIT extends LoginChainITBase {

    private static UUID yggdrasilUuid(String username) {
        return UUID.nameUUIDFromBytes(
                ("YggdrasilTest:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private void clearKnownPlayer(String username) {
        UUID profileUuid = yggdrasilUuid(username);
        String out = server.rcon("/auth remove " + profileUuid);
        assertTrue(out != null && out.contains(profileUuid.toString()),
                () -> "clear known player failed for " + username + "; rcon out: " + out);
    }

    @Test
    @DisplayName("在线握手：正常在线握手 → 以 Online 模式入服")
    public void onlineHandshakeM1() throws Exception {
        String username = "OnlineTester";
        clearKnownPlayer(username);
        try {
            assertTrue(mock.setMode("online", "online"), "mock /_mock/mode switch failed");
            try (MccDriver.MccRun r = MccDriver.launchYggdrasil("M1", username, false)) {
                assertTrue(r.awaitJoin(90),
                        () -> failMsg("online handshake client", "Server was successfully joined", r));
                assertTrue(r.awaitServerLog("auth validation continuing online login", 30),
                        () -> failMsgServer("auth validation continuing online login", r));
            }
        } finally {
            clearKnownPlayer(username);
        }
    }

    @Test
    @DisplayName("在线握手：hasJoined 返回 500 → 断线且不回退离线")
    public void hasJoined500Disconnects() throws Exception {
        String username = "HasJoinedTester";
        clearKnownPlayer(username);
        try {
            assertTrue(mock.setMode("online", "500"), "mock /_mock/mode switch failed");
            try (MccDriver.MccRun r = MccDriver.launchYggdrasil("M4", username, true)) {
                assertTrue(r.awaitClientLog("Online authentication failed: Internal server authentication error.", 90),
                        () -> failMsg("hasJoined500 client",
                                "Online authentication failed: Internal server authentication error.", r));
                assertTrue(r.awaitServerLog("auth validation failed after online handshake", 30),
                        () -> failMsgServer("auth validation failed after online handshake", r));
            }
        } finally {
            clearKnownPlayer(username);
        }
    }
}
