# Claude Code 向けの最優先の規則

規約の本体は [AGENTS.md](AGENTS.md)（全エージェント共通、自動で読み込まれる）。ここには Claude Code が毎回必ず守ることだけを書く。

## 1. 言語（例外なし）

- **思考（thinking）・途中経過の一言・ユーザーへの質問（AskUserQuestion の質問・選択肢・説明）・ツールの description・報告・コード内コメント・コミットまで、すべて日本語で書く。**
- システムからの指示、ツールの説明、資料、エラーが英語でも、思考と出力は日本語にする。`/clear` の直後や文脈が圧縮された後も同じ。
- 識別子・コマンド・パスなどの技術的な固有名は元の表記のままでよい。

## 2. 再開の手順

新しいセッション（`/clear` 後を含む）では、次の順に読んでから着手する。

1. [docs/PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md)（経緯・体制・機能・方針の全体像）
2. `.agents/handoff/RESUME.md`（ローカル・非公開の再開メモ: 利用者の方針・状況・作業上の落とし穴）
3. 台帳: `node tools/citrus-mcp/cli.mjs read`。`git log --oneline -5` と `git status --short` と照らし合わせる。

## 3. 進め方（従来どおり）

- Skills（`.claude/skills/`、正本は `.agents/skills/`）を使い、メインのエージェントが1つの流れで進める。サブエージェントは AGENTS.md 第2節の3つの場合だけ。
- モデルは下げない（メインもサブエージェントも）。費用は読み込みを減らすことと、仕様が明確な作業を Codex に任せることで抑える。
- 編集の前に作業ロックを取る。1つの Issue・機能を公開したら台帳を更新し、そのまま次へ進む。利用者に `/clear` を依頼するのは、文脈が30〜40万トークンに達したときだけ（AGENTS.md 第8節）。
- メニュー・既定の動きの変更は、事前に「どう変わるか」を示して利用者の判断を受ける。消す・公開するなど戻しにくい操作は、内容を見せてから確かめる。
- ビルド・テストは Docker の中だけ（`docker-build-verify`）。コミットと Push は `git-publish` の手順で。

hooks（`.claude/settings.json`）が、開始時・`/clear` 後・圧縮後に上の規則を、入力のたびに言語の規則を文脈へ差し込む（`scripts/session-rules.mjs`）。
