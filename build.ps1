# Build + sign AA Kick without Gradle.
# Edit the paths below to match your machine, then run:  powershell -ExecutionPolicy Bypass -File build.ps1
# Requires: a JDK 11+ (d8), Android SDK build-tools + an android.jar, and the platform signing key.

$ErrorActionPreference = "Stop"

# --- paths to edit ---
$JDK  = "C:\Program Files\Android\Android Studio\jbr\bin"               # JDK 11+ (Android Studio's JBR works)
$BT   = "$env:LOCALAPPDATA\Android\Sdk\build-tools\34.0.0"              # Android SDK build-tools
$AJAR = "$env:LOCALAPPDATA\Android\Sdk\platforms\android-28\android.jar" # any android.jar (API 28+)
$KEY  = "platform.pk8"                                                  # AOSP platform private key
$CERT = "platform.x509.pem"                                            # AOSP platform certificate
# ---------------------

$work = $PSScriptRoot
$out  = Join-Path $work "out"
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host "[1/5] compile"
$srcs = (Get-ChildItem (Join-Path $work "src") -Recurse -Filter *.java).FullName
& "$JDK\javac.exe" -nowarn -source 11 -target 11 -classpath $AJAR -d $out $srcs

Write-Host "[2/5] dex"
$classes = (Get-ChildItem $out -Recurse -Filter *.class).FullName
& "$JDK\java.exe" -cp "$BT\lib\d8.jar" com.android.tools.r8.D8 --min-api 28 --lib $AJAR --output $out $classes

Write-Host "[3/5] link manifest -> apk"
& "$BT\aapt2.exe" link -I $AJAR --manifest (Join-Path $work "AndroidManifest.xml") `
    --min-sdk-version 28 --target-sdk-version 28 -o (Join-Path $out "base.apk")

Write-Host "[4/5] add classes.dex + align"
Copy-Item (Join-Path $out "classes.dex") (Join-Path $work "classes.dex") -Force
Push-Location $work
& "$JDK\jar.exe" uf (Join-Path $out "base.apk") classes.dex
Pop-Location
& "$BT\zipalign.exe" -f 4 (Join-Path $out "base.apk") (Join-Path $out "aakick-aligned.apk")

Write-Host "[5/5] sign with platform key"
& "$BT\apksigner.bat" sign --key (Join-Path $work $KEY) --cert (Join-Path $work $CERT) `
    --out (Join-Path $work "aakick-signed.apk") (Join-Path $out "aakick-aligned.apk")

Write-Host "Done -> $(Join-Path $work 'aakick-signed.apk')"
