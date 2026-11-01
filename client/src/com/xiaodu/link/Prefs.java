package com.xiaodu.link;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置存储 */
public final class Prefs {
    private static final String FILE = "xiaodu";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 形如 192.168.1.10:8765；空表示还没配置 */
    public static String serverHost(Context c) {
        return sp(c).getString("host", "");
    }

    public static void setServerHost(Context c, String v) {
        sp(c).edit().putString("host", v).apply();
    }

    /** 拼成 WebSocket 地址；没配置时返回空串 */
    public static String wsUrl(Context c) {
        String h = serverHost(c);
        if (h == null || h.isEmpty()) return "";
        return "ws://" + h + "/ws";
    }

    public static boolean autoConnect(Context c) {
        return sp(c).getBoolean("autoconnect", true);
    }

    public static void setAutoConnect(Context c, boolean v) {
        sp(c).edit().putBoolean("autoconnect", v).apply();
    }

    /** auto | dark | light，默认浅色 */
    public static String theme(Context c) {
        return sp(c).getString("theme", "light");
    }

    public static void setTheme(Context c, String v) {
        sp(c).edit().putString("theme", v).apply();
    }

    public static boolean hasShownTakeover(Context c) {
        return sp(c).getBoolean("takeover_shown", false);
    }

    public static void setShownTakeover(Context c) {
        sp(c).edit().putBoolean("takeover_shown", true).apply();
    }

    /** 把用户输入整理成 host:port */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return "";
        s = s.replace("ws://", "").replace("wss://", "").replace("http://", "").replace("https://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.isEmpty()) return "";
        if (!s.contains(":")) s = s + ":8765";
        return s;
    }
}
