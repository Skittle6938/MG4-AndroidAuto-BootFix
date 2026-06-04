package com.mg4.aakick;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;

/**
 * Fix for "phone plugged before boot is not detected by Android Auto".
 *
 * Root cause: when an Android phone is already connected at boot, the SAIC/Allgo
 * stack (com.saicmotor.caradapter / com.allgo.rui RemoteUIService) fails to switch
 * it into AOAP accessory mode, and no fresh USB_DEVICE_ATTACHED is delivered for an
 * already-connected device. A manual unplug/replug fixes it because it forces a
 * fresh USB re-enumeration once the service is ready.
 *
 * This service reproduces that unplug/replug in software via
 * UsbDeviceConnection.resetDevice() (the same API caradapter itself uses), which
 * triggers a kernel-level re-enumeration -> caradapter's now-ready receiver picks
 * the phone up and performs the AOAP switch -> AA launches.
 *
 * Non-destructive: it never touches the AA services, and it never resets a device
 * once any device is already in AOAP mode (i.e. once AA is/was working).
 */
public class KickService extends Service {

    public static final String TAG = "AAKick";

    static final int VID_APPLE   = 0x05ac; // iPhone -> CarPlay path, leave alone
    static final int VID_AOAP    = 0x18d1; // Google / Android Open Accessory
    static final int VID_ROOTHUB = 0x1d6b; // Linux Foundation root hubs

    static final int CLASS_MASS_STORAGE = 8;
    static final int CLASS_HUB          = 9;

    static final long INITIAL_DELAY_MS = 4000L;
    static final long RETRY_DELAY_MS   = 4000L;
    static final long POST_RESET_MS    = 14000L; // max wait for AOAP after a kick (polled, returns early)
    static final long POLL_STEP_MS     = 1000L;
    static final int  MAX_ATTEMPTS     = 15;

    private boolean running = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG, "onStartCommand running=" + running);
        startFg();
        if (!running) {
            running = true;
            try {
                Thread t = new Thread(new KickWorker(this), "aakick");
                t.start();
                Log.e(TAG, "worker thread started");
            } catch (Throwable t) {
                Log.e(TAG, "failed to start worker", t);
            }
        }
        return START_NOT_STICKY;
    }

    private void startFg() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(
                        "aakick", "AA Kick", NotificationManager.IMPORTANCE_MIN);
                if (nm != null) nm.createNotificationChannel(ch);
                Notification n = new Notification.Builder(this, "aakick")
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setContentTitle("AA Kick")
                        .setContentText("Checking USB for Android Auto")
                        .build();
                startForeground(1, n);
            } else {
                startForeground(1, new Notification());
            }
        } catch (Throwable t) {
            Log.e(TAG, "startFg failed", t);
        }
    }

    /** Runs on the worker thread. */
    public void runLoop() {
        Log.e(TAG, "runLoop entered");
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um == null) {
            Log.e(TAG, "no UsbManager");
            return;
        }

        sleep(INITIAL_DELAY_MS);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HashMap<String, UsbDevice> list = um.getDeviceList();

            if (hasAoapDevice(list)) {
                Log.e(TAG, "AOAP device present -> AA active, nothing to do (attempt " + attempt + ")");
                return;
            }

            UsbDevice phone = findCandidatePhone(list);
            if (phone != null) {
                Log.e(TAG, "candidate phone " + phone.getDeviceName()
                        + " vid=0x" + Integer.toHexString(phone.getVendorId())
                        + " pid=0x" + Integer.toHexString(phone.getProductId())
                        + " -> kick (attempt " + attempt + ")");
                // Freshen the phone's USB state (clean re-enumeration), then deliver
                // the USB_DEVICE_ATTACHED that caradapter never got for an
                // already-connected device. We run as uid system, so we are allowed
                // to send this protected broadcast to caradapter's receiver.
                resetDevice(um, phone);
                sleep(1000L);
                sendAttach(phone);
                if (waitForAoap(um, POST_RESET_MS)) {
                    Log.e(TAG, "AOAP mode reached -> done");
                    return;
                }
                continue;
            } else {
                Log.e(TAG, "no candidate phone yet (attempt " + attempt + ")");
            }
            sleep(RETRY_DELAY_MS);
        }
        Log.e(TAG, "giving up after " + MAX_ATTEMPTS + " attempts");
    }

    /** Poll for an AOAP device, returning as soon as one appears (or after maxMs). */
    private boolean waitForAoap(UsbManager um, long maxMs) {
        long waited = 0L;
        while (waited < maxMs) {
            sleep(POLL_STEP_MS);
            waited += POLL_STEP_MS;
            if (hasAoapDevice(um.getDeviceList())) return true;
        }
        return false;
    }

    private boolean hasAoapDevice(HashMap<String, UsbDevice> list) {
        if (list == null) return false;
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == VID_AOAP) return true;
        }
        return false;
    }

    private UsbDevice findCandidatePhone(HashMap<String, UsbDevice> list) {
        if (list == null) return null;
        for (UsbDevice d : list.values()) {
            if (isCandidatePhone(d)) return d;
        }
        return null;
    }

    private boolean isCandidatePhone(UsbDevice d) {
        int vid = d.getVendorId();
        if (vid == VID_ROOTHUB) return false; // root hub
        if (vid == VID_APPLE) return false;   // iPhone -> CarPlay
        if (vid == VID_AOAP) return false;    // already accessory

        // Ignore the internal "gray port" bus (/dev/bus/usb/001/...) which carries
        // the modem / MCU / internal hub, exactly like caradapter does.
        String name = d.getDeviceName();
        if (name != null && name.contains("/001/")) return false;

        // Ignore hubs and mass-storage-only devices.
        try {
            int n = d.getInterfaceCount();
            for (int i = 0; i < n; i++) {
                UsbInterface intf = d.getInterface(i);
                int c = intf.getInterfaceClass();
                if (c == CLASS_HUB) return false;
                if (c == CLASS_MASS_STORAGE) return false;
            }
        } catch (Throwable ignored) {}

        return true;
    }

    private void sendAttach(UsbDevice d) {
        try {
            Intent i = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            i.putExtra(UsbManager.EXTRA_DEVICE, d);
            sendBroadcast(i);
            Log.e(TAG, "sent USB_DEVICE_ATTACHED for " + d.getDeviceName());
        } catch (Throwable t) {
            Log.e(TAG, "sendAttach failed", t);
        }
    }

    private boolean resetDevice(UsbManager um, UsbDevice d) {
        UsbDeviceConnection conn = null;
        try {
            conn = um.openDevice(d);
            if (conn == null) {
                Log.e(TAG, "openDevice returned null (no permission?)");
                return false;
            }
            // UsbDeviceConnection.resetDevice() is @hide in the public SDK, so we
            // call it reflectively. It exists at runtime and platform-signed /
            // system-uid apps are exempt from hidden-API restrictions.
            java.lang.reflect.Method m =
                    UsbDeviceConnection.class.getMethod("resetDevice");
            Object r = m.invoke(conn);
            return (r instanceof Boolean) ? ((Boolean) r).booleanValue() : true;
        } catch (Throwable t) {
            Log.e(TAG, "resetDevice exception", t);
            return false;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Throwable ignored) {}
            }
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
