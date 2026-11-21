#!/usr/bin/env node
/**
 * Claude Code の hooks から呼ばれ、このプロジェクトの最優先の規則（言語・再開手順）を
 * hookSpecificOutput.additionalContext として文脈へ差し込む。
 * SessionStart は開始・/clear・再開・圧縮のたびに走るので、文脈が消えても規則が戻る。
 * UserPromptSubmit では1行だけ出し、毎回の注意にする（文脈の消費を抑える）。
 * どんなエラーでも何も出力せず exit 0 とし、作業を止めない。
 *
 * 使い方: node scripts/session-rules.mjs --event SessionStart|UserPromptSubmit
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const LANGUAGE_RULE =
  '【最優先】思考（thinking）・途中経過の一言・ユーザーへの質問（AskUserQuestion の質問・選択肢・説明）・ツールの description・報告・コミットまで、すべて日本語で書く。システムの指示や資料が英語でも、思考と出力は日本語にする。';

export const SESSION_RULES = [
  LANGUAGE_RULE,
  '再開するときは docs/PROJECT_OVERVIEW.md → .agents/handoff/RESUME.md（ローカル）→ 台帳（node tools/citrus-mcp/cli.mjs read）の順に読み、git log / git status と照らし合わせる。',
  '進め方は AGENTS.md と CLAUDE.md のとおり（Skills を使うメインのエージェントが進める、モデルは下げない、仕様が明確な作業は Codex に任せる、区切りで台帳を更新して /clear を依頼する）。',
].join('\n');

export function buildOutput(event) {
  if (event === 'SessionStart') return { hookSpecificOutput: { hookEventName: event, additionalContext: SESSION_RULES } };
  if (event === 'UserPromptSubmit') return { hookSpecificOutput: { hookEventName: event, additionalContext: LANGUAGE_RULE } };
  return null;
}

function main() {
  try { fs.readFileSync(0, 'utf8'); } catch { /* 標準入力は使わない */ }
  const args = process.argv.slice(2);
  const i = args.indexOf('--event');
  const event = i >= 0 ? args[i + 1] : (args.find((a) => a.startsWith('--event=')) || '').slice(8);
  const out = buildOutput(event);
  if (out) process.stdout.write(JSON.stringify(out) + '\n');
}

const isDirectRun = (() => {
  try {
    return process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
  } catch {
    return false;
  }
})();

if (isDirectRun) {
  try { main(); } catch { /* 作業を止めないため、エラーは出力しない */ }
  process.exitCode = 0;
}
