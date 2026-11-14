---
name: multi-agent-workflow
description: >-
  CitrusBrowser におけるマルチエージェント開発体制（Orchestrator-Workers パターン）と
  コンテキスト（トークン/メモリ）最適化の標準運用手順。タスクの調査・実装・レビューを進める際に参照する。
---

# Multi-Agent Orchestration & Context Hygiene Workflow

Gemini (Antigravity) / Claude Code / ChatGPT (OpenAI Agents) で提唱されているマルチエージェント・コンテキストエンジニアリングのベストプラクティスに基づき、メインエージェント（Orchestrator）のコンテキストウィンドウ肥大化を防ぎ、高い推論精度を維持するための運用手順です。

## 1. 役割分担（Orchestrator-Specialist パターン）

1. **Orchestrator（メインエージェント）**:
   - ユーザーとの対話、全体アーキテクチャ設計、タスク分解、最終的な Docker ビルド＆Git Push の統括に専念する。
   - **禁止事項**: メインコンテキスト内で多数のファイルを連続して `read_file` したり、複数モジュールにわたる機械的な編集を直接行ってトークンを浪費しないこと。
2. **`code-explorer`（調査・影響範囲分析サブエージェント / Read-Only）**:
   - コードベース全体の検索、変更対象ファイルの特定、API利用箇所の洗い出しを担当する。
   - 並列調査が可能な場合は複数の `code-explorer` を同時に起動してよい。
3. **`module-implementer`（モジュール実装サブエージェント / Write-Enabled）**:
   - モジュール単位または機能単位に切り分けられた実装・リファクタリング作業を独立コンテキスト内で実行する。
   - ファイルの読み込み・編集（`edit_file` / `write_file`）のトランザクションをサブエージェント内に閉じ込め、メインエージェントには「変更ファイル一覧と要約」のみを返す。
4. **`code-reviewer`（品質監査・コードレビューサブエージェント / Read-Only）**:
   - 実装完了後、`AGENTS.md` の規約遵守、ライセンスヘッダ保持、参照漏れや退行バグの有無を独立した視点で監査する。

## 2. 標準タスク実行サイクル

```text
[1. 調査 (Explore)]  -> code-explorer サブエージェントへ調査を委譲し、対象一覧を取得
[2. 実装 (Implement)] -> スコープ別に module-implementer サブエージェントへ実装を委譲
[3. 監査 (Review)]    -> code-reviewer サブエージェントで変更漏れ・規約違反をチェック
[4. 検証 (Verify)]    -> Docker ビルド (assembleDebug & testDebugUnitTest) で検証し Git Push
```

## 3. コンテキスト衛生管理（Context Compaction & Externalization）

- **進捗の外部化**: ロードマップやタスク状態は常にアーティファクト（`modernization_plan.md`）に記録し、会話履歴への依存度を下げる。
- **サブエージェント再利用**: 同一モジュールに対する追加修正が発生した場合は、新規サブエージェントを乱立させず `send_message` で既存の担当サブエージェント（`conversationId`）に継続指示を送る。
