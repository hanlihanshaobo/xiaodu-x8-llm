package com.xiaodu.link;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/** 监听服务端广播，自动发现地址 */
public final class Discover {
    private static final String TAG = "XDL/Discover";
    private static volatile boolean running;

    public interface Callback {
        void onFound(String host, int port);
    }

    private Discover() {}

    public static void start(final Callback cb) {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket(8766);
                    byte[] buf = new byte[512];
                    while (running) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        socket.receive(p);
                        String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (!text.contains("xiaodu-brain")) continue;
                        int port = 8765;
                        try {
                            port = new JSONObject(text).optInt("port", 8765);
                        } catch (Exception ignored) {
                        }
                        String host = p.getAddress().getHostAddress();
                        running = false;
                        cb.onFound(host, port);
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "发现失败: " + e);
                } finally {
                    if (socket != null) socket.close();
                }
            }
        }, "discover").start();
    }
}
