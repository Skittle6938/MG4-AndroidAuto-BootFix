package com.mg4.aakick;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Blocks the head unit's Bluetooth auto-connect to the phone (client profile priorities set
 * to OFF) so a wireless Android Auto dongle can grab the phone first, then restores them.
 * Pairings are never removed.
 */
final class BtGuard {
    static final String TAG = "AABtGuard";

    // Settings.Global keys read by HeadsetClient, A2dpSink and PbapClient on Android 9.
    private static final String[] KEYS = {
            "bluetooth_headset_priority_",
            "bluetooth_a2dp_src_priority_",
            "bluetooth_pbap_client_priority_",
    };
    private static final int PRIORITY_OFF = 0;
    private static final int PRIORITY_UNDEFINED = -1;

    private BtGuard() {}

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("btguard", Context.MODE_PRIVATE);
    }

    /** Known addresses: currently bonded devices plus the ones remembered earlier. */
    private static Set<String> devices(Context c) {
        SharedPreferences p = prefs(c);
        Set<String> macs = new HashSet<>(p.getStringSet("macs", new HashSet<String>()));
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null) {
            try {
                for (BluetoothDevice d : a.getBondedDevices()) macs.add(d.getAddress());
            } catch (Exception e) {
                Log.w(TAG, "getBondedDevices", e);
            }
        }
        p.edit().putStringSet("macs", macs).commit();
        return macs;
    }

    static boolean isArmed(Context c) {
        return prefs(c).getBoolean("armed", false);
    }

    /** Wireless dongle fix switch (in-app toggle). On by default. */
    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).commit();
        Log.e(TAG, on ? "enabled" : "disabled");
    }

    /** Sets the client profiles to OFF, remembering the original values if not already armed. */
    static synchronized void arm(Context c, String why) {
        if (!isEnabled(c)) return;
        ContentResolver cr = c.getContentResolver();
        SharedPreferences p = prefs(c);
        boolean already = p.getBoolean("armed", false);
        SharedPreferences.Editor e = p.edit();
        for (String mac : devices(c)) {
            for (String k : KEYS) {
                String key = k + mac;
                if (!already) e.putInt("orig_" + key, Settings.Global.getInt(cr, key, PRIORITY_UNDEFINED));
                Settings.Global.putInt(cr, key, PRIORITY_OFF);
            }
        }
        e.putBoolean("armed", true).commit();
        Log.e(TAG, "armed (" + why + ")");
    }

    /** Restores the original priorities. */
    static synchronized void release(Context c, String why) {
        ContentResolver cr = c.getContentResolver();
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("armed", false)) return;
        for (String mac : devices(c)) {
            for (String k : KEYS) {
                String key = k + mac;
                Settings.Global.putInt(cr, key, p.getInt("orig_" + key, PRIORITY_UNDEFINED));
            }
        }
        p.edit().putBoolean("armed", false).commit();
        Log.e(TAG, "released (" + why + ")");
    }
}
