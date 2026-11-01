package com.xiaodu.link;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 主界面：小度同学 / 歌曲 / 设置 */
public class MainActivity extends Activity implements Bus.Listener {
    private boolean dark;
    private int cBg, cCard, cText, cSub, cAccent, cLine;

    private FrameLayout content;
    private LinearLayout assistantView, musicView, settingsView;
    private Button tabAssistant, tabMusic;

    // 助手页
    private RingView ring;
    private TextView status, subtitle;

    // 歌曲页
    private ImageView npCover;
    private TextView npTitle, npArtist, npLyric, npTime;
    private SeekBar npProgress;
    private Button npPlayBtn;
    private boolean npDragging, npPlaying, npHasTrack;
    private int npLastIdx = -1;
    private final ArrayList<Long> npTimes = new ArrayList<>();
    private final ArrayList<String> npLines = new ArrayList<>();
    private final Handler npHandler = new Handler();
    private Runnable npTick;

    // 设置页
    private EditText setServer;
    private CheckBox setAuto;
    private RadioGroup setTheme;

    private String lastUser = "";
    private final StringBuilder reply = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPalette();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        Bus.add(this);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            connectOrDiscover();
        }

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
        npHandler.removeCallbacksAndMessages(null);
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

    // ---------------- 主题 ----------------

    private void applyPalette() {
        String t = Prefs.theme(this);
        if ("auto".equals(t)) {
            int night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } else {
            dark = "dark".equals(t);
        }
        if (dark) {
            cBg = 0xFF0B0F14;
            cCard = 0xFF161B22;
            cText = 0xFFE5E7EB;
            cSub = 0xFF9CA3AF;
            cAccent = 0xFF3B82F6;
            cLine = 0xFF21262D;
        } else {
            cBg = 0xFFF3F5F9;
            cCard = 0xFFFFFFFF;
            cText = 0xFF111827;
            cSub = 0xFF6B7280;
            cAccent = 0xFF2563EB;
            cLine = 0xFFE5E7EB;
        }
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(cCard);
        d.setCornerRadius(dp(14));
        return d;
    }

    // ---------------- 布局 ----------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setPadding(dp(20), dp(10), dp(20), dp(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tabAssistant = pill("小度同学", true);
        tabAssistant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(0);
            }
        });
        tabMusic = pill("歌曲", false);
        tabMusic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(1);
            }
        });
        bar.addView(tabAssistant);
        bar.addView(tabMusic);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button gear = pill("设置", false);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillSettings();
                showTab(2);
            }
        });
        bar.addView(gear);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        buildAssistant();
        buildMusic();
        buildSettings();

        content.addView(assistantView, matchFrame());
        content.addView(musicView, matchFrame());
        content.addView(settingsView, matchFrame());

        setContentView(root);
        showTab(0);
        setState("idle");
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private Button pill(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(active ? Color.WHITE : cSub);
        b.setBackground(makePill(active));
        b.setPadding(dp(18), dp(6), dp(18), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable makePill(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(active ? cAccent : cCard);
        d.setCornerRadius(dp(18));
        if (!active) d.setStroke(dp(1), cLine);
        return d;
    }

    private void buildAssistant() {
        assistantView = new LinearLayout(this);
        assistantView.setOrientation(LinearLayout.VERTICAL);
        assistantView.setGravity(Gravity.CENTER);

        ring = new RingView(this);
        assistantView.addView(ring, new LinearLayout.LayoutParams(dp(250), dp(250)));

        status = new TextView(this);
        status.setTextColor(cSub);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        assistantView.addView(status, slp);

        subtitle = new TextView(this);
        subtitle.setTextColor(cText);
        subtitle.setTextSize(20);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(6), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        tlp.leftMargin = dp(40);
        tlp.rightMargin = dp(40);
        assistantView.addView(subtitle, tlp);
    }

    private void buildMusic() {
        musicView = new LinearLayout(this);
        musicView.setOrientation(LinearLayout.VERTICAL);
        musicView.setPadding(dp(6), dp(12), dp(6), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        musicView.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        npCover = new ImageView(this);
        npCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setColor(cCard);
        coverBg.setCornerRadius(dp(16));
        npCover.setBackground(coverBg);
        npCover.setClipToOutline(true);
        int coverSide = (int) (getResources().getDisplayMetrics().widthPixels * 0.42f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(coverSide, coverSide);
        clp.rightMargin = dp(28);
        clp.gravity = Gravity.CENTER_VERTICAL;
        top.addView(npCover, clp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(info, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView label = new TextView(this);
        label.setText("正在播放");
        label.setTextColor(cAccent);
        label.setTextSize(12);
        label.setLetterSpacing(0.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        info.addView(label, lp);

        npTitle = new TextView(this);
        npTitle.setText("还没有在播放");
        npTitle.setTextColor(cText);
        npTitle.setTextSize(32);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(npTitle);

        npArtist = new TextView(this);
        npArtist.setText("说「小度同学，放首歌」");
        npArtist.setTextColor(cSub);
        npArtist.setTextSize(16);
        npArtist.setSingleLine(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(4);
        info.addView(npArtist, alp);

        npLyric = new TextView(this);
        npLyric.setTextColor(cSub);
        npLyric.setTextSize(24);
        npLyric.setLineSpacing(dp(16), 1f);
        npLyric.setGravity(Gravity.CENTER_VERTICAL);
        npLyric.setSingleLine(false);
        npLyric.setHorizontallyScrolling(false);
        npLyric.setEllipsize(null);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(12);
        info.addView(npLyric, llp);

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbp.topMargin = dp(10);
        musicView.addView(ctrl, cbp);

        ctrl.addView(smallButton("上一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("上一首");
            }
        }));
        npPlayBtn = smallButton("播放", new View.OnClickListener() {
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
        ctrl.addView(smallButton("下一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("下一首");
            }
        }));
        ctrl.addView(smallButton("停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("停止播放");
            }
        }));

        npProgress = new SeekBar(this);
        npProgress.setMax(100);
        npProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                npDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                npDragging = false;
                VoiceService svc = VoiceService.INSTANCE;
                if (svc != null) svc.seekPlayer(bar.getProgress());
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(16);
        slp.rightMargin = dp(10);
        ctrl.addView(npProgress, slp);

        npTime = new TextView(this);
        npTime.setText("00:00");
        npTime.setTextColor(cSub);
        npTime.setTextSize(13);
        ctrl.addView(npTime);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(cText);
        GradientDrawable d = new GradientDrawable();
        d.setColor(cCard);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), cLine);
        b.setBackground(d);
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildSettings() {
        settingsView = new LinearLayout(this);
        settingsView.setOrientation(LinearLayout.VERTICAL);
        settingsView.setPadding(dp(30), dp(20), dp(30), dp(10));

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(cText);
        title.setTextSize(26);
        settingsView.addView(title);

        TextView label1 = new TextView(this);
        label1.setText("服务端地址");
        label1.setTextColor(cSub);
        label1.setTextSize(14);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p1.topMargin = dp(16);
        settingsView.addView(label1, p1);

        setServer = new EditText(this);
        setServer.setHint("192.168.1.10:8765");
        setServer.setHintTextColor(cSub);
        setServer.setTextColor(cText);
        setServer.setTextSize(17);
        setServer.setInputType(InputType.TYPE_CLASS_TEXT);
        setServer.setBackground(cardBg());
        setServer.setPadding(dp(14), dp(10), dp(14), dp(10));
        settingsView.addView(setServer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setAuto = new CheckBox(this);
        setAuto.setText("启动时自动连接");
        setAuto.setTextColor(cText);
        setAuto.setTextSize(16);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p2.topMargin = dp(14);
        settingsView.addView(setAuto, p2);

        TextView label2 = new TextView(this);
        label2.setText("主题");
        label2.setTextColor(cSub);
        label2.setTextSize(14);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p3.topMargin = dp(14);
        settingsView.addView(label2, p3);

        setTheme = new RadioGroup(this);
        setTheme.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"自动", "深色", "浅色"};
        String[] values = {"auto", "dark", "light"};
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setTag(values[i]);
            rb.setTextColor(cText);
            rb.setTextSize(16);
            rb.setId(1000 + i);
            setTheme.addView(rb);
        }
        settingsView.addView(setTheme);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p4 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p4.topMargin = dp(20);
        settingsView.addView(buttons, p4);

        Button save = smallButton("保存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
        save.setTextColor(Color.WHITE);
        GradientDrawable solid = new GradientDrawable();
        solid.setColor(cAccent);
        solid.setCornerRadius(dp(12));
        save.setBackground(solid);
        buttons.addView(save);

        buttons.addView(smallButton("接管麦克风", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTakeover();
            }
        }));
        buttons.addView(smallButton("重新发现", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discover();
            }
        }));
    }

    private void fillSettings() {
        setServer.setText(Prefs.serverHost(this));
        setAuto.setChecked(Prefs.autoConnect(this));
        String t = Prefs.theme(this);
        int idx = "auto".equals(t) ? 0 : ("dark".equals(t) ? 1 : 2);
        setTheme.check(1000 + idx);
    }

    private void saveSettings() {
        String host = Prefs.normalize(setServer.getText().toString());
        Prefs.setServerHost(this, host);
        Prefs.setAutoConnect(this, setAuto.isChecked());
        int id = setTheme.getCheckedRadioButtonId();
        RadioButton rb = findViewById(id);
        String theme = rb != null ? String.valueOf(rb.getTag()) : "light";
        boolean changed = !theme.equals(Prefs.theme(this));
        Prefs.setTheme(this, theme);

        if (changed) {
            recreate();
            return;
        }
        if (host.isEmpty()) {
            Toast.makeText(this, "先填服务端地址", Toast.LENGTH_SHORT).show();
            return;
        }
        restartVoice();
        showTab(0);
        setStatus("正在连接…");
    }

    private void showTab(int which) {
        if (assistantView == null) return;
        assistantView.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        musicView.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        settingsView.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        tabAssistant.setBackground(makePill(which == 0));
        tabAssistant.setTextColor(which == 0 ? Color.WHITE : cSub);
        tabMusic.setBackground(makePill(which == 1));
        tabMusic.setTextColor(which == 1 ? Color.WHITE : cSub);
    }

    private void send(String cmd) {
        VoiceService svc = VoiceService.INSTANCE;
        if (svc != null) svc.sendCommand(cmd);
    }

    // ---------------- 连接 ----------------

    private void connectOrDiscover() {
        String host = Prefs.serverHost(this);
        if (!host.isEmpty()) {
            startVoice();
            return;
        }
        if (Prefs.autoConnect(this)) {
            discover();
        } else {
            fillSettings();
            showTab(2);
        }
    }

    private void discover() {
        setStatus("正在查找服务端…");
        Toast.makeText(this, "正在局域网里查找服务端…", Toast.LENGTH_SHORT).show();
        Discover.start(new Discover.Callback() {
            @Override
            public void onFound(final String host, final int port) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        final String addr = host + ":" + port;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("发现服务端")
                                .setMessage("地址：" + addr + "\n是否连接并记住？")
                                .setPositiveButton("连接", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        Prefs.setServerHost(MainActivity.this, addr);
                                        Prefs.setAutoConnect(MainActivity.this, true);
                                        restartVoice();
                                        showTab(0);
                                        setStatus("正在连接…");
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    private void startVoice() {
        Intent i = new Intent(this, VoiceService.class);
        i.setAction(VoiceService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void restartVoice() {
        stopService(new Intent(this, VoiceService.class));
        startVoice();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            connectOrDiscover();
        } else {
            setStatus("没有麦克风权限");
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
                setStatus("服务端已连接");
                ring.setColor(cAccent);
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
            ring.setColor(cAccent);
        } else if ("thinking".equals(value)) {
            setStatus("想一下");
            ring.setColor(0xFFF59E0B);
        } else if ("speaking".equals(value)) {
            setStatus("说给你听");
            ring.setColor(0xFF10B981);
        } else if ("idle".equals(value)) {
            setStatus("待机 · 说「小度同学」叫我");
            ring.setColor(cSub);
        }
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        if (lastUser.length() > 0) sb.append("你说：").append(lastUser).append("\n");
        if (reply.length() > 0) sb.append("小度：").append(reply);
        subtitle.setText(sb.toString());
    }

    // ---------------- 歌曲页 ----------------

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
            showTab(1);
            startNpTick();
        } catch (Exception ignored) {
        }
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
            if (frac != null) ms += frac.length() == 3 ? Long.parseLong(frac) : Long.parseLong(frac) * 10;
            String text = m.group(4).replace("\r", "").trim();
            if (text.isEmpty()) continue;
            int i = npTimes.size();
            while (i > 0 && npTimes.get(i - 1) > ms) i--;
            npTimes.add(i, ms);
            npLines.add(i, text);
        }
    }

    private void renderLyrics(int idx) {
        if (npLines.isEmpty()) {
            npLyric.setText("");
            return;
        }
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - 1);
        int end = Math.min(npLines.size(), idx + 2);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = start; i < end; i++) {
            int from = sb.length();
            sb.append(npLines.get(i));
            int to = sb.length();
            if (i == idx) {
                sb.setSpan(new ForegroundColorSpan(cAccent), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.15f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new ForegroundColorSpan(cSub), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append("\n");
        }
        npLyric.setText(sb);
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

    // ---------------- 接管麦克风 ----------------

    private void showTakeover() {
        TextView text = new TextView(this);
        text.setTextSize(19);
        text.setTextColor(cText);
        text.setPadding(dp(20), dp(16), dp(20), dp(8));
        text.setLineSpacing(dp(6), 1f);
        text.setText("关掉原生小度的唤醒，这里才能独占麦克风。\n\n"
                + "最简单的办法：按一下机器顶部的麦克风静音键。\n\n"
                + "其它办法：\n"
                + "· 屏幕顶部下拉 → 禁用唤醒\n"
                + "· 设置 → 隐私 → 语音唤醒 → 关闭\n"
                + "· 或把我的「选择应用」打开，关掉小度的麦克风权限");

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
                .setNegativeButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setShownTakeover(MainActivity.this);
                    }
                })
                .show();
    }

    private void showPackagePicker() {
        final java.util.List<String[]> list = findCandidates();
        if (list.isEmpty()) {
            openPrivacySettings();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i)[1] + "\n" + list.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("选择要关闭的应用")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openAppInfo(list.get(which)[0]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private java.util.List<String[]> findCandidates() {
        PackageManager pm = getPackageManager();
        java.util.List<String[]> out = new ArrayList<>();
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
            boolean hit = label.contains("小度") || label.contains("度秘") || n.contains("duer")
                    || (n.contains("baidu") && (n.contains("voice") || n.contains("assist") || n.contains("speech")));
            if (hit) out.add(new String[]{pkg, label});
        }
        return out;
    }

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
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        openAppInfo(null);
    }

    private void openAppInfo(String pkg) {
        try {
            if (pkg != null) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + pkg));
                startActivity(i);
                return;
            }
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "打不开系统设置", Toast.LENGTH_LONG).show();
        }
    }
}
