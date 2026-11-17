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
   - Kotlin の永続データも `/build-cache` 内へ隔離する。デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保持し、コンテナの再作成で署名が変わることを防ぐ。鍵をソースや公開成果物へ含めない。
   - APK の取り出しは既知の root/app の `assemble`・`package` 系・`build` で指定した種類だけ行う。debug・release・androidTest を混ぜず、テスト・設定確認・通知更新・不明 variant ではキャッシュ内の古い APK をコピーしない。
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
├── docs/PROGRESS.md          # 公開用の進捗・検証範囲・未完了事項
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
- **Phase 2: Rebranding & Identity Setup (アプリ名・パッケージ名の早期刷新)** — *命名移行完了・独自アイコンの検証を継続*
  - アプリ表示名・内部命名資産（`Citrus*`）・`citrus:` スキーム・独自権限等を刷新。ソースのパッケージと物理ディレクトリも `io.github.ajichama.citrusbrowser` へ移行済み。
  - 独自ランチャー・通知アイコンの実装、本体ビルドと APK 内の梱包照合は完了。各 OS・ランチャーでの実機表示確認は未実施。
- **Phase 3: Build System & Dependency Modernization (最新ビルド基盤への刷新)** — *正式 API 37 構成へ移行・自動検証済み*
  - JDK 21 / Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12。AGP の built-in Kotlin と、Data Binding を使うモジュールの `com.android.legacy-kapt` に移行済み。Firebase トラッカー削除・`kotlinx.parcelize` 移行を保持。
  - 正式 SDK パッケージを使用し、API 37 SDK の別名ディレクトリ作成と非対応警告抑止を削除。Gradle Wrapper を更新し、配布 ZIP の SHA-256 固定と Wrapper JAR の公式チェックサム照合を実施。
  - OSS 通知は Material 1.14.0 更新後の runtime 102 依存・114 文書とソース由来 18 項目を固定入力として生成。通常生成は通知本文の通信取得を行わず、取得候補の更新は明示タスクとレビューで行う。
  - 通信を切断した環境での通知再生成と APK 内の生成通知の一致も確認済み。ライセンス画面のバー重なりを最小修正し、Android 15の縦画面・現在のテーマ・3ボタンナビでInsets回帰の旧版失敗→修正版成功を確認。横画面・別テーマ・他OSへは一般化しない。
- **Phase 4: Android 16 / 17 (API 36 / 37) OS Compatibility (最新OS仕様対応・非推奨API刷新)** — *基盤・ページ配色実装済み・OS横断検証を継続*
  - `compileSdk 37` / `targetSdk 37` 引き上げ、Edge-to-Edge (`WindowInsetsCompat`)、Predictive Back (`OnBackPressedDispatcher`)、`WebSettingsCompat.setAlgorithmicDarkeningAllowed`、`JobIntentService` 廃止、`IntentCompat`/`BundleCompat`/`ParcelCompat`、Android 11〜17 パッケージ可視性・Scoped Storage 互換対応完了。
  - ページ配色は専用 WebView Theme でアプリ UI と独立し、明色・サイト暗色・サイト優先自動暗色の3選択へ整合。旧値3は2へ読み替え、サイト未指定−1を保持。Android 15の配色計測5件でDOM・入力・JS・読込回数の保持、独立配色、旧key移行、PixelCopy暗色化を確認。他OS・Edge-to-Edge・IME等の操作確認は残る。
- **Phase 5: AdBlock & Feature Evolution (広告ブロック等の機能強化)** ← *Current*
  - AdBlock フィルタエンジン（ABP / uBlock 互換ルール、Cosmetic Filtering、デフォルトフィルタリスト最新化等）の強化。
  - キャッシュ v2 の 32bit 保存・v1 読取互換、行単位ドメイン条件の最具体指定優先、`elemhide` / `generichide`、汎用要素フィルタ無視設定との連動を実装・単体検証済み。
  - minSdk 23 を維持する WorkManager 2.11.2 による永続更新、再試行、通信取消、実 UUID と Lifecycle Flow による画面復元を実装。最新 DB の再確認、自己世代を確認したキャッシュ復旧、UI の列限定更新で競合を防ぐ。
  - Android 15のWorker等計測5件で実Roomの列限定SQL、不正入力Worker、広告OFF時のSkipped、要求UUID・SavedStateモデルを確認。本体Hiltのローカルfile://購読統合2件も成功。外部HTTP購読、実プロセス再起動・Doze・Wi-Fi制約・実画面復元は未確認。
  - リクエスト種別の誤分類、PSL null・IPv6 等の第三者判定、部分一致ルールの索引漏れを修正。比較回数の回帰を用意し、実サイト・実リスト・端末の性能評価を継続する。拡張 uBlock 構文の完全互換や Phase 5 全体の完了は宣言しない。

2026-11-16 の検証ではOSS修正後も `assembleDebug testDebugUnitTest assembleDebugAndroidTest` が成功し、通常モジュールの単体テスト165件が成功。別実行の `:buildSrc:test` は15件成功。修正後の `assembleRelease` もR8・lintを含め成功。SO-51DのAndroid 15（API35）・WebView 157.0.8103.38で配色5件・Worker等5件・本体Hiltローカル購読2件・ライセンスInsets1件の計13件が、最終再実行まで失敗・スキップ0。本体UIは起動・HTTPS・メニュー・タブ・設定/About/OSS導線と見た目のスモーク確認まで、IMEは要求フラグ確認のみ。外部HTTP・回線切替・プロセス/OS再起動・Doze・他OS・本番署名は未確認として、詳細を [公開進捗](docs/PROGRESS.md)へ記録する。

2026-11-17 は旧データの安全な取込・Google検索・Material UI・動的な広告枠への対処・API37の明示LAN許可設定を追加。通常単体247件・buildSrc15件、debug・全計測APK・releaseのR8/lintが成功し、独立レビューも通過。追加計測28件はAPK生成までで、今回の修正版による実データ・実サイト・実画面の検証は未実施。[改善一覧](docs/IMPROVEMENT_PLAN.md)と[実機確認](docs/DEVICE_CHECKS.md)に従い再確認する。

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
