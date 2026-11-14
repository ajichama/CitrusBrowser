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
    const val min_sdk_version = 23
    const val target_sdk_version = 37
}

// アプリケーションバージョン定義
object AppVersions {
    const val version_name = "6.0.2"
    const val version_code = 410021
}

// 依存ライブラリのバージョン一元管理
object Versions {
    const val agp = "8.13.2"
    const val androidX = "1.8.2"
    const val appCompat = "1.7.0"
    const val activityX = "1.9.3"
    const val androidKTX = "1.13.1"
    const val fragmentKtx = "1.8.5"
    const val recyclerView = "1.3.2"
    const val androidxRoom = "2.8.5"
    const val lifeCycle = "2.8.7"
    const val material = "1.12.0"
    const val preference = "1.2.1"
    const val constraintLayout = "2.2.0"
    const val documentFile = "1.0.1"
    const val print = "1.0.0"
    const val swipeRefreshLayout = "1.1.0"
    const val moshi = "1.15.2"
    const val okhttp = "4.12.0"
    const val okio = "3.9.1"
    const val kotlin = "2.3.21"
    const val ksp = "2.3.12"
    const val kotlin_coroutines = "1.10.2"
    const val junit = "4.13.2"
    const val assertk = "0.28.1"
    const val espresso = "3.6.1"
    const val testCore = "1.6.1"
    const val runner = "1.2.1"
    const val header_decor = "0.2.8"
    const val materialprogressbar = "1.6.1"
    const val mockito = "3.8.0"
    const val jsoup = "1.18.3"
    const val re2j = "1.8"
    const val webkit = "1.12.1"
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
}

// サードパーティおよびKotlin標準ライブラリ定義
object Libs {
    const val androidKTX = AndroidX.KTX

    // JSON / IO
    const val moshi = "com.squareup.moshi:moshi:${Versions.moshi}"
    const val moshiCodeGen = "com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}"
    const val okio = "com.squareup.okio:okio:${Versions.okio}"

    // Kotlin & Coroutines
    const val kotlin_stdlib = "org.jetbrains.kotlin:kotlin-stdlib:${Versions.kotlin}"
    const val kotlin_coroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.kotlin_coroutines}"
    const val kotlin_coroutines_android = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.kotlin_coroutines}"

    // テスト用ライブラリ
    const val junit = "junit:junit:${Versions.junit}"
    const val espressoCore = "androidx.test.espresso:espresso-core:${Versions.espresso}"
    const val androidTestCore = "androidx.test:core:${Versions.testCore}"
    const val androidRunner = "androidx.test.ext:junit:${Versions.runner}"
    const val assertk = "com.willowtreeapps.assertk:assertk-jvm:${Versions.assertk}"
    const val mockito = "org.mockito:mockito-core:${Versions.mockito}"

    // HTTP / HTML / 正規表現 / UI補助
    const val okhttp = "com.squareup.okhttp3:okhttp:${Versions.okhttp}"
    const val header_decor = "ca.barrenechea.header-decor:header-decor:${Versions.header_decor}"
    const val materialProgressBar = "me.zhanghai.android.materialprogressbar:library:${Versions.materialprogressbar}"
    const val jsoup = "org.jsoup:jsoup:${Versions.jsoup}"
    const val re2j = "com.google.re2j:re2j:${Versions.re2j}"
}

// Gradle プラグイン定義
object Dependencies {
    const val kotlin_plugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.kotlin}"
}

// DI (Dagger Hilt) 定義
object Dagger {
    private const val hiltVersion = "2.58"

    const val hiltPlugin = "com.google.dagger:hilt-android-gradle-plugin:$hiltVersion"
    const val hilt = "com.google.dagger:hilt-android:$hiltVersion"
    const val hiltCompiler = "com.google.dagger:hilt-compiler:$hiltVersion"
}
