// ============================================================================
// 极简 Minecraft RCON 客户端：实现认证握手与命令发送，供测试控制服务器使用。
// ============================================================================
package io.github.truscdo.mixauth.loginchain;

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

public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final int requestId;

    public RconClient(String host, int port, String password) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(3000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        requestId = (int) (System.nanoTime() & 0x7fffffff) | 0x1000;
        if (!authenticate(password)) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw new IOException("RCON auth failed");
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public String sendCommand(String command) throws IOException {
        sendPacket(TYPE_COMMAND, command);
        List<String> parts = new ArrayList<>();
        long deadline = System.nanoTime() + 3_000_000_000L; // 3s total
        boolean gotResponse = false;
        while (System.nanoTime() < deadline) {
            Packet p;
            try {
                p = readPacket();
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (p == null)
                break;
            if (p.type == TYPE_RESPONSE && p.requestId == requestId) {
                gotResponse = true;
                if (!p.body.isEmpty())
                    parts.add(p.body);
            }
        }
        if (!gotResponse && parts.isEmpty())
            return "";
        return String.join("\n", parts) + (parts.isEmpty() ? "" : "\n");
    }

    private boolean authenticate(String password) throws IOException {
        sendPacket(TYPE_AUTH, password);
        while (true) {
            Packet p = readPacket();
            if (p == null)
                return false;
            if (p.type == TYPE_AUTH_RESPONSE)
                return p.requestId == requestId;
        }
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
        // single write of the whole packet: the server reads it in one read();
        out.write(buf.toByteArray());
        out.flush();
    }

    private void writeIntLE(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write((v >>> 8) & 0xFF);
        b.write((v >>> 16) & 0xFF);
        b.write((v >>> 24) & 0xFF);
    }

    private int readIntLE() throws IOException {
        return in.readUnsignedByte()
                | (in.readUnsignedByte() << 8)
                | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 24);
    }

    private Packet readPacket() throws IOException {
        try {
            int length = readIntLE();
            if (length < 4 || length > 65536)
                return null;
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

    record Packet(int requestId, int type, String body) {
    }
}