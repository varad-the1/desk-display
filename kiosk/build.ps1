# Builds the Desk Display kiosk APK without Gradle.  Output: kiosk\out\desk-display.apk
#
# Needs: JDK 8+ (javac, jar, keytool on PATH) and the Android SDK command-line "build-tools" + one "platforms" folder.
#   Set ANDROID_HOME (or ANDROID_SDK_ROOT) if the SDK is not in %LOCALAPPDATA%\Android\Sdk.
param([string]$VersionName = "1.0.0", [int]$VersionCode = 1)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }; if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found. Install it and set ANDROID_HOME (see README)." }
$bt  = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d\.].*$','') } | Select-Object -Last 1
$pl  = Get-ChildItem "$sdk\platforms" -Directory | Where-Object { $_.Name -match '^android-\d+$' } | Sort-Object { [int]($_.Name -replace 'android-','') } | Select-Object -Last 1
if (-not $bt -or -not $pl) { throw "Need Android SDK build-tools and a platform (sdkmanager `"build-tools;34.0.0`" `"platforms;android-34`")." }
$bt = $bt.FullName; $jar = "$($pl.FullName)\android.jar"
$out = "$root\out"
$ks  = "$root\debug.keystore"          # created on first build; keep it so updates install over older versions

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes","$out\dex","$out\assets" | Out-Null

# the web UI and the paintings are packed into the APK as assets
Copy-Item "$root\..\index.html" "$out\assets\index.html"
Copy-Item "$root\..\remote\remote.html" "$out\assets\remote.html"
Get-ChildItem "$root\..\art" -Filter *.jpg | ForEach-Object { Copy-Item $_.FullName ("$out\assets\art_" + $_.Name) -Force }

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    -A "$out\assets" --min-sdk-version 21 --target-sdk-version 28 --version-code $VersionCode --version-name $VersionName
if ($LASTEXITCODE) { throw "aapt2 failed" }

$src = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object FullName
$ErrorActionPreference = "Continue"
& javac -source 8 -target 8 -nowarn -classpath $jar -d "$out\classes" $src 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch "deprecat|Note:|warning|bootstrap" }
$jc = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($jc) { throw "javac failed" }

$cls = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
$ErrorActionPreference = "Continue"
& "$bt\d8.bat" --lib $jar --min-api 21 --output "$out\dex" $cls 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match "rror" }
$dc = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($dc) { throw "d8 failed" }

Push-Location "$out\dex"; & jar uf "$out\base.apk" classes.dex; Pop-Location
if ($LASTEXITCODE) { throw "jar failed" }

& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if (-not (Test-Path $ks)) {
  $ErrorActionPreference = "Continue"
  & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias desk -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Desk Display" 2>&1 | Out-Null
  $ErrorActionPreference = "Stop"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\desk-display.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
Get-Item "$out\desk-display.apk" | Select-Object Name, Length
