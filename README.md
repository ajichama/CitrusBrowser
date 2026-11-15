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
  - `compileSdk` / `targetSdk` 37、Edge-to-Edge（全画面インセット制御）、Predictive Back（予測型「戻る」ジェスチャー）、権限・ストレージ互換の基盤を実装。各 OS での実機検証とページ配色の刷新は今後実施します。
- **モダンな技術スタックへの段階的刷新**:
  - Kotlin Coroutines / Flow、Dagger Hilt、Room を使用。WorkManager による更新の永続化、Material Design 3 (Material You) への移行、広告ブロック（AdBlock）の性能改善は継続課題です。

## 開発状況

正式な API 37 対応構成として JDK 21 / Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12 を採用しています。SDK の別名配置や非対応警告の抑止を使わずビルドします。

2026-11-15 の実装スナップショットは、本体・計測テスト APK のビルドと通常モジュールの単体テスト 128 件が成功しました。buildSrc の単体テスト 15 件も別途成功しています。AdBlock のキャッシュ互換・ドメイン条件・要素非表示例外と、固定入力からの OSS 通知生成を改善済みです。独自アイコンと新しい OSS 通知は APK 内の梱包を照合済みで、通知の通信なしでの再生成も確認しています。

実機でのテスト、ページ配色の新実装、更新ジョブの永続化などは未完了です。[公開進捗と検証範囲](docs/PROGRESS.md) に、完了した実装と残タスクを記載しています。

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

テスト・`help`・OSS 通知更新のみの実行では、キャッシュ内の古い APK を `release/` へコピーしません。デバッグ署名鍵と adb 鍵は Docker Named Volume `citrus-android-home` に保持します。

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
