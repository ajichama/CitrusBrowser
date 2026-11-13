# Project Constitution & Agent Guidelines (`AGENTS.md`)

## 1. Project Overview (プロジェクト概要)

**CitrusBrowser** (`ajichama/CitrusBrowser`) は、オープンソースのAndroid向け高機能Webブラウザ **Yuzu Browser** (`hazuki0x0/YuzuBrowser`, Apache License 2.0) の全コミット履歴を継承し、最新のAndroid OS対応・モダンなUIデザイン・広告ブロック等の機能強化を行う後継ブラウザ開発プロジェクトです。

---

## 2. Core Engineering Principles (開発・運用原則)

1. **All-Japanese Operation (完全日本語運用の徹底)**:
   - **コマンド実行前の確認説明（`toolSummary` 等）、作成する各種ドキュメント、計画書（アーティファクト）、コード内コメント（新規追加分）、ユーザーへの報告・質問はすべて日本語で行うことを徹底する。**
2. **Always Verify Latest Versions via Web Search (前提知識に頼らない最新仕様の裏付け調査)**:
   - OSバージョン（Android 16 = API 36 / **Android 17 = API 37**）、ビルドツール（AGP 9.x / Gradle 9.x）、言語・ライブラリ（Kotlin 2.4.x / Hilt 2.60.x / Room / OkHttp 等）の選定・移行にあたっては、モデルの古い内部知識で決めつけず、必ず **Web検索（`search_web`）および公式SDKリポジトリ（`sdkmanager --list`）で現時点（2026年）の最新安定版と破壊的変更を確認してから設計・実装する**。
3. **Clean Host & Zero Dropbox Pollution (Docker隔離ビルド & Dropbox衛生管理)**:
   - Windowsホスト環境を汚さないため、JDK・Android SDK・Gradleはすべて **Dockerコンテナ (`yuzu-android-builder`)** 内で実行する。
   - 本ワークスペースは Dropbox 配下にあるため、各モジュールの `build/` 中間生成物および `.gradle` キャッシュはすべて Docker Named Volume (`yuzu-build-cache`, `yuzu-gradle-cache`) に隔離し、完成した `.apk` のみを `release/` に出力する（[docker/init.gradle](docker/init.gradle) および [scripts/docker-build.ps1](scripts/docker-build.ps1)）。
   - `.git` や `release/` 等のディレクトリには必ず `pwsh -File scripts/setup-dropbox-ignore.ps1` を適用して Dropbox 同期対象から除外する。
4. **Apache License 2.0 Compliance (ライセンス遵守)**:
   - 既存ソースコード内の `Copyright (C) 2017-2021 Hazuki` および Apache License 2.0 ヘッダはそのまま保持する。
   - 後継アプリとして独自のアプリ名（**CitrusBrowser**）・パッケージID（`applicationId`）・アイコンを採用しつつ、Yuzu Browser（作者: Hazuki氏）をベースとしている旨を明記する。
5. **Always Buildable & Testable (`main` ブランチの品質維持)**:
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

- **Phase 1: Repository & Docker Build Infrastructure (リポジトリ＆Dockerビルド基盤構築)** — *Completed*
  - `hazuki0x0/YuzuBrowser` の全履歴取り込み、`ajichama/CitrusBrowser` への初回Push完了。
  - Docker ビルド環境の構築と、閉鎖済みリポジトリ (`jcenter`, `maven.fabric.io`)・`kvs-schema` の整理によるベースライン APK ビルドの確立。
- **Phase 2: Rebranding & Identity Setup (アプリ名・パッケージ名の早期刷新)** ← *Current*
  - アプリ表示名を **CitrusBrowser** に変更。
  - パッケージID（`applicationId`）および関連する独自権限（`permission.myapp.signature`）・ショートカット定義の刷新。
- **Phase 3: Build System & Dependency Modernization (2026年最新ビルド基盤への刷新)**
  - Docker 環境への JDK 21 および **Android 17 (API 37) / Android 16 (API 36)** SDK の導入。
  - Gradle 9.x / Android Gradle Plugin (AGP) 9.x（または段階的に 8.x → 9.x） / Kotlin 2.4.x / Dagger Hilt 2.60.x / Room 2.8.x (または 3.x) へのアップデート。
  - 廃止された `kotlinx.android.parcel` 等から最新の `kotlinx.parcelize` / ViewBinding / KSP への移行。
- **Phase 4: Android 16 / 17 (API 36 / 37) OS Compatibility & UI Modernization (最新OS仕様対応・デザイン刷新)**
  - `compileSdk 37` / `targetSdk 37`（**Android 17: Cinnamon Bun**）への引き上げ。
  - **Edge-to-Edge 完全強制化（API 36+ で opt-out 廃止）** への対応（`enableEdgeToEdge()` と `WindowInsetsCompat` によるステータスバー・ナビゲーションバー・IME 余白制御）。
  - **Predictive Back（予測型「戻る」ジェスチャー、API 36+ 標準化）** 対応（旧 `onBackPressed()` から `OnBackPressedDispatcher` / `OnBackInvokedCallback` への移行）。
  - **大画面・折りたたみ端末での向き制限撤廃（API 36+）**、**バックグラウンド音声・ForegroundService・通知/ストレージ権限（API 33〜37）** への対応。
  - Material Design 3 (Material You) テーマ・ダイナミックカラー対応。
- **Phase 5: AdBlock & Feature Evolution (広告ブロック等の機能強化)**
  - AdBlock フィルタエンジン（ABP / uBlock 互換ルール、Cosmetic Filtering、スクリプトインジェクション等）の最新化と高速化。

---

## 5. Git & GitHub Workflow (コード管理・SSH運用)

- **Remote Configuration**:
  - `origin`: `git@github-android-browser:ajichama/CitrusBrowser.git` （SSH Deploy Key エイリアス `github-android-browser`）
  - `upstream`: `https://github.com/hazuki0x0/YuzuBrowser.git`
- **Commit Convention**:
  - Conventional Commits 形式（`feat:`, `fix:`, `build:`, `refactor:`, `docs:`, `chore:`）を使用する。
