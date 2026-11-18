#!/usr/bin/env node
/**
 * citrus-collab の台帳・ロックを MCP なしで操作する CLI。
 * MCP を読み込めないセッションや、スクリプトからの定型操作で使う（中身は MCP と同じ handoff-store）。
 *
 *   node tools/citrus-mcp/cli.mjs read
 *   node tools/citrus-mcp/cli.mjs update --json <入力JSONファイル>
 *   node tools/citrus-mcp/cli.mjs claim --task <ID> --agent <名前> --scope a,b [--ttl 分] [--force]
 *   node tools/citrus-mcp/cli.mjs release --task <ID> --agent <名前> [--outcome completed|paused|aborted] [--note 文]
 *
 * ロックの所有者は既定で `cli:<agent>` とし、同じエージェントの別プロセスから解放できる。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const AGENTS = new Set(['antigravity', 'codex', 'claude', 'human']);

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const options = {};
  for (let i = 0; i < rest.length; i++) {
    const key = rest[i];
    if (!key.startsWith('--')) throw new Error(`不明な引数です: ${key}`);
    const name = key.slice(2);
    if (name === 'force') { options.force = true; continue; }
    const value = rest[i + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`${key} に値が必要です。`);
    options[name] = value;
    i++;
  }
  return { command, options };
}

function requireAgent(options) {
  if (!AGENTS.has(options.agent)) throw new Error('--agent は antigravity / codex / claude / human のいずれかです。');
  return options.agent;
}

async function main() {
  const { command, options } = parseArgs(process.argv.slice(2));
  if (options.agent && AGENTS.has(options.agent) && !process.env.CITRUS_SESSION_OWNER) {
    // handoff-store は読込時に所有者を確定するため、import より前に設定する。
    process.env.CITRUS_SESSION_OWNER = `cli:${options.agent}`;
  }
  const here = path.dirname(fileURLToPath(import.meta.url));
  const store = await import(pathToFileURL(path.join(here, 'handoff-store.mjs')).href);
  const root = store.resolveWorkspaceRoot(options.workspace);

  switch (command) {
    case 'read': {
      const { markdownPath } = store.getHandoffPaths(root);
      process.stdout.write(fs.existsSync(markdownPath) ? fs.readFileSync(markdownPath, 'utf8') : '台帳はまだありません。\n');
      return;
    }
    case 'update': {
      if (!options.json) throw new Error('--json に入力ファイルを指定してください。');
      const input = JSON.parse(fs.readFileSync(options.json, 'utf8').replace(/^﻿/, ''));
      requireAgent(input);
      for (const key of ['summary', 'nextAgent', 'nextObjective']) {
        if (!input[key]) throw new Error(`入力 JSON に ${key} がありません。`);
      }
      const saved = store.updateHandoffState(root, input);
      const length = Array.from(input.summary).length;
      console.log(JSON.stringify({ updated: true, updatedAt: saved.state.updatedAt, summaryChars: length }));
      if (length > store.SUMMARY_SOFT_LIMIT) {
        console.error(`警告: summary が ${store.SUMMARY_SOFT_LIMIT} 字を超えています（${length} 字）。要点を絞り、詳細はレポートのパスで示してください。`);
      }
      return;
    }
    case 'claim': {
      const agent = requireAgent(options);
      if (!options.task) throw new Error('--task を指定してください。');
      const scopePaths = (options.scope ?? '').split(',').map((s) => s.trim()).filter(Boolean);
      if (scopePaths.length === 0) throw new Error('--scope に編集するパスをカンマ区切りで指定してください（リポジトリ全体 . は避ける）。');
      const result = store.claimTaskLock(root, {
        taskId: options.task, agent, scopePaths, ttlMinutes: Number(options.ttl ?? 120), force: options.force === true,
      });
      console.log(JSON.stringify({ acquired: result.acquired, reason: result.reason, expiresAt: result.lock?.expiresAt }));
      process.exitCode = result.acquired ? 0 : 3;
      return;
    }
    case 'release': {
      const agent = requireAgent(options);
      if (!options.task) throw new Error('--task を指定してください。');
      const result = store.releaseTaskLock(root, {
        taskId: options.task, agent, outcome: options.outcome ?? 'completed', note: options.note ?? '',
      });
      console.log(JSON.stringify({ released: result.released, reason: result.reason }));
      process.exitCode = result.released ? 0 : 3;
      return;
    }
    default:
      throw new Error('コマンドは read / update / claim / release のいずれかです。');
  }
}

main().catch((error) => {
  console.error(`エラー: ${error.message}`);
  process.exitCode = 2;
});
