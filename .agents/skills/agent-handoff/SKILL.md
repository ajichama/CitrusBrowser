---
name: agent-handoff
description: >-
  Antigravity・Codex・Claude Code の間で、引き継ぎ台帳・作業ロック・利用枠を共有して作業を交代する手順。
  セッションの開始時、編集に着手する前、利用枠の警告を受けたとき、作業を中断・交代・完了するときに使う。
---

# エージェント間の引き継ぎ

台帳（`.agents/handoff/HANDOFF.md` / `state.json`）・ロック・レポートは Git 管理外のローカル状態。操作は MCP `citrus-collab`（`handoff_read` / `handoff_update` / `task_claim` / `task_release` / `usage_status`）か、MCP を使えない場合は同じ処理を呼ぶ CLI で行う。

```powershell
node tools/citrus-mcp/cli.mjs read
node tools/citrus-mcp/cli.mjs claim --task <ID> --agent claude --scope module/adblock,docs --ttl 120
node tools/citrus-mcp/cli.mjs update --json <入力JSONファイル>   # 項目は下の「更新の内容」
node tools/citrus-mcp/cli.mjs release --task <ID> --agent claude --outcome paused
```

エージェント名は `antigravity` / `codex` / `claude` / `human`。CLI のロックは「同じエージェント名」なら別プロセスから解放できる（MCP はプロセスごとの所有者）。

## 開始時

1. 台帳を読み、`git log --oneline -5` と `git status --short` で実際の状態と照らし合わせる（台帳の HEAD・ロック・端末の状態は古いことがある）。
2. 利用枠を `usage_status` で確認する（AGENTS.md 第8節）。
3. 編集する範囲だけをロックする（リポジトリ全体 `.` は避ける）。他のエージェントの有効なロックを `--force` で奪うのは、ユーザーが承認した場合だけ。

## 中断・交代・完了時

1. 変更をビルドが通る状態に戻すか、確定（`git-publish`）する。
2. 台帳を更新する。
3. ロックを解放する。

### 更新の内容

| 項目 | 書くこと |
| --- | --- |
| `agent` / `nextAgent` | 自分 / 次の担当（`any` も可） |
| `taskId` / `phase` | タスク ID / 現在の段階を短く |
| `summary` | 平文で600字程度まで。「完了したこと（コミットのハッシュ、Push の有無）／未完了・未検証のこと／戻すべき一時状態／次の一手」を分けて書く |
| `nextObjective` | 次の担当が最初に着手する具体的な目標 |
| `acceptanceCriteria` / `targetFiles` / `constraints` | 合格条件 / 主な対象ファイル / 守るべき制約 |
| `verificationRecord` | `{task, status, verifiedAt, agent, note}`。最後に成功した検証コマンド |

- 担当記号（A/B/C）や英単語の羅列（freeze, PASS 等）だけの圧縮表現を使わない。詳細はレポート（`.agents/handoff/reports/`）のパスで示す。
- 端末の設定、起動中のエミュレーター、一時ファイルなど「戻すべき状態」は必ず書く。

## 利用枠ガード

Claude Code はステータスラインが `%USERPROFILE%\.citrus\usage\claude.json` を書き、`.claude/settings.json` の hooks が閾値を超えたときに警告を出す。Codex は rollout 記録から `scripts/usage-guard.mjs` が読み取り、`.agents/hooks/codex-hooks.json` を `.codex/hooks.json` へ複製して `/hooks` で承認すると警告が出る。Antigravity は残量を取得できない（`unavailable`）ので、作業を小さく区切って台帳を更新する。

## Codex への委譲

`codex_delegate`（MCP）または `pwsh -File scripts/codex-delegate.ps1 -Mode exec|review` を使う。結果は `.agents/handoff/reports/` に保存される。委譲した変更も、委譲元が監査してから公開する。
