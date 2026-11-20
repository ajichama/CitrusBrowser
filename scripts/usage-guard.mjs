#!/usr/bin/env node
/**
 * 利用枠（レート制限）ガード。Claude Code / Codex の hooks から呼ばれ、
 * 閾値を超えたときだけ hookSpecificOutput.additionalContext で区切りの指示を返す。
 * どんなエラーでも何も出力せず exit 0 とし、作業を止めない。
 * 外部 npm パッケージ依存なし（Node.js 標準モジュールのみ）。
 *
 * 使い方: node scripts/usage-guard.mjs --agent claude|codex --event <hookEventName>
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const AGENTS = ['claude', 'codex', 'antigravity'];
// additionalContext がモデルの文脈に入るイベントだけを対象にする。
export const CONTEXT_EVENTS = ['SessionStart', 'UserPromptSubmit', 'PreToolUse', 'PostToolUse', 'SubagentStart'];
export const THRESHOLDS = {
  five_hour: { notice: 90, checkpoint: 93, stop: 97 },
  seven_day: { notice: 80, checkpoint: 90, stop: 97 },
};
export const STALE_MS = 15 * 60 * 1000;
export const NOTICE_INTERVAL_MS = 30 * 60 * 1000;
const LEVELS = ['none', 'notice', 'checkpoint', 'stop'];
const TAIL_BYTES = 512 * 1024;
const WINDOW_LABELS = { five_hour: '5時間枠', seven_day: '週枠' };
const AGENT_LABELS = { claude: 'Claude Code', codex: 'Codex', antigravity: 'Antigravity' };
const ACTIONS = {
  notice: '新しい大作業（複数モジュールの変更・全体ビルド）を始めない。',
  checkpoint: '今の変更をビルドが通る状態に戻すか退避し、handoff_update で進捗・次の目標・未検証の項目を記録する。',
  stop: '新しい作業を止め、台帳の更新（未コミットの変更の有無と最後に成功した検証コマンドを含む）だけを行って中断する。',
};

/** キャッシュの保存先。テスト用に CITRUS_USAGE_DIR で差し替えられる。 */
export function usageDir() {
  return process.env.CITRUS_USAGE_DIR || path.join(os.homedir(), '.citrus', 'usage');
}

/** Codex のセッション保存先（公式の CODEX_HOME を優先）。 */
export function codexSessionsDir() {
  return path.join(process.env.CODEX_HOME || path.join(os.homedir(), '.codex'), 'sessions');
}

function isNum(v) {
  return typeof v === 'number' ? Number.isFinite(v) : (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)));
}

/** 一時ファイル → rename で JSON を書く。失敗は呼び出し側で無視する。 */
export function writeJsonAtomic(filePath, data) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), 'utf8');
  try {
    fs.renameSync(tmp, filePath);
  } catch (error) {
    try { fs.unlinkSync(tmp); } catch { /* 片付けの失敗は無視する */ }
    throw error;
  }
}

export function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8').replace(/^﻿/, ''));
  } catch {
    return null;
  }
}

/** キャッシュを読み、形式が正しいものだけ返す。 */
export function readCache(agent, dir = usageDir()) {
  const data = readJson(path.join(dir, `${agent}.json`));
  return normalizeSnapshot(data);
}

function normalizeSnapshot(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.windows)) return null;
  const capturedMs = Date.parse(data.captured_at);
  if (!Number.isFinite(capturedMs)) return null;
  const windows = data.windows
    .filter((w) => w && THRESHOLDS[w.kind] && isNum(w.used_pct) && isNum(w.resets_at))
    .map((w) => ({ kind: w.kind, used_pct: Number(w.used_pct), resets_at: Number(w.resets_at) }));
  return { ...data, windows };
}

