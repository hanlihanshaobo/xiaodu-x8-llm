package com.xiaodu.link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启：小度插着电，断电恢复后自动把服务拉起来 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.autoConnect(context)) return;
        if (Prefs.serverHost(context).isEmpty()) return;
        Intent svc = new Intent(context, VoiceService.class);
        svc.setAction(VoiceService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
