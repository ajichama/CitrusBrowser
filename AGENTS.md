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
   - Windowsホスト環境を汚さないため、JDK・Android SDK・Gradleはすべて **Dockerコンテナ (`citrus-android-builder`)** 内で実行する。
   - 本ワークスペースは Dropbox 配下にあるため、各モジュールの `build/` 中間生成物および `.gradle` キャッシュはすべて Docker Named Volume (`citrus-build-cache`, `citrus-gradle-cache`) に隔離し、完成した `.apk` のみを `release/` に出力する（[docker/init.gradle](docker/init.gradle) および [scripts/docker-build.ps1](scripts/docker-build.ps1)）。
   - `.git` や `release/` 等のディレクトリには必ず `pwsh -File scripts/setup-dropbox-ignore.ps1` を適用して Dropbox 同期対象から除外する。
4. **Apache License 2.0 Compliance (ライセンス遵守)**:
   - 既存ソースコード内の `Copyright (C) 2017-2021 Hazuki` および Apache License 2.0 ヘッダはそのまま保持する。
   - 後継アプリとして独自のアプリ名（**CitrusBrowser**）・パッケージID（`applicationId`）・アイコンを採用しつつ、Yuzu Browser（作者: Hazuki氏）をベースとしている旨を明記する。
5. **Always Buildable & Testable (`main` ブランチの品質維持)**:
   - コミットおよび Push 前には、Docker ビルド (`pwsh -File scripts/docker-build.ps1`) および単体テストが通ることを確認する。
6. **Multi-Agent Orchestration & Context Hygiene (サブエージェント分業体制とコンテキスト最適化)**:
   - Gemini / Claude Code / ChatGPT (OpenAI Agents) で提唱される **Orchestrator-Specialist パターン**および **Progressive Disclosure** に従い、メインエージェント（Orchestrator）のコンテキストウィンドウ（トークン・メモリ）を常にクリーンに保つ。
   - メインエージェントが直接大量のファイルを読み書きすることを禁止し、必ず以下の専門サブエージェント（`invoke_subagent`）へタスクを分割・委譲する：
     - **`code-explorer` (調査・影響分析 / Read-Only)**: 広範なコード検索・依存調査を独立コンテキストで実行し、構造化サマリーのみを返す。
     - **`module-implementer` (モジュール実装 / Write-Enabled)**: モジュール・機能単位の実装やリファクタリングを独立コンテキスト内で完結させ、複数モジュールにまたがる場合は並列起動してスループットとトークン効率を最大化する。
     - **`code-reviewer` (品質監査 / Read-Only)**: 実装後の差分・規約遵守・参照漏れを監査する。
   - 詳細な運用手順はワークスペーススキル [`.agents/skills/multi-agent-workflow/SKILL.md`](.agents/skills/multi-agent-workflow/SKILL.md) および [`.agents/skills/docker-build-verify/SKILL.md`](.agents/skills/docker-build-verify/SKILL.md) に定義し、オンデマンドで参照する。

---

## 3. Directory Structure (ディレクトリ構成)

