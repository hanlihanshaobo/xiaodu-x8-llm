package com.xiaodu.link;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * 播放器：音乐和语音分成两条通道。
 *
 * 关键点：
 * - 语音（TTS）优先：来了就暂停音乐，播完再恢复音乐，不会排在整首歌后面
 * - 音乐和语音都空闲时才回调 onIdle
 * - 换歌/停止会清掉各自的队列，不会串音
 */
public class Player {
    private static final String TAG = "XDL/Player";

    public interface Callback {
        void onDone(String id);

        void onIdle();

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback cb;

    // 音乐
    private MediaPlayer musicMp;
    private String[] musicItem;
    private boolean musicRetried;
    private boolean musicPausedBySpeech;   // 因播报暂停，播完要恢复
    private boolean musicPausedByUser;     // 用户主动暂停，播完不自动恢复

    // 语音
    private MediaPlayer speechMp;
    private String speechId;
    private final ArrayDeque<String[]> speechQueue = new ArrayDeque<>();

    // 等语音播完再开始的音乐
    private String[] pendingMusic;
    private boolean idleNotified = true;

    public Player(Callback cb) {
        this.cb = cb;
    }

    // ---------------- 对外接口 ----------------

    public void enqueueMusic(final String id, final String path) {
        main.post(new Runnable() {
            @Override
            public void run() {
                idleNotified = false;
                musicPausedBySpeech = false;
                musicPausedByUser = false;
                musicRetried = false;
                releaseMusic();
                pendingMusic = new String[]{id, path};
                startMusicIfReady();
            }
        });
    }

    public void enqueueSpeech(final String id, final String path) {
        main.post(new Runnable() {
            @Override
            public void run() {
                idleNotified = false;
                speechQueue.add(new String[]{id, path});
                if (speechMp == null) startSpeechNext();
            }
        });
    }

