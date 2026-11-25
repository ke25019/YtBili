# Copies the official Bilibili client's offline cache into a folder YtBili can
# read, without root and without moving gigabytes over USB twice.
#
# Why this is needed:
#   From Android 11, no third-party app can read another app's Android/data
#   directory — not even with the "all files access" special permission
#   (developer.android.com/training/data-storage/manage-all-files). YtBili
#   therefore cannot list tv.danmaku.bili's cache directly; it reads a copy in
#   /sdcard/Download/BiliCache instead. `adb shell` is allowed to look inside
#   Android/data, so this script makes the copy on the phone itself.
#
# Usage (phone connected, USB debugging allowed):
#   powershell -ExecutionPolicy Bypass -File tools\import-phone-cache.ps1
#
# Then open YtBili → 设置 → 实验性功能 → 读取其他客户端的缓存 → 重新检测.
# The copy doubles the space the cache occupies; delete /sdcard/Download/BiliCache
# (or the individual videos) when you no longer need it.

param(
    [string]$Adb = "",
    [string]$Destination = "/sdcard/Download/BiliCache",
    [switch]$AllPackages
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    param([string]$Explicit)
    if ($Explicit) {
        if (-not (Test-Path $Explicit)) { throw "adb not found: $Explicit" }
        return $Explicit
    }
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }
    $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdk) { return $sdk }
    throw "adb was not found. Pass -Adb <path to adb.exe>."
}

$adb = Find-Adb -Explicit $Adb

# `adb devices` has a header line; a real device line ends in "device".
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    throw "No authorised device. Unlock the phone, allow USB debugging, and try again."
}

# Both ids the official client has shipped under. Only the one that exists on
# this phone is copied; the other is skipped.
$packages = if ($AllPackages) {
    @("tv.danmaku.bili", "com.bilibili.app.in")
} else {
    @("tv.danmaku.bili")
}

& $adb shell "mkdir -p '$Destination'" | Out-Null

$copied = 0
foreach ($package in $packages) {
    $source = "/sdcard/Android/data/$package/download"
    $exists = (& $adb shell "test -d '$source' && echo yes" | Out-String).Trim()
    if ($exists -ne "yes") {
        Write-Host "跳过 ${package}：手机上不存在 $source"
        continue
    }

    # Copy into a per-package subfolder so two clients never overwrite one
    # another. `cp -r` overwrites files that are already there, which is what
    # re-running after a new download should do.
    $target = "$Destination/$package"
    Write-Host "复制 ${package} …（缓存很大时会花几分钟）"
    & $adb shell "mkdir -p '$target' && cp -r '$source/.' '$target/'"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "cp 返回 $LASTEXITCODE；可能有部分文件没有复制（空间不足？）"
    }

    $count = (& $adb shell "find '$target' -name entry.json 2>/dev/null | wc -l" | Out-String).Trim()
    Write-Host "  $target 里现在有 $count 个 entry.json"
    $copied += [int]$count
}

if ($copied -eq 0) {
    Write-Host ""
    Write-Host "没有复制到任何缓存。先确认官方客户端里确实有已下载完成的视频。" -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "完成：共 $copied 个缓存。现在打开 YtBili → 设置 → 实验性功能 → 重新检测。" -ForegroundColor Green
}
