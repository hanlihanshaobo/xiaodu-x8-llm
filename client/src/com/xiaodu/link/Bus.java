package com.xiaodu.link;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** 极简事件总线：服务往界面推状态，避免绑定服务的样板代码 */
public final class Bus {
    public interface Listener {
        void onEvent(String type, String payload);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bus() {}

    public static void add(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    public static void post(final String type, final String payload) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) {
                    l.onEvent(type, payload);
                }
            }
        });
    }
}
