# YtBili（哔哩视界）

[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm_Noncommercial-orange.svg)](https://polyformproject.org/licenses/noncommercial/1.0.0/)
![version](https://img.shields.io/badge/version-1.13.3-orange)
![platform](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7f52ff)
![tests](https://img.shields.io/badge/tests-228%20passed-brightgreen)

一个用 YouTube 界面语言写的哔哩哔哩 Android 客户端。独立实现，只调用 B 站公开的 Web API，与官方客户端完全隔离、可以同时安装。

An Android client for Bilibili with a YouTube-style interface. Independently implemented on Bilibili's public web APIs, so it stays separate from the official app and can be installed alongside it.

---

## 文档 / Documentation

| | |
| --- | --- |
| 简体中文 | [README.zh-CN.md](README.zh-CN.md) |
| English | [README.en.md](README.en.md) |

---

## 安装 / Install

**中文：** 到 [Releases](https://github.com/ke25019/YtBili/releases) 下载最新版本的安装包，点开安装即可（需要允许安装未知来源应用）。日常使用下 `release` 版，要抓日志或自己调试下 `debug` 版，两者包名不同，可以同时装。

**English:** Download the latest APK from [Releases](https://github.com/ke25019/YtBili/releases) and install it (you will need to allow installs from unknown sources). Use the `release` build for daily use, or the `debug` build if you want to capture logs or debug; they have different package names and can coexist.

系统要求：**Android 8.0（API 26）及以上**。

Requires **Android 8.0 (API 26) or later**.

---

## 简单说说 / In short

- 首页推荐流、六分区搜索、个人主页、收藏夹、观看记录、稍后再看、直播间、会员中心
- DASH 音视频合并播放、清晰度切换、长按倍速、画中画、切后台暂停、跨设备续看、横屏控制条
- 弹幕：protobuf 解析、车道防重叠、字号/透明度/区域/速度设置、发送与彩色弹幕
- 互动：点赞、投币、收藏夹选择、评论（含表情图片与楼中楼）、关注、分享
- 番剧与影视原生播放（含选集、弹幕、评论），直播 HLS 播放与实时弹幕
- 投屏：DLNA/SSDP 发现设备，本机代理转发媒体流
- 私信会话与发送、动态点赞/评论/转发
- 离线缓存与离线播放，应用内检查更新、下载并安装
- 四路登录（密码 / 短信 / 扫码 / Cookie）、多账户切换、内置诊断日志
- 不包含广告、统计、推送 SDK

- Home recommendation feed, six-category search, user space, favourites, watch history, watch later, live rooms, VIP centre
- DASH playback with quality switching, long-press speed-up, picture-in-picture, auto-pause in background, cross-device resume, landscape controls
- Danmaku with protobuf parsing, lane collision avoidance, size/opacity/area/speed settings, sending and coloured comments
- Likes, coins, favourite folders, comments (emote images and nested replies), follows, sharing
- Native anime and film playback (episodes, danmaku, comments), live streaming over HLS with real-time danmaku
- Casting over DLNA/SSDP with an on-device proxy for media streams
- Private messages, dynamic feed likes/comments/reposts
- Offline caching and playback, in-app update check, download and install
- Four sign-in paths (password / SMS / QR / cookie), multiple accounts, built-in diagnostic log
- No ad, analytics or push SDKs

---

## 声明 / Notice

- 此项目包含 AI 生成内容。
- This project includes AI-generated content.

---

## 许可证 / License

本项目基于 [PolyForm Noncommercial License 1.0.0](LICENSE) 发布。

你可以为非商业目的自由使用、修改和分发本软件，但必须保留原始版权声明，且不得用于任何商业用途。

Copyright (c) 2026 ke25019

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

You may use, modify and distribute this software for noncommercial purposes only, provided you retain the original copyright notice. Commercial use is not permitted.

Copyright (c) 2026 ke25019

---

## 免责声明 / Disclaimer

1. **非官方工具**：本项目为个人开源项目，与哔哩哔哩（B站）官方无任何关联，未获得 B站的官方授权或认可。
   **Unofficial Tool**: This is a personal open-source project and is not affiliated with, authorized, or endorsed by Bilibili.

2. **使用风险**：本客户端通过 B站公开接口读取内容，可能违反 B站《用户使用协议》中关于第三方客户端的相关条款。用户应自行评估使用风险。
   **Usage Risk**: This client reads content through Bilibili's public APIs, which may violate Bilibili's Terms of Service regarding third-party clients. Users should evaluate the risks themselves.

3. **数据安全**：登录凭据仅保存在本机应用私有存储中，不收集、不上传任何用户个人信息至外部服务器。
   **Data Security**: Sign-in credentials are stored only in the app's private storage on the device. No personal information is collected or uploaded to any external server.

4. **责任限制**：本软件按"原样"提供，不提供任何明示或暗示的担保。作者不对因使用本软件导致的账号限制、数据丢失或其他损失承担责任。
   **Limitation of Liability**: This software is provided "as is" without any warranties. The author is not liable for any account restrictions, data loss, or other damages resulting from the use of this software.
