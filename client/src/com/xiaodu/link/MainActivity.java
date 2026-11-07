package com.xiaodu.link;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
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
    private long npSkipAt;
    private final ArrayList<Long> npTimes = new ArrayList<>();
    private final ArrayList<String> npLines = new ArrayList<>();
    private final Handler npHandler = new Handler();
    private Runnable npTick;

    // 设置页
    private EditText setServer;
    private CheckBox setAuto;
    private RadioGroup setTheme;
    private RadioGroup setQuality;
    private RadioGroup setCache;
    private RadioGroup setProxy;
    private EditText proxyCustom;
    private android.widget.ScrollView settingsScroll;

    private static final String[] P_NAMES = {"直连", "gh-proxy", "ghfast", "ghproxy", "自定义"};
    private static final String[] P_VALUES = {"", "https://gh-proxy.com/", "https://ghfast.top/",
            "https://ghproxy.net/", "custom"};

    private static final String[] Q_NAMES = {"自动", "不转码", "128k", "192k", "320k"};
    private static final String[] Q_VALUES = {"auto", "original", "128", "192", "320"};
    private static final int[] CACHE_MB = {512, 1024, 2048, 5120};

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
    protected void onResume() {
        super.onResume();
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
        content.addView(settingsScroll, matchFrame());

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
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        // 去掉默认的阴影/涟漪残留，那个小点就是它
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setBackgroundTintList(null);
        b.setForeground(null);
        b.setDefaultFocusHighlightEnabled(false);
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
        ring.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService svc = VoiceService.INSTANCE;
                if (svc == null) {
                    Toast.makeText(MainActivity.this, "语音服务还没启动", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (svc.isMusicPlaying()) {
                    svc.pausePlayback();
                    setStatus("已暂停");
                    Toast.makeText(MainActivity.this, "已暂停", Toast.LENGTH_SHORT).show();
                } else if (svc.hasMusic()) {
                    svc.resumePlayback();
                    setStatus("正在播放");
                    Toast.makeText(MainActivity.this, "继续播放", Toast.LENGTH_SHORT).show();
                } else {
                    svc.sendCommand("放首歌");
                    setStatus("正在找歌…");
                    Toast.makeText(MainActivity.this, "正在找歌…", Toast.LENGTH_SHORT).show();
                }
            }
        });
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
        musicView.setPadding(dp(6), dp(12), dp(6), dp(20));

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
                skipTo("上一首");
            }
        }));
        npPlayBtn = smallButton("播放", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService svc = VoiceService.INSTANCE;
                if (!npHasTrack || svc == null || !svc.hasMusic()) {
                    send("放首歌");
                    return;
                }
                if (svc.isMusicPlaying()) {
                    svc.pausePlayback();
                    npPlaying = false;
                    ((Button) v).setText("播放");
                } else {
                    svc.resumePlayback();
                    npPlaying = true;
                    ((Button) v).setText("暂停");
                }
            }
        });
        ctrl.addView(npPlayBtn);
        ctrl.addView(smallButton("下一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipTo("下一首");
            }
        }));
        ctrl.addView(smallButton("停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VoiceService svc = VoiceService.INSTANCE;
                if (svc != null) svc.stopPlayback();
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
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setBackgroundTintList(null);
        b.setForeground(null);
        b.setDefaultFocusHighlightEnabled(false);
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

        TextView labelQ = new TextView(this);
        labelQ.setText("音质");
        labelQ.setTextColor(cSub);
        labelQ.setTextSize(14);
        LinearLayout.LayoutParams pQ = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pQ.topMargin = dp(14);
        settingsView.addView(labelQ, pQ);

        setQuality = new RadioGroup(this);
        setQuality.setOrientation(RadioGroup.HORIZONTAL);
        String[] qNames = Q_NAMES;
        String[] qValues = Q_VALUES;
        for (int i = 0; i < qNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(qNames[i]);
            rb.setTag(qValues[i]);
            rb.setTextColor(cText);
            rb.setTextSize(15);
            rb.setId(2000 + i);
            setQuality.addView(rb);
        }
        settingsView.addView(setQuality);

        TextView qHint = new TextView(this);
        qHint.setText("自动：先 128k 秒开，后台再补高码率");
        qHint.setTextColor(cSub);
        qHint.setTextSize(12);
        settingsView.addView(qHint);

        TextView labelC = new TextView(this);
        labelC.setText("歌曲缓存上限");
        labelC.setTextColor(cSub);
        labelC.setTextSize(14);
        LinearLayout.LayoutParams pC = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pC.topMargin = dp(14);
        settingsView.addView(labelC, pC);

        setCache = new RadioGroup(this);
        setCache.setOrientation(RadioGroup.HORIZONTAL);
        String[] cNames = {"512M", "1G", "2G", "5G"};
        for (int i = 0; i < cNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(cNames[i]);
            rb.setTag(CACHE_MB[i]);
            rb.setTextColor(cText);
            rb.setTextSize(15);
            rb.setId(3000 + i);
            setCache.addView(rb);
        }
        settingsView.addView(setCache);

        TextView cHint = new TextView(this);
        cHint.setText("超过上限自动删除最久没听的歌");
        cHint.setTextColor(cSub);
        cHint.setTextSize(12);
        settingsView.addView(cHint);

        TextView labelP = new TextView(this);
        labelP.setText("GitHub 加速下载");
        labelP.setTextColor(cSub);
        labelP.setTextSize(14);
        LinearLayout.LayoutParams pP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pP.topMargin = dp(14);
        settingsView.addView(labelP, pP);

        setProxy = new RadioGroup(this);
        setProxy.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < P_NAMES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(P_NAMES[i]);
            rb.setTag(P_VALUES[i]);
            rb.setTextColor(cText);
            rb.setTextSize(15);
            rb.setId(4000 + i);
            setProxy.addView(rb);
        }
        settingsView.addView(setProxy);

        proxyCustom = new EditText(this);
        proxyCustom.setHint("https://gh-proxy.com/");
        proxyCustom.setHintTextColor(cSub);
        proxyCustom.setTextColor(cText);
        proxyCustom.setTextSize(15);
        proxyCustom.setBackground(cardBg());
        proxyCustom.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams pP2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pP2.topMargin = dp(8);
        settingsView.addView(proxyCustom, pP2);

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
        buttons.addView(smallButton("检查更新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        }));

        settingsScroll = new android.widget.ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.addView(settingsView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        settingsView.setVisibility(View.VISIBLE);
    }

    private void fillSettings() {
        setServer.setText(Prefs.serverHost(this));
        setAuto.setChecked(Prefs.autoConnect(this));
        String t = Prefs.theme(this);
        int idx = "auto".equals(t) ? 0 : ("dark".equals(t) ? 1 : 2);
        setTheme.check(1000 + idx);

        String q = Prefs.quality(this);
        int qi = 0;
        for (int i = 0; i < Q_VALUES.length; i++) {
            if (Q_VALUES[i].equals(q)) qi = i;
        }
        setQuality.check(2000 + qi);

        int mb = Prefs.cacheLimitMb(this);
        int ci = 1;
        for (int i = 0; i < CACHE_MB.length; i++) {
            if (CACHE_MB[i] == mb) ci = i;
        }
        setCache.check(3000 + ci);

        String proxy = Prefs.proxyPrefix(this);
        int pi = 0;
        boolean custom = true;
        for (int i = 0; i < P_VALUES.length - 1; i++) {
            if (P_VALUES[i].equals(proxy)) {
                pi = i;
                custom = false;
                break;
            }
        }
        if (custom) {
            pi = P_VALUES.length - 1;
            proxyCustom.setText(proxy);
        }
        setProxy.check(4000 + pi);
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

        RadioButton qb = findViewById(setQuality.getCheckedRadioButtonId());
        String q = qb != null ? String.valueOf(qb.getTag()) : "auto";
        boolean qChanged = !q.equals(Prefs.quality(this));
        Prefs.setQuality(this, q);

        RadioButton cb = findViewById(setCache.getCheckedRadioButtonId());
        int mb = cb != null ? (Integer) cb.getTag() : 1024;
        Prefs.setCacheLimitMb(this, mb);
        VoiceService svc = VoiceService.INSTANCE;
        if (svc != null) svc.trimCache();

        RadioButton pb = findViewById(setProxy.getCheckedRadioButtonId());
        String pv = pb != null ? String.valueOf(pb.getTag()) : "";
        if ("custom".equals(pv)) pv = proxyCustom.getText().toString().trim();
        Prefs.setProxyPrefix(this, pv);

        if (changed) {
            recreate();
            return;
        }
        if (qChanged) {
            restartVoice();
            Toast.makeText(this, "音质已切换", Toast.LENGTH_SHORT).show();
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
        if (settingsScroll != null) {
            settingsScroll.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        }
        tabAssistant.setBackground(makePill(which == 0));
        tabAssistant.setTextColor(which == 0 ? Color.WHITE : cSub);
        tabMusic.setBackground(makePill(which == 1));
        tabMusic.setTextColor(which == 1 ? Color.WHITE : cSub);
    }

    private void send(String cmd) {
        VoiceService svc = VoiceService.INSTANCE;
        if (svc != null) svc.sendCommand(cmd);
    }

    /** 上一首/下一首：先给界面一个即时反馈，避免看起来没反应 */
    private void skipTo(String cmd) {
        long now = System.currentTimeMillis();
        if (now - npSkipAt < 800) return;
        npSkipAt = now;
        if (npTitle != null) npTitle.setText("正在切歌…");
        if (npArtist != null) npArtist.setText("");
        send(cmd);
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

    private void checkUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        Updater.check(this, new Updater.Callback() {
            @Override
            public void onResult(final boolean ok, final String message, final String url,
                                 final long id, final boolean newer) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!newer || url == null) {
                            Toast.makeText(MainActivity.this, "已是最新：" + message,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        final String target = Updater.proxied(MainActivity.this, url);
                        showCard("发现新版本", message + "\n\n下载地址：\n" + target,
                                new String[]{"稍后", "下载并安装"},
                                new Runnable[]{null, new Runnable() {
                                    @Override
                                    public void run() {
                                        Prefs.setUpdateId(MainActivity.this, id);
                                        Updater.downloadAndInstall(MainActivity.this, target);
                                        Toast.makeText(MainActivity.this, "开始下载，完成后会弹出安装",
                                                Toast.LENGTH_LONG).show();
                                    }
                                }});
                    }
                });
            }
        });
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
                        showCard("发现服务端", "地址：" + addr + "\n是否连接并记住？",
                                new String[]{"取消", "连接"},
                                new Runnable[]{null, new Runnable() {
                                    @Override
                                    public void run() {
                                        Prefs.setServerHost(MainActivity.this, addr);
                                        Prefs.setAutoConnect(MainActivity.this, true);
                                        restartVoice();
                                        showTab(0);
                                        setStatus("正在连接…");
                                    }
                                }});
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
        } else if ("paused".equals(type)) {
            npPlaying = false;
            if (npPlayBtn != null) npPlayBtn.setText("播放");
            setStatus("已暂停");
            if (ring != null) ring.setColor(cSub);
        } else if ("playing".equals(type)) {
            npPlaying = true;
            if (npPlayBtn != null) npPlayBtn.setText("暂停");
            setStatus("正在播放");
            if (ring != null) ring.setColor(cAccent);
        } else if ("volume".equals(type)) {
            // 语音调完音量只在状态栏提示一下，界面不再放音量条
            try {
                int pct = Integer.parseInt(payload);
                setStatus("音量 " + pct + "%");
            } catch (Exception ignored) {
            }
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
        // 六行：当前行上下各两到三行
        int start = Math.max(0, idx - 2);
        int end = Math.min(npLines.size(), idx + 4);
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

    private Dialog card;

    /** 自定义圆角卡片弹窗：底色和文字都跟主题走，避免系统对话框在浅色下看不清 */
    private void showCard(String title, CharSequence body, String[] labels, Runnable[] actions) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextColor(cSub);
        tv.setTextSize(16);
        tv.setLineSpacing(dp(6), 1f);
        showCardView(title, tv, labels, actions);
    }

    /** 标题 + 任意内容 + 一排按钮，按钮从右到左依次是主操作、次操作… */
    private void showCardView(String title, View body, String[] labels, final Runnable[] actions) {
        if (card != null && card.isShowing()) card.dismiss();

        final Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(26), dp(22), dp(26), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(20));
        bg.setStroke(Math.max(1, (int) dp(1)), cLine);
        box.setBackground(bg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText);
        t.setTextSize(21);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        box.addView(body, blp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        box.addView(row, rlp);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = cardButton(labels[i], i == labels.length - 1);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (actions != null && idx < actions.length && actions[idx] != null) {
                        actions[idx].run();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(b, lp);
        }

        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
        }
        card = d;
        d.show();
        if (w != null) {
            int width = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.68f),
                    (int) dp(680));
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private Button cardButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : cText);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? cAccent : cBg);
        g.setCornerRadius(dp(22));
        if (!primary) g.setStroke(Math.max(1, (int) dp(1)), cLine);
        b.setBackground(g);
        b.setPadding(dp(20), dp(9), dp(20), dp(9));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setBackgroundTintList(null);
        b.setForeground(null);
        b.setDefaultFocusHighlightEnabled(false);
        return b;
    }

    private void showTakeover() {
        String body = "关掉原生小度的唤醒，这里才能独占麦克风。\n\n"
                + "最简单的办法：按一下机器顶部的麦克风静音键。\n\n"
                + "其它办法：\n"
                + "· 屏幕顶部下拉 → 禁用唤醒\n"
                + "· 设置 → 隐私 → 语音唤醒 → 关闭\n"
                + "· 或点「选择应用」，关掉小度的麦克风权限";

        showCard("接管麦克风", body,
                new String[]{"知道了", "隐私设置", "选择应用"},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                Prefs.setShownTakeover(MainActivity.this);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                Prefs.setShownTakeover(MainActivity.this);
                                openPrivacySettings();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                Prefs.setShownTakeover(MainActivity.this);
                                showPackagePicker();
                            }
                        }
                });
    }

    private void showPackagePicker() {
        final java.util.List<String[]> list = findCandidates();
        if (list.isEmpty()) {
            openPrivacySettings();
            return;
        }

        LinearLayout apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < list.size(); i++) {
            final String pkg = list.get(i)[0];
            final String label = list.get(i)[1];
            TextView row = new TextView(this);
            row.setText(label + "\n" + pkg);
            row.setTextSize(16);
            row.setTextColor(cText);
            row.setLineSpacing(dp(3), 1f);
            row.setPadding(dp(2), dp(12), dp(2), dp(12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (card != null) card.dismiss();
                    openAppInfo(pkg);
                }
            });
            apps.addView(row);
        }

        showCardView("选择要关闭的应用", apps, new String[]{"取消"}, new Runnable[]{null});
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
