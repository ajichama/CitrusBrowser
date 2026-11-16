# CitrusBrowser

[![license](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

<img src="app/src/main/ic_launcher-web.png" width="192" height="192">

**CitrusBrowser** (`io.github.ajichama.citrusbrowser`) は、オープンソースのAndroid向け高機能・高カスタマイズWebブラウザ **[Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser)**（作者: Hazuki氏）をベースに、**ajichama** が最新Android OS（Android 16 / 17）への対応・モダンなアーキテクチャへの刷新・プライバシー強化（トラッカー完全排除）・広告ブロック等の機能進化を行う後継ブラウザプロジェクトです。

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

正式な API 37 対応構成として JDK 21 / Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12 を採用しています。SDK の別名配置や非対応警告の抑止を使わずビルドします。

2026-11-16 の実装スナップショットは、本体・計測テスト APK のビルド、通常モジュールの単体テスト 165 件、buildSrc の単体テスト 15 件が成功しました。ページ配色と旧設定の移行、WorkManager による永続更新・再試行、広告の種別・第三者判定と索引を改善しています。OSS画面の最小修正後も、リリース構成は R8・lint を含め成功しました。本番用署名は未設定です。OSS 通知は runtime 102 依存を固定入力から生成し、APK 内の一致と通信なしの再生成を確認しています。独自アイコンの全画素・参照も確認済みです。

Android 15（SO-51D、WebView 157.0.8103.38）で、配色5件・Worker等5件・本体Hiltローカル購読統合2件・ライセンスInsets回帰1件の計測テスト計13件が、最終再実行も失敗・スキップ0で成功しました。更新テストはRoom列限定SQL、広告OFF時のSkipped、UUID・SavedStateモデルと、本体のfile://購読更新・無効購読skipが対象です。外部HTTP購読、回線切替、実プロセス・OS再起動、Dozeや他OSを検証済みとはしていません。

本体の起動・HTTPS表示・メニュー・タブ・設定/About/OSSの導線と見た目をスモーク確認しました。OSS本文とバーの重なりは最小修正し、旧版で回帰失敗・修正版で成功を確認。同端末の縦画面・現在のテーマ・3ボタンナビで非重なりを確認した範囲です。IMEは要求フラグのみ確認し、詳細動作や全操作を確認済みとはしていません。

実サイトでの広告判定と性能の評価、更新インストールや各種操作、アプリ UI 全体のテーマ刷新は継続課題です。[公開進捗と検証範囲](docs/PROGRESS.md)に完了した実装と残タスク、[実機確認手順](docs/DEVICE_CHECKS.md)に実施結果と次の確認項目を記載しています。

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
| `docker/` / `scripts/` | Docker 隔離ビルド環境定義およびビルド自動化スクリプト |

---

## ビルド方法（Docker 隔離ビルド）

Windows ホスト環境に JDK や Android SDK を直接インストールすることなく、Docker コンテナ (`citrus-android-builder`) 内でクリーンにビルドおよび単体テストを実行できます。中間生成物（`build/` や `.gradle`）はすべて Docker Volume 内に隔離されます。

```powershell
# デバッグAPKのビルド（出力先: release/app-debug.apk）
pwsh -File scripts/docker-build.ps1

# 単体テストの実行
pwsh -File scripts/docker-build.ps1 -Task "testDebugUnitTest"

# 本体・単体テスト・計測テストAPKをまとめて検証（実機実行は含まない）
pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest"

# OSS通知生成処理など、buildSrc の単体テスト
pwsh -File scripts/docker-build.ps1 -Task ":buildSrc:test"

# Docker イメージの再構築を伴うビルド
pwsh -File scripts/docker-build.ps1 -RebuildImage
```

テスト・`help`・OSS 通知更新のみの実行では、キャッシュ内の古い APK を `release/` へコピーしません。APK を指定した場合も、その種類だけを取り出します。デバッグ署名鍵と adb 鍵は Docker Named Volume `citrus-android-home` に保持します。

---

## ドキュメント・リンク

- [開発規約・ロードマップ (AGENTS.md)](AGENTS.md)
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
