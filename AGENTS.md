# Project Constitution & Agent Guidelines (`AGENTS.md`)

> 経緯・体制・機能・設計の意図・方針の全体像は [docs/PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md) にまとめています。新しいセッションでは、まずそれを読んでください。
>
> 本ファイルは Antigravity / Codex / Claude Code のすべてのエージェントが**毎回読み込む規約**です。日付付きの検証結果・件数・所要時間などの進捗はここへ書かず、[公開進捗](docs/PROGRESS.md)・[実機確認](docs/DEVICE_CHECKS.md)・[内蔵uBO計画](docs/ADBLOCK_ENGINE_PLAN.md)・[改善一覧](docs/IMPROVEMENT_PLAN.md)へ記録してください（全エージェントのコンテキスト消費を抑えるため）。

## 1. Project Overview (プロジェクト概要)

**CitrusBrowser** (`ajichama/CitrusBrowser`) は、オープンソースのAndroid向け高機能Webブラウザ **Yuzu Browser** (`hazuki0x0/YuzuBrowser`, Apache License 2.0) の全コミット履歴を継承し、最新のAndroid OS対応・モダンなUIデザイン・広告ブロック等の機能強化を行う後継ブラウザ開発プロジェクトです。

---

## 2. Core Engineering Principles (開発・運用原則)

1. **All-Japanese Operation (完全日本語運用の徹底)**:
   - **コマンド実行前の確認説明、作成する各種ドキュメント、計画書、コード内コメント（新規追加分）、ユーザーへの報告・質問はすべて日本語で行う。**
2. **Always Verify Latest Versions via Web Search (前提知識に頼らない最新仕様の裏付け調査)**:
   - OSバージョン（Android 16 = API 36 / **Android 17 = API 37**）、ビルドツール（AGP 9.x / Gradle 9.x）、言語・ライブラリ（Kotlin 2.4.x / Hilt 2.60.x / Room / OkHttp 等）の選定・移行にあたっては、モデルの古い内部知識で決めつけず、必ず **各エージェントの Web 検索機能および公式SDKリポジトリ（`sdkmanager --list`）で現時点の最新安定版と破壊的変更を確認してから設計・実装する**。
3. **Clean Host & Zero Dropbox Pollution (Docker隔離ビルド & Dropbox衛生管理)**:
   - Windowsホスト環境を汚さないため、JDK・Android SDK・Gradle・adb はすべて **Dockerコンテナ (`citrus-android-builder`)** 内で実行する。
   - 本ワークスペースは Dropbox 配下にあるため、各モジュールの `build/` 中間生成物および `.gradle` キャッシュはすべて Docker Named Volume (`citrus-build-cache`, `citrus-gradle-cache`) に隔離し、完成した `.apk` のみを `release/` に出力する（[docker/init.gradle](docker/init.gradle) および [scripts/docker-build.ps1](scripts/docker-build.ps1)）。
   - Kotlin の永続データも `/build-cache` 内へ隔離する。デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保持し、コンテナの再作成で署名が変わることを防ぐ。鍵をソースや公開成果物へ含めない。リリース署名の鍵は Named Volume `citrus-release-key` だけに置き、Release を含むタスクのときだけ読み取り専用でつなぐ（作成・バックアップは scripts/release-key.ps1、手順は [docs/RELEASE.md](docs/RELEASE.md)）。Named Volume の削除禁止の対象に含める。
   - APK の取り出しは既知の root/app の `assemble`・`package` 系・`build` で指定した種類だけ行う。debug・release・androidTest を混ぜず、テスト・設定確認・通知更新・不明 variant ではキャッシュ内の古い APK をコピーしない。
   - 独立プレビューだけは明示した `:gecko-preview:assembleDebug` を `release/gecko-preview-debug.apk` へ取り出す。本体APKの出力先と保存領域は共有しない。
   - `.git`、`release/`、`.agents/handoff/reports` 等には必ず `pwsh -File scripts/setup-dropbox-ignore.ps1` を適用して Dropbox 同期対象から除外する。
4. **Apache License 2.0 Compliance (ライセンス遵守)**:
   - 既存ソースコード内の `Copyright (C) 2017-2021 Hazuki` および Apache License 2.0 ヘッダはそのまま保持する。
   - 後継アプリとして独自のアプリ名（**CitrusBrowser**）・パッケージID（`applicationId`）・アイコンを採用しつつ、Yuzu Browser（作者: Hazuki氏）をベースとしている旨を明記する。
5. **Always Buildable & Testable (`main` ブランチの品質維持)**:
   - コミットおよび Push 前には、Docker ビルド (`pwsh -File scripts/docker-build.ps1`) および単体テストが通ることを確認する。
