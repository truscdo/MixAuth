import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Minecraft RCON client for scripted server control (testutil).
 *
 * Usage:
 * java RconCli.java <host> <port> <password> <command...>
 *
 * Prints the concatenated server response (or a marker on auth failure).
 * Exit code 0 on success (auth OK + command sent), 1 on auth/IO failure.
 */
public final class RconCli {

    // RCON packet types
    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final int requestId;

    private RconCli(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
        // Random-looking request id; not security sensitive for local testing.
        this.requestId = (int) (System.nanoTime() & 0x7fffffff) | 0x1000;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java RconCli.java <host> <port> <password> <command...>");
            System.exit(1);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String password = args[2];
        String command = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(3000);
            RconCli client = new RconCli(socket);

            if (!client.authenticate(password)) {
                System.err.println("RCON auth failed");
                System.exit(1);
            }

            String response = client.sendCommand(command);
            System.out.print(response);
        } catch (IOException e) {
            System.err.println("RCON error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private boolean authenticate(String password) throws IOException {
        sendPacket(TYPE_AUTH, password);
        while (true) {
            Packet p = readPacket();
            if (p == null) {
                return false;
            }
            // AUTH_RESPONSE with matching id => success.
            if (p.type == TYPE_AUTH_RESPONSE) {
                return p.requestId == requestId;
            }
        }
    }

    private String sendCommand(String command) throws IOException {
        sendPacket(TYPE_COMMAND, command);
        List<String> parts = new ArrayList<>();
        long deadline = System.nanoTime() + 3_000_000_000L; // 3s total
        boolean gotResponse = false;
        while (System.nanoTime() < deadline) {
            Packet p;
            try {
                p = readPacket();
            } catch (java.net.SocketTimeoutException e) {
                break; // quiet period => done
            }
            if (p == null) {
                break;
            }
            if (p.type == TYPE_RESPONSE && p.requestId == requestId) {
                gotResponse = true;
                if (!p.body.isEmpty()) {
                    parts.add(p.body);
                }
                // Minecraft may send a second empty termination packet; keep reading
                // until a short quiet period elapses.
                continue;
            }
        }
        if (!gotResponse && parts.isEmpty()) {
            return "";
        }
        return String.join("\n", parts) + (parts.isEmpty() ? "" : "\n");
    }

    private void sendPacket(int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + payload.length + 2;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeIntLE(buf, length);
        writeIntLE(buf, requestId);
        writeIntLE(buf, type);
        buf.write(payload);
        buf.write(0);
        buf.write(0);
        // 一次性写入整个包：服务器端 RconClient 用单次 read() 读包，要求每次
        // TCP read 恰好包含一个完整 RCON 包（分片/粘包都会立即断开连接）。
        // DataOutputStream 逐字节 writeByte 会让首个 4 字节 length 独立成段，
        // 服务器 read() 只读到 n<=10 字节即 RST（WinError 10053）。
        out.write(buf.toByteArray());
        out.flush();
    }

    /**
     * RCON wire format is little-endian; DataOutputStream.writeInt is big-endian.
     */
    private void writeIntLE(ByteArrayOutputStream b, int v) throws IOException {
        b.write(v & 0xFF);
        b.write((v >>> 8) & 0xFF);
        b.write((v >>> 16) & 0xFF);
        b.write((v >>> 24) & 0xFF);
    }

    private int readIntLE() throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private Packet readPacket() throws IOException {
        try {
            int length = readIntLE();
            if (length < 4 || length > 65536) {
                return null;
            }
            int id = readIntLE();
            int type = readIntLE();
            int bodyLen = length - 8 - 2;
            byte[] body = new byte[Math.max(0, bodyLen)];
            in.readFully(body);
            in.readShort(); // two null bytes
            return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
        } catch (EOFException e) {
            return null;
        }
    }

    private record Packet(int requestId, int type, String body) {
    }
}
