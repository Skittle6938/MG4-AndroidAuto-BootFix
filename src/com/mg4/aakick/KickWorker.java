package com.mg4.aakick;

import android.util.Log;

/** Worker thread body, kept as a named class to avoid anonymous-class dexing issues. */
public class KickWorker implements Runnable {

    private final KickService svc;

    public KickWorker(KickService svc) {
        this.svc = svc;
    }

    @Override
    public void run() {
        try {
            svc.runLoop();
        } catch (Throwable t) {
            Log.e(KickService.TAG, "loop crashed", t);
        } finally {
            try { svc.stopForeground(true); } catch (Throwable ignored) {}
            svc.stopSelf();
        }
    }
}