6. **Skills を持つメインエージェントを基本とする (Skills-first & Context Hygiene)**:
   - 繰り返す手順は Skills（[`.agents/skills/`](.agents/skills/)）に書き、必要なときだけ読み込む（Progressive Disclosure）。メインのエージェントが Skills を使って1つの流れで進めることを基本とする（Anthropic の公式見解: Skills を持つ単一ループはサブエージェント構成より品質・コスト・待ち時間で上回ることが多い）。
   - サブエージェント（Antigravity の `invoke_subagent`、Claude Code の Agent ツール、Codex のサブエージェント等）は次の3つの場合に限る: (1) 大量の読み取りで文脈が膨らむ調査、(2) 独立した作業の並列化（編集範囲を重ねない）、(3) リスクの高い変更の独立監査（読み取り専用）。結論・根拠（ファイル:行）・未解決事項だけを返させ、長いログは `.agents/handoff/reports/` に保存してパスで参照する。判断基準と進め方は [`multi-agent-workflow`](.agents/skills/multi-agent-workflow/SKILL.md)。
   - 利用枠（レート制限）を使い切って作業が中断することも品質上のリスクとして扱う（第8節）。
   - Skills や進め方を変えたら、[評価セット](.agents/evals/golden-cases.md) の模範例に照らして、守るべき振る舞いが失われていないかを確かめる。

---

## 3. Directory Structure (ディレクトリ構成)

```text
.
├── AGENTS.md                 # 全エージェント共通の規約（本ファイル。進捗は書かない）
├── .mcp.json                 # Claude Code 用の citrus-collab 接続設定（相対パスのみ）
├── .agents/
│   ├── skills/               # 共通スキルの正本（docker-build-verify, git-publish, emulator-measure, adblock-verify, oss-notices, agent-handoff, multi-agent-workflow）
│   ├── evals/                # 進め方・Skills の評価セット（模範例）
│   ├── hooks/                # Codex 用 hooks の雛形（利用枠ガード）
│   └── handoff/              # 引き継ぎ台帳・ロック・レポート（Git管理外のローカル状態）
├── .claude/                  # Claude Code 用（settings.json の hooks、skills/ は正本を指す生成ファイル、agents/ は専門サブエージェントの定義）
├── tools/citrus-mcp/         # 引き継ぎ台帳・ロック・Codex委譲・Docker検証を提供する共通 MCP サーバー
├── docker/
│   ├── Dockerfile            # Android SDK (API 30/36/37) + JDK 11/17/21 ビルド環境定義
│   └── init.gradle           # ビルド中間生成物を Docker Volume (/build-cache) へ隔離する設定
├── scripts/
│   ├── docker-build.ps1      # Docker コンテナ内でのビルド＆APK抽出スクリプト
│   ├── setup-dropbox-ignore.ps1 # Dropbox 同期除外設定スクリプト
│   └── setup-github-ssh.ps1  # GitHub Deploy Key (github-android-browser) セットアップ
├── release/                  # ビルド済み APK 出力先（Git管理外・Dropbox同期除外）
├── buildSrc/                 # ビルドバージョン・依存ライブラリ一元管理 (Dependencies.kt)
├── docs/                     # PROGRESS（公開進捗）・DEVICE_CHECKS・ADBLOCK_ENGINE_PLAN・IMPROVEMENT_PLAN
├── app/                      # アプリケーションエントリ・パッケージ定義モジュール
├── browser/                  # メインブラウザ画面モジュール
├── legacy/                   # 旧アーキテクチャ資産（段階的にモダン化対象）
├── gecko-preview/            # min26/compile37.2 の独立 Gecko・内蔵uBO検証アプリ
├── gecko-ui-probe/           # 外部 runtime 依存なし・debug専用の標準UI検証用アプリ
├── libraries/                # 内蔵サブライブラリ (asyncpermissions, colorpicker, floatingactionbutton)
└── module/                   # 機能別モジュール群 (adblock, bookmark, core, download, engine-api, favicon, gecko, history, search, ui, webview 等)
```

---

## 4. Roadmap & Technical Decisions (ロードマップと維持すべき技術的決定)

### フェーズの現在地（詳細・検証範囲は [docs/PROGRESS.md](docs/PROGRESS.md)）

- **Phase 1: リポジトリ＆Dockerビルド基盤** — 完了。
- **Phase 2: アプリ名・パッケージ名の刷新** — 命名移行・独自アイコン実装は完了。各OS・ランチャーでの実機表示確認は未実施。
- **Phase 3: ビルド基盤と依存の刷新** — 完了。現構成は JDK21 / Gradle9.6.1 / AGP9.5.1 / Kotlin2.4.21 / Hilt2.60.1 / KSP2.3.12。
- **Phase 4: Android 16 / 17 (API 36 / 37) 対応** — 基盤実装済み。他OS・Edge-to-Edge・IME等の横断検証を継続。
- **Phase 5: AdBlock と機能強化** ← *Current*。本体の広告遮断エンジン改善、独立アプリでの GeckoView＋原本uBO 内蔵の実証、`module:engine-api` による本体からの段階的接続を進めている。