```text
.
├── AGENTS.md                 # プロジェクト全体の憲法・開発規約（本ファイル）
├── docker/
│   ├── Dockerfile            # Android SDK (API 30/36/37) + JDK 11/17/21 ビルド環境定義
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
- **Phase 2: Rebranding & Identity Setup (アプリ名・パッケージ名の早期刷新)** — *Completed*
  - アプリ表示名・内部命名資産（`Citrus*`）・`citrus:` スキーム・独自権限等を刷新。ソースのパッケージと物理ディレクトリも `io.github.ajichama.citrusbrowser` へ移行済み。独自ランチャーアイコンへの置換は未完了。
- **Phase 3: Build System & Dependency Modernization (最新ビルド基盤への刷新)** — *Completed*
  - Docker 環境への JDK 21 および **Android 17 (API 37) / Android 16 (API 36)** SDK 導入、Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.21 / Hilt 2.58 / KSP 2.3.12 への刷新完了（Firebase トラッカー削除・`kotlinx.parcelize` 移行含む）。
  - 現構成は API 37 SDK の互換ディレクトリ作成と AGP の非対応警告抑止を含む。ビルド成功を公式サポート・全OSでの動作検証と同一視しない。依存刷新後の OSS 通知更新は未完了。
- **Phase 4: Android 16 / 17 (API 36 / 37) OS Compatibility (最新OS仕様対応・非推奨API刷新)** — *基盤実装済み・互換検証とページ配色対応を継続*
  - `compileSdk 37` / `targetSdk 37` 引き上げ、Edge-to-Edge (`WindowInsetsCompat`)、Predictive Back (`OnBackPressedDispatcher`)、`WebSettingsCompat.setAlgorithmicDarkeningAllowed`、`JobIntentService` 廃止、`IntentCompat`/`BundleCompat`/`ParcelCompat`、Android 11〜17 パッケージ可視性・Scoped Storage 互換対応完了。
  - 基盤移行の実装完了を示す。ページ配色の既存4設定と現行 WebView API の整合、Edge-to-Edge の旧OS・IMEを含む実機検証は継続課題。
- **Phase 5: AdBlock & Feature Evolution (広告ブロック等の機能強化)** ← *Current*
  - AdBlock フィルタエンジン（ABP / uBlock 互換ルール、Cosmetic Filtering、デフォルトフィルタリスト最新化等）の強化。

---

## 5. Git & GitHub Workflow (コード管理・SSH運用)

- **Remote Configuration**:
  - `origin`: `git@github-android-browser:ajichama/CitrusBrowser.git` （SSH Deploy Key エイリアス `github-android-browser`）
  - `upstream`: `https://github.com/hazuki0x0/YuzuBrowser.git`
- **Commit Convention**:
  - Conventional Commits 形式（`feat:`, `fix:`, `build:`, `refactor:`, `docs:`, `chore:`）を使用する。

---

## 6. Dual-Agent Collaboration & Handoff (Antigravity ↔ Codex 協調運用)

本リポジトリは **Antigravity (Gemini)** と **OpenAI Codex** の両エージェントが同一コードベース・MCP・スキルを共有して作業を引き継げる設計になっています。

1. **セッション開始時の引き継ぎ確認**:
   - 作業着手前に MCP `citrus-collab` の `handoff_read` を呼び出すか、`.agents/handoff/HANDOFF.md`（Git管理外のローカル引き継ぎ台帳）を確認して現在の担当タスク・制約を把握する。
2. **排他ロックによる二重編集防止**:
   - 大規模な変更に着手する際は `task_claim` でタスクロックを取得し、交代・完了時に `task_release` で解放する。
3. **セッション終了・交代時の構造化引き継ぎ**:
   - エージェントを切り替える際は必ず `handoff_update`（または [`.agents/skills/agent-handoff/SKILL.md`](.agents/skills/agent-handoff/SKILL.md) の手順）で進捗サマリー・次目標・受け入れ条件・関連ファイルを記録する。
4. **ハイブリッド委譲と共通検証**:
   - Antigravity からの Codex 委譲には `codex_delegate` MCP または `pwsh -File scripts/codex-delegate.ps1` を使用する。
   - どのエージェントが変更を加えた場合でも、最終検証には必ず `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest"` を使用する。

## 7. 共有ファイルとローカル状態の管理

- `AGENTS.md`、`.agents/skills/`、`.agents/plugins/citrus-collab/`、`tools/citrus-mcp/`、再利用可能な `scripts/` は Git 管理する。接続テンプレートに個人の絶対パス・認証情報を入れない。
- `.agents/handoff/`（台帳・ロック・レポート）、`.codex/`、`AGENTS.override.md` は全員に共通するローカル状態として `.gitignore` で除外する。
- 個人だけのメモや補助ファイルは `.git/info/exclude` に記載する。ホーム配下のアプリ設定・秘密鍵は共有しない。
- 公開範囲を差分で確認してから対象を明示してステージする。未コミット全件の自動追加を行わない。