/** ファイル末尾だけを読み、途中で切れた先頭行を捨てる。 */
export function readTail(filePath, maxBytes = TAIL_BYTES) {
  const fd = fs.openSync(filePath, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const start = Math.max(0, size - maxBytes);
    const buf = Buffer.alloc(size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    let text = buf.toString('utf8');
    if (start > 0) {
      const nl = text.indexOf('\n');
      text = nl >= 0 ? text.slice(nl + 1) : '';
    }
    return text;
  } finally {
    fs.closeSync(fd);
  }
}

function codexWindowKind(name, w) {
  if (Number(w.window_minutes) === 300) return 'five_hour';
  if (Number(w.window_minutes) === 10080) return 'seven_day';
  if (w.window_minutes === undefined || w.window_minutes === null) {
    return name === 'primary' ? 'five_hour' : name === 'secondary' ? 'seven_day' : null;
  }
  return null;
}

/**
 * rollout JSONL の末尾から最後の token_count.rate_limits を取り出す。
 * 内部形式のため、想定外の形なら null（unavailable 扱い）を返す。
 */
export function parseCodexRollout(text) {
  const lines = String(text).split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (!line || !line.includes('"token_count"') || !line.includes('rate_limits')) continue;
    let entry;
    try { entry = JSON.parse(line); } catch { continue; }
    const payload = entry && entry.payload;
    if (!payload || payload.type !== 'token_count' || !payload.rate_limits) continue;
    const rl = payload.rate_limits;
    const windows = [];
    for (const name of ['primary', 'secondary']) {
      const w = rl[name];
      if (!w || !isNum(w.used_percent) || !isNum(w.resets_at)) continue;
      const kind = codexWindowKind(name, w);
      if (kind) windows.push({ kind, used_pct: Number(w.used_percent), resets_at: Number(w.resets_at) });
    }
    const capturedMs = Date.parse(entry.timestamp);
    if (!windows.length || !Number.isFinite(capturedMs)) return null;
    return {
      agent: 'codex',
      windows,
      captured_at: new Date(capturedMs).toISOString(),
      source: 'codex-rollout',
      limit_reached: rl.rate_limit_reached_type ? true : false,
    };
  }
  return null;
}

/** sessions/YYYY/MM/DD を新しい順にたどり、直近2日分から最も新しい rollout を返す。 */
export function findLatestRollout(sessionsDir = codexSessionsDir()) {
  const listDirs = (dir) => {
    try {
      return fs.readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory() && /^\d+$/.test(d.name))
        .map((d) => d.name).sort().reverse();
    } catch {
      return [];
    }
  };
  const candidates = [];
  let dayDirs = 0;
  outer: for (const y of listDirs(sessionsDir)) {
    for (const m of listDirs(path.join(sessionsDir, y))) {
      for (const d of listDirs(path.join(sessionsDir, y, m))) {
        const dayPath = path.join(sessionsDir, y, m, d);
        let files = [];
        try { files = fs.readdirSync(dayPath).filter((f) => /^rollout-.*\.jsonl$/.test(f)); } catch { /* 読めない日は飛ばす */ }
        if (!files.length) continue;
        for (const f of files) {
          try { candidates.push({ file: path.join(dayPath, f), mtime: fs.statSync(path.join(dayPath, f)).mtimeMs }); } catch { /* 消えたファイルは無視 */ }
        }
        if (++dayDirs >= 2) break outer;
      }
    }
  }
  candidates.sort((a, b) => b.mtime - a.mtime);
  return candidates.length ? candidates[0].file : null;
}

function readRolloutSnapshot(file) {
  if (!file || typeof file !== 'string' || !file.endsWith('.jsonl')) return null;
  try {
    return parseCodexRollout(readTail(file));
  } catch {
    return null;
  }
}

function newer(a, b) {
  if (!a) return b;
  if (!b) return a;
  return Date.parse(a.captured_at) >= Date.parse(b.captured_at) ? a : b;
}

/**
 * エージェントごとの最新スナップショットを得る。
 * codex は transcript（無ければ最新 rollout）を読み、キャッシュより新しければキャッシュも更新する。
 */
export function loadSnapshot(agent, { transcriptPath = null, dir = usageDir(), sessionsDir = codexSessionsDir() } = {}) {
  if (agent === 'claude') return readCache('claude', dir);
  if (agent !== 'codex') return null;
  const cached = readCache('codex', dir);
  let fresh = readRolloutSnapshot(transcriptPath);
  if (!fresh) {
    const latest = findLatestRollout(sessionsDir);
    if (latest && latest !== transcriptPath) fresh = readRolloutSnapshot(latest);
  }
  const best = newer(cached, fresh);
  if (fresh && best === fresh && (!cached || fresh.captured_at !== cached.captured_at)) {
    try { writeJsonAtomic(path.join(dir, 'codex.json'), fresh); } catch { /* キャッシュ書込の失敗は無視する */ }
  }
  return best;
}

function levelFor(kind, pct) {
  const t = THRESHOLDS[kind];
  if (pct >= t.stop) return 'stop';
  if (pct >= t.checkpoint) return 'checkpoint';
  if (pct >= t.notice) return 'notice';
  return 'none';
}

const maxLevel = (a, b) => (LEVELS.indexOf(a) >= LEVELS.indexOf(b) ? a : b);

/**
 * スナップショットを判定する。推定値は使わない。
 * 戻り値: { status: ok|stale|unavailable, level, windows, reason, captured_at }
 */