### 維持すべき技術的決定（変更する場合はユーザーと合意し、本節を更新する）

- **SDK 構成**: 本体は minSdk24 / compileSdk37 / targetSdk37（minSdk は 2026-11-20 に利用者の決定で 23 から 24 へ。理由と各段階の比較は [ADBLOCK_STRATEGY.md](docs/ADBLOCK_STRATEGY.md) 第4節）。別 applicationId の `:gecko-preview` / `:module:gecko` だけが minSdk26 / compileSdk37.2 を使い、本体と保存領域を共有しない。
- **ビルド**: AGP の built-in Kotlin と、Data Binding を使うモジュールの `com.android.legacy-kapt` を維持する。依存は目的のある更新だけ行い、無関係に Kotlin/Hilt 等を上げない。Gradle Wrapper の配布ZIP SHA-256 固定と Wrapper JAR の公式チェックサム照合を維持する。
- **OSS 通知**: 固定入力から生成し、通常生成では通信しない。取得候補の更新は明示タスクとレビューで行う。本体とプレビューの通知は別に管理し、Gecko の `about:license` と原本uBOの GPL 通知も保持する。
- **内蔵uBO**: SHA を検証した原本を改造せずに同梱する。private RPC・拡張 storage を直接書き換えない。uBOの有効表示を、フィルタの準備完了や初回起動の保護の証拠にしない。
- **広告遮断の判定**: 成功条件は汎用処理（実通信・実DOM）で判定し、サイト個別ルールの追加を成功条件にしない。uBlock 拡張構文の完全互換や Phase 5 全体の完了は宣言しない。
- **scriptlet のライセンス（2026-11-19 ユーザー決定）**: 本体は Apache-2.0 を維持する。scriptlet は uBO と名前・引数だけを合わせた独自実装とし、uBO の scriptlet のソース（GPLv3）を参照・流用しない。uBO wiki の公開仕様と実サイトの挙動だけを元に書く（クリーンルーム）。uBO 原本との完全互換が必要な用途は GeckoView＋uBO 原本の別アプリで受け持つ。GPLv3 化や実行時取得へ変える場合は、ユーザーと改めて合意する（詳細 `.agents/handoff/reports/scriptlet-policy-comparison.md`）。
- **エンジン共通API**: `module:engine-api` は Android/Gecko の型を公開しない。保存形式・操作ID・履歴ロック・既定操作を保持し、未実装の操作を成功扱いしない。旧タブの Bundle・ログイン状態・POST を別エンジンへ無言で移さない。
- **ページ配色**: 専用 WebView Theme でアプリ UI と独立させる（明色・サイト暗色・サイト優先自動暗色の3択）。

### 検証結果の記録ルール

- ビルド・単体テストの成功と実機での成功を分けて記録する。失敗した試行を成功件数に含めず、検証していない OS・端末・条件へ一般化しない。
- 実機接続情報・個人画面・鍵・private ログは Git に含めない。端末の設定を一時的に変えた場合は、作業終了・交代前に元へ戻し、戻すまでは台帳に明記する。

---

## 5. Git & GitHub Workflow (コード管理・SSH運用)

- **Remote Configuration**:
  - `origin`: `git@github-android-browser:ajichama/CitrusBrowser.git` （SSH Deploy Key エイリアス `github-android-browser`）
  - `upstream`: `https://github.com/hazuki0x0/YuzuBrowser.git`
- **Commit Convention**:
  - Conventional Commits 形式（`feat:`, `fix:`, `build:`, `refactor:`, `docs:`, `chore:`）を使用する。

---

## 6. Multi-Agent Collaboration & Handoff (Antigravity / Codex / Claude Code 協調運用)

本リポジトリは **Antigravity (Gemini)**・**OpenAI Codex**・**Claude Code** が同一コードベース・MCP・スキルを共有して作業を引き継げる設計になっています。台帳とロックのエージェント名は `antigravity` / `codex` / `claude` / `human` です。Claude Code はプロジェクト共有の [`.mcp.json`](.mcp.json)（相対パスのみ）から `citrus-collab` を読み込みます。MCP を使えない場合は同じ処理を呼ぶ CLI（`node tools/citrus-mcp/cli.mjs read|update|claim|release`）を使います。手順の詳細は [`agent-handoff`](.agents/skills/agent-handoff/SKILL.md)。

