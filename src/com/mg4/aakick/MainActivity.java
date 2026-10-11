package com.mg4.aakick;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * Settings screen (launcher icon): an on/off switch for the wireless dongle fix.
 * Turning it off restores the car's normal Bluetooth, so the app can then be uninstalled
 * without ADB. Opening this screen once also takes a freshly installed app out of Android's
 * "stopped" state, so it receives the boot event.
 */
public class MainActivity extends Activity implements CompoundButton.OnCheckedChangeListener {

    private static final boolean FR = "fr".equals(Locale.getDefault().getLanguage());

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AA Kick");
        title.setTextSize(32);
        root.addView(title);

        Switch sw = new Switch(this);
        sw.setText(FR ? "Correctif dongle Android Auto sans fil" : "Wireless Android Auto dongle fix");
        sw.setTextSize(22);
        sw.setPadding(0, pad, 0, pad / 2);
        sw.setChecked(BtGuard.isEnabled(this));
        sw.setOnCheckedChangeListener(this);
        root.addView(sw);

        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);

        TextView help = new TextView(this);
        help.setTextSize(16);
        help.setPadding(0, pad, 0, 0);
        help.setText(FR
                ? "Empêche la voiture de se connecter au téléphone en Bluetooth avant le dongle, "
                + "pour qu'Android Auto démarre.\n\n"
                + "⚠️ Désactivez-le AVANT de désinstaller l'application, sinon la voiture ne "
                + "se reconnectera plus toute seule à votre téléphone en Bluetooth.\n\n"
                + "Le correctif USB pour téléphone filaire reste actif dans tous les cas."
                : "Stops the car from connecting to the phone over Bluetooth before the dongle "
                + "does, so Android Auto starts.\n\n"
                + "⚠️ Turn it off BEFORE uninstalling the app, otherwise the car won't "
                + "auto-connect to your phone over Bluetooth anymore.\n\n"
                + "The wired-phone USB fix stays active either way.");
        root.addView(help);

        root.setGravity(Gravity.TOP);
        setContentView(root);
        showStatus(sw.isChecked());
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean on) {
        startService(new Intent(this, BtGuardService.class)
                .setAction(on ? BtGuardService.ACTION_ENABLE : BtGuardService.ACTION_DISABLE));
        showStatus(on);
    }

    private void showStatus(boolean on) {
        if (on) {
            status.setText(FR ? "Activé" : "On");
        } else {
            status.setText(FR
                    ? "Désactivé : Bluetooth normal rétabli. Vous pouvez désinstaller l'application."
                    : "Off: normal Bluetooth restored. You can uninstall the app.");
        }
    }
}
