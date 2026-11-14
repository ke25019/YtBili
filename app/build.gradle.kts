plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ytstyle.bili"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ytstyle.bili"
        minSdk = 26
        targetSdk = 35
        // 1.5.0: player rebuilt (folder picker for 收藏, native PGC playback,
        // full-screen only, reply threads, accurate interaction counts), login
        // state unified on the persisted cookie, search history, continuous home
        // feed, reskinned embedded browser, status-bar insets everywhere.
        //
        // 1.5.1: on-device fixes — settings back now returns to the first-level
        // menu, the two-column home feed loads more again, and the search field
        // no longer clips typed text (Material3's TextField box could not fit
        // the 44dp pill). Plus status-bar icons follow the app palette.
        //
        // 1.5.2: comments page and threads, comment feedback moved next to the
        // composer, autoplay became a per-network policy with a data warning,
        // 首页推荐设置 split out of the experimental list, and interaction
        // buttons now fill solid when active.
        //
        // 1.6.0: the video page rebuilt around 简介/评论/发弹幕 tabs, with a
        // redone comment list (sorting, UP/TOP badges, IP location, thread boxes,
        // like/dislike, replies), a 合集 entry and a related-video rail; player
        // chrome auto-hides and picture-in-picture gained skip/play controls;
        // 观看记录 and 稍后再看 fixes; write endpoints no longer report success
        // as failure.
        // 1.7.0: danmaku pill + colour, portrait player back to dark, action row
        // wraps instead of overflowing, collection list fixed, multi-account
        // switching, passport-aware login headers, 音画设置 (default quality and
        // audio track), avatar opens your own space page.
        // 1.7.1: the seven items 1.7.0 could not deliver — space page rebuilt,
        // native message centre, 追更 lists, offline caching, the home partition
        // editor, and narrow-screen degradation.
        // 1.8.0: seamless quality switching (the position survives a track
        // change) and cross-device resume (read `x/player/v2.last_play_time`,
        // report through `x/v2/history/report`).
        // 1.8.1: built-in diagnostics — a rolling on-device log (network results,
        // player errors, crash stacks) plus an export entry in settings.
        // Version chosen by the user.
        // 1.8.2: password and SMS login — the captcha page no longer aborts the
        // whole flow on a blocked sub-resource, and a challenge that cannot load
        // is retryable in place. Version chosen by the user.
        // 1.9.0: landscape player chrome, the 动态 feed rebuilt on feed/all,
        // 私信会话 in the message centre, single-column duration without the
        // black pill, and favourites play counts fixed. Version chosen by the user.
        // 1.10.0: dynamic like/comment/repost, private-message history, live
        // danmaku over the message queue, and DLNA casting through a local
        // proxy. Version chosen by the user.
        // 1.11.0: log policy (release drops debug/info, 10 MB rolling, manual
        // clear), message-centre fixes, player fixes (pause while composing,
        // no preload on mobile data, gallery screenshots, landscape comments,
        // danmaku settings panel) and the rebuilt space page. Version chosen by
        // the user.
        // 1.12.0: player/danmaku overhaul (cast single-stream fix, landscape
        // episode picker, danmaku send panel, translucent secondary menus),
        // comment paging, space header fix, 资源库 section, per-partition feed
        // cache. Version chosen by the user.
        //
        // 1.12.1: 番剧 like/coin/追番, full-screen image viewer for 动态图文,
        // the space page's 最近点赞 section, and live-danmaku reconnect.
        // Version chosen by the user.
        //
        // 1.12.2: 粉丝/关注 second-level lists with the privacy refusals handled,
        // and images inside comments. Version chosen by the user.
        //
        // 1.12.3: sending text 私信. Version chosen by the user.
        //
        // 1.12.4: live danmaku now walks the whole host list (with a 443
        // fallback) instead of only the first entry. Version chosen by the user.
        //
        // 1.13.0: in-app update (GitHub Releases check, Material 3 dialog, APK
        // download and install, 正式版/测试版 channels), the live-danmaku endpoint
        // that still answers, landscape layout under auto-rotate, and eight
        // smaller fixes. Version chosen by the user.
        //
        // 1.13.1: offline player gains landscape, the update channel selector is
        // gone (the APK is chosen by build type instead), and the system bars are
        // transparent globally. Version chosen by the user.
        //
        // 1.13.2: settings back one level at a time, an auto-update switch, 安装
        // from an already-downloaded APK, trimmed landscape chrome, status-bar
        // icons that follow the banner, the offline player on the app palette,
        // and working 番剧/收藏 taps on the space page. Version chosen by the user.
        //
        // 1.13.3: the shell's bars fade out on scroll in landscape, the offline
        // player lists and switches cached videos (portrait list + landscape
        // 选集), and the landscape 截图/锁定 buttons are back. Version chosen by
        // the user.
        //
        // 1.13.4-beta: update channels are back as a preference (Stable / Beta,
        // persisted, independent of the build type), the APK inside a release is
        // matched to the installed package and never substituted, and the
        // offline player's landscape chrome was tidied. Version chosen by the
        // user.
        //
        // 1.13.5-beta: a play/pause control in 听视频 mode, one owner for the
        // system bars (icon tint and visibility, re-applied on resume), partition
        // refresh that actually brings new uploads, and the 图文动态 reading
        // page. Version chosen by the user.
        //
        // 1.13.6-beta: a partition's feed now starts from its newest uploads
        // (paginated) instead of the ranking snapshot, and 个人主页's 图文 and
        // dynamic rows open the reader. Version chosen by the user.
        versionCode = 29
        versionName = "1.13.6-beta"

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the local debug-style keystore so the APK installs
            // alongside the official com.bilibili.app.in client.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Core library desugaring is intentionally NOT enabled: it forces L8
        // over the whole dependency graph and stalled dexing on this machine.
        // minSdk 26 already provides the java.time APIs we would need it for.
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.accompanist.permissions)

    // Local QR generation for the login screen.
    implementation(libs.zxing.core)

    // JVM unit tests. These cover the pure logic that cannot be verified by
    // compiling alone: the hand-written protobuf danmaku reader, WBI signing,
    // and the display formatters.
    testImplementation(libs.junit)
}