共通スキルの正本は `.agents/skills/` に置き、Claude Code 用の `.claude/skills/` は `node scripts/sync-agent-skills.mjs` で生成する（正本を変えたら実行し、`--check` でずれがないことを確かめる）。

1. **セッション開始時の引き継ぎ確認**:
   - 作業着手前に MCP `citrus-collab` の `handoff_read` を呼び出すか、`.agents/handoff/HANDOFF.md`（Git管理外のローカル引き継ぎ台帳）を確認する。台帳の HEAD・ロック・端末状態は古いことがあるため、`git log` / `git status` と照合する。
2. **排他ロックによる二重編集防止**:
   - 変更に着手する際は `task_claim` でタスクロックを取得し、交代・完了時に `task_release` で解放する。
   - ロック範囲は実際に編集するパスに絞り（リポジトリ全体 `.` は避ける）、TTL は作業見込みに合わせる。他エージェントの有効なロックの強制取得は、ユーザーの承認がある場合に限る。
3. **セッション終了・交代時の構造化引き継ぎ**:
   - エージェントを切り替える際は必ず `handoff_update`（または [`.agents/skills/agent-handoff/SKILL.md`](.agents/skills/agent-handoff/SKILL.md) の手順）で進捗サマリー・次目標・受け入れ条件・関連ファイルを記録する。
   - サマリーは**人間が読める平文で600字程度まで**とし、「完了したこと／未完了・未検証のこと／戻すべき一時状態／次の一手」を分けて書く。担当記号（A/B/C）や英単語の羅列（freeze, PASS 等）だけの圧縮表現を避け、詳細はレポートのパスで示す。
   - 利用枠の上限が近づいたら、上限に達する前に台帳を更新する（段階と手順は第8節）。
4. **ハイブリッド委譲と共通検証**:
   - Codex への委譲には `codex_delegate` MCP または `pwsh -File scripts/codex-delegate.ps1` を使用する。
   - どのエージェントが変更を加えた場合でも、最終検証には必ず `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest"` を使用する。

## 7. 共有ファイルとローカル状態の管理

- `AGENTS.md`、`.mcp.json`、`.agents/skills/`・`evals/`・`hooks/`、`.claude/settings.json`・`.claude/skills/`・`.claude/agents/`、`.agents/plugins/citrus-collab/`、`tools/citrus-mcp/`、再利用可能な `scripts/` は Git 管理する。接続テンプレートに個人の絶対パス・認証情報を入れない。
- `.agents/handoff/`（台帳・ロック・レポート）、`.codex/`、`AGENTS.override.md` は全員に共通するローカル状態として `.gitignore` で除外する。
- `.agents/handoff/reports/` は作業記録の置き場であり、保管庫ではない。公開済みの成果に対応する古いビルドログや、外部から取得した生データは、作業が完了したら整理する。
- 個人だけのメモや補助ファイルは `.git/info/exclude` に記載する。ホーム配下のアプリ設定・秘密鍵は共有しない。
- 公開範囲を差分で確認してから対象を明示してステージする。未コミット全件の自動追加を行わない。

## 8. 利用枠に応じた作業の区切り

利用枠（レート制限）の上限で作業が途中で止まらないよう、上限に達する前にビルドが通る状態と台帳の更新を残す。記録は `%USERPROFILE%\.citrus\usage\`（ローカル・非共有）に置き、判定は [`scripts/usage-guard.mjs`](scripts/usage-guard.mjs) が行う。

- 作業開始時と大きな作業の前に `usage_status`（MCP `citrus-collab`）で残量を確認する。Claude Code（[`.claude/settings.json`](.claude/settings.json)）と Codex（[`.agents/hooks/codex-hooks.json`](.agents/hooks/codex-hooks.json) を `.codex/hooks.json` へ複製）は hooks が閾値超過時に自動で警告する。
- 5時間枠90%未満（週枠80%未満）: 抑えずに通常どおり進める（2026-11-20 利用者の決定）。
- 5時間枠90%以上（週枠80%以上）: 新しい大作業（複数モジュールの変更・全体ビルド）を始めない。
- 93%以上（週枠90%以上）: 今の変更をビルドが通る状態に戻すか退避し、`handoff_update` で進捗・次の目標・未検証の項目を記録する。保有するロックは第6節どおり `task_release`（`outcome: "paused"`）で解放する。
- 97%以上（週枠97%以上）、または上限の警告を受けたら: 新しい作業を止め、台帳の更新だけを行って中断する。
- `unavailable` / `stale`（Antigravity は公式の取得手段がないため常に `unavailable`）は残量不明として扱い、作業単位を小さく保ち、区切りごとに台帳を更新する。推定値で判断しない。
- 中断時は未コミットの変更の有無と、最後に成功した検証コマンドを必ず台帳に残す。
