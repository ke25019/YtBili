# YtBili (哔哩视界)

[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm_Noncommercial-orange.svg)](https://polyformproject.org/licenses/noncommercial/1.0.0/)
![version](https://img.shields.io/badge/version-1.13.3-orange)
![platform](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7f52ff)
![tests](https://img.shields.io/badge/tests-228%20passed-brightgreen)

An Android client for Bilibili (哔哩哔哩) with a YouTube-style interface. The UI and playback logic are implemented from scratch; the app only calls Bilibili's public web APIs.

- **Package**: `com.ytstyle.bili` (`com.ytstyle.bili.debug` for debug builds)
- **Stack**: Kotlin 2.1.21 · Jetpack Compose (Material 3) · Media3/ExoPlayer · Hilt · Retrofit + kotlinx.serialization
- **minSdk** 26 (Android 8.0) · **targetSdk** 35
- **Current version**: 1.13.3 (versionCode 26)

> The project contains no ad, analytics or push SDKs. "Ad-free" here means those SDKs were never integrated, not that they are filtered out.

---

## Install

Download the latest build from [Releases](https://github.com/ke25019/YtBili/releases) and open it to install (you will need to allow installs from unknown sources).

### The two builds

The same source is compiled twice; both APKs install and work:

| | **release** | **debug** |
|---|---|---|
| File name | `YtBili-<version>-release.apk` | `YtBili-<version>-debug.apk` |
| Size (1.13.3) | 3.40 MB | 24.35 MB |
| Package | `com.ytstyle.bili` | `com.ytstyle.bili.debug` |
| R8 shrinking / resource shrinking | on | off |
| Debuggable | no | yes |
| Purpose | daily use | log capture, debugging |

Use `release` for daily use; grab `debug` when something crashes or when you want to modify and debug the code.

### They can coexist

What matters is the package name, not the signing certificate: `com.ytstyle.bili` and `com.ytstyle.bili.debug` are two different apps as far as Android is concerned, so they do not overwrite each other. You can keep the debug build installed for troubleshooting without touching the release build you actually use.

> A signing certificate only has to match when **updating the same package name**. This project currently signs the release APK with the debug certificate. If it ever moves to a proper certificate, updating `com.ytstyle.bili` will fail on signature mismatch and the old build must be uninstalled first.

### In-app updates

Since 1.13.0 the app can check for, download and install new versions itself, and the automatic check can be turned off in Settings. Only the newest published release is considered, and the file is chosen to match the installed build type (`-debug.apk` for debug, `-release.apk` for release) — the two are signed differently, so installing the wrong one is rejected by the system.

---

## Features

### Browsing

- Home recommendation feed: endless scrolling, channel chips, pull to refresh, appends while in the foreground
- Search across six categories — videos, anime, films/TV, users, articles and live rooms — with trending searches, input suggestions, sort and duration filters, and recent-search history
- User space: profile, following and followers, uploads, dynamic feed, followed anime, favourite folders, recent likes, live room entry
- Favourite folders, watch history, watch later, VIP centre

### Playback

- DASH audio and video tracks are fetched separately and played together; a second-level quality menu; switching quality keeps the position
- Long-press for 2× speed, audio-only mode, auto-pause when the app goes to the background (audio-only excepted), cross-device resume
- Fullscreen and landscape: a dedicated landscape control bar that hides itself, and exiting fullscreen returns straight to the video position
- Picture-in-picture, screenshots saved to the gallery, a casting entry
- The player page has three tabs — description, comments and danmaku — plus related videos and collections

### Danmaku

- Hand-written protobuf wire-format parser; all segments for the video's duration are fetched
- Lane collision avoidance, clipping to the visible area, per-second bucketing and measurement caching
- Settings for size, opacity, display area and speed, plus switches for fixed, scrolling and bottom danmaku
- Sending danmaku, including coloured danmaku with a preview

### Anime and films

- Native playback inside the same player page, with episode selection, quality and danmaku
- Comments for anime hang off the season rather than the episode, and are handled accordingly
- Likes, coins and "follow anime" work for anime too, using the episode's own video ID

### Interaction

- Likes, coins (up to 2), favourites (pick a folder, tap again to remove), follows
- Comments: top-level comments and nested replies with paging, emote images, comment likes, posting and replying
- Sharing through the system share sheet

### Live and casting

- Live rooms: HLS preferred, quality and stream information, real-time danmaku over a WebSocket with heartbeats and automatic reconnection
- Casting: DLNA renderers are discovered over SSDP and controlled with SOAP; media streams are proxied through the phone so request headers are added and `Range` is forwarded, keeping seeking working on the TV side

### Messages and dynamics

- Private-message session list and conversation view, with text sending
- Dynamic feed: likes, comments and reposts
- Replies, mentions and likes notifications open in an embedded web view

### Offline

- Cache videos on the device and play them offline
- In portrait, the player lists other cached videos below and switches on tap
- In landscape, the control bar opens an episode picker built from the cached list

### Account and settings

- Four sign-in paths: password (RSA-encrypted, never stored), SMS code, QR code and pasted cookie
- Password and SMS sign-in go through a Geetest captcha; QR and cookie do not
- Multiple accounts and sign-out
- Settings split into six sub-pages: appearance, playback, audio and video, danmaku, experiments, about
- A built-in diagnostic log that rolls over, records crash stacks and can be exported from Settings

---

## Building

### Requirements

| Component | Version |
|---|---|
| JDK | 17 (`JAVA_HOME` pointing at 17, not 21) |
| Android SDK | Platform 35 + Build-Tools 35.0.0 |
| Gradle | 8.9 |

`local.properties` must point at your SDK:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

### Commands

```bash
# Compile the main sources only (fastest syntax check)
./gradlew compileDebugKotlin

# Unit tests (228 cases, on the JVM, no device needed)
./gradlew testDebugUnitTest

# Build the APKs
./gradlew assembleDebug assembleRelease
```

On Windows use `gradlew.bat` instead of `./gradlew`. The first build downloads dependencies, so a reliable network or mirror helps.

The unit tests cover the parsers, signing, cookies, the danmaku protocol, casting messages and similar device-independent parts. Anything that only a real device can prove has to be re-checked on a device.

---

## Project layout

```
app/src/main/java/com/ytstyle/bili/
├─ data
│  ├─ net        API endpoints, WBI signing, cookie capture, network setup
│  ├─ repo       BiliRepository: wraps everything in Result, errors never reach the UI
│  ├─ session    session, cookie, csrf and buvid persistence
│  ├─ settings   theme, danmaku and experiment settings
│  ├─ danmaku    danmaku protobuf parsing
│  ├─ live       live danmaku WebSocket protocol
│  ├─ cast       DLNA discovery and the media proxy
│  ├─ download   offline caching
│  ├─ update     in-app updates
│  ├─ auth       RSA password encryption for sign-in
│  ├─ search     search history
│  └─ log        diagnostic log
└─ ui
   ├─ theme / nav / components   theme, routes, shared components
   ├─ home / search / player / space / live
   ├─ messages / dynamic / download / settings / update
   └─ login / account / vip / history / favorites / watchlater / cast / browser / upload
```

`app/src/test/` holds the unit tests, and `app/src/test/resources/fixtures/` holds responses captured from the real APIs, used as regression fixtures for the parsers.

---

## Why not modify the official app

The official client uses R8 obfuscation, string encryption and native integrity checks, and signs releases with Bilibili's own key. Repacking it after a UI change fails the startup signature check, cannot be installed over the original, and cannot coexist with it.

This project therefore implements the UI and the logic from scratch against the public web APIs, without signature bypasses or anti-bot workarounds.

## Coexisting with the official app

The APK declares nothing that would clash with the official client:

- no `sharedUserId` / `sharedUserLabel`
- no Bilibili custom scheme or authority registered
- `<queries>` does not declare the official package name

Both apps install and run side by side.

---

## Known limitations

- **Manga subscriptions are not implemented**: the public APIs expose no usable "my subscriptions" endpoint.
- **System notifications open in an embedded web view**: replies, mentions and likes need a signed-in session and lack stable endpoints, so there is no native screen for them.
- **Casting needs a DLNA renderer on the same network**, and seeking on the TV depends on that device honouring `Range`.
- **Anime playback is not reported back to Bilibili**; cross-device resume for ordinary videos relies on Bilibili's own watch-history API.

---

## Notice

- This project includes AI-generated content.

## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

You may use, modify and distribute this software for noncommercial purposes only, provided you retain the original copyright notice. Commercial use is not permitted.

Copyright (c) 2026 ke25019

## Disclaimer

1. **Unofficial tool**: this is a personal open-source project and is not affiliated with, authorized, or endorsed by Bilibili.
2. **Usage risk**: the client reads content through Bilibili's public APIs, which may violate Bilibili's Terms of Service regarding third-party clients. Users should evaluate the risks themselves.
3. **Data security**: sign-in credentials are stored only in the app's private storage on the device. No personal information is collected or uploaded to any external server.
4. **Limitation of liability**: this software is provided "as is" without any warranties. The author is not liable for any account restrictions, data loss, or other damages resulting from the use of this software.
