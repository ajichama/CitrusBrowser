// cli.mjs の結合テスト。一時ワークスペースで実行し、実際の台帳には触れない。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const cli = path.join(path.dirname(fileURLToPath(import.meta.url)), 'cli.mjs');

function workspace() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'citrus-cli-'));
  fs.writeFileSync(path.join(dir, 'AGENTS.md'), '# test\n');
  return dir;
}

function run(dir, ...args) {
  const env = { ...process.env };
  delete env.CITRUS_SESSION_OWNER;
  return spawnSync(process.execPath, [cli, ...args, '--workspace', dir], { encoding: 'utf8', env });
}

test('別プロセスの CLI で取得したロックを同じエージェントが解放できる', () => {
  const dir = workspace();
  const claim = run(dir, 'claim', '--task', 't1', '--agent', 'claude', '--scope', 'module/a', '--ttl', '30');
  assert.equal(claim.status, 0, claim.stderr);
  assert.equal(JSON.parse(claim.stdout).acquired, true);
  const release = run(dir, 'release', '--task', 't1', '--agent', 'claude', '--outcome', 'paused');
  assert.equal(release.status, 0, release.stderr);
  assert.equal(JSON.parse(release.stdout).released, true);
});

test('別のエージェントのロックは解放できず、終了コード3を返す', () => {
  const dir = workspace();
  assert.equal(run(dir, 'claim', '--task', 't2', '--agent', 'codex', '--scope', 'module/b').status, 0);
  const release = run(dir, 'release', '--task', 't2', '--agent', 'claude');
  assert.equal(release.status, 3);
  assert.equal(JSON.parse(release.stdout).released, false);
});

test('範囲の指定がない claim は拒否する', () => {
  const result = run(workspace(), 'claim', '--task', 't3', '--agent', 'claude');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /--scope/);
});

test('update は JSON から台帳を更新し、長すぎる要約を警告する', () => {
  const dir = workspace();
  const input = path.join(dir, 'input.json');
  fs.writeFileSync(input, JSON.stringify({
    agent: 'claude', summary: 'あ'.repeat(900), nextAgent: 'codex', nextObjective: '次の作業', taskId: 'task-x',
  }));
  const result = run(dir, 'update', '--json', input);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).summaryChars, 900);
  assert.match(result.stderr, /800 字を超えています/);
  assert.match(execFileSync(process.execPath, [cli, 'read', '--workspace', dir], { encoding: 'utf8' }), /task-x/);
});
