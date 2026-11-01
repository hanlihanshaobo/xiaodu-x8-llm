package com.xiaodu.link;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置存储 */
public final class Prefs {
    private static final String FILE = "xiaodu";
    public static final String DEFAULT_SERVER = "ws://<PC_IP>:8765/ws";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String server(Context c) {
        return sp(c).getString("server", DEFAULT_SERVER);
    }

    public static void setServer(Context c, String v) {
        sp(c).edit().putString("server", v).apply();
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean("autostart", true);
    }

    public static void setAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }

    public static boolean hasShownTakeover(Context c) {
        return sp(c).getBoolean("takeover_shown", false);
    }

    public static void setShownTakeover(Context c) {
        sp(c).edit().putBoolean("takeover_shown", true).apply();
    }
}
