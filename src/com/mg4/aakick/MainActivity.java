package com.mg4.aakick;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * App screen (launcher icon): on/off switch for the wireless dongle fix plus its settings.
 * Turning the fix off restores the car's normal Bluetooth, so the app can then be uninstalled
 * without ADB. Opening this screen once also takes a freshly installed app out of Android's
 * "stopped" state, so it receives the boot event.
 *
 * (No anonymous classes: the activity itself is the listener, rows are told apart by tag.)
 */
public class MainActivity extends Activity
        implements CompoundButton.OnCheckedChangeListener, SeekBar.OnSeekBarChangeListener, View.OnClickListener {

    private static boolean FR; // set on each build() so a language change is picked up

    // Delay sliders: seconds = MIN + progress * STEP.
    private static final int STEP_S = 10;
    private static final int BOOT_MIN_S = 30, BOOT_MAX_S = 600;
    private static final int RETURN_MIN_S = 30, RETURN_MAX_S = 300;

    private static final String TAG_ENABLED = "enabled";

    private int pad;
    private LinearLayout root;
    private TextView status;
    private Switch master;
    private final java.util.List<View> details = new java.util.ArrayList<>();

    private static String t(String fr, String en) { return FR ? fr : en; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pad = (int) (24 * getResources().getDisplayMetrics().density);
        build();
    }

    private void build() {
        FR = "fr".equals(Locale.getDefault().getLanguage());
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        details.clear();

        TextView title = new TextView(this);
        title.setText("AA Kick");
        title.setTextSize(30);
        root.addView(title);

        master = addSwitch(TAG_ENABLED, BtGuard.isEnabled(this),
                t("Correctif dongle Android Auto sans fil", "Wireless Android Auto dongle fix"),
                t("Empêche la voiture de se connecter au téléphone en Bluetooth avant le dongle. "
                        + "⚠️ À désactiver AVANT de désinstaller l'appli, sinon la voiture ne se "
                        + "reconnectera plus toute seule à votre téléphone.",
                  "Stops the car from connecting to the phone over Bluetooth before the dongle. "
                        + "⚠️ Turn it off BEFORE uninstalling the app, otherwise the car won't "
                        + "auto-connect to your phone anymore."), false);
        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        addSeek(BtGuard.S_BOOT_WAIT, BtGuardService.DEF_BOOT_WAIT_S, BOOT_MIN_S, BOOT_MAX_S,
                t("Attente d'Android Auto au démarrage", "Wait for Android Auto at startup"),
                t("Si AA n'arrive pas dans ce délai, la voiture se reconnecte au téléphone en "
                        + "Bluetooth normal. Augmentez-le si AA rate parfois au démarrage à froid.",
                  "If AA doesn't arrive in time, the car reconnects to the phone over normal "
                        + "Bluetooth. Raise it if AA sometimes misses a cold start."));
        addSeek(BtGuard.S_RETURN_WAIT, BtGuardService.DEF_RETURN_WAIT_S, RETURN_MIN_S, RETURN_MAX_S,
                t("Attente d'Android Auto à votre retour", "Wait for Android Auto when you come back"),
                t("Même chose quand vous revenez à la voiture restée allumée.",
                  "Same, when you come back to a car that stayed on."));
        addSwitch(BtGuard.S_CYCLE_AT_BOOT, BtGuard.getBool(this, BtGuard.S_CYCLE_AT_BOOT, true),
                t("Couper le Bluetooth au démarrage si besoin", "Cut Bluetooth at startup if needed"),
                t("Si la voiture a déjà pris le téléphone au démarrage, coupe et rallume son "
                        + "Bluetooth pour laisser la place au dongle.",
                  "If the car already took the phone at startup, turns its Bluetooth off and on "
                        + "to give the dongle a chance."), true);
        addSwitch(BtGuard.S_HFP_RECONNECT, BtGuard.getBool(this, BtGuard.S_HFP_RECONNECT, true),
                t("Reconnecter le mains libres après AA", "Reconnect hands-free after AA starts"),
                t("Si le mains libres ne revient pas tout seul quand AA démarre, la voiture le "
                        + "reconnecte (nécessaire pour les appels dans AA).",
                  "If hands-free doesn't come back on its own when AA starts, the car reconnects "
                        + "it (needed for calls in AA)."), true);
        addSwitch(BtGuard.S_USB_KICK, BtGuard.getBool(this, BtGuard.S_USB_KICK, true),
                t("Correctif téléphone filaire (USB)", "Wired phone fix (USB)"),
                t("Au démarrage, relance la détection d'un téléphone branché en USB pour qu'AA "
                        + "démarre sans débrancher/rebrancher. Indépendant du dongle.",
                  "At startup, re-triggers detection of a USB-connected phone so AA starts "
                        + "without unplugging/replugging. Independent of the dongle fix."), false);

        Button reset = new Button(this);
        reset.setText(t("Réglages par défaut", "Restore defaults"));
        reset.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        root.addView(reset, lp);

        TextView note = new TextView(this);
        note.setTextSize(14);
        note.setPadding(0, pad / 2, 0, 0);
        note.setText(t("Les délais s'appliquent au prochain démarrage ou retour.",
                "Delays apply from the next startup or return."));
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        showStatus(master.isChecked());
    }

    private Switch addSwitch(String tag, boolean on, String label, String help, boolean detail) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(20);
        sw.setTag(tag);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(this);
        sw.setPadding(0, pad, 0, 0);
        root.addView(sw);
        TextView h = helpText(help);
        root.addView(h);
        if (detail) { details.add(sw); details.add(h); }
        return sw;
    }

    private void addSeek(String key, int def, int min, int max, String label, String help) {
        int v = BtGuard.getInt(this, key, def);
        TextView l = new TextView(this);
        l.setTextSize(20);
        l.setPadding(0, pad, 0, 0);
        root.addView(l);
        SeekBar sb = new SeekBar(this);
        sb.setMax((max - min) / STEP_S);
        sb.setProgress((Math.max(min, Math.min(max, v)) - min) / STEP_S);
        sb.setTag(new Object[]{key, min, l, label});
        sb.setOnSeekBarChangeListener(this);
        root.addView(sb);
        TextView h = helpText(help);
        root.addView(h);
        details.add(l); details.add(sb); details.add(h);
        showSeek(l, label, v);
    }

    private TextView helpText(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextSize(14);
        h.setAlpha(0.7f);
        return h;
    }

    private static void showSeek(TextView l, String label, int s) {
        l.setText(label + " : " + s + " s");
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean on) {
        String tag = (String) b.getTag();
        if (TAG_ENABLED.equals(tag)) {
            startService(new Intent(this, BtGuardService.class)
                    .setAction(on ? BtGuardService.ACTION_ENABLE : BtGuardService.ACTION_DISABLE));
            showStatus(on);
        } else {
            BtGuard.put(this, tag, on);
        }
    }

    @Override
    public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
        Object[] tag = (Object[]) sb.getTag();
        int s = (Integer) tag[1] + p * STEP_S;
        showSeek((TextView) tag[2], (String) tag[3], s);
        if (fromUser) BtGuard.put(this, (String) tag[0], s);
    }

    @Override public void onStartTrackingTouch(SeekBar sb) {}
    @Override public void onStopTrackingTouch(SeekBar sb) {}

    @Override
    public void onClick(View v) {
        // Restore defaults (the main on/off switch is left as is).
        BtGuard.put(this, BtGuard.S_BOOT_WAIT, BtGuardService.DEF_BOOT_WAIT_S);
        BtGuard.put(this, BtGuard.S_RETURN_WAIT, BtGuardService.DEF_RETURN_WAIT_S);
        BtGuard.put(this, BtGuard.S_CYCLE_AT_BOOT, true);
        BtGuard.put(this, BtGuard.S_HFP_RECONNECT, true);
        BtGuard.put(this, BtGuard.S_USB_KICK, true);
        build();
    }

    private void showStatus(boolean on) {
        status.setText(on
                ? t("Activé", "On")
                : t("Désactivé : Bluetooth normal rétabli. Vous pouvez désinstaller l'application.",
                    "Off: normal Bluetooth restored. You can uninstall the app."));
        // Dongle-fix settings only matter while the fix is on.
        for (View d : details) d.setAlpha(on ? 1f : 0.4f);
    }
}
