package com.xiaodu.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

/**
 * 常驻前台服务：占着麦克风，把音频推给电脑上的大脑，收到回复就播。
 *
 * 之所以要前台服务：Android 8 以后后台应用拿不到麦克风，
 * 没有前台通知的话息屏几分钟就被系统掐掉了。
 */
public class VoiceService extends Service {
    private static final String TAG = "XDL/Service";
    private static final String CHANNEL = "voice";
    private static final int NOTIF_ID = 1001;

    /** 界面上的按钮要通过它给服务端发指令 */
    public static volatile VoiceService INSTANCE;

    public static final String ACTION_START = "com.xiaodu.link.START";
    public static final String ACTION_STOP = "com.xiaodu.link.STOP";

    private volatile boolean running;
    private volatile boolean muted;
    private volatile boolean connected;
    /** 音乐播放中：改用带回声消除的采集源，尽量听见唤醒词 */
    private volatile boolean musicMode;

    private volatile Ws ws;
    private Player player;
    private AudioRecord recorder;
    private boolean recorderAec;   // 当前采集源是否启用了回声消除
    private int sampleRate = 16000;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    /** 网络发送专用线程：播放回调在主线程，直接发会触发 NetworkOnMainThreadException */
    private final java.util.concurrent.ExecutorService netExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Thread captureThread;
    private Thread connThread;
    private long backoff = 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        installCrashHandler();
        cleanCache();
        player = new Player(new Player.Callback() {
            @Override
            public void onDone(String id) {
                netExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("type", "speak_done");
                            o.put("id", id);
                            Ws w = ws;
                            if (w != null) w.sendText(o.toString());
                        } catch (Exception ignored) {
                        }
                    }
                });
                // 只有语音播报时才需要闭麦；音乐继续放时也要能听见唤醒词
                if (!player.isSpeechBusy()) muted = false;
            }

            @Override
            public void onIdle() {
                muted = false;
                Bus.post("music_end", "");
            }

            @Override
            public void onError(final String message) {
                Bus.post("play_error", message);
                netExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("type", "log");
                            o.put("text", "播放失败 " + message);
                            Ws w = ws;
                            if (w != null) w.sendText(o.toString());
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (!running) start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (ws != null) ws.close();
        releaseRecorder();
        if (player != null) player.stopAll();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        netExec.shutdownNow();
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 启动 ----------------

    private void start() {
        running = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaodu:voice");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        // WiFi 锁：息屏后路由器不会把连接踢掉，长连接才稳
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xiaodu:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "WiFi 锁申请失败: " + t);
        }

        Bus.post("conn", "connecting");
        connThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "conn");
        connThread.start();

        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "capture");
        captureThread.start();
    }

    private void connectLoop() {
        while (running) {
            try {
                ws = new Ws();
                String url = Prefs.wsUrl(VoiceService.this);
                if (url.isEmpty()) {
                    Bus.post("conn", "disconnected|还没配置服务端地址");
                    Thread.sleep(3000);
                    continue;
                }
                ws.connect(url, new Ws.Listener() {
                    @Override
                    public void onOpen() {
                        connected = true;
                        backoff = 1000;
                        Bus.post("conn", "connected");
                        try {
                            JSONObject o = new JSONObject();
                            o.put("type", "hello");
                            o.put("sampleRate", 16000);
                            o.put("quality", Prefs.quality(VoiceService.this));
                            ws.sendText(o.toString());
                        } catch (Exception ignored) {
                        }
                        reportCrash();
                    }

                    @Override
                    public void onText(String text) {
                        handle(text);
                    }

                    @Override
                    public void onClose(String reason) {
                        connected = false;
                        Bus.post("conn", "disconnected|" + reason);
                    }
                });
                long lastPing = 0;
                while (running && ws.isOpen()) {
                    Thread.sleep(500);
                    // 定期打个心跳，防止路由器/NAT 把长连接悄悄回收
                    long now = System.currentTimeMillis();
                    if (now - lastPing > 20000) {
                        lastPing = now;
                        try {
                            JSONObject p = new JSONObject();
                            p.put("type", "ping");
                            ws.sendText(p.toString());
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                Bus.post("conn", "disconnected|" + e.getMessage());
                Log.w(TAG, "连接失败: " + e);
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 15000);
        }
    }

    public int playerPosition() {
        return player == null ? 0 : player.position();
    }

    public int playerDuration() {
        return player == null ? 0 : player.duration();
    }

    public void seekPlayer(int ms) {
        if (player != null) player.seekTo(ms);
    }

    /** 把整首歌拉到本地，失败返回 false */
    private boolean download(String url, java.io.File out) {
        return download(url, out, 100_000, 8000, 30000);
    }

    /** 语音文件很小，阈值和超时都单独放宽/收紧 */
    private boolean downloadTts(String url, java.io.File out) {
        return download(url, out, 1000, 4000, 15000);
    }

    private boolean download(String url, java.io.File out, long minBytes,
                             int connectMs, int readMs) {
        java.io.InputStream in = null;
        java.io.FileOutputStream fos = null;
        try {
            java.io.File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", "XiaoduLink/1.0");
            c.connect();
            if (c.getResponseCode() >= 400) {
                Log.w(TAG, "下载 HTTP " + c.getResponseCode());
                return false;
            }
            in = c.getInputStream();
            fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            return out.length() > minBytes;
        } catch (Exception e) {
            Log.w(TAG, "下载失败: " + e);
            out.delete();
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 用 URL 的哈希做缓存名：预加载和正式播放能对上同一个文件 */
    private String cacheName(String url) {
        String u = url == null ? "" : url;
        String lower = u.toLowerCase();
        String ext = lower.contains(".flac") ? ".flac"
                : (lower.contains(".m4a") || lower.contains(".m4s")) ? ".m4a" : ".mp3";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(u.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("m_");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString() + ext;
        } catch (Exception e) {
            return "m_" + Math.abs(u.hashCode()) + ext;
        }
    }

    /** 清掉两天前的歌曲缓存，并按上限删到 90% 以内 */
    private void cleanCache() {
        final long cutoff = System.currentTimeMillis() - 2L * 24 * 3600 * 1000;
        final long limit = Math.max(64, Prefs.cacheLimitMb(this)) * 1024L * 1024L;
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File ttsDir = new java.io.File(getCacheDir(), "tts");
                java.io.File[] tts = ttsDir.listFiles();
                if (tts != null) {
                    for (java.io.File f : tts) {
                        if (f.lastModified() < cutoff) f.delete();
                    }
                }
                java.io.File[] fs = getCacheDir().listFiles();
                if (fs == null) return;
                java.util.ArrayList<java.io.File> songs = new java.util.ArrayList<>();
                long total = 0;
                for (java.io.File f : fs) {
                    if (!f.getName().startsWith("m_")) continue;
                    if (f.lastModified() < cutoff) {
                        f.delete();
                        continue;
                    }
                    songs.add(f);
                    total += f.length();
                }
                if (total <= limit) return;
                // 最久没用的先删，删到上限的 90%
                songs.sort(new java.util.Comparator<java.io.File>() {
                    @Override
                    public int compare(java.io.File a, java.io.File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (java.io.File f : songs) {
                    if (total <= limit * 9 / 10) break;
                    long len = f.length();
                    if (f.delete()) total -= len;
                }
                Log.i(TAG, "缓存清理完成，剩余 " + (total / 1024 / 1024) + "MB / 上限 " + (limit / 1024 / 1024) + "MB");
            }
        }, "cache-clean").start();
    }

    private void postLog(final String text) {
        netExec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "log");
                    o.put("text", text);
                    Ws w = ws;
                    if (w != null) w.sendText(o.toString());
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 语音根本没播出来时，也要给服务端回执，清掉它的 pending */
    private void sendSpeakDone(final String id) {
        netExec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "speak_done");
                    o.put("id", id);
                    Ws w = ws;
                    if (w != null) w.sendText(o.toString());
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 界面改完缓存上限后立即清一次 */
    public void trimCache() {
        cleanCache();
    }

    // ---------------- 供界面调用 ----------------

    /** 界面按钮下发指令（暂停/下一首/停止…），走和语音同一条处理链路 */
    public void sendCommand(final String text) {
        netExec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "command");
                    o.put("text", text);
                    Ws w = ws;
                    if (w != null) w.sendText(o.toString());
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 停止当前播放（音乐或播报），并告诉服务端别再往下发 */
    public void stopPlayback() {
        try {
            player.stopAll();
            muted = false;
            musicMode = false;
            netExec.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("type", "stop");
                        Ws w = ws;
                        if (w != null) w.sendText(o.toString());
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    public boolean isMusicPlaying() {
        return player != null && player.isMusicPlaying();
    }

    public boolean hasMusic() {
        return player != null && player.hasMusic();
    }

    /** 圆环/播放按钮用：本地暂停，不经过服务端，点下去立即生效 */
    public void pausePlayback() {
        if (player != null) player.pauseMusic();
        musicMode = false;
        muted = false;
        Bus.post("paused", "");
    }

    /** 圆环/播放按钮用：本地恢复 */
    public void resumePlayback() {
        if (player != null) player.resumeMusic();
        musicMode = true;
        Bus.post("playing", "");
    }

    // ---------------- 崩溃自诊断 ----------------

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                            new java.io.File(getFilesDir(), "crash.txt"), false);
                    fw.write(t.getName() + " " + Log.getStackTraceString(e));
                    fw.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** 上次崩溃的话，一连上就把栈发给服务端，方便定位 */
    private void reportCrash() {
        java.io.File f = new java.io.File(getFilesDir(), "crash.txt");
        if (!f.exists()) return;
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            JSONObject o = new JSONObject();
            o.put("type", "log");
            o.put("text", "上次崩溃: " + new String(b, 0, Math.max(n, 0), "UTF-8"));
            ws.sendText(o.toString());
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 下行事件 ----------------
    private void handle(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            if ("state".equals(type)) {
                Bus.post("state", o.optString("value"));
            } else if ("asr".equals(type)) {
                Bus.post("asr", o.optString("text"));
            } else if ("reply".equals(type)) {
                Bus.post("reply", o.optString("delta"));
            } else if ("speak".equals(type)) {
                muted = true;
                final String sid = o.optString("id");
                final String surl = o.optString("url");
                final java.io.File ttsDir = new java.io.File(getCacheDir(), "tts");
                if (!ttsDir.exists()) ttsDir.mkdirs();
                final java.io.File sf = new java.io.File(ttsDir, cacheName(surl));
                if (sf.exists() && sf.length() > 1000) {
                    player.enqueueSpeech(sid, sf.getAbsolutePath());
                } else {
                    netExec.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (downloadTts(surl, sf)) {
                                player.enqueueSpeech(sid, sf.getAbsolutePath());
                            } else {
                                // 语音下载失败时不能一直闭麦，否则用户后面说什么都听不到
                                muted = false;
                                Bus.post("error", "语音下载失败");
                                postLog("语音下载失败 " + surl);
                                sendSpeakDone(sid);
                            }
                        }
                    });
                }
                Bus.post("speak", o.optString("text"));
            } else if ("music".equals(type)) {
                musicMode = true;
                Bus.post("music", text);   // 先切界面
                final String mid = o.optString("id");
                final String murl = o.optString("url");
                final java.io.File f = new java.io.File(getCacheDir(), cacheName(murl));
                if (f.exists() && f.length() > 100_000) {
                    player.enqueueMusic(mid, f.getAbsolutePath());   // 预加载命中，秒开
                } else {
                    netExec.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (download(murl, f)) {
                                player.enqueueMusic(mid, f.getAbsolutePath());
                                cleanCache();          // 下完就按阈值清一次
                            } else {
                                Bus.post("play_error", "音频下载失败");
                                postLog("音乐下载失败 " + murl);
                            }
                        }
                    });
                }
            } else if ("preload".equals(type)) {
                // 下一首提前下载，切歌时直接用本地文件
                final String purl = o.optString("url");
                if (!purl.isEmpty()) {
                    final java.io.File pf = new java.io.File(getCacheDir(), cacheName(purl));
                    if (!(pf.exists() && pf.length() > 100_000)) {
                        netExec.execute(new Runnable() {
                            @Override
                            public void run() {
                                download(purl, pf);
                                cleanCache();
                            }
                        });
                    }
                }
            } else if ("volume".equals(type)) {
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am != null) {
                        int dir = "up".equals(o.optString("dir"))
                                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir,
                                AudioManager.FLAG_SHOW_UI);
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int pct = max > 0 ? Math.round(cur * 100f / max) : 0;
                        Bus.post("volume", String.valueOf(pct));
                    }
                } catch (Exception ignored) {
                }
            } else if ("audio_pause".equals(type)) {
                player.pauseMusic();
                musicMode = false;
                muted = false;
                Bus.post("paused", "");
            } else if ("audio_resume".equals(type)) {
                player.resumeMusic();
                musicMode = true;
                Bus.post("playing", "");
            } else if ("stop_speech".equals(type)) {
                muted = false;
                player.stopSpeech();
            } else if ("stop".equals(type)) {
                boolean silent = o.optBoolean("silent");
                muted = false;
                musicMode = false;
                player.stopAll(!silent);
                Bus.post("state", "listening");
            } else if ("release".equals(type)) {
                muted = false;
                musicMode = false;
                player.stopAll();
                Bus.post("state", "idle");
            } else if ("error".equals(type)) {
                Bus.post("error", o.optString("message"));
            }
        } catch (Exception e) {
            Log.w(TAG, "解析失败: " + e);
        }
    }

    // ---------------- 上行音频 ----------------

    private void captureLoop() {
        while (running) {
            // 放歌时切到带 AEC 的采集源，暂停/停止后切回来
            if (recorder != null && musicMode != recorderAec) {
                releaseRecorder();
            }
            if (!ensureRecorder()) {
                Bus.post("error", "打不开麦克风，检查权限");
                sleep(2000);
                continue;
            }
            int frameBytes = sampleRate / 50 * 2; // 20ms
            byte[] buf = new byte[frameBytes];
            while (running) {
                if (musicMode != recorderAec) break;   // 需要换采集源
                int n;
                try {
                    n = recorder.read(buf, 0, buf.length);
                } catch (Exception e) {
                    Log.w(TAG, "read 失败: " + e);
                    break;
                }
                if (n <= 0) {
                    sleep(30);
                    continue;
                }
                if (muted || !connected || ws == null || !ws.isOpen()) {
                    continue; // 只有播报时闭麦；放歌时继续收音，才能听见唤醒词
                }
                byte[] out = convert(buf, n);
                if (out != null && out.length > 0) ws.sendBinary(out, 0, out.length);
            }
            boolean switching = musicMode != recorderAec;
            releaseRecorder();
            if (running && !switching) sleep(500);
        }
    }

    private boolean ensureRecorder() {
        if (recorder != null) return true;
        // 放歌时优先 VOICE_COMMUNICATION：系统会做回声消除，减少把歌喂给 ASR
        // 平时优先 VOICE_RECOGNITION：不做通话那套 AGC/降噪，识别更自然
        boolean wantAec = musicMode;
        int[] sources = wantAec
                ? new int[]{
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC}
                : new int[]{
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        int[] rates = {16000, 44100};
        for (int src : sources) {
            for (int r : rates) {
                int min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                int size = Math.max(min * 2, r / 5 * 2);
                AudioRecord ar;
                try {
                    ar = new AudioRecord(src, r, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, size);
                } catch (Exception e) {
                    continue;
                }
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                    ar.release();
                    continue;
                }
                sampleRate = r;
                recorder = ar;
                recorderAec = wantAec;
                if (wantAec) {
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            aec = AcousticEchoCanceler.create(ar.getAudioSessionId());
                            if (aec != null) aec.setEnabled(true);
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            ns = NoiseSuppressor.create(ar.getAudioSessionId());
                            if (ns != null) ns.setEnabled(true);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    ar.startRecording();
                } catch (Exception e) {
                    releaseRecorder();
                    continue;
                }
                Log.i(TAG, "麦克风就绪 src=" + src + " " + r + "Hz aec=" + wantAec);
                postLog("麦克风 " + (wantAec ? "AEC" : "普通") + " src=" + src + " " + r + "Hz");
                return true;
            }
        }
        return false;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (aec != null) {
            try {
                aec.release();
            } catch (Exception ignored) {
            }
            aec = null;
        }
        if (ns != null) {
            try {
                ns.release();
            } catch (Exception ignored) {
            }
            ns = null;
        }
    }

    /** 设备只支持 44.1k 时重采样到 16k，服务端统一按 16k 处理 */
    private byte[] convert(byte[] buf, int n) {
        if (sampleRate == 16000) {
            byte[] o = new byte[n];
            System.arraycopy(buf, 0, o, 0, n);
            return o;
        }
        int samples = n / 2;
        short[] in = new short[samples];
        for (int i = 0; i < samples; i++) {
            in[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
        }
        int outN = (int) ((long) samples * 16000 / sampleRate);
        byte[] out = new byte[outN * 2];
        for (int i = 0; i < outN; i++) {
            double pos = (double) i * sampleRate / 16000.0;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, samples - 1);
            double f = pos - i0;
            int v = (int) (in[i0] * (1 - f) + in[i1] * f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[2 * i] = (byte) (v & 0xFF);
            out[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------------- 通知 ----------------

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "语音助手", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("小度同学正在听")
                .setContentText("说「小度同学」叫醒我")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