    /** 用户点暂停：只停音乐 */
    public void pauseMusic() {
        main.post(new Runnable() {
            @Override
            public void run() {
                musicPausedByUser = true;
                try {
                    if (musicMp != null && musicMp.isPlaying()) musicMp.pause();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 用户点继续：如果正在播报，等播报结束再恢复 */
    public void resumeMusic() {
        main.post(new Runnable() {
            @Override
            public void run() {
                musicPausedByUser = false;
                if (speechMp != null || !speechQueue.isEmpty()) {
                    musicPausedBySpeech = true;
                    return;
                }
                try {
                    if (musicMp != null && !musicMp.isPlaying()) musicMp.start();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 只停语音，音乐继续（用于用户插话打断播报） */
    public void stopSpeech() {
        main.post(new Runnable() {
            @Override
            public void run() {
                speechQueue.clear();
                releaseSpeech();
                if (musicPausedBySpeech && !musicPausedByUser) {
                    try {
                        if (musicMp != null && !musicMp.isPlaying()) musicMp.start();
                    } catch (Exception ignored) {
                    }
                }
                musicPausedBySpeech = false;
                startMusicIfReady();
                maybeIdle();
            }
        });
    }

    public void stopAll() {
        stopAll(true);
    }

    public void stopAll(final boolean notifyIdle) {
        main.post(new Runnable() {
            @Override
            public void run() {
                speechQueue.clear();
                pendingMusic = null;
                musicPausedBySpeech = false;
                musicPausedByUser = false;
                releaseSpeech();
                releaseMusic();
                idleNotified = true;
                if (notifyIdle) cb.onIdle();
            }
        });
    }

    public boolean isBusy() {
        return musicMp != null || speechMp != null
                || !speechQueue.isEmpty() || pendingMusic != null;
    }

    public boolean isSpeechBusy() {
        return speechMp != null || !speechQueue.isEmpty();
    }

    public boolean hasMusic() {
        return musicMp != null;
    }

    public boolean isMusicPlaying() {
        try {
            return musicMp != null && musicMp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int position() {
        MediaPlayer p = musicMp;
        if (p == null) return 0;
        try {
            return p.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        MediaPlayer p = musicMp;
        if (p == null) return 0;
        try {
            int d = p.getDuration();
            return d > 0 ? d : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(final int ms) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (musicMp != null) musicMp.seekTo(ms);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------------- 音乐 ----------------

    private void startMusicIfReady() {
        if (speechMp != null || !speechQueue.isEmpty()) return;  // 先让语音播完
        if (pendingMusic == null || musicMp != null) return;
        String[] item = pendingMusic;
        pendingMusic = null;
        startMusic(item);
    }

    private void startMusic(final String[] item) {
        musicItem = item;
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(item[1]);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    if (speechMp != null || !speechQueue.isEmpty() || musicPausedByUser) {
                        // 语音正在播 / 用户暂停了：先停在准备好状态，稍后再 start
                        if (speechMp != null || !speechQueue.isEmpty()) musicPausedBySpeech = true;
                        return;
                    }
                    try {
                        player.start();
                    } catch (Exception ignored) {
                    }
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    finishMusic();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    Log.w(TAG, "音乐播放失败 what=" + what + " extra=" + extra
                            + " retried=" + musicRetried);
                    if (!musicRetried) {
                        musicRetried = true;
                        String[] retry = musicItem;
                        releaseMusic();
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (retry != null) startMusic(retry);
                            }
                        }, 700);
                        return true;
                    }
                    cb.onError("what=" + what + " extra=" + extra);
                    finishMusic();
                    return true;
                }
            });
            musicMp = p;
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "音乐 prepare 失败: " + e);
            finishMusic();
        }
    }

    private void finishMusic() {
        String id = musicItem != null ? musicItem[0] : null;
        releaseMusic();
        if (id != null) cb.onDone(id);
        maybeIdle();
    }

    private void releaseMusic() {
        if (musicMp != null) {
            try {
                if (musicMp.isPlaying()) musicMp.stop();
            } catch (Exception ignored) {
            }
            try {
                musicMp.release();
            } catch (Exception ignored) {
            }
            musicMp = null;
        }
        musicItem = null;
        musicPausedBySpeech = false;
    }

    // ---------------- 语音 ----------------

    private void startSpeechNext() {
        if (speechMp != null) return;
        String[] item = speechQueue.poll();
        if (item == null) {
            afterSpeechQueue();
            return;
        }
        // 音乐先让路
        try {
            if (musicMp != null && musicMp.isPlaying()) {
                musicMp.pause();
                musicPausedBySpeech = true;
            }
        } catch (Exception ignored) {
        }

        speechId = item[0];
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(item[1]);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    try {
                        player.start();
                    } catch (Exception ignored) {
                    }
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    finishSpeech();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    Log.w(TAG, "语音播放失败 what=" + what + " extra=" + extra);
                    cb.onError("what=" + what + " extra=" + extra);
                    finishSpeech();
                    return true;
                }
            });
            speechMp = p;
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "语音 prepare 失败: " + e);
            finishSpeech();
        }
    }

    private void finishSpeech() {
        String id = speechId;
        releaseSpeech();
        if (id != null) cb.onDone(id);
        if (!speechQueue.isEmpty()) {
            startSpeechNext();
            return;
        }
        afterSpeechQueue();
    }

    private void releaseSpeech() {
        if (speechMp != null) {
            try {
                if (speechMp.isPlaying()) speechMp.stop();
            } catch (Exception ignored) {
            }
            try {
                speechMp.release();
            } catch (Exception ignored) {
            }
            speechMp = null;
        }
        speechId = null;
    }

    private void afterSpeechQueue() {
        if (musicPausedBySpeech && !musicPausedByUser) {
            try {
                if (musicMp != null && !musicMp.isPlaying()) musicMp.start();
            } catch (Exception ignored) {
            }
        }
        musicPausedBySpeech = false;
        startMusicIfReady();
        maybeIdle();
    }

    private void maybeIdle() {
        if (isBusy()) return;
        if (!idleNotified) {
            idleNotified = true;
            cb.onIdle();
        }
    }
}
