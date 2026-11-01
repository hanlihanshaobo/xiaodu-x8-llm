package com.xiaodu.link;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;

/**
 * 最小可用的 WebSocket 客户端（RFC6455，只做客户端需要的那部分）。
 * 不引第三方库，省得为一个长连接拉进来一堆依赖。
 */
public class Ws {
    private static final String TAG = "XDL/Ws";

    public interface Listener {
        void onOpen();

        void onText(String text);

        void onClose(String reason);
    }

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean open;

    public boolean isOpen() {
        return open;
    }

    public void connect(String url, Listener listener) throws Exception {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.startsWith("ws")) {
            throw new IllegalArgumentException("地址要以 ws:// 开头: " + url);
        }
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 6000);
        socket.setTcpNoDelay(true);
        try {
            socket.setKeepAlive(true);
        } catch (Exception ignored) {
        }
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);

        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes("UTF-8"));
        out.flush();

        String status = readLine(in);
        if (status == null || !status.contains("101")) {
            throw new IOException("握手失败: " + status);
        }
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) break;
        }

        open = true;
        listener.onOpen();

        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(listener);
            }
        }, "ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(Listener listener) {
        String reason = "连接关闭";
        try {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (open) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                boolean masked = (b1 & 0x80) != 0;
                if (len == 126) {
                    len = ((long) in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(in, mask, 4);
                }
                if (len > 4 * 1024 * 1024) {
                    reason = "帧过大";
                    break;
                }
                byte[] payload = new byte[(int) len];
                readFully(in, payload, payload.length);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }

                if (opcode == 0x8) {
                    reason = "服务端关闭";
                    break;
                } else if (opcode == 0x9) {
                    writeFrame(0xA, payload, 0, payload.length);
                } else if (opcode == 0x1) {
                    text.write(payload, 0, payload.length);
                    if (fin) {
                        String s = text.toString("UTF-8");
                        text.reset();
                        listener.onText(s);
                    }
                }
            }
        } catch (Throwable e) {
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            open = false;
            closeQuietly();
            listener.onClose(reason);
        }
    }

    public void sendText(String s) {
        if (!open) return;
        try {
            byte[] b = s.getBytes("UTF-8");
            writeFrame(0x1, b, 0, b.length);
        } catch (Throwable e) {
            Log.w(TAG, "sendText 失败 " + e);
            closeQuietly();
        }
    }

    public void sendBinary(byte[] data, int off, int len) {
        if (!open) return;
        try {
            writeFrame(0x2, data, off, len);
        } catch (Throwable e) {
            closeQuietly();
        }
    }

    private synchronized void writeFrame(int opcode, byte[] data, int off, int len) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream(len + 14);
        h.write(0x80 | opcode);
        if (len < 126) {
            h.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            h.write(0x80 | 126);
            h.write((len >>> 8) & 0xFF);
            h.write(len & 0xFF);
        } else {
            h.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) h.write((int) (((long) len >>> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        h.write(mask, 0, 4);

        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (data[off + i] ^ mask[i & 3]);

        out.write(h.toByteArray());
        out.write(masked);
        out.flush();
    }

    public void close() {
        open = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) throw new IOException("连接中断");
            read += n;
        }
    }
}
