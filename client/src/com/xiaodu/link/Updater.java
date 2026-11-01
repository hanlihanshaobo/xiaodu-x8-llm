package com.xiaodu.link;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 在线更新：查 GitHub Release → 下载 APK → 调起安装 */
public final class Updater {
    private static final String TAG = "XDL/Updater";
    public static final String REPO = "hanlihanshaobo/xiaodu-x8-llm";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    public interface Callback {
        void onResult(boolean ok, String message, String apkUrl, long releaseId, boolean newer);
    }

    private Updater() {}

    public static void check(final android.content.Context c, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
                    conn.setRequestProperty("User-Agent", "XiaoduLink");
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        cb.onResult(false, "GitHub 返回 " + code, null, 0, false);
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    InputStream in = conn.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    in.close();

                    JSONObject o = new JSONObject(sb.toString());
                    long id = o.optLong("id");
                    String name = o.optString("name", o.optString("tag_name"));
                    String url = null;
                    JSONArray assets = o.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").endsWith(".apk")) {
                                url = a.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                    boolean newer = id > Prefs.updateId(c);
                    cb.onResult(true, name, url, id, newer);
                } catch (Exception e) {
                    cb.onResult(false, "检查失败：" + e.getMessage(), null, 0, false);
                }
            }
        }, "update-check").start();
    }

    /** 按设置里的加速前缀改写下载地址 */
    public static String proxied(android.content.Context c, String url) {
        String p = Prefs.proxyPrefix(c);
        if (p == null || p.isEmpty()) return url;
        if (p.contains("#")) return p.replace("#", url);
        if (!p.endsWith("/")) p = p + "/";
        return p + url;
    }

    private static File apkDir(android.content.Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        return new File(base, "apk");
    }

    public static void downloadAndInstall(final Activity act, final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = apkDir(act);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, "XiaoduLink.apk");

                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestProperty("User-Agent", "XiaoduLink");
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(120000);
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                    in.close();

                    final File apk = out;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            install(act, apk);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "下载失败: " + e);
                }
            }
        }, "update-download").start();
    }

    private static void install(Activity act, File apk) {
        try {
            Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "调起安装失败: " + e);
        }
    }

    /** 给系统安装器读 APK 用 */
    public static class ApkProvider extends ContentProvider {
        public static final String AUTHORITY = "com.xiaodu.link.apk";

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            File dir = apkDir(getContext());
            File f = new File(dir, uri.getLastPathSegment());
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public String getType(Uri uri) {
            return "application/vnd.android.package-archive";
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                            String[] selectionArgs, String sortOrder) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }
    }
}