export function evaluate(agent, snapshot, now = Date.now()) {
  if (agent === 'antigravity') {
    return { status: 'unavailable', level: 'none', windows: [], reason: 'Antigravity には利用枠を取得する公式の手段がないため、残量不明として扱う。' };
  }
  if (!snapshot) {
    return { status: 'unavailable', level: 'none', windows: [], reason: '利用枠のキャッシュまたは記録が見つからない。' };
  }
  const capturedMs = Date.parse(snapshot.captured_at);
  const windows = snapshot.windows.map((w) => {
    const expired = w.resets_at * 1000 <= now;
    return { ...w, expired, level: expired ? 'none' : levelFor(w.kind, w.used_pct) };
  });
  const active = windows.filter((w) => !w.expired);
  const base = { windows, captured_at: snapshot.captured_at };
  if (!Number.isFinite(capturedMs) || now - capturedMs > STALE_MS) {
    return { ...base, status: 'stale', level: 'none', reason: `記録が15分より古い（取得 ${snapshot.captured_at}）。` };
  }
  if (!active.length) {
    return { ...base, status: 'stale', level: 'none', reason: '記録された枠はすべてリセット時刻を過ぎている。' };
  }
  let level = active.reduce((acc, w) => maxLevel(acc, w.level), 'none');
  let reason = '';
  if (snapshot.limit_reached) {
    level = 'stop';
    reason = '上限到達の通知が記録されている。';
  }
  return { ...base, status: 'ok', level, reason };
}

function formatReset(kind, resetsAt) {
  const t = new Date(resetsAt * 1000);
  const pad = (n) => String(n).padStart(2, '0');
  const hm = `${pad(t.getHours())}:${pad(t.getMinutes())}`;
  return kind === 'five_hour' ? hm : `${t.getMonth() + 1}/${t.getDate()} ${hm}`;
}

/** 重複抑止の状態ファイルを参照し、出力すべきかを決める。checkpoint 以上は毎回出す。 */
export function shouldNotify(agent, result, now = Date.now(), dir = usageDir()) {
  if (result.status !== 'ok' || result.level === 'none') return false;
  if (result.level !== 'notice') return true;
  const statePath = path.join(dir, 'guard-state.json');
  const state = readJson(statePath) || {};
  const notices = state.notices && typeof state.notices === 'object' ? state.notices : {};
  const keys = result.windows.filter((w) => !w.expired && w.level === 'notice').map((w) => `${agent}:${w.kind}:notice`);
  const due = keys.filter((k) => !(Number(notices[k]) > now - NOTICE_INTERVAL_MS));
  if (!due.length) return false;
  for (const k of due) notices[k] = now;
  try { writeJsonAtomic(statePath, { ...state, notices }); } catch { /* 状態保存の失敗は無視する（通知は出す） */ }
  return true;
}

export function buildMessage(agent, result) {
  const parts = result.windows.filter((w) => !w.expired)
    .map((w) => `${WINDOW_LABELS[w.kind]} ${Math.round(w.used_pct)}%（リセット ${formatReset(w.kind, w.resets_at)}）`);
  const stageName = { notice: '注意', checkpoint: '区切り', stop: '中断' }[result.level];
  return [
    `[利用枠] ${AGENT_LABELS[agent]}: ${parts.join('、')}。${result.reason}`,
    `段階: ${stageName}（AGENTS.md 第8節）。${ACTIONS[result.level]}`,
  ].join('\n');
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if ((a === '--agent' || a === '--event') && argv[i + 1]) out[a.slice(2)] = argv[++i];
    else if (a.startsWith('--agent=')) out.agent = a.slice(8);
    else if (a.startsWith('--event=')) out.event = a.slice(8);
  }
  return out;
}

/** hook として実行したときの処理。出力すべき文字列（無ければ空文字）を返す。 */
export function runHook(argv, stdinText, now = Date.now()) {
  const { agent, event } = parseArgs(argv);
  if (!['claude', 'codex'].includes(agent) || !CONTEXT_EVENTS.includes(event)) return '';
  let input = {};
  try { input = JSON.parse(String(stdinText || '').replace(/^﻿/, '') || '{}') || {}; } catch { input = {}; }
  const transcriptPath = agent === 'codex' && typeof input.transcript_path === 'string' ? input.transcript_path : null;
  const result = evaluate(agent, loadSnapshot(agent, { transcriptPath }), now);
  if (!shouldNotify(agent, result, now)) return '';
  return JSON.stringify({ hookSpecificOutput: { hookEventName: event, additionalContext: buildMessage(agent, result) } });
}

function main() {
  let stdinText = '';
  try { stdinText = fs.readFileSync(0, 'utf8'); } catch { /* 標準入力が無くても続行する */ }
  const out = runHook(process.argv.slice(2), stdinText);
  if (out) process.stdout.write(out + '\n');
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
