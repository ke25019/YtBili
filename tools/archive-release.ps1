<#
.SYNOPSIS
    Archives the current build outputs into a per-version folder.

.DESCRIPTION
    Run this after every successful "assembleDebug assembleRelease".

    It copies the APKs into releases/v<version>/, writes a SHA-256 manifest and
    a build-info file, and never overwrites an existing version folder.
    Re-running for a version that already exists fails loudly instead of
    silently replacing the archived artifact.

    This exists because an earlier rebuild deleted the v1.0.0 APKs from the
    staging folder and they were unrecoverable: Android's package task is not
    covered by the Gradle build cache, so there was no copy to restore.

.EXAMPLE
    & H:\bilibili\tools\archive-release.ps1
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = 'H:\bilibili\YtBili',
    [string]$ReleasesRoot = 'H:\bilibili\releases',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# Build outputs live on D: via the build-out junction; fall back to the
# in-tree path if that redirect is not present.
$outRoot = Join-Path $ProjectRoot 'build-out\app\outputs\apk'
if (-not (Test-Path $outRoot)) {
    $outRoot = Join-Path $ProjectRoot 'app\build\outputs\apk'
}

$release = Join-Path $outRoot 'release\app-release.apk'
$debug   = Join-Path $outRoot 'debug\app-debug.apk'

if (-not (Test-Path $release)) { throw "release APK not found at $release - run assembleRelease first" }
if (-not (Test-Path $debug))   { throw "debug APK not found at $debug - run assembleDebug first" }

# aapt identifies the version; read it from the artifact so the folder name can
# never disagree with what the APK actually reports.
$aapt = @(
    'D:\Android\Sdk\build-tools\35.0.0\aapt.exe',
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\aapt.exe')
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $aapt) { throw 'aapt.exe not found; cannot read the APK version' }

$badging  = & $aapt dump badging $release 2>&1
$pkgLine  = ($badging | Select-String -Pattern '^package:' | Select-Object -First 1).Line
if ($pkgLine -notmatch "versionName='([^']+)'") { throw "cannot parse versionName from: $pkgLine" }
$version  = $Matches[1]
if ($pkgLine -notmatch "name='([^']+)'")        { throw "cannot parse package name from: $pkgLine" }
$packageName = $Matches[1]
$versionCode = if ($pkgLine -match "versionCode='(\d+)'") { $Matches[1] } else { '?' }

$dest = Join-Path $ReleasesRoot ("v" + $version)
if ((Test-Path $dest) -and -not $Force) {
    throw ("releases/v" + $version + " already exists. Version folders are immutable by design - " +
           "bump versionCode/versionName in app/build.gradle.kts, or pass -Force to replace it deliberately.")
}
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$releaseDest = Join-Path $dest ("YtBili-" + $version + "-release.apk")
$debugDest   = Join-Path $dest ("YtBili-" + $version + "-debug.apk")
Copy-Item $release $releaseDest -Force
Copy-Item $debug   $debugDest   -Force

# Hash manifest.
$manifestLines = @()
foreach ($f in @($releaseDest, $debugDest)) {
    $hash = (Get-FileHash $f -Algorithm SHA256).Hash
    $manifestLines += ($hash + '  ' + (Split-Path $f -Leaf))
}
$manifestLines | Set-Content -Path (Join-Path $dest 'SHA256SUMS.txt') -Encoding ASCII

$sizeRelease = [math]::Round((Get-Item $releaseDest).Length / 1MB, 2)
$sizeDebug   = [math]::Round((Get-Item $debugDest).Length / 1MB, 2)

# Each concatenation is parenthesised: inside an array literal the comma
# operator binds tighter than '+', so an unparenthesised 'a' + $x, 'b' + $y
# silently collapses the whole array into one concatenated string.
$info = @(
    ('version         : ' + $version),
    ('versionCode     : ' + $versionCode),
    ('package         : ' + $packageName),
    ('debug package   : ' + $packageName + '.debug'),
    ('archived at     : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')),
    ('release APK     : YtBili-' + $version + '-release.apk (' + $sizeRelease + ' MB, R8 minified)'),
    ('debug APK       : YtBili-' + $version + '-debug.apk (' + $sizeDebug + ' MB, unminified)'),
    ('signing         : debug keystore (see app/build.gradle.kts)'),
    ('minSdk / target : 26 / 35'),
    '',
    'SHA-256'
) + $manifestLines
$info | Set-Content -Path (Join-Path $dest 'build-info.txt') -Encoding UTF8

Write-Host ('Archived v' + $version + ' -> ' + $dest)
Get-ChildItem $dest | Select-Object Name, Length | Format-Table -AutoSize
