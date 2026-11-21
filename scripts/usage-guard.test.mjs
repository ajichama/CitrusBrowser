// usage-guard.mjs の単体テスト（node --test scripts/usage-guard.test.mjs）
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  evaluate,
  findLatestRollout,
  loadSnapshot,
  parseCodexRollout,
  readTail,
  runHook,
  shouldNotify,
} from './usage-guard.mjs';

const GUARD_PATH = path.join(path.dirname(fileURLToPath(import.meta.url)), 'usage-guard.mjs');
const NOW = Date.parse('2026-11-19T03:00:00Z');
const SEC = (ms) => Math.floor(ms / 1000);

function tmpDir(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'citrus-usage-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

function snapshot(fivePct, weekPct, { capturedMs = NOW - 60_000, reset5 = NOW + 3600_000, reset7 = NOW + 86400_000 } = {}) {
  const windows = [];
  if (fivePct !== null) windows.push({ kind: 'five_hour', used_pct: fivePct, resets_at: SEC(reset5) });
  if (weekPct !== null) windows.push({ kind: 'seven_day', used_pct: weekPct, resets_at: SEC(reset7) });
  return { agent: 'claude', windows, captured_at: new Date(capturedMs).toISOString(), source: 'statusline' };
}

function rolloutLine(timestamp, primaryPct, secondaryPct, extra = {}) {
  return JSON.stringify({
    timestamp,
    type: 'event_msg',
    payload: {
      type: 'token_count',
      rate_limits: {
        primary: { used_percent: primaryPct, window_minutes: 300, resets_at: SEC(NOW + 3600_000) },
        secondary: { used_percent: secondaryPct, window_minutes: 10080, resets_at: SEC(NOW + 86400_000) },
        ...extra,
      },
    },
  });
}

test('5時間枠と週枠の閾値で段階を判定する', () => {
  const cases = [
    [89.9, 0, 'none'], [90, 0, 'notice'], [92.9, 0, 'notice'], [93, 0, 'checkpoint'], [97, 0, 'stop'],
    [0, 79, 'none'], [0, 80, 'notice'], [0, 90, 'checkpoint'], [0, 97, 'stop'], [85, 91, 'checkpoint'],
  ];
  for (const [five, week, expected] of cases) {
    const result = evaluate('claude', snapshot(five, week), NOW);
    assert.equal(result.status, 'ok');
    assert.equal(result.level, expected, `5h=${five} 週=${week}`);
  }
  // 片方の窓が欠けていても残りで判定する
  assert.equal(evaluate('claude', snapshot(null, 92), NOW).level, 'checkpoint');
});

test('古い記録・リセット済みの窓は stale、記録なし・Antigravity は unavailable', () => {
  const old = evaluate('claude', snapshot(99, 99, { capturedMs: NOW - 16 * 60_000 }), NOW);
  assert.equal(old.status, 'stale');
  assert.equal(old.level, 'none');
  const reset = evaluate('claude', snapshot(99, 99, { reset5: NOW - 1000, reset7: NOW - 1000 }), NOW);
  assert.equal(reset.status, 'stale');
  // リセット済みの窓だけを除外し、残りの窓で判定する
  const partial = evaluate('claude', snapshot(99, 10, { reset5: NOW - 1000 }), NOW);
  assert.equal(partial.status, 'ok');
  assert.equal(partial.level, 'none');
  assert.equal(evaluate('claude', null, NOW).status, 'unavailable');
  assert.equal(evaluate('antigravity', snapshot(99, 99), NOW).status, 'unavailable');
});

test('notice は同じ窓で30分に1回まで、checkpoint 以上は毎回出す', (t) => {
  const dir = tmpDir(t);
  const notice = evaluate('claude', snapshot(91, 0), NOW);
  assert.equal(shouldNotify('claude', notice, NOW, dir), true);
  assert.equal(shouldNotify('claude', notice, NOW + 10 * 60_000, dir), false);
  // 別エージェント・別の窓は独立して扱う
  assert.equal(shouldNotify('codex', notice, NOW + 10 * 60_000, dir), true);
  const weekNotice = evaluate('claude', snapshot(91, 85), NOW);
  assert.equal(shouldNotify('claude', weekNotice, NOW + 10 * 60_000, dir), true);
  assert.equal(shouldNotify('claude', notice, NOW + 31 * 60_000, dir), true);
  const checkpoint = evaluate('claude', snapshot(94, 0), NOW);
  assert.equal(shouldNotify('claude', checkpoint, NOW, dir), true);
  assert.equal(shouldNotify('claude', checkpoint, NOW, dir), true);
  const stale = evaluate('claude', snapshot(99, 0, { capturedMs: NOW - 60 * 60_000 }), NOW);
  assert.equal(shouldNotify('claude', stale, NOW, dir), false);
});

test('Codex の rollout から最後の rate_limits を読み、壊れた行や他形式は無視する', () => {
  const text = [
    rolloutLine('2026-11-19T02:50:00.000Z', 10, 1),
    '{"type":"event_msg","payload":{"type":"agent_message","message":"token_count rate_limits"}}',
    rolloutLine('2026-11-19T02:58:00.000Z', 94, 20),
    '{"broken": "token_count rate_limits',
    '',
  ].join('\n');
  const snap = parseCodexRollout(text);
  assert.equal(snap.captured_at, '2026-11-19T02:58:00.000Z');
  assert.deepEqual(snap.windows.map((w) => [w.kind, w.used_pct]), [['five_hour', 94], ['seven_day', 20]]);
  assert.equal(evaluate('codex', snap, NOW).level, 'checkpoint');
  // 上限到達の記録は stop として扱う
  const reached = parseCodexRollout(rolloutLine('2026-11-19T02:59:00.000Z', 50, 20, { rate_limit_reached_type: 'primary' }));
  assert.equal(evaluate('codex', reached, NOW).level, 'stop');
  // 想定外の形式は unavailable
  assert.equal(parseCodexRollout('{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":"x"}}}}'), null);
  assert.equal(parseCodexRollout(''), null);
});

test('巨大な rollout は末尾だけを読み、途中で切れた先頭行を捨てる', (t) => {
  const dir = tmpDir(t);
  const file = path.join(dir, 'rollout-big.jsonl');
  const filler = JSON.stringify({ type: 'response_item', payload: { text: 'x'.repeat(1000) } });
  fs.writeFileSync(file, `${Array(1200).fill(filler).join('\n')}\n${rolloutLine('2026-11-19T02:58:00.000Z', 71, 5)}\n`);
  const tail = readTail(file, 64 * 1024);
  assert.ok(tail.length <= 64 * 1024);
  assert.ok(tail.split('\n').filter(Boolean).every((line) => { JSON.parse(line); return true; }));
  assert.equal(parseCodexRollout(tail).windows[0].used_pct, 71);
});

test('最新 rollout を探し、transcript を優先してキャッシュを更新する', (t) => {
  const root = tmpDir(t);
  const sessions = path.join(root, 'sessions');
  const cacheDir = path.join(root, 'usage');
  const older = path.join(sessions, '2026', '11', '18');
  const newest = path.join(sessions, '2026', '11', '19');
  fs.mkdirSync(older, { recursive: true });
  fs.mkdirSync(newest, { recursive: true });
  fs.writeFileSync(path.join(older, 'rollout-a.jsonl'), rolloutLine('2026-11-18T10:00:00.000Z', 30, 3) + '\n');
  const latestFile = path.join(newest, 'rollout-b.jsonl');
  fs.writeFileSync(latestFile, rolloutLine('2026-11-19T02:57:00.000Z', 72, 6) + '\n');
  assert.equal(findLatestRollout(sessions), latestFile);
  assert.equal(findLatestRollout(path.join(root, 'missing')), null);

  const snap = loadSnapshot('codex', { dir: cacheDir, sessionsDir: sessions });
  assert.equal(snap.windows[0].used_pct, 72);
  assert.equal(JSON.parse(fs.readFileSync(path.join(cacheDir, 'codex.json'), 'utf8')).windows[0].used_pct, 72);

  const transcript = path.join(root, 'rollout-current.jsonl');
  fs.writeFileSync(transcript, rolloutLine('2026-11-19T02:59:00.000Z', 90, 7) + '\n');
  assert.equal(loadSnapshot('codex', { transcriptPath: transcript, dir: cacheDir, sessionsDir: sessions }).windows[0].used_pct, 90);
  // transcript に記録が無ければ、より新しいキャッシュを使う
  const empty = path.join(root, 'rollout-empty.jsonl');
  fs.writeFileSync(empty, '{"type":"session_meta","payload":{}}\n');
  assert.equal(loadSnapshot('codex', { transcriptPath: empty, dir: cacheDir, sessionsDir: path.join(root, 'none') }).windows[0].used_pct, 90);
});

test('hook 出力は閾値超過時だけ hookSpecificOutput 形式で、不正な引数では何も出さない', (t) => {
  const dir = tmpDir(t);
  const prev = process.env.CITRUS_USAGE_DIR;
  process.env.CITRUS_USAGE_DIR = dir;
  t.after(() => { if (prev === undefined) delete process.env.CITRUS_USAGE_DIR; else process.env.CITRUS_USAGE_DIR = prev; });

  fs.writeFileSync(path.join(dir, 'claude.json'), JSON.stringify(snapshot(50, 10)));
  assert.equal(runHook(['--agent', 'claude', '--event', 'PostToolUse'], '{}', NOW), '');
  fs.writeFileSync(path.join(dir, 'claude.json'), JSON.stringify(snapshot(98, 10)));
  const out = JSON.parse(runHook(['--agent', 'claude', '--event', 'UserPromptSubmit'], '{}', NOW));
  assert.equal(out.hookSpecificOutput.hookEventName, 'UserPromptSubmit');
  assert.match(out.hookSpecificOutput.additionalContext, /5時間枠 98%/);
  assert.match(out.hookSpecificOutput.additionalContext, /中断/);
  assert.equal(runHook(['--agent', 'claude', '--event', 'Stop'], '{}', NOW), '');
  assert.equal(runHook(['--agent', 'other', '--event', 'PostToolUse'], '{}', NOW), '');
  assert.equal(runHook(['--agent', 'claude', '--event', 'PostToolUse'], '{壊れた入力', NOW).length > 0, true);
});

test('CLI は壊れたキャッシュや入力でも何も出さず exit 0 で終わる', (t) => {
  const dir = tmpDir(t);
  fs.writeFileSync(path.join(dir, 'claude.json'), '{壊れたJSON');
  const env = { ...process.env, CITRUS_USAGE_DIR: dir, CODEX_HOME: path.join(dir, 'codex-home') };
  for (const agent of ['claude', 'codex']) {
    const result = spawnSync(process.execPath, [GUARD_PATH, '--agent', agent, '--event', 'PostToolUse'], {
      input: '{"transcript_path": 123}', encoding: 'utf8', env, windowsHide: true,
    });
    assert.equal(result.status, 0);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
  }
});

test('文脈の量は自分のセッションの記録だけで判定し、30万で検討・40万で区切りを間隔をあけて知らせる', (t) => {
  const dir = tmpDir(t);
  const prev = process.env.CITRUS_USAGE_DIR;
  process.env.CITRUS_USAGE_DIR = dir;
  t.after(() => { if (prev === undefined) delete process.env.CITRUS_USAGE_DIR; else process.env.CITRUS_USAGE_DIR = prev; });
  fs.writeFileSync(path.join(dir, 'claude.json'), JSON.stringify(snapshot(50, 10)));
  const writeCtx = (sessions) => fs.writeFileSync(path.join(dir, 'claude-context.json'), JSON.stringify({ sessions }));
  const at = (ms) => new Date(ms).toISOString();
  const hook = (sid, now = NOW) => runHook(['--agent', 'claude', '--event', 'PostToolUse'], JSON.stringify({ session_id: sid }), now);

  writeCtx({ a: { tokens: 250_000, window_size: 1_000_000, captured_at: at(NOW - 1000) }, b: { tokens: 450_000, captured_at: at(NOW - 1000) } });
  assert.equal(hook('a'), '');
  assert.equal(hook('zzz'), '');
  assert.equal(runHook(['--agent', 'claude', '--event', 'PostToolUse'], '{}', NOW), '');

  writeCtx({ a: { tokens: 320_000, captured_at: at(NOW - 1000) } });
  const notice = JSON.parse(hook('a')).hookSpecificOutput.additionalContext;
  assert.match(notice, /約32万トークン/);
  assert.match(notice, /準備/);
  assert.equal(hook('a', NOW + 60_000), '');

  writeCtx({ a: { tokens: 410_000, captured_at: at(NOW - 1000) } });
  assert.match(JSON.parse(hook('a', NOW + 60_000)).hookSpecificOutput.additionalContext, /\/clear（新しいセッション）を依頼/);
  assert.equal(hook('a', NOW + 5 * 60_000), '');
  assert.ok(hook('a', NOW + 12 * 60_000).length > 0);

  writeCtx({ a: { tokens: 410_000, captured_at: at(NOW - 20 * 60_000) } });
  assert.equal(hook('a', NOW + 60 * 60_000), '');

  fs.writeFileSync(path.join(dir, 'claude.json'), JSON.stringify(snapshot(98, 10)));
  writeCtx({ c: { tokens: 500_000, captured_at: at(NOW - 1000) } });
  const both = JSON.parse(hook('c')).hookSpecificOutput.additionalContext;
  assert.match(both, /5時間枠 98%/);
  assert.match(both, /約50万トークン/);
});
