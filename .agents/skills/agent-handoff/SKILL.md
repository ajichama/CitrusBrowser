---
name: agent-handoff
description: >-
  Antigravity (Gemini) と OpenAI Codex 間で作業コンテキスト・進捗・タスクロックを相互に引き継ぐ標準運用手順。
  セッション開始時の引き継ぎ確認、トークン上限対策の交代、Codex CLI へのサブタスク委譲時に参照する。
---

# Dual-Agent Handoff & Interoperability Workflow (Antigravity ↔ Codex)

本リポジトリは **Antigravity (Gemini)** と **OpenAI Codex（CLI / IDE拡張 / Desktopアプリ）** を併用・相互引き継ぎしながら開発を進めるための共通ハーネス（Shared Harness）を備えています。

---

## 1. 共有基盤の構成（Git 管理対象 vs ローカル専有）

- **共有規約・スキル（Git 管理）**:
  - `AGENTS.md`: 両エージェント共通のプロジェクト憲法・技術規約。
  - `.agents/skills/`: Antigravity・Codex 共通のワークフロースキル群。
  - `tools/citrus-mcp/server.mjs`: 両エージェント共通の MCP サーバー (`citrus-collab`)。
  - `scripts/codex-delegate.ps1` / `scripts/setup-ai-collab.ps1`: 連携＆環境登録スクリプト。
- **ローカル動的引き継ぎ状態（`.gitignore` 対象・GitHub 非公開）**:
  - `.agents/handoff/state.json`: 構造化された最新開発状態（フェーズ・アクティブタスク・制約・履歴）。
  - `.agents/handoff/HANDOFF.md`: 人間にも AI にも一読で状況が伝わる Markdown 引き継ぎ台帳。
  - `.agents/handoff/locks/*.lock.json`: 二重編集防止用の TTL 付き排他ロック。
  - `.agents/handoff/reports/*.md`: `codex exec` / `codex review` 実行レポート保存先。

---

## 2. 標準運用パターン（2つのハイブリッド運用）

### パターン A: クオータ節約・モデル交代時の手動引き継ぎ（Antigravity ↔ Codex）

トークン上限や作業の区切りでエージェントを切り替える際、チャット履歴ではなく **外部化された引き継ぎ台帳** を介してバトンタッチします。

1. **引き継ぐ側（退任エージェント）の手順**:
   - 編集中の変更が中途半端でないか確認し、可能であれば `docker_verify` でビルド状態を確認する。
   - MCP ツール **`handoff_update`**（または `.agents/handoff/state.json` と `HANDOFF.md` の更新）を実行し、以下を必ず構造化して記録する：
     - `summary`: どこまで完了し、何が未完か
     - `nextAgent`: `"codex"` または `"antigravity"`
     - `nextObjective`: 次のエージェントが最初に着手すべき具体的目標
     - `acceptanceCriteria`: 完了・合格条件
     - `targetFiles`: 参照・編集すべき主要ファイル一覧
   - 取得中のタスクロックがあれば **`task_release`** で解放する。
2. **引き継がれる側（新任エージェント）の手順**:
   - セッション開始直後に MCP **`handoff_read`** を呼ぶか、`.agents/handoff/HANDOFF.md` を読む。
   - 必要に応じ **`task_claim`** で該当タスクの排他ロックを取得してから作業を開始する。
   - 完了または再度中断する際は、再び **`handoff_update`** → **`task_release`** でバトンを返す。

### パターン B: ハイブリッド委譲（Antigravity 作業中の Codex CLI 呼び出し）

Antigravity を設計・統括役（Orchestrator）として維持したまま、個別モジュールの実装や独立レビューだけを Codex に任せる運用です。

- **MCP ツール経由**: `codex_delegate` ツール（`mode: "exec" | "review"`, `prompt`, `sandbox`）を呼び出す。
- **ターミナルコマンド経由**:
  ```powershell
  # 実装タスクを Codex CLI に委譲（出力は .agents/handoff/reports/ に保存）
  pwsh -File scripts/codex-delegate.ps1 -Mode exec -Prompt "HANDOFF.md に従い ... を実装してください"

  # 現在の未コミット差分を Codex に独立レビューさせる
  pwsh -File scripts/codex-delegate.ps1 -Mode review -Uncommitted
  ```

---

## 3. 二重編集防止とビルド検証の鉄則

1. 同じファイルを Antigravity と Codex で同時に編集しないこと（着手前に `task_claim` / `handoff_read` でロック状況を確認する）。
2. コードを変更したエージェントは、最終的に必ず Docker 隔離ビルド＆テストを実行する：
   ```powershell
   pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest"
   ```
3. Git へのコミット・Push は Dropbox ロック回避のため **`scripts/git-commit-push.ps1`** を使用する。公開対象だけを事前にステージして差分を確認する。Push は依頼範囲に含まれる場合だけ `-Push` を明示し、既定ではローカルコミットに留める。
