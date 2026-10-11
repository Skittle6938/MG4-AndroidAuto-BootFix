# AA Kick — Android Auto fixes for the MG4

A small app for the **MG4** head unit (SAIC, Android Automotive 9) that fixes two
Android Auto problems:

| Problem | Without AA Kick | With AA Kick |
|---|---|---|
| **Wired phone** plugged in before the car starts | AA doesn't start until you unplug and replug the cable | AA starts on its own |
| **Wireless AA dongle** | The dongle connects to the phone but AA never shows on the car. Only deleting the MG4 from the phone's Bluetooth helps, until next time | AA starts through the dongle, at startup and when you come back to the car |

> 🧪 The wireless dongle fix (since v1.1.0) works well so far but is **still being tested**.
> If something goes wrong, please [open an issue](https://github.com/Skittle6938/MG4-AndroidAuto-BootFix/issues)
> and include `adb logcat -d -s AAKick AABtGuard GalReceiver` if you can.

## Compatibility

| Firmware (SWI) | Status |
|---|---|
| **SWI69** | ✅ Tested |
| SWI131 | 🟡 Very likely (same software base as SWI69, per [MG4Control](https://github.com/SliDeeN/MG4Control)) |
| SWI132 | 🟡 Likely (same base, not tested) |
| SWI133 / SWI68 / SWI165 | ❓ Unknown (different software base) |

Tried it on another firmware? An [issue](https://github.com/Skittle6938/MG4-AndroidAuto-BootFix/issues)
saying whether it works helps everyone.

## Install

Download **[aakick-signed.apk](https://github.com/Skittle6938/MG4-AndroidAuto-BootFix/releases/latest/download/aakick-signed.apk)**, then:

### Option A — USB stick (no computer needed)

1. Copy the APK to a USB stick and plug it into the car.
2. Open the APK with the head unit's built-in file manager and install it.
3. **Open the AA Kick app once** (Bluetooth icon in the app list). Android doesn't run a
   never-opened app at boot.
4. Restart the head unit (hold **Home** ~20 s). Done.

### Option B — ADB

1. **Enable ADB** on the head unit with **ADB_util** by Leon Kerman
   ([XDA thread](https://xdaforums.com/t/mg4-electric-aaos-9-playing-and-possibly-other-mg-models.4697712/post-90591053)).
2. Install the APK:
   ```bash
   adb push aakick-signed.apk /data/local/tmp/aakick.apk
   adb shell pm install -r -f /data/local/tmp/aakick.apk
   ```
3. **First install only:** open the AA Kick app once on the car, or run:
   ```bash
   adb shell am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_BOOT
   ```
4. Restart the head unit (hold **Home** ~20 s). Done.

## Settings

Open the **AA Kick** app (Bluetooth icon in the app list). The screen follows the car's
language (French, otherwise English).

| Setting | Default | What it does |
|---|---|---|
| **Wireless Android Auto dongle fix** | On | Main switch. Off = the car's normal Bluetooth is restored and the fix stays off, also at startup |
| Wait for Android Auto at startup | 180 s | If AA doesn't arrive in time, the car reconnects to the phone over normal Bluetooth. Raise it if AA sometimes misses a cold start |
| Wait for Android Auto when you come back | 90 s | Same, when you come back to a car that stayed on |
| Cut Bluetooth at startup if needed | On | If the car already took the phone at startup, turns its Bluetooth off and on to give the dongle a chance |
| Reconnect hands-free after AA starts | On | If hands-free doesn't come back on its own, the car reconnects it (needed for calls in AA) |
| Wired phone fix (USB) | On | The USB re-detection at startup for a phone plugged in by cable. Independent of the dongle fix |

Delays apply from the next startup or return. **Restore defaults** resets everything but the main switch.

## Uninstall

**Turn the switch off before uninstalling**, otherwise the car won't auto-connect to your
phone over Bluetooth anymore. Then uninstall it (from the car's app settings if your launcher allows it, or
`adb shell pm uninstall com.mg4.aakick`).

Already uninstalled without turning it off? Reinstall, turn the switch off, uninstall again.
Or, with ADB, reset the 3 priorities for your phone (replace the address):

```bash
adb shell settings put global bluetooth_headset_priority_AA:BB:CC:DD:EE:FF -1
adb shell settings put global bluetooth_a2dp_src_priority_AA:BB:CC:DD:EE:FF -1
adb shell settings put global bluetooth_pbap_client_priority_AA:BB:CC:DD:EE:FF -1
```

## Known limitations

- **Turning the phone's Wi-Fi off/on while driving** isn't covered: turn the phone's
  Bluetooth off/on instead (or replug the dongle).
- **Other phones paired to the car** without a dongle connect over Bluetooth up to 180 s
  (at startup) or 90 s (car already on) later than usual (adjustable in the app).

---

## How it works

### Wired phone: USB kick at boot

When the phone is already plugged in at boot, the car's AA detection (`com.saicmotor.caradapter`)
misses it: Android sends no "USB device attached" event for a device that was there before,
and caradapter's one-time check at startup runs too early and never retries.

At boot, AA Kick looks for a phone on USB (skipping hubs, storage, iPhones, the internal
bus and anything already in Android Auto mode), resets it and re-sends the missing
`USB_DEVICE_ATTACHED` event to caradapter. caradapter then switches the phone to Android
Auto mode as if it had just been plugged in. It retries until AA mode is reached, then
stops, and never touches a device already in AA mode.

### Wireless dongle: Bluetooth guard (`BtGuardService`)

**Cause.** When the phone's Bluetooth comes up, the car connects to it first (hands-free)
and the dongle loses its own Bluetooth handshake with the phone. The dongle then shows up
on USB without the phone and answers the car with its own certificate, which the car
rejects and never retries:

```
GalReceiver: Phone reported protocol version 4.0
GalReceiver: Invalid peer certificate name JVC Kenwood
```

A working session shows `Phone reported protocol version 1.7` instead (the real phone
behind the dongle).

**Fix.** AA Kick stops the car from auto-connecting to paired phones over Bluetooth
(profile priorities set to OFF in `Settings.Global`; pairings are kept and a live connection
is never dropped), so the dongle reaches the phone first:

- **Blocking** is saved whenever hands-free is connected, so it is already in place at the
  next startup, even after a hard reset. It is also applied when you leave the car with it
  still on. If the phone is already connected when the app starts, the car's Bluetooth is
  turned off and on.
- **Released** as soon as a real AA session starts. If hands-free doesn't come back on its
  own within 5 s, the car reconnects it (so calls work in AA), then blocks again for next time.
- **Safety net:** without AA after 180 s (startup) or 90 s (phone back), both adjustable, it releases and lets
  the car reconnect over normal Bluetooth.

On a firmware where the AA log line differs, the block only lifts through the safety net:
Bluetooth reconnects later, but nothing breaks.

## Debug

```bash
adb logcat -s AAKick AABtGuard                                                        # app logs (ERROR level, the unit filters DEBUG)
adb shell "dumpsys usb | grep vendor_id"                                              # 6353 (0x18d1) = Android Auto mode reached
adb shell am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_ARM  # block manually (BT_RELEASE to release)
```

No log at all after a reboot means the app was never opened once (Install, step 3).

## Build

Plain Java, no Gradle. The app must be signed with the platform key and runs as the system
user (`sharedUserId="android.uid.system"`). These units use the public **AOSP test platform
key** (`platform.pk8` / `platform.x509.pem` from `build/make/target/product/security`).

Edit the paths at the top of [`build.ps1`](build.ps1), then:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Why a separate app and not a patched caradapter: it is a persistent system app
(`pm install` refuses to update it) and `/system` is protected by dm-verity on these `user` builds.

```
src/com/mg4/aakick/
  MainActivity.java     # app screen: on/off switch for the wireless fix
  BootReceiver.java     # starts the services at boot, saves the BT block at shutdown
  KickService.java      # wired fix: detect → USB reset → re-send USB_DEVICE_ATTACHED
  KickWorker.java       # worker thread for KickService
  BtGuard.java          # saves/restores the Bluetooth profile priorities
  BtGuardService.java   # wireless fix: when to block and release
```

## Disclaimer

Unofficial community fix, provided as-is, without warranty. It only installs a regular app
(nothing in `/system` is modified), but you change your car's software at your own risk.
