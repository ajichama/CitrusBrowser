# Project Constitution & Agent Guidelines (`AGENTS.md`)

## 1. Project Overview (プロジェクト概要)

**CitrusBrowser** (`isiakiaji/CitrusBrowser`) は、オープンソースのAndroid向け高機能Webブラウザ **Yuzu Browser** (`hazuki0x0/YuzuBrowser`, Apache License 2.0) の全コミット履歴を継承し、最新のAndroid OS対応・モダンなUIデザイン・広告ブロック等の機能強化を行う後継ブラウザ開発プロジェクトです。

---

## 2. Core Engineering Principles (開発・運用原則)

1. **All-Japanese Operation (完全日本語運用の徹底)**:
   - **コマンド実行前の確認説明（`toolSummary` 等）、作成する各種ドキュメント、計画書（アーティファクト）、コード内コメント（新規追加分）、ユーザーへの報告・質問はすべて日本語で行うことを徹底する。**
2. **Clean Host & Zero Dropbox Pollution (Docker隔離ビルド & Dropbox衛生管理)**:
   - Windowsホスト環境を汚さないため、JDK・Android SDK・Gradleはすべて **Dockerコンテナ (`yuzu-android-builder`)** 内で実行する。
   - 本ワークスペースは Dropbox 配下にあるため、各モジュールの `build/` 中間生成物および `.gradle` キャッシュはすべて Docker Named Volume (`yuzu-build-cache`, `yuzu-gradle-cache`) に隔離し、完成した `.apk` のみを `release/` に出力する（[docker/init.gradle](docker/init.gradle) および [scripts/docker-build.ps1](scripts/docker-build.ps1)）。
   - `.git` や `release/` 等のディレクトリには必ず `pwsh -File scripts/setup-dropbox-ignore.ps1` を適用して Dropbox 同期対象から除外する。
3. **Apache License 2.0 Compliance (ライセンス遵守)**:
   - 既存ソースコード内の `Copyright (C) 2017-2021 Hazuki` および Apache License 2.0 ヘッダはそのまま保持する。
   - 後継アプリとして独自のアプリ名（**CitrusBrowser**）・パッケージID（`applicationId`）・アイコンを採用しつつ、Yuzu Browser（作者: Hazuki氏）をベースとしている旨を明記する。
4. **Always Buildable & Testable (`main` ブランチの品質維持)**:
   - コミットおよび Push 前には、Docker ビルド (`pwsh -File scripts/docker-build.ps1`) および単体テストが通ることを確認する。

---

## 3. Directory Structure (ディレクトリ構成)

```text
.
├── AGENTS.md                 # プロジェクト全体の憲法・開発規約（本ファイル）
├── docker/
│   ├── Dockerfile            # Android SDK (API 30/35) + JDK 11/17 ビルド環境定義
│   └── init.gradle           # ビルド中間生成物を Docker Volume (/build-cache) へ隔離する設定
├── scripts/
│   ├── docker-build.ps1      # Docker コンテナ内でのビルド＆APK抽出スクリプト
│   ├── setup-dropbox-ignore.ps1 # Dropbox 同期除外設定スクリプト
│   └── setup-github-ssh.ps1  # GitHub Deploy Key (github-android-browser) セットアップ
├── release/                  # ビルド済み APK 出力先（Git管理外・Dropbox同期除外）
├── buildSrc/                 # ビルドバージョン・依存ライブラリ一元管理 (Dependencies.kt)
├── app/                      # アプリケーションエントリ・パッケージ定義モジュール
├── browser/                  # メインブラウザ画面モジュール
├── legacy/                   # 旧アーキテクチャ資産（段階的にモダン化対象）
├── libraries/                # 内蔵サブライブラリ (asyncpermissions, colorpicker, floatingactionbutton)
└── module/                   # 機能別モジュール群 (adblock, bookmark, core, download, favicon, history, search, ui, webview 等)
```

---

## 4. Development Roadmap (開発ロードマップ)

- **Phase 1: Repository & Docker Build Infrastructure (リポジトリ＆Dockerビルド基盤構築)** ← *Current*
  - `hazuki0x0/YuzuBrowser` の全履歴取り込み、`isiakiaji/CitrusBrowser` への初回Push完了。
  - Docker ビルド環境の構築と、閉鎖済みリポジトリ (`jcenter`, `maven.fabric.io` 等) の整理によるベースライン APK ビルドの確立。
- **Phase 2: Build System & Dependency Modernization (ビルドシステム＆依存ライブラリの最新化)**
  - Gradle 8.x / Android Gradle Plugin (AGP) 8.x / Kotlin 最新安定版への段階的アップデート。
  - 廃止された `kotlin-android-extensions` (Synthetic) から ViewBinding 等への移行。
- **Phase 3: Latest Android OS Compatibility & Rebranding (最新OS対応＆CitrusBrowserブランド化)**
  - `compileSdk` / `targetSdk` を最新 Android (API 35 / Android 15+) へ引き上げ。
  - ストレージ権限、通知権限 (`POST_NOTIFICATIONS`)、ForegroundService、WebView 仕様変更への対応。
  - アプリ名（`CitrusBrowser`）および `applicationId` の刷新。
- **Phase 4: UI/UX Modernization (デザイン・UIの刷新)**
  - Edge-to-Edge 表示対応、Material Design 3 (Material You) コンポーネント・ダイナミックカラー対応、モダンなナビゲーション体験への改良。
- **Phase 5: AdBlock & Feature Evolution (広告ブロック等の機能強化)**
  - AdBlock フィルタエンジン（ABP / uBlock 互換ルール、Cosmetic Filtering、スクリプトインジェクション等）の最新化と高速化。

---

## 5. Git & GitHub Workflow (コード管理・SSH運用)

- **Remote Configuration**:
  - `origin`: `git@github-android-browser:isiakiaji/CitrusBrowser.git` （SSH Deploy Key エイリアス `github-android-browser`）
  - `upstream`: `https://github.com/hazuki0x0/YuzuBrowser.git`
- **Commit Convention**:
  - Conventional Commits 形式（`feat:`, `fix:`, `build:`, `refactor:`, `docs:`, `chore:`）を使用する。
