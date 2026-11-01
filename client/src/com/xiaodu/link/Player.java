package com.xiaodu.link;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;

/** 播报队列：服务端把一句话合成成 mp3/wav，这里按顺序播，播完回执 */
public class Player {
    private static final String TAG = "XDL/Player";

    public interface Callback {
        void onDone(String id);

        void onIdle();

        void onError(String message);
    }

    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback cb;
    private MediaPlayer mp;
    private String currentId;
    private boolean retried;

    public Player(Callback cb) {
        this.cb = cb;
    }

    public void enqueue(final String id, final String url) {
        main.post(new Runnable() {
            @Override
            public void run() {
                queue.add(new String[]{id, url});
                if (mp == null) playNext();
            }
        });
    }

    public boolean isBusy() {
        return mp != null || !queue.isEmpty();
    }

    public void stopAll() {
        main.post(new Runnable() {
            @Override
            public void run() {
                queue.clear();
                releaseCurrent();
                cb.onIdle();
            }
        });
    }

    public int position() {
        MediaPlayer p = mp;
        if (p == null) return 0;
        try {
            return p.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int duration() {
        MediaPlayer p = mp;
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
                    if (mp != null) mp.seekTo(ms);
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void pause() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mp != null && mp.isPlaying()) mp.pause();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void resume() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mp != null && !mp.isPlaying()) mp.start();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void playNext() {
        if (mp != null) return;
        final String[] item = queue.poll();
        if (item == null) {
            cb.onIdle();
            return;
        }
        currentId = item[0];
        retried = false;
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(item[1]);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    player.start();
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    finish(item[0]);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    Log.w(TAG, "播放失败 what=" + what + " extra=" + extra + " retried=" + retried);
                    if (!retried) {
                        // 第一次失败可能是瞬时网络问题，换个播放器实例重来一次
                        retried = true;
                        releaseCurrent();
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                queue.addFirst(new String[]{item[0], item[1]});
                                playNext();
                            }
                        }, 700);
                        return true;
                    }
                    cb.onError("what=" + what + " extra=" + extra);
                    finish(item[0]);
                    return true;
                }
            });
            mp = p;
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "prepare 失败: " + e);
            finish(item[0]);
        }
    }

    private void finish(String id) {
        releaseCurrent();
        cb.onDone(id);
        playNext();
    }

    private void releaseCurrent() {
        if (mp != null) {
            try {
                if (mp.isPlaying()) mp.stop();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        currentId = null;
    }
}
