package com.mg4.aakick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(KickService.TAG, "BootReceiver: " + action);
        if (Intent.ACTION_SHUTDOWN.equals(action)) {
            // Arm before power-off so the head unit won't race the dongle for the phone at next boot.
            BtGuard.arm(ctx, "shutdown");
            return;
        }
        Intent s = new Intent(ctx, KickService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(s);
        } else {
            ctx.startService(s);
        }
        ctx.startService(new Intent(ctx, BtGuardService.class).setAction(BtGuardService.ACTION_BOOT));
    }
}
