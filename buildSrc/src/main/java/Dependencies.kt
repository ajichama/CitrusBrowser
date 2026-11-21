/*
 * Copyright (C) 2017-2021 Hazuki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ビルドSDKバージョン定義
object Build {
    const val compile_sdk_version = 37
    const val build_tools_version = "37.0.0"
    const val min_sdk_version = 24
    const val target_sdk_version = 37
}

// Gecko プレビューだけの要件。本体（API24 以上）の SDK・依存 graph へ混ぜない。
object GeckoPreview {
    const val minSdk = 26
    const val compileSdk = 37
    const val compileMinorSdk = 2
    const val geckoviewVersion = "160.0.20261116130455"
    const val geckoview = "org.mozilla.geckoview:geckoview:$geckoviewVersion"
    const val ublockVersion = "1.76.4"
    const val ublockSha256 = "66d0e3f21ce3b32b1bc0a9399b93cb2c9aa598ef18805d92118c76f66e266c1e"
    const val ublockUrl = "https://addons.mozilla.org/firefox/downloads/file/5257481/ublock_origin-1.76.4.xpi"
    const val ublockSource = "https://github.com/gorhill/uBlock/tree/1.76.4"
}

// アプリケーションバージョン定義
object AppVersions {
    // CitrusBrowser としての版（2026-11-21 に Yuzu Browser の 6.0.2 から 1.0.0 へ）。
    // version_code は 主版×1000000＋副版×1000＋修正版。旧版の移行処理（AppData の 410010 等との比較）を再び動かさないよう、Yuzu の番号より大きく保つ。
    const val version_name = "1.0.2"
    const val version_code = 1000002
}

// 依存ライブラリのバージョン一元管理
object Versions {
    const val agp = "9.5.1"
    const val androidX = "1.8.2"
    const val appCompat = "1.7.0"
    const val activityX = "1.9.3"
    const val androidKTX = "1.13.1"
    const val fragmentKtx = "1.8.5"
    const val recyclerView = "1.3.2"
    const val androidxRoom = "2.8.5"
    const val lifeCycle = "2.8.7"
    const val material = "1.14.0"
    const val preference = "1.2.1"
    const val constraintLayout = "2.2.0"
    const val documentFile = "1.0.1"
    const val print = "1.0.0"
    const val swipeRefreshLayout = "1.1.0"
    const val moshi = "1.15.2"
    const val okhttp = "4.12.0"
    const val okio = "3.9.1"
    const val kotlin = "2.4.21"
    const val ksp = "2.3.12"
    const val kotlin_coroutines = "1.10.2"
    const val junit = "4.13.2"
    const val assertk = "0.28.1"
    const val espresso = "3.6.1"
    const val testCore = "1.6.1"
    const val runner = "1.2.1"
    const val instrumentationRunner = "1.6.2"
    const val header_decor = "0.2.8"
    const val materialprogressbar = "1.6.1"
    const val mockito = "3.8.0"
    const val jsoup = "1.18.3"
    const val re2j = "1.8"
    const val webkit = "1.15.0"
    // QR コードの読み取り（#18）。カメラは CameraX、解析は ZXing の core だけを使う。
    const val cameraX = "1.6.2"
    const val zxing = "3.5.3"
    // 2.11 系は minSdk23 の時期に選んだ版。minSdk24 にしたため 2.12 系へ更新できる（目的ができた時点で更新する）。
    const val workManager = "2.11.2"
}

// AndroidX / Jetpack ライブラリ定義
object AndroidX {
    const val annotations = "androidx.annotation:annotation:${Versions.androidX}"
    const val appcompat = "androidx.appcompat:appcompat:${Versions.appCompat}"
    const val design = "com.google.android.material:material:${Versions.material}"
    const val recyclerView = "androidx.recyclerview:recyclerview:${Versions.recyclerView}"
    const val constraintLayout = "androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}"
    const val fix_preference = "androidx.preference:preference-ktx:${Versions.preference}"
    const val KTX = "androidx.core:core-ktx:${Versions.androidKTX}"
    const val fragmentKtx = "androidx.fragment:fragment-ktx:${Versions.fragmentKtx}"
    const val activty = "androidx.activity:activity-ktx:${Versions.activityX}"
    const val room = "androidx.room:room-runtime:${Versions.androidxRoom}"
    const val roomKtx = "androidx.room:room-ktx:${Versions.androidxRoom}"
    const val roomCompiler = "androidx.room:room-compiler:${Versions.androidxRoom}"
    const val documentFile = "androidx.documentfile:documentfile:${Versions.documentFile}"
    const val swipeRefreshLayout = "androidx.swiperefreshlayout:swiperefreshlayout:${Versions.swipeRefreshLayout}"
    const val lifecycle = "androidx.lifecycle:lifecycle-common-java8:${Versions.lifeCycle}"
    const val print = "androidx.print:print:${Versions.print}"
    const val webkit = "androidx.webkit:webkit:${Versions.webkit}"
    const val cameraCamera2 = "androidx.camera:camera-camera2:${Versions.cameraX}"
    const val cameraLifecycle = "androidx.camera:camera-lifecycle:${Versions.cameraX}"
    const val cameraView = "androidx.camera:camera-view:${Versions.cameraX}"
    const val workRuntime = "androidx.work:work-runtime:${Versions.workManager}"
}

// サードパーティおよびKotlin標準ライブラリ定義
object Libs {
    const val androidKTX = AndroidX.KTX

    // JSON / IO
    const val moshi = "com.squareup.moshi:moshi:${Versions.moshi}"
    const val moshiCodeGen = "com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}"
    const val okio = "com.squareup.okio:okio:${Versions.okio}"
    const val zxingCore = "com.google.zxing:core:${Versions.zxing}"

    // Kotlin & Coroutines
    const val kotlin_stdlib = "org.jetbrains.kotlin:kotlin-stdlib:${Versions.kotlin}"
    const val kotlin_coroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.kotlin_coroutines}"
    const val kotlin_coroutines_android = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.kotlin_coroutines}"

    // テスト用ライブラリ
    const val junit = "junit:junit:${Versions.junit}"
    const val espressoCore = "androidx.test.espresso:espresso-core:${Versions.espresso}"
    const val androidTestCore = "androidx.test:core:${Versions.testCore}"
    const val androidRunner = "androidx.test.ext:junit:${Versions.runner}"
    // JUnit 拡張とは別に、計測 APK のエントリポイント本体を明示する。
    const val androidInstrumentationRunner = "androidx.test:runner:${Versions.instrumentationRunner}"
    const val assertk = "com.willowtreeapps.assertk:assertk-jvm:${Versions.assertk}"
    const val mockito = "org.mockito:mockito-core:${Versions.mockito}"

    // HTTP / HTML / 正規表現 / UI補助
    const val okhttp = "com.squareup.okhttp3:okhttp:${Versions.okhttp}"
    const val header_decor = "ca.barrenechea.header-decor:header-decor:${Versions.header_decor}"
    const val materialProgressBar = "me.zhanghai.android.materialprogressbar:library:${Versions.materialprogressbar}"
    const val materialScrollBar = "com.github.turing-tech:MaterialScrollBar:13.3.4"
    const val jsoup = "org.jsoup:jsoup:${Versions.jsoup}"
    const val re2j = "com.google.re2j:re2j:${Versions.re2j}"
}

// Gradle プラグイン定義
object Dependencies {
    const val kotlin_plugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.kotlin}"
}

// DI (Dagger Hilt) 定義
object Dagger {
    private const val hiltVersion = "2.60.1"

    const val hiltPlugin = "com.google.dagger:hilt-android-gradle-plugin:$hiltVersion"
    const val hilt = "com.google.dagger:hilt-android:$hiltVersion"
    const val hiltCompiler = "com.google.dagger:hilt-compiler:$hiltVersion"
}
