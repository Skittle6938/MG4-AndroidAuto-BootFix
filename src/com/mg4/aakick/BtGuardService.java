package com.mg4.aakick;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wireless dongle fix: lets the AA dongle reach the phone before the head unit does.
 *
 * Root cause: when the phone's Bluetooth comes up, the head unit (bonded, HFP client)
 * connects to it first and the dongle's own Bluetooth handshake with the phone loses the
 * race. The dongle then enumerates without the phone and answers the head unit with its own
 * "JVC Kenwood" certificate (protocol 4.0), which the Allgo stack rejects
 * ("Invalid peer certificate name JVC Kenwood") and never retries.
 *
 * - Armed (head unit may not auto-connect to the phone): stored whenever hands-free is
 *   connected (the live link is kept), on shutdown, at boot, and when the phone leaves the car
 *   (hands-free gone with no AA session; a Wi-Fi drop alone doesn't count). Because it is
 *   stored while connected, it is already in place at the next boot, even after a hard reset.
 * - Released: as soon as a real AA session starts (AA then re-pairs hands-free itself), while
 *   hands-free reconnects during an AA session, or 90 s after boot / after the phone comes
 *   back without AA, in which case Bluetooth is cycled so the head unit reconnects normally.
 *
 * Manual control:
 *   am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_ARM
 *   am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_RELEASE
 */
public class BtGuardService extends Service implements Handler.Callback {
    static final String ACTION_BOOT = "com.mg4.aakick.BT_BOOT";
    static final String ACTION_ARM = "com.mg4.aakick.BT_ARM";
    static final String ACTION_RELEASE = "com.mg4.aakick.BT_RELEASE";

    private static final long TIMEOUT_MS = 90_000;
    private static final int HEADSET_CLIENT = 16; // BluetoothProfile.HEADSET_CLIENT (@hide)
    private static final String HFP_CLIENT_STATE =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";

    // Allgo AA stack log lines: real session established (not the dongle alone) / session ended.
    static final String AA_UP = "Service discovery callback called";
    static final String AA_DOWN = "Stopping GAL";

    static final int MSG_AA_UP = 1;
    static final int MSG_AA_DOWN = 2;
    private static final int MSG_TIMEOUT = 3;
    private static final int MSG_CYCLE_DONE = 4;
    private static final int MSG_HFP_CHECK = 5;
    private static final long HFP_CHECK_MS = 5_000;

    private final Handler h = new Handler(Looper.getMainLooper(), this);
    private final Events events = new Events(this);
    private LogWatcher watcher;
    private boolean started;
    private boolean aaActive;
    private boolean cycling;

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(HFP_CLIENT_STATE);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        registerReceiver(events, f);
        watcher = new LogWatcher(h);
        watcher.start();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(events);
        if (watcher != null) watcher.kill();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i != null ? i.getAction() : null;
        if (ACTION_ARM.equals(a)) {
            BtGuard.arm(this, "manual");
        } else if (ACTION_RELEASE.equals(a)) {
            release("manual", false);
        } else if (i == null) {
            started = true; // restarted by the system after a kill: just resume watching
        } else if (!started) {
            started = true;
            BtGuard.arm(this, "boot");
            if (hfpConnected()) {
                // The head unit already took the phone: drop it so the dongle gets a chance.
                Log.e(BtGuard.TAG, "phone already connected to head unit, cycling Bluetooth");
                cycleBluetooth();
            }
            h.sendEmptyMessageDelayed(MSG_TIMEOUT, TIMEOUT_MS);
        }
        return START_STICKY;
    }

    @Override
    public boolean handleMessage(Message m) {
        switch (m.what) {
            case MSG_AA_UP:
                aaActive = true;
                release("aa session", false);
                if (hfpConnected()) BtGuard.arm(this, "hfp already connected");
                else h.sendEmptyMessageDelayed(MSG_HFP_CHECK, HFP_CHECK_MS);
                return true;
            case MSG_HFP_CHECK:
                // The phone gave up on hands-free while we were blocking it and doesn't retry:
                // connect it from the head unit side so calls work in AA.
                if (aaActive && !hfpConnected()) connectHfp();
                return true;
            case MSG_AA_DOWN:
                aaActive = false;
                maybeArmPhoneLeft();
                return true;
            case MSG_TIMEOUT:
                Log.e(BtGuard.TAG, "no AA session after " + TIMEOUT_MS / 1000 + "s");
                release("timeout", true);
                return true;
            case MSG_CYCLE_DONE:
                cycling = false;
                return true;
        }
        return false;
    }

    void onHfpState(int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) {
            // Keep the block stored while connected (it doesn't drop the live link), so it is
            // already in place at the next boot however the head unit gets powered off.
            BtGuard.arm(this, "hfp connected");
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            if (aaActive && !cycling) {
                // Hands-free dropped during an AA session: let the phone reconnect it.
                BtGuard.release(this, "hfp lost during aa");
            } else {
                maybeArmPhoneLeft();
            }
        }
    }

    void onAclConnected(BluetoothDevice d) {
        // The phone is back while we block: give it 90 s to come up through the dongle.
        if (BtGuard.isArmed(this) && !h.hasMessages(MSG_TIMEOUT) && d.getBondState() == BluetoothDevice.BOND_BONDED) {
            Log.e(BtGuard.TAG, "device back while armed: " + d.getAddress());
            h.sendEmptyMessageDelayed(MSG_TIMEOUT, TIMEOUT_MS);
        }
    }

    /** Phone left: no hands-free and no AA session (a Wi-Fi drop alone keeps hands-free up). */
    private void maybeArmPhoneLeft() {
        if (aaActive || BtGuard.isArmed(this) || hfpConnected()) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || ad.getState() != BluetoothAdapter.STATE_ON) return;
        h.removeMessages(MSG_TIMEOUT);
        BtGuard.arm(this, "phone left");
    }

    private void release(String why, boolean reconnect) {
        h.removeMessages(MSG_TIMEOUT);
        boolean wasArmed = BtGuard.isArmed(this);
        BtGuard.release(this, why);
        // Cycling Bluetooth makes the head unit run its normal auto-connect to the phone.
        if (reconnect && wasArmed && !hfpConnected()) cycleBluetooth();
    }

    private static boolean hfpConnected() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        return ad != null && ad.getProfileConnectionState(HEADSET_CLIENT) != BluetoothProfile.STATE_DISCONNECTED;
    }

    private void connectHfp() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) return;
        ad.getProfileProxy(this, new HfpConnector(ad), HEADSET_CLIENT);
    }

    /** Asks the HFP client (@hide BluetoothHeadsetClient) to connect each bonded device. */
    static final class HfpConnector implements BluetoothProfile.ServiceListener {
        private final BluetoothAdapter ad;

        HfpConnector(BluetoothAdapter ad) { this.ad = ad; }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            try {
                java.lang.reflect.Method connect = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    Object ok = connect.invoke(proxy, d);
                    Log.e(BtGuard.TAG, "hfp connect " + d.getAddress() + " -> " + ok);
                }
            } catch (Throwable t) {
                Log.e(BtGuard.TAG, "hfp connect failed", t);
            } finally {
                ad.closeProfileProxy(profile, proxy);
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {}
    }

    private void cycleBluetooth() {
        cycling = true;
        new BtCycler(h).start();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    static final class Events extends BroadcastReceiver {
        private final BtGuardService s;

        Events(BtGuardService s) { this.s = s; }

        @Override
        public void onReceive(Context c, Intent i) {
            if (HFP_CLIENT_STATE.equals(i.getAction())) {
                s.onHfpState(i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) s.onAclConnected(d);
            }
        }
    }

    /** Follows the AA stack's logcat to know when a session starts/stops. */
    static final class LogWatcher extends Thread {
        private final Handler target;
        private volatile Process proc;
        private volatile boolean killed;

        LogWatcher(Handler target) {
            super("aakick-btguard-logcat");
            this.target = target;
        }

        void kill() {
            killed = true;
            Process p = proc;
            if (p != null) p.destroy();
        }

        @Override
        public void run() {
            while (!killed) {
                try {
                    // Only read lines from now on (never an old session).
                    String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                    proc = new ProcessBuilder("logcat", "-v", "brief", "-T", since,
                            "CC_CTRL:I", "AAP_SESSION:I", "*:S").redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains(AA_UP)) target.sendEmptyMessage(MSG_AA_UP);
                        else if (line.contains(AA_DOWN)) target.sendEmptyMessage(MSG_AA_DOWN);
                    }
                } catch (Exception e) {
                    Log.e(BtGuard.TAG, "logcat watcher", e);
                }
                if (!killed) pause(2000);
            }
        }
    }

    /** Turns the head unit's Bluetooth off and back on. */
    static final class BtCycler extends Thread {
        private final Handler target;

        BtCycler(Handler target) {
            super("aakick-btguard-cycle");
            this.target = target;
        }

        @Override
        public void run() {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.isEnabled()) {
                ad.disable();
                for (int n = 0; n < 50 && ad.getState() != BluetoothAdapter.STATE_OFF; n++) pause(200);
                pause(1000);
                ad.enable();
                for (int n = 0; n < 50 && ad.getState() != BluetoothAdapter.STATE_ON; n++) pause(200);
                pause(5000); // let the restart's own disconnect/reconnect events pass
                Log.e(BtGuard.TAG, "Bluetooth cycled");
            }
            target.sendEmptyMessage(MSG_CYCLE_DONE);
        }
    }

    static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
