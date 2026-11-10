# Desk Display: update and back up the tablet over Wi-Fi (no USB cable needed)
#
#   .\tablet.ps1 setup -Ip 192.168.1.50 -Pin 123456   remember the tablet's address and PIN (see SYSTEM > Device > Phone remote)
#   .\tablet.ps1 push                                  send changed pages / paintings and reload the tablet
#   .\tablet.ps1 push -All                             send everything again
#   .\tablet.ps1 status                                what the tablet is running
#   .\tablet.ps1 reset                                 forget pushed files, go back to the page built into the app
#   .\tablet.ps1 backup [-File mybackup.json]          save alarms, routine, events, countdowns and settings
#   .\tablet.ps1 restore -File mybackup.json           put a backup back
#
# Java changes (kiosk\src) still need a rebuilt APK; page changes (index.html, remote\remote.html, art\*.jpg) do not.
param(
    [Parameter(Position = 0)][string]$Cmd = "push",
    [string]$Ip, [string]$Pin, [string]$File,
    [switch]$All
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$cfgFile = Join-Path $root ".tablet.json"

if ($Cmd -eq "setup") {
    if (-not $Ip -or -not $Pin) { throw "usage: .\tablet.ps1 setup -Ip <tablet address> -Pin <6 digits>" }
    @{ ip = $Ip; pin = $Pin } | ConvertTo-Json | Set-Content -Encoding ASCII $cfgFile
    Write-Host "saved to $cfgFile"
    $Cmd = "status"
}
if (-not (Test-Path $cfgFile)) { throw "run first: .\tablet.ps1 setup -Ip <tablet address> -Pin <PIN shown in SYSTEM on the tablet>" }
$cfg = Get-Content $cfgFile -Raw | ConvertFrom-Json
if ($Ip) { $cfg.ip = $Ip }
if ($Pin) { $cfg.pin = $Pin }
$base = "http://$($cfg.ip):8080"

function Call([string]$method, [string]$path, $body = $null) {
    $args2 = @{ Uri = "$base$path"; Method = $method; Headers = @{ "X-Pin" = $cfg.pin }; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($null -ne $body) { $args2.Body = $body; $args2.ContentType = "application/octet-stream" }
    try { return (Invoke-WebRequest @args2).Content }
    catch [System.Net.WebException] {
        $r = $_.Exception.Response
        if ($r) {
            $txt = (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd()
            if ([int]$r.StatusCode -eq 401) { throw "The tablet says the PIN is wrong. Check SYSTEM > Device > Phone remote and run setup again. ($txt)" }
            throw "The tablet answered $([int]$r.StatusCode): $txt"
        }
        throw "Could not reach the tablet at $base. Is it on the same Wi-Fi? ($($_.Exception.Message))"
    }
}

switch ($Cmd) {
    "status" {
        $hello = Call "GET" "/api/hello" | ConvertFrom-Json
        $st = Call "GET" "/api/ota" | ConvertFrom-Json
        Write-Host "connected to $($hello.name) at $($cfg.ip)"
        if ($st.active) { Write-Host "running a page pushed over Wi-Fi:"; $st.files | ForEach-Object { Write-Host ("  {0,-22} {1,9} bytes" -f $_.name, $_.size) } }
        else { Write-Host "running the page built into the app (nothing pushed)" }
    }
    "push" {
        $st = Call "GET" "/api/ota" | ConvertFrom-Json
        $remote = @{}; $st.files | ForEach-Object { $remote[$_.name] = $_.sha1 }
        $apk = Join-Path $root "kiosk\out\desk-display.apk"
        $built = if (Test-Path $apk) { (Get-Item $apk).LastWriteTime } else { [datetime]::MinValue }
        $list = @(
            @{ name = "index.html"; path = (Join-Path $root "index.html") },
            @{ name = "remote.html"; path = (Join-Path $root "remote\remote.html") }
        )
        Get-ChildItem (Join-Path $root "art") -Filter *.jpg -ErrorAction SilentlyContinue | ForEach-Object { $list += @{ name = "art_" + $_.Name; path = $_.FullName } }
        $sent = 0
        foreach ($f in $list) {
            if (-not (Test-Path $f.path)) { continue }
            $sha = (Get-FileHash $f.path -Algorithm SHA1).Hash.ToLower()
            if (-not $All) {
                if ($remote[$f.name] -eq $sha) { continue }                                  # the tablet already has exactly this
                if (-not $remote.ContainsKey($f.name) -and (Get-Item $f.path).LastWriteTime -le $built) { continue }   # same as what the app was built with
            }
            $bytes = [IO.File]::ReadAllBytes($f.path)
            $r = Call "PUT" ("/api/ota/file?name=" + $f.name) $bytes | ConvertFrom-Json
            Write-Host ("sent {0,-22} {1,9} bytes" -f $f.name, $r.size)
            $sent++
        }
        if ($sent -eq 0) { Write-Host "nothing to send: the tablet is up to date"; return }
        Call "POST" "/api/ota/reload" "" | Out-Null
        Write-Host "reloading the tablet ($sent file(s) sent). If the new page does not start within about 45 seconds the tablet goes back to its built-in page by itself."
    }
    "reset" {
        Call "POST" "/api/ota/reset" "" | Out-Null
        Write-Host "pushed files removed; the tablet is back on the page built into the app"
    }
    "backup" {
        $out = if ($File) { $File } else { Join-Path $root ("backups\desk-backup-" + (Get-Date -Format "yyyyMMdd-HHmm") + ".json") }
        New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
        $txt = Call "GET" "/api/backup"
        [IO.File]::WriteAllText($out, $txt, (New-Object Text.UTF8Encoding($false)))
        $j = $txt | ConvertFrom-Json
        Write-Host ("backup saved to {0} ({1} items: alarms, routine, events, countdowns, settings)" -f $out, @($j.data.PSObject.Properties).Count)
    }
    "restore" {
        if (-not $File -or -not (Test-Path $File)) { throw "usage: .\tablet.ps1 restore -File <backup.json>" }
        $bytes = [IO.File]::ReadAllBytes((Resolve-Path $File))
        $r = Call "POST" "/api/restore" $bytes | ConvertFrom-Json
        Write-Host "tablet says: $($r.result)"
    }
    default { throw "unknown command '$Cmd' (setup, push, status, reset, backup, restore)" }
}
