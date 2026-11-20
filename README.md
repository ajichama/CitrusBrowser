# CitrusBrowser

[![license](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

<img src="app/src/main/ic_launcher-web.png" width="192" height="192">

**CitrusBrowser** (`io.github.ajichama.citrusbrowser`) は、オープンソースのAndroid向け高機能・高カスタマイズWebブラウザ **[Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser)**（作者: Hazuki氏）をベースに、最新Android OS（Android 16 / 17）への対応・モダンなアーキテクチャへの刷新・プライバシー強化（トラッカー完全排除）・広告ブロック等の機能進化を行う後継ブラウザプロジェクトです。

---

## 主な特徴・開発方針

- **圧倒的なカスタマイズ性の継承**:
  - ツールバー配置、ソフトボタン、マルチタッチジェスチャー、カスタムアクション、ユーザースクリプト（UserScript）、リソースブロック等の高度なカスタマイズ機能を継承。
- **完全トラッカーフリー（ゼロ・テレメトリ）**:
  - Firebase Analytics / Crashlytics 等の外部解析・追跡SDKを完全に排除。閲覧データや設定はすべて端末ローカルで完結します（詳細は [プライバシーポリシー (Privacy.md)](Privacy.md) を参照）。
- **最新 Android OS（Android 16 / 17 = API 36 / 37）対応**:
  - `compileSdk` / `targetSdk` 37、Edge-to-Edge（全画面インセット制御）、Predictive Back（予測型「戻る」ジェスチャー）、権限・ストレージ互換の基盤と、アプリ UI から独立したページ配色を実装。各 OS での実機検証を進めます。
- **モダンな技術スタックへの段階的刷新**:
  - Kotlin Coroutines / Flow、Dagger Hilt、Room、WorkManager によるフィルタの永続更新・再試行を使用。Material Design 3 (Material You) への移行と、広告ブロック（AdBlock）の実環境での性能改善は継続課題です。

## 開発状況

版ごとの内容と確認の範囲は [公開進捗 (docs/PROGRESS.md)](docs/PROGRESS.md) にあります。公開した版は [Releases](https://github.com/ajichama/CitrusBrowser/releases) から入手できます（Google Play で公開するまではプレリリース）。

---

## モジュール構成

| ディレクトリ | 説明 |
| :--- | :--- |
| `app/` | アプリケーションエントリ・マニフェスト・DI統合モジュール |
| `browser/` | メインブラウザ画面・タブ管理モジュール |
| `module/` | 機能別モジュール群 (`adblock`, `bookmark`, `connecter`, `core`, `download`, `favicon`, `history`, `search`, `ui`, `webview`) |
| `legacy/` | 旧アーキテクチャ資産（段階的にモダン化・機能モジュールへ移行中） |
| `libraries/` | 内蔵UI・権限サブライブラリ (`asyncpermissions`, `colorpicker`, `floatingactionbutton`) |
| `languages/` | 多言語リソース定義モジュール |
| `buildSrc/` | ビルドバージョン・依存ライブラリ一元管理 (`Dependencies.kt`) |
| `docker/` | Docker のビルド環境定義 |

---

## ビルド方法

JDK 21 と Android SDK（API 37 を含む）で、Gradle を使ってビルドします。同じ環境を用意する `docker/Dockerfile` もあります。

```sh
# デバッグ APK のビルドと単体テスト
./gradlew assembleDebug testDebugUnitTest

# 本体・単体テスト・計測テスト APK をまとめて検証（実機実行は含まない）
./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest

# OSS 通知生成処理など、buildSrc の単体テスト
./gradlew :buildSrc:test
```

計測テストの実行には、エミュレーターか実機が必要です。

---

## ドキュメント・リンク

- [開発の規約と技術的な決定 (DEVELOPMENT.md)](docs/DEVELOPMENT.md)
- [公開進捗・検証範囲 (docs/PROGRESS.md)](docs/PROGRESS.md)
- [コントリビューションガイドライン (Contributing.md)](Contributing.md)
- [プライバシーポリシー (Privacy.md)](Privacy.md)

---

## クレジット・謝辞 (Credits)

本プロジェクトは [hazuki0x0/YuzuBrowser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードおよびコミット履歴をベースに改変・開発されています。素晴らしいブラウザを開発・公開された Hazuki 氏およびコントリビューターの皆様に深く感謝いたします。

## ライセンス (License)

    Copyright (C) 2017-2021 Hazuki
    Copyright (C) 2026 ajichama

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
