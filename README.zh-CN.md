# YtBili（哔哩视界）

[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm_Noncommercial-orange.svg)](https://polyformproject.org/licenses/noncommercial/1.0.0/)
![version](https://img.shields.io/badge/version-1.13.3-orange)
![platform](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7f52ff)
![tests](https://img.shields.io/badge/tests-228%20passed-brightgreen)

一个用 YouTube 界面语言写的哔哩哔哩 Android 客户端，从零实现界面与播放逻辑，只调用 B 站公开的 Web API。

- **包名**：`com.ytstyle.bili`（debug 构建为 `com.ytstyle.bili.debug`）
- **技术栈**：Kotlin 2.1.21 · Jetpack Compose (Material 3) · Media3/ExoPlayer · Hilt · Retrofit + kotlinx.serialization
- **minSdk** 26（Android 8.0）· **targetSdk** 35
- **当前版本**：1.13.3（versionCode 26）

> 项目中不包含任何广告 SDK、统计 SDK 或推送 SDK。"无广告"的实现方式是从来就没集成过，而不是事后过滤。

---

## 安装

到 [Releases](https://github.com/ke25019/YtBili/releases) 下载最新版本，点开即装（需要允许安装未知来源应用）。

### 两个安装包

同一份源码编译两次，两个包都能装、都能用：

| | **release** | **debug** |
|---|---|---|
| 文件名 | `YtBili-<版本>-release.apk` | `YtBili-<版本>-debug.apk` |
| 体积（1.13.3） | 3.40 MB | 24.35 MB |
| 包名 | `com.ytstyle.bili` | `com.ytstyle.bili.debug` |
| R8 压缩 / 资源裁剪 | 开启 | 关闭 |
| 可调试 | 否 | 是 |
| 用途 | 日常使用 | 抓日志、自己调试 |

日常使用下 `release`；遇到崩溃或想自己改代码时下 `debug`。

### 两者可以共存

判定依据是包名而不是签名：`com.ytstyle.bili` 与 `com.ytstyle.bili.debug` 是两个不同的应用，Android 会当成两个独立 App 处理，互不覆盖。所以排查问题时可以装上 debug 版，完全不影响日常用的 release 版。

> 签名证书只在**更新同一个包名**时才需要一致。本项目目前用 debug 证书给 release 包签名；如果将来换成正式证书，更新 `com.ytstyle.bili` 时会因签名不一致安装失败，那时必须先卸载旧版。

### 应用内更新

1.13.0 起支持在应用内检查新版本、下载并安装，可在设置里关闭自动检查。只取最新发布的一版，并按当前安装包类型选择对应文件（debug 装 `-debug.apk`，release 装 `-release.apk`）——两者签名不同，装错会被系统拒绝。

---

## 功能

### 浏览与发现

- 首页推荐流：无限滚动、频道标签、下拉刷新、前台时持续追加
- 搜索：视频 / 番剧 / 影视 / 用户 / 专栏 / 直播间六个分区，含热搜、输入联想、排序与时长筛选、最近搜索记录
- 个人主页：资料、关注与粉丝列表、投稿、动态、追番、收藏夹、最近点赞、直播间入口
- 收藏夹、观看记录、稍后再看、会员中心

### 播放

- DASH 音视频分轨获取后合并播放，清晰度二级菜单，切换时不重置进度
- 长按 2 倍速、仅音频模式、切后台自动暂停（仅音频模式例外）、跨设备续看
- 全屏与横屏：横屏独立控制条、自动隐藏、从视频位置直接退出
- 画中画（PiP）、截图保存到相册、投屏入口
- 播放页三个标签页：简介、评论、发弹幕；含相关推荐与合集

### 弹幕

- 手写 protobuf wire-format 解析弹幕分段，按视频时长取全部段
- 车道防重叠、按画面实际显示区域裁剪、按秒分桶与测量缓存
- 字号、不透明度、显示区域、速度四项设置，固定 / 滚动 / 底端三种类型开关
- 发送弹幕，含彩色弹幕与发送前预览

### 番剧与影视

- 原生播放（复用同一个播放页），含选集、清晰度、弹幕
- 番剧评论挂在「季」上，与普通视频的评论入口分开处理
- 点赞、投币、追番对番剧同样可用（使用该集自带的稿件 ID）

### 互动

- 点赞、投币（上限 2 币）、收藏（可选收藏夹，再点即取消）、关注
- 评论：一级评论与楼中楼翻页、表情图片渲染、评论点赞、发表与回复
- 分享：系统分享面板

### 直播与投屏

- 直播间：HLS 优先播放、清晰度与线路信息、实时弹幕（WebSocket 长连接 + 心跳 + 自动重连）
- 投屏：SSDP 发现局域网内的 DLNA 渲染设备，通过 SOAP 控制播放；媒体流由手机端本机代理补请求头并转发 `Range`，保证电视端可拖动进度

### 消息与动态

- 私信会话列表与会话详情，支持发送文本消息
- 动态流：关注动态、点赞 / 评论 / 转发
- 回复、@、点赞等系统通知走内嵌网页

### 离线

- 缓存视频到本机，离线播放器
- 竖屏播放器下方列出其他缓存视频，点击即可切换
- 横屏控制条可打开「选集」，从缓存列表里挑选

### 账号与设置

- 四路登录：账号密码（RSA 加密，密码不落盘）、短信验证码、扫码、Cookie 粘贴
- 密码与短信登录走 Geetest 验证码，扫码与 Cookie 不需要验证码
- 多账户切换、退出登录
- 设置分为六个子页：外观、播放、音画、弹幕、实验功能、关于
- 内置诊断日志：滚动记录、崩溃堆栈、可在设置里导出

---

## 构建

### 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17（`JAVA_HOME` 指向 17，不是 21） |
| Android SDK | Platform 35 + Build-Tools 35.0.0 |
| Gradle | 8.9 |

`local.properties` 指向本机 SDK：

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

### 命令

```bash
# 只编译主源码（最快的语法检查）
./gradlew compileDebugKotlin

# 单元测试（228 例，跑在 JVM 上，不需要设备）
./gradlew testDebugUnitTest

# 出安装包
./gradlew assembleDebug assembleRelease
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。首次构建会联网下载依赖，建议使用稳定的镜像与网络。

单元测试覆盖解析器、签名、Cookie、弹幕协议、投屏报文与点击行为等不依赖设备的部分；凡是需要真机才能验证的改动，都要在设备上复测。

---

## 项目结构

```
app/src/main/java/com/ytstyle/bili/
├─ data
│  ├─ net        BiliApi 端点、WBI 签名、Cookie 抓取、网络配置
│  ├─ repo       BiliRepository：统一 Result 包装，异常不外泄到 UI
│  ├─ session    会话、Cookie、csrf、buvid 持久化
│  ├─ settings   主题、弹幕、实验开关等设置项
│  ├─ danmaku    弹幕 protobuf 解析
│  ├─ live       直播弹幕 WebSocket 协议
│  ├─ cast       DLNA 设备发现与媒体代理
│  ├─ download   离线缓存
│  ├─ update     应用内更新
│  ├─ auth       登录密码 RSA 加密
│  ├─ search     搜索历史
│  └─ log        诊断日志
└─ ui
   ├─ theme / nav / components   主题、路由、通用组件
   ├─ home / search / player / space / live
   ├─ messages / dynamic / download / settings / update
   └─ login / account / vip / history / favorites / watchlater / cast / browser / upload
```

`app/src/test/` 下为单元测试，`app/src/test/resources/fixtures/` 是从真实接口抓取的响应夹具，用于回归解析逻辑。

---

## 为什么不修改官方客户端

官方客户端使用 R8 混淆、字符串加密与原生完整性校验，并以 B 站私钥做 v2/v3 签名。修改其界面后重新打包会触发启动时的签名校验而无法运行，也会因签名不同无法覆盖安装、无法与官方客户端共存。

因此本项目从零实现界面与逻辑，只调用公开 Web API，不涉及签名绕过或风控对抗。

## 与官方客户端共存

APK 中不存在会与官方客户端冲突的声明：

- 没有 `sharedUserId` / `sharedUserLabel`
- 没有注册 B 站的自定义 scheme 或 authority
- `<queries>` 中未声明官方包名

两者可同时安装、同时运行。

---

## 已知限制

- **追漫（我的漫画订阅）未实现**：公开接口里没有可用的「我的订阅」端点。
- **系统通知走内嵌网页**：回复、@、点赞三类通知需要登录态且缺少稳定端点，未做原生实现。
- **投屏需要局域网内的 DLNA 渲染设备**：且电视端能否拖动进度取决于设备对 `Range` 的支持。
- **番剧不向 B 站上报观看进度**；普通视频的跨设备续看依赖 B 站的观看记录接口。

---

## 声明

- 此项目包含 AI 生成内容。

## 许可证

本项目基于 [PolyForm Noncommercial License 1.0.0](LICENSE) 发布。

你可以为非商业目的自由使用、修改和分发本软件，但必须保留原始版权声明，且不得用于任何商业用途。

Copyright (c) 2026 ke25019

## 免责声明

1. **非官方工具**：本项目为个人开源项目，与哔哩哔哩（B站）官方无任何关联，未获得 B站的官方授权或认可。
2. **使用风险**：本客户端通过 B站公开接口读取内容，可能违反 B站《用户使用协议》中关于第三方客户端的相关条款。用户应自行评估使用风险。
3. **数据安全**：登录凭据仅保存在本机应用私有存储中，不收集、不上传任何用户个人信息至外部服务器。
4. **责任限制**：本软件按"原样"提供，不提供任何明示或暗示的担保。作者不对因使用本软件导致的账号限制、数据丢失或其他损失承担责任。
