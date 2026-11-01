package com.xiaodu.link;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 主界面：一个状态环 + 字幕 + 三个按钮，尽量少跳转 */
public class MainActivity extends Activity implements Bus.Listener {
    private RingView ring;
    private TextView status;
    private TextView subtitle;
    private Button stopBtn;

    // 顶部两个模式切换
    private LinearLayout assistantView;
    private Button tabAssistant;
    private Button tabMusic;
    private boolean musicTabActive;

    // 正在播放（歌词 / 进度）
    private LinearLayout nowPlaying;
    private android.widget.ImageView npCover;
    private TextView npTitle;
    private TextView npArtist;
    private TextView npTime;
    private TextView npLyric;
    private android.widget.SeekBar npProgress;
    private boolean npDragging;
    private Button npPlayBtn;
    private boolean npPlaying = true;
    private int npLastIdx = -1;
    private boolean npHasTrack;
    private final android.os.Handler npHandler = new android.os.Handler();
    private final ArrayList<Long> npTimes = new ArrayList<>();
    private final ArrayList<String> npLines = new ArrayList<>();
    private Runnable npTick;

    private String lastUser = "";
    private final StringBuilder reply = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            startVoice();
        }
        Bus.add(this);
        setState("idle");

        // 第一次打开就自动把「关掉原生唤醒」的引导弹出来
        if (!Prefs.hasShownTakeover(this)) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) showTakeover();
                }
            }, 900);
        }
    }

    @Override
    protected void onDestroy() {
        Bus.remove(this);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(20), dp(10), dp(20), dp(12));

        // 顶部：两个模式切换
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(tabBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tabAssistant = tabButton("小度同学", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(false);
            }
        });
        tabMusic = tabButton("放歌", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(true);
            }
        });
        tabBar.addView(tabAssistant);
        tabBar.addView(tabMusic);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout assistant = new LinearLayout(this);
        assistant.setOrientation(LinearLayout.VERTICAL);
        content.addView(assistant, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        assistantView = assistant;

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams centerLp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        assistant.addView(center, centerLp);

        ring = new RingView(this);
        center.addView(ring, new LinearLayout.LayoutParams(dp(230), dp(230)));

        status = new TextView(this);
        status.setTextColor(0xFF9CA3AF);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        center.addView(status, slp);

        subtitle = new TextView(this);
        subtitle.setTextColor(0xFFE5E7EB);
        subtitle.setTextSize(20);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(18);
        center.addView(subtitle, tlp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        assistant.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bar.addView(makeButton("服务端地址", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        }));
        bar.addView(makeButton("接管麦克风", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTakeover();
            }
        }));
        stopBtn = makeButton("停止播放", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService svc = VoiceService.INSTANCE;
                if (svc != null) {
                    svc.stopPlayback();
                    setStatus("已停止播放");
                } else {
                    setStatus("服务没在运行");
                }
            }
        });
        bar.addView(stopBtn);

        buildNowPlaying();

        FrameLayout contentParent = content;
        contentParent.addView(nowPlaying, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        nowPlaying.setVisibility(View.GONE);

        setContentView(root);
        showTab(false);
    }

    private Button tabButton(String text, boolean active, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(active ? 0xFFFFFFFF : 0xFF9CA3AF);
        b.setBackgroundColor(active ? 0xFF1F6FEB : 0xFF161B22);
        b.setPadding(dp(18), dp(6), dp(18), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void showTab(boolean music) {
        musicTabActive = music;
        if (nowPlaying != null) nowPlaying.setVisibility(music ? View.VISIBLE : View.GONE);
        if (assistantView != null) assistantView.setVisibility(music ? View.GONE : View.VISIBLE);
        if (tabMusic != null) {
            tabMusic.setBackgroundColor(music ? 0xFF1F6FEB : 0xFF161B22);
            tabMusic.setTextColor(music ? 0xFFFFFFFF : 0xFF9CA3AF);
        }
        if (tabAssistant != null) {
            tabAssistant.setBackgroundColor(music ? 0xFF161B22 : 0xFF1F6FEB);
            tabAssistant.setTextColor(music ? 0xFF9CA3AF : 0xFFFFFFFF);
        }
    }

    /** 播放音乐时的整屏界面：歌名、进度、滚动歌词 */
    private void buildNowPlaying() {
        nowPlaying = new LinearLayout(this);
        nowPlaying.setOrientation(LinearLayout.VERTICAL);
        nowPlaying.setBackgroundColor(0xFF0B0F14);
        nowPlaying.setPadding(dp(36), dp(18), dp(36), dp(14));
        nowPlaying.setVisibility(View.GONE);

        // 上半部分：左封面 + 右信息（横屏排布）
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        nowPlaying.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        npCover = new android.widget.ImageView(this);
        npCover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        // 海报占屏幕宽度约 44%，和右侧歌词基本各占一半
        int coverSide = (int) (getResources().getDisplayMetrics().widthPixels * 0.44f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(coverSide, coverSide);
        clp.rightMargin = dp(26);
        clp.gravity = Gravity.CENTER_VERTICAL;
        top.addView(npCover, clp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(info, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        npTitle = new TextView(this);
        npTitle.setTextColor(0xFFE5E7EB);
        npTitle.setTextSize(34);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(npTitle);

        npArtist = new TextView(this);
        npArtist.setTextColor(0xFF9CA3AF);
        npArtist.setTextSize(17);
        npArtist.setSingleLine(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(4);
        info.addView(npArtist, alp);

        npLyric = new TextView(this);
        npLyric.setTextColor(0xFF7EE787);
        npLyric.setTextSize(26);
        npLyric.setLineSpacing(dp(14), 1f);
        npLyric.setGravity(Gravity.TOP);
        // 长句自动换行，不要省略号
        npLyric.setSingleLine(false);
        npLyric.setHorizontallyScrolling(false);
        npLyric.setEllipsize(null);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(14);
        info.addView(npLyric, llp);

        // 底部控制条
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbp.topMargin = dp(10);
        nowPlaying.addView(ctrl, cbp);

        ctrl.addView(ctrlButton("上一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("上一首");
            }
        }));
        npPlayBtn = ctrlButton("播放", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!npHasTrack) {
                    send("放首歌");
                    return;
                }
                npPlaying = !npPlaying;
                ((Button) v).setText(npPlaying ? "暂停" : "播放");
                send(npPlaying ? "暂停" : "继续放");
            }
        });
        ctrl.addView(npPlayBtn);
        ctrl.addView(ctrlButton("下一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("下一首");
            }
        }));
        ctrl.addView(ctrlButton("停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("停止播放");
            }
        }));

        npProgress = new android.widget.SeekBar(this);
        npProgress.setMax(100);
        npProgress.setProgress(0);
        npProgress.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
                npDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
                npDragging = false;
                VoiceService svc = VoiceService.INSTANCE;
                if (svc != null) svc.seekPlayer(bar.getProgress());
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(18);
        slp.rightMargin = dp(12);
        ctrl.addView(npProgress, slp);

        npTime = new TextView(this);
        npTime.setTextColor(0xFF6B7280);
        npTime.setTextSize(13);
        npTime.setGravity(Gravity.RIGHT);
        ctrl.addView(npTime);
    }

    private Button ctrlButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF1F2937);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void send(String cmd) {
        VoiceService svc = VoiceService.INSTANCE;
        if (svc != null) svc.sendCommand(cmd);
    }

    private void showNowPlaying(String json) {
        try {
            JSONObject o = new JSONObject(json);
            npTitle.setText(o.optString("title", "正在播放"));
            npArtist.setText(o.optString("artist", ""));
            npTimes.clear();
            npLines.clear();
            parseLrc(o.optString("lrc", ""));
            loadCover(o.optString("pic", ""));
            npLyric.setText(npLines.isEmpty() ? "（这首歌没有歌词）" : "");
            npProgress.setProgress(0);
            npTime.setText("00:00");
            npPlaying = true;
            npHasTrack = true;
            npLastIdx = -1;
            if (npPlayBtn != null) npPlayBtn.setText("暂停");
            showTab(true);
            startNpTick();
        } catch (Exception ignored) {
        }
    }

    /** 显示当前行前后各几行，当前行高亮；长句子由 TextView 自动换行 */
    private void renderLyrics(int idx) {
        if (npLines.isEmpty()) {
            npLyric.setText("");
            return;
        }
        if (idx < 0) idx = 0;
        // 只显示三行：上一行、当前行、下一行
        int start = Math.max(0, idx - 1);
        int end = Math.min(npLines.size(), idx + 2);

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = start; i < end; i++) {
            int from = sb.length();
            sb.append(npLines.get(i));
            int to = sb.length();
            if (i == idx) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF7EE787), from, to,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.RelativeSizeSpan(1.15f), from, to,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, to,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF6B7280), from, to,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append("\n");
        }
        npLyric.setText(sb);
    }

    private void loadCover(final String url) {
        npCover.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URLConnection c = new java.net.URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    final android.graphics.Bitmap bmp =
                            android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                    if (bmp != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                npCover.setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void parseLrc(String lrc) {
        Pattern p = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)");
        for (String line : lrc.split("\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.find()) continue;
            long min = Long.parseLong(m.group(1));
            long sec = Long.parseLong(m.group(2));
            String frac = m.group(3);
            long ms = min * 60000 + sec * 1000;
            if (frac != null) {
                ms += frac.length() == 3 ? Long.parseLong(frac) : Long.parseLong(frac) * 10;
            }
            String text = m.group(4).replace("\r", "").trim();
            if (text.isEmpty()) continue;
            int i = npTimes.size();
            while (i > 0 && npTimes.get(i - 1) > ms) i--;
            npTimes.add(i, ms);
            npLines.add(i, text);
        }
    }

    private void startNpTick() {
        stopNpTick();
        npTick = new Runnable() {
            @Override
            public void run() {
                VoiceService svc = VoiceService.INSTANCE;
                if (svc != null) {
                    int pos = svc.playerPosition();
                    int dur = svc.playerDuration();
                    if (dur > 0) {
                        npProgress.setMax(dur);
                        if (!npDragging) npProgress.setProgress(pos);
                        npTime.setText(fmt(pos) + " / " + fmt(dur));
                    }
                    int idx = -1;
                    for (int i = 0; i < npTimes.size(); i++) {
                        if (npTimes.get(i) <= pos) idx = i;
                        else break;
                    }
                    if (idx != npLastIdx) {
                        npLastIdx = idx;
                        renderLyrics(idx);
                    }
                }
                npHandler.postDelayed(this, 400);
            }
        };
        npHandler.post(npTick);
    }

    private void stopNpTick() {
        if (npTick != null) {
            npHandler.removeCallbacks(npTick);
            npTick = null;
        }
    }

    private String fmt(int ms) {
        int s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF1F2937);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(6), 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- 服务 ----------------

    private void startVoice() {
        Intent i = new Intent(this, VoiceService.class);
        i.setAction(VoiceService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        setStatus("正在连接…");
    }

    private void restartVoice() {
        stopService(new Intent(this, VoiceService.class));
        startVoice();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startVoice();
        } else {
            status.setText("没有麦克风权限，请在系统设置里授权");
        }
    }

    // ---------------- 事件 ----------------

    @Override
    public void onEvent(String type, String payload) {
        if ("state".equals(type)) {
            setState(payload);
        } else if ("music".equals(type)) {
            showNowPlaying(payload);
        } else if ("music_end".equals(type)) {
            npHasTrack = false;
            npPlaying = false;
            if (npPlayBtn != null) npPlayBtn.setText("播放");
            stopNpTick();
        } else if ("play_error".equals(type)) {
            Toast.makeText(this, "播放失败：" + payload, Toast.LENGTH_LONG).show();
        } else if ("asr".equals(type)) {
            lastUser = payload;
            render();
        } else if ("reply".equals(type)) {
            reply.append(payload);
            render();
        } else if ("conn".equals(type)) {
            if ("connected".equals(payload)) {
                setStatus("已连接电脑");
                ring.setColor(0xFF3B82F6);
            } else if (payload.startsWith("disconnected")) {
                setStatus("未连接，正在重试…");
                ring.setColor(0xFFEF4444);
            } else if ("connecting".equals(payload)) {
                setStatus("正在连接…");
            }
        } else if ("error".equals(type)) {
            Toast.makeText(this, payload, Toast.LENGTH_SHORT).show();
        }
    }

    private void setState(String value) {
        if ("thinking".equals(value)) {
            reply.setLength(0);
            render();
        }
        if ("listening".equals(value)) {
            setStatus("我在听");
            ring.setColor(0xFF3B82F6);
        } else if ("thinking".equals(value)) {
            setStatus("想一下");
            ring.setColor(0xFFF59E0B);
        } else if ("speaking".equals(value)) {
            setStatus("说给你听");
            ring.setColor(0xFF10B981);
        } else if ("idle".equals(value)) {
            setStatus("待机 · 说「小度同学」叫我");
            ring.setColor(0xFF6B7280);
        }
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        if (lastUser.length() > 0) {
            sb.append("你说：").append(lastUser).append("\n");
        }
        if (reply.length() > 0) {
            sb.append("小度：").append(reply);
        }
        subtitle.setText(sb.toString());
    }

    // ---------------- 弹窗 ----------------

    private void showSettings() {
        final EditText et = new EditText(this);
        et.setText(Prefs.server(this));
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("服务端地址")
                .setMessage("电脑上 run.py 打印的那个地址，形如 ws://<PC_IP>:8765/ws")
                .setView(et)
                .setPositiveButton("保存并重连", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setServer(MainActivity.this, et.getText().toString().trim());
                        restartVoice();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTakeover() {
        TextView text = new TextView(this);
        text.setTextSize(20);
        text.setTextColor(0xFFE5E7EB);
        text.setPadding(dp(20), dp(16), dp(20), dp(8));
        text.setLineSpacing(dp(6), 1f);
        text.setText("原生小度一直占着麦克风，不关掉这里就听不见。\n\n"
                + "两种关法，任选一种：\n\n"
                + "① 点下面的「隐私设置」\n"
                + "    找到【语音唤醒】→ 关掉\n\n"
                + "② 点「选择应用」，选带「小度」的那个\n"
                + "    进去点【权限】→ 关掉【麦克风】\n"
                + "    （没有权限选项就点【停用】）");

        new AlertDialog.Builder(this)
                .setTitle("接管麦克风")
                .setView(text)
                .setPositiveButton("选择应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setShownTakeover(MainActivity.this);
                        showPackagePicker();
                    }
                })
                .setNeutralButton("隐私设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setShownTakeover(MainActivity.this);
                        openPrivacySettings();
                    }
                })
                .setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setShownTakeover(MainActivity.this);
                    }
                })
                .show();
    }

    /** 列出可疑的「小度」应用，让用户自己挑，比猜包名靠谱 */
    private void showPackagePicker() {
        final java.util.List<String[]> list = findCandidates();
        if (list.isEmpty()) {
            Toast.makeText(this, "没找到小度相关应用，直接打开系统设置", Toast.LENGTH_LONG).show();
            openDuerSettings(null);
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i)[1] + "\n" + list.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要关闭的应用")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openDuerSettings(list.get(which)[0]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 返回 [包名, 显示名] 列表 */
    private java.util.List<String[]> findCandidates() {
        PackageManager pm = getPackageManager();
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(0)) {
            String pkg = pi.packageName;
            if (pkg.equals(getPackageName())) continue;
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Exception e) {
                continue;
            }
            String n = pkg.toLowerCase();
            boolean hit = label.contains("小度") || label.contains("度秘")
                    || n.contains("duer")
                    || (n.contains("baidu") && (n.contains("voice") || n.contains("assist") || n.contains("speech")));
            if (hit) out.add(new String[]{pkg, label});
        }
        return out;
    }

    /** 逐级尝试，进到隐私设置；都不支持就退回系统设置首页 */
    private void openPrivacySettings() {
        String[] actions = {
                "android.settings.PRIVACY_SETTINGS",
                "com.android.settings.Settings$PrivacySettingsActivity",
                "android.settings.SECURITY_SETTINGS",
                "android.settings.SETTINGS"
        };
        for (String action : actions) {
            try {
                Intent i;
                if (action.startsWith("com.android.settings.")) {
                    i = new Intent();
                    i.setClassName("com.android.settings", action);
                } else {
                    i = new Intent(action);
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {
                // 这个入口不存在，试下一个
            }
        }
        Toast.makeText(this, "系统设置里没有隐私入口，手动找一下：设置 → 隐私", Toast.LENGTH_LONG).show();
        openDuerSettings(null);
    }

    private void openDuerSettings(String pkg) {
        try {
            if (pkg != null) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + pkg));
                startActivity(i);
                return;
            }
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "打不开系统设置：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
