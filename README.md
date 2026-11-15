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
  - Edge-to-Edge（全画面インセット制御）、Predictive Back（予測型「戻る」ジェスチャー）、最新の権限・バックグラウンド実行仕様への準拠。
- **モダンな技術スタックへの段階的刷新**:
  - Kotlin Coroutines / Flow、Dagger Hilt、Room、Jetpack WorkManager、Material Design 3 (Material You) への移行と、広告ブロック（AdBlock）エンジンの高速化。

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

# Docker イメージの再構築を伴うビルド
pwsh -File scripts/docker-build.ps1 -RebuildImage
```

---

## ドキュメント・リンク

- [開発規約・ロードマップ (AGENTS.md)](AGENTS.md)
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
