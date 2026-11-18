# CitrusBrowser Multi-AI Collaboration MCP Server (`citrus-collab`)

Antigravity (Gemini) と OpenAI Codex（CLI / IDE 拡張 / Desktop アプリ）が同一リポジトリ上で安全にコンテキスト・進捗・作業ロック・ビルド検証を共有するための **ゼロ依存ローカル MCP (Model Context Protocol) サーバー** です。

## 特徴

- **外部 npm 依存ゼロ (`node_modules` 不要)**: Node.js 標準モジュールのみで構成し、Dropbox 配下のワークスペースを肥大化させません。
- **Structured Blackboard & Handoff Contract**: 状態 JSON (`.agents/handoff/state.json`) と可読台帳 (`.agents/handoff/HANDOFF.md`) を同時管理し、モデル・クオータ切替時の文脈欠落を防ぎます。
- **排他タスクロック (`task_claim` / `task_release`)**: 対象パスが重なるタスクを別 MCP プロセスからも二重取得できないようにします（TTL 期限切れ自動解放つき）。実際のファイル書き込みを OS 権限で禁止する仕組みではないため、全担当が着手前にロックを取得してください。
- **ハイブリッド連携 (`codex_delegate` / `docker_verify`)**: Antigravity からの Codex CLI 非対話委譲や、両エージェント共通の Docker ビルド検証を単一コマンド経路で実行できます。
- **利用枠の確認 (`usage_status`)**: `scripts/usage-guard.mjs` の記録（`%USERPROFILE%\.citrus\usage\`）を `ok` / `stale` / `unavailable` で返します。推定値は返さず、Antigravity は常に `unavailable` です（AGENTS.md 第8節）。

## セットアップ

Node.js、PowerShell (`pwsh`)、Git を利用します。Codex 委譲には Codex CLI、Android の検証には Docker が必要です。

```powershell
# 既定ではプロジェクト内の設定だけを更新する
pwsh -File scripts/setup-ai-collab.ps1

# ホスト側の Gemini / Codex への MCP 登録も行う場合
pwsh -File scripts/setup-ai-collab.ps1 -RegisterHost

# 上記に加え、このワークスペースの Codex 信頼登録も行う場合
pwsh -File scripts/setup-ai-collab.ps1 -RegisterHost -TrustWorkspace
```

`-LocalOnly` は既定動作と同じです。`-SkipGemini` / `-SkipCodex` はホスト側の各製品への登録を省略します。MCP 登録だけでは既存のプロジェクト信頼設定を変更しません。

既存ファイルは変更前に同じディレクトリへ `.bak.*` としてバックアップします。既存のモデル・サンドボックス設定、他の MCP 登録、対象 MCP の管理外キーは保持します。TOML 更新処理は管理対象キーの単一行値に対応します。複数行文字列や管理対象キーの複数行配列を含む設定は、安全に自動更新できないため元ファイルを変更せず停止します。その場合はバックアップ元を参照して手動で登録を統合してください。

`codex-delegate.ps1 -Mode review -Sandbox read-only` は review にも指定のサンドボックスを適用します。`-DryRun` で実際に組み立てる引数を確認できます（レポート用ディレクトリは作成します）。

## Git に共有する内容とローカル状態

規約・スキル、`tools/citrus-mcp/`、汎用セットアップ／委譲スクリプトを共有します。`.agents/plugins/citrus-collab/` は登録テンプレートです。グローバル MCP 登録との二重起動を避けるため既定では無効です。

`.agents/handoff/`、`.codex/`、ホームディレクトリの設定・認証情報はローカル状態です。Git に追加しないでください。バックアップにも既存設定が入るため、設定ファイルと同じ管理範囲に置きます。

## ロックの範囲と復旧

- `scopePaths` はワークスペース相対パスを推奨します。省略・空配列はワークスペース全体を対象にします。
- `module/adblock` と `module/adblock/filter` のような親子関係も競合します。区切り文字、Windows の大小文字、既存パスのリンク先を正規化し、ワークスペース外への指定を拒否します。
- 同じ `agent` 名の別セッションも別所有者です。同一セッションでも通常の再取得は拒否します。解放は取得した MCP プロセスで行ってください。旧形式のロックは同じ agent から解放できます。
- `force:true` は指定した taskId の上書き専用です。別 taskId の対象パス競合は無視しません。`agent:"human"` からの解放は手動復旧用です。利用前に実際の担当が作業を終了していることを確認してください。
- 共通排他用の `.store-mutex` は、Dropbox 同期除外済みの `.agents/handoff/locks/` 配下に作成します。解放時の一時的な `EBUSY` / `EPERM` は約1秒まで再試行し、解消しない場合はエラーとして停止します。
- 異常終了で `.agents/handoff/locks/.store-mutex` が残った場合は20秒で待機を打ち切ります。稼働中のロックを自動で奪取することはありません。全 MCP サーバーを停止したことを確認してから、この空ディレクトリだけを手動で除去し、再起動してください。タスクロックの破損も無視せず処理を停止します。

`docker_verify` は `assembleDebug`、`testDebugUnitTest`、`assembleDebug testDebugUnitTest` のみ受け付けます。クライアントのスキーマ検証に加えてサーバー側でも引数を検証します。

## テスト実行

```powershell
node --test tools/citrus-mcp/test-mcp.mjs
```

複数サーバー間の取得競合と所有権、パス境界、不正な実行引数、期限切れ／破損ロックを検証します。PowerShell がある環境では、仮ワークスペースと仮ホーム内だけで既存設定の保全・バックアップ・登録の明示指定・レビュー引数も検証します。実際のホーム設定、Codex API、Docker ビルドはこのテストから呼び出しません。
