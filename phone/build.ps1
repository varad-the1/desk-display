# Builds the Desk Remote phone APK without Gradle.  Output: phone\out\desk-remote.apk
#
# Needs: JDK 8+ (javac, jar, keytool on PATH) and the Android SDK build-tools + one platform.
#   Set ANDROID_HOME (or ANDROID_SDK_ROOT) if the SDK is not in %LOCALAPPDATA%\Android\Sdk.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }; if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found. Install it and set ANDROID_HOME (see README)." }
$btd = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d\.].*$','') } | Select-Object -Last 1
$pld = Get-ChildItem "$sdk\platforms" -Directory | Where-Object { $_.Name -match '^android-\d+$' } | Sort-Object { [int]($_.Name -replace 'android-','') } | Select-Object -Last 1
if (-not $btd -or -not $pld) { throw "Need Android SDK build-tools and a platform (see README)." }
$bt = $btd.FullName; $jar = "$($pld.FullName)\android.jar"
$out = "$root\out"
$ks  = "$root\debug.keystore"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes","$out\dex" | Out-Null

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    -A "$root\assets" --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name 1.0.0
if ($LASTEXITCODE) { throw "aapt2 failed" }

$src = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object FullName
$ErrorActionPreference = "Continue"
& javac -source 8 -target 8 -nowarn -classpath $jar -d "$out\classes" $src 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch "deprecat|Note:|warning|bootstrap" }
$jc = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($jc) { throw "javac failed" }

$cls = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
$ErrorActionPreference = "Continue"
& "$bt\d8.bat" --lib $jar --min-api 24 --output "$out\dex" $cls 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match "rror" }
$dc = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($dc) { throw "d8 failed" }

Push-Location "$out\dex"; & jar uf "$out\base.apk" classes.dex; Pop-Location
if ($LASTEXITCODE) { throw "jar failed" }
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if (-not (Test-Path $ks)) {
  $ErrorActionPreference = "Continue"
  & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias remote -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Desk Remote" 2>&1 | Out-Null
  $ErrorActionPreference = "Stop"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\desk-remote.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
Get-Item "$out\desk-remote.apk" | Select-Object Name, Length
