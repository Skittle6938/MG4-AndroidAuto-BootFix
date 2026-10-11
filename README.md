# MG4 Android Auto Boot Fix (AA Kick)

A tiny companion app for the **MG4** (SAIC AOSP head unit) that fixes a long‑standing
annoyance: when your phone is **already plugged in by USB before the head unit boots**,
wired Android Auto often **fails to start**, and you have to physically **unplug and
re‑plug** the cable to get it working.

`com.mg4.aakick` makes that unplug/re‑plug happen automatically, in software, at boot.

Since **v1.1.0** it also fixes **wireless Android Auto dongles** that connect to the phone but
never start AA on the car, because the head unit grabs the phone over Bluetooth first
(see [Wireless dongle fix](#wireless-dongle-fix-btguard)).

> Tested on an MG4 (EH32 platform, AOSP Automotive 9, MediaTek) with a wired Android phone,
> and with an Android phone + wireless AA dongle. Other SAIC cars using the same
> Allgo/`caradapter` stack (MG/Roewe) may benefit too.

> 🧪 **The wireless dongle fix seems to work perfectly so far, but it is still being tested.**
> If you run into a problem (AA not starting, phone not reconnecting over Bluetooth…),
> please [open an issue](https://github.com/Skittle6938/MG4-AndroidAuto-BootFix/issues)
> with what happened and, if you can, the output of `adb logcat -d -s AAKick AABtGuard`.

## Scope & limitations

- ✅ **Works for a USB (wired) Android Auto phone** — a phone connected by cable that the
  head unit fails to pick up. AA Kick re-enumerates it and re-triggers detection so the
  AOAP switch happens and Android Auto starts.
- ✅ **Wireless Android Auto dongles: fixes the Bluetooth race** (`BtGuardService`, see
  [Wireless dongle fix](#wireless-dongle-fix-btguard)). When the head unit grabs the phone
  over Bluetooth before the dongle does, AA never starts; AA Kick now keeps the head unit
  out of the way until the dongle has the phone, at boot and when you come back to the car.
- ❌ **Not covered: toggling the phone's Wi-Fi while driving.** The dongle only gets the phone
  back when the phone's own Bluetooth restarts; nothing on the head unit side can trigger
  that. Toggle the phone's Bluetooth off/on (or replug the dongle).

---

## The problem

On this head unit, wired Android Auto uses **AOAP** (Android Open Accessory Protocol):
the phone first enumerates as a normal USB device (MTP/charging), then the car asks it
to **switch into accessory mode**, after which Android Auto starts.

The detection lives in the system app **`com.saicmotor.caradapter`**
(`com.allgo.rui.RemoteUIService` / `RemoteUIDeviceListener`). It works in two ways:

1. A **broadcast receiver** for `USB_DEVICE_ATTACHED` — handles phones plugged in *after*
   the service is running.
2. A one‑shot **`checkConnectedDevices()`** at service start — meant to handle phones that
   are *already connected* at boot.

When the phone is connected **before** boot, two things go wrong:

- Android never delivers a `USB_DEVICE_ATTACHED` broadcast for a device that was already
  attached before the receiver registered, so path (1) never fires.
- The one‑shot `checkConnectedDevices()` runs too early during init — the AA services it
  depends on are still being bound — so the AOAP switch fails. There is **no retry**.

Result: the phone sits in normal USB mode forever, Android Auto never launches.
A manual unplug/re‑plug works because it delivers a fresh `USB_DEVICE_ATTACHED` *after*
everything is ready.

This was confirmed live: force‑stopping the (persistent) `caradapter` so it re‑runs
`checkConnectedDevices()` with the phone connected still does **not** bring up AA.

## Why not just patch `caradapter`?

You can't, without rooting the unit:

- `caradapter` is a **persistent** system app, so `pm install` refuses to update it
  (*"Persistent apps are not updateable"*).
- Overwriting it in `/system/priv-app` is blocked: the unit ships a **`user`** build with
  **dm‑verity `enforcing`** and a locked, verified boot — modifying `/system` breaks boot.

So the fix has to live **outside** `caradapter`, as a separate app.

## How AA Kick works

AA Kick is a small, **non‑destructive** service. On `BOOT_COMPLETED` it:

1. Waits a few seconds, then looks at `UsbManager.getDeviceList()` for a **candidate phone**
   — skipping USB root hubs, hubs, mass‑storage devices, Apple devices (CarPlay), anything
   already in AOAP mode, and the car's internal **"gray port"** bus (`/dev/bus/usb/001/…`,
   which carries the modem/MCU) exactly like `caradapter` does.
2. Calls **`UsbDeviceConnection.resetDevice()`** on it (the same API `caradapter` itself
   uses) to give the phone a clean USB re‑enumeration.
3. **Re‑sends the `USB_DEVICE_ATTACHED` broadcast** (with the `UsbDevice` extra) to
   `caradapter`'s receiver — the event Android never delivered for the already‑connected
   phone. Because the app runs as the **system** user, it is allowed to send this protected
   broadcast.
4. `caradapter` (now fully initialised) performs the AOAP switch → the phone re‑enumerates
   as an accessory (vendor id `0x18d1`) → **Android Auto launches**.

It retries until a device reaches AOAP mode, then **stops**. It **never** touches the AA
services themselves, and it **never** acts once any device is already in accessory mode, so
it can't disturb a working session. It only runs at boot.

> Note: `resetDevice()` *alone* is not enough — a USB reset does not generate the
> remove/add events that `caradapter`'s receiver listens for. The re‑sent broadcast is the
> key part; the reset just freshens the phone's state first.

### Why this is **not** the "AA boot‑fix watchdog" antipattern

A naive fix force‑stops the Android Auto services on a timer hoping they'll re‑detect the
phone. That *causes* failures — it interrupts the stock session setup. AA Kick does the
opposite: it leaves the services alone and simply delivers the one USB event they were
waiting for.

## Requirements

- An MG4 / SAIC head unit using the Allgo `com.saicmotor.caradapter` stack.
- The app must be **signed with the platform key** and declares
  `sharedUserId="android.uid.system"` (needed to send the protected USB broadcast and to
  open the USB device). These units use the standard public **AOSP test/dev platform key**
  (`android@android.com`), so the test keys (`platform.pk8` / `platform.x509.pem` from
  `build/make/target/product/security` in AOSP) work.
- ADB access to the head unit.

## Build

No Gradle required — it's three small Java files. Using the Android SDK command‑line tools
(JDK 11+ for `d8`, e.g. the JBR shipped with Android Studio):

```powershell
# adjust paths to your SDK / JDK
$JDK  = "C:\Program Files\Android\Android Studio\jbr\bin"
$BT   = "$env:LOCALAPPDATA\Android\Sdk\build-tools\34.0.0"
$AJAR = "$env:LOCALAPPDATA\Android\Sdk\platforms\android-28\android.jar"

# 1. compile + dex
& "$JDK\javac.exe" -source 11 -target 11 -classpath $AJAR -d out (Get-ChildItem src -Recurse -Filter *.java).FullName
& "$JDK\java.exe" -cp "$BT\lib\d8.jar" com.android.tools.r8.D8 --min-api 28 --lib $AJAR --output out (Get-ChildItem out -Recurse -Filter *.class).FullName

# 2. package (manifest only, no resources) + add dex
& "$BT\aapt2.exe" link -I $AJAR --manifest AndroidManifest.xml --min-sdk-version 28 --target-sdk-version 28 -o out\base.apk
Copy-Item out\classes.dex .\classes.dex -Force
& "$JDK\jar.exe" uf out\base.apk classes.dex
& "$BT\zipalign.exe" -f 4 out\base.apk out\aakick-aligned.apk

# 3. sign with the platform key
& "$BT\apksigner.bat" sign --key platform.pk8 --cert platform.x509.pem --out aakick-signed.apk out\aakick-aligned.apk
```

See [`build.ps1`](build.ps1) for a ready‑to‑run script (edit the paths at the top).

## Install

### Option A — Direct APK install (recommended)

1. Download **[aakick-signed.apk](https://github.com/Skittle6938/MG4-AndroidAuto-BootFix/raw/main/apk/aakick-signed.apk)**
2. Copy it to a USB stick and plug it into the head unit's USB port
3. Open the APK with the built-in file manager to install it

> After installing, **restart the car (or the head unit)** with your phone plugged in —
> Android Auto should then start on its own. To restart the unit, hold the Home button for
> ~20 seconds until the screen turns off and it reboots.

### Option B — ADB over USB

ADB is disabled by default on the MG4 head unit. You need to enable it first using
**ADB_util**, a tool developed by Leon Kerman:

> [XDA thread — MG4 Electric AAOS 9 playing (and possibly other MG models)](https://xdaforums.com/t/mg4-electric-aaos-9-playing-and-possibly-other-mg-models.4697712/post-90591053)

Once USB debugging is enabled:

```bash
adb push aakick-signed.apk /data/local/tmp/aakick.apk
adb shell pm install -r -f /data/local/tmp/aakick.apk
```

The app is a normal (non‑persistent) package installed into `/data/app`, so this works
without root. It takes effect on the next boot — power‑cycle the car with the phone plugged
in and Android Auto should start on its own.

> ⚠️ **After a fresh install (or uninstall + reinstall), start the app once.** It has no
> launcher icon, and Android keeps a never‑launched app in the "stopped" state, in which it
> does **not** receive the boot event. Run this once (an in‑place update doesn't need it):
>
> ```bash
> adb shell am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_BOOT
> ```
>
> If after a reboot `adb logcat -d -s AAKick AABtGuard` shows nothing, this is why.

## Wireless dongle fix (BtGuard)

### The problem

With a wireless AA dongle, Android Auto regularly fails to start: the dongle connects to the
phone but nothing shows up on the car. Deleting the MG4 from the phone's Bluetooth devices
fixes it, until AA re-pairs the car and it happens again.

What the head unit logs show:

- When the phone's Bluetooth comes up, the head unit (bonded, HFP client) connects to it
  **first**, and the dongle loses its own Bluetooth handshake with the phone.
- The dongle then enumerates on USB **without the phone** and answers the head unit itself,
  with protocol 4.0 and a **"JVC Kenwood"** certificate:
  `GalReceiver: Invalid peer certificate name JVC Kenwood` → `Shutting down connection due to auth failure.`
  The Allgo stack never retries on that USB device.
- A good session looks like `Phone reported protocol version 1.7` (the real phone behind the dongle).
- Cutting the head unit's Bluetooth before the phone's Bluetooth restarts lets the dongle win
  and AA comes up — confirmed by hand before writing the fix.

### How BtGuard works

`BtGuardService` sets the head unit's Bluetooth **client profile priorities** for bonded
phones (`bluetooth_headset_priority_*`, `bluetooth_a2dp_src_priority_*`,
`bluetooth_pbap_client_priority_*` in `Settings.Global`) to **OFF**. That stops automatic
(re)connections without touching pairings or dropping a live link.

- **Armed** while hands‑free is connected (so it is already in place at the next boot, even
  after a hard reset), at shutdown and at boot, and when the phone leaves the car (hands‑free
  gone with no AA session — a Wi‑Fi drop alone doesn't count). If the phone is already
  connected when the service starts at boot, the head unit's Bluetooth is cycled.
- **Released** as soon as a real AA session starts (detected from the AA stack's
  `Service discovery callback called` log line). If hands‑free doesn't come back on its own
  within 5 s, the head unit connects it, then re‑arms.
- **Safety net:** if no AA session starts within 180 s of boot (cold starts can be slow) or 90 s of the phone coming back
  (no dongle, AA disabled…), it releases and cycles the head unit's Bluetooth so the phone
  reconnects normally.

Manual control:

```powershell
adb shell am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_ARM
adb shell am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_RELEASE
```

## Verify / debug

The head unit **filters DEBUG‑level logcat**, so AA Kick logs at ERROR level:

```powershell
adb logcat -s AAKick AABtGuard
```

Ground truth for the AOAP switch (vendor id `6353` = `0x18d1` means accessory mode reached):

```powershell
adb shell "dumpsys usb | grep vendor_id"
```

## Uninstall / revert

```powershell
adb shell am startservice -n com.mg4.aakick/.BtGuardService -a com.mg4.aakick.BT_RELEASE
adb shell pm uninstall com.mg4.aakick
```

Release BtGuard first: otherwise the head unit's Bluetooth priorities stay OFF and it won't
auto-connect to your phone. Apart from those priorities, nothing else is modified on the
system, so this fully reverts the change.

## Project layout

```
AndroidManifest.xml          # system-uid app, BOOT_COMPLETED receiver + service
src/com/mg4/aakick/
  BootReceiver.java          # starts the services on boot, arms BtGuard on shutdown
  KickService.java           # the detect → reset → re-broadcast logic
  KickWorker.java            # worker thread body
  BtGuard.java               # stores/restores the Bluetooth profile priorities
  BtGuardService.java        # wireless dongle fix: arm/release logic
build.ps1                    # command-line build (no Gradle)
```

## Disclaimer

This is an unofficial community fix, provided as‑is, with no warranty. You modify your
vehicle's software at your own risk. It only adds a normal user‑space app and does not
touch `/system`, but use it knowingly.
