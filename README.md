# MG4 Android Auto Boot Fix (AA Kick)

A tiny companion app for the **MG4** (SAIC AOSP head unit) that fixes a long‑standing
annoyance: when your phone is **already plugged in by USB before the head unit boots**,
wired Android Auto often **fails to start**, and you have to physically **unplug and
re‑plug** the cable to get it working.

`com.mg4.aakick` makes that unplug/re‑plug happen automatically, in software, at boot.

> Tested on an MG4 (EH32 platform, AOSP Automotive 9, MediaTek) with a Motorola Edge 60.
> Other SAIC cars using the same Allgo/`caradapter` stack (MG/Roewe) may benefit too.

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

```powershell
adb push aakick-signed.apk /data/local/tmp/aakick.apk
adb shell pm install -r -f /data/local/tmp/aakick.apk
```

`pm install -f` is required on these `user` builds. The app is a normal (non‑persistent)
package installed into `/data/app`, so this works without root.

Then power‑cycle the car with the phone plugged in: Android Auto should start on its own.

## Verify / debug

The head unit **filters DEBUG‑level logcat**, so AA Kick logs at ERROR level:

```powershell
adb logcat -s AAKick
```

Ground truth for the AOAP switch (vendor id `6353` = `0x18d1` means accessory mode reached):

```powershell
adb shell "dumpsys usb | grep vendor_id"
```

## Uninstall / revert

```powershell
adb shell pm uninstall com.mg4.aakick
```

Nothing else is modified on the system, so removing the app fully reverts the change.

## Project layout

```
AndroidManifest.xml          # system-uid app, BOOT_COMPLETED receiver + service
src/com/mg4/aakick/
  BootReceiver.java          # starts the service on boot
  KickService.java           # the detect → reset → re-broadcast logic
  KickWorker.java            # worker thread body
build.ps1                    # command-line build (no Gradle)
```

## Disclaimer

This is an unofficial community fix, provided as‑is, with no warranty. You modify your
vehicle's software at your own risk. It only adds a normal user‑space app and does not
touch `/system`, but use it knowingly.
