package com.mg4.aakick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.d(KickService.TAG, "BootReceiver: " + (intent != null ? intent.getAction() : "null"));
        Intent s = new Intent(ctx, KickService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(s);
        } else {
            ctx.startService(s);
        }
    }
}
