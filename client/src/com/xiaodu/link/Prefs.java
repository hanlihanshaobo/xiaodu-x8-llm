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

    /** auto | original | 128 | 192 | 320 */
    public static String quality(Context c) {
        return sp(c).getString("quality", "auto");
    }

    public static void setQuality(Context c, String v) {
        sp(c).edit().putString("quality", v).apply();
    }

    /** App 内音量增益 0~100，默认 100（不衰减） */
    public static int volume(Context c) {
        return sp(c).getInt("volume", 100);
    }

    public static boolean hasVolume(Context c) {
        return sp(c).contains("volume");
    }

    public static void setVolume(Context c, int v) {
        sp(c).edit().putInt("volume", Math.max(0, Math.min(100, v))).apply();
    }

    /** 设备上歌曲缓存上限，单位 MB，默认 1G */
    public static int cacheLimitMb(Context c) {
        return sp(c).getInt("cache_mb", 1024);
    }

    public static void setCacheLimitMb(Context c, int v) {
        sp(c).edit().putInt("cache_mb", v).apply();
    }

    /** 已见到的 Release id，用于判断有没有新版本 */
    public static long updateId(Context c) {
        return sp(c).getLong("update_id", 0L);
    }

    public static void setUpdateId(Context c, long v) {
        sp(c).edit().putLong("update_id", v).apply();
    }

    /** GitHub 下载加速前缀，空表示直连 */
    public static String proxyPrefix(Context c) {
        return sp(c).getString("proxy", "");
    }

    public static void setProxyPrefix(Context c, String v) {
        sp(c).edit().putString("proxy", v == null ? "" : v).apply();
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
