import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const TASK_ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/;
const MAX_HISTORY_ENTRIES = 20;
const SESSION_OWNER = randomUUID();
const heldMutexes = new Set();

/**
 * 別 MCP プロセスからの更新も直列化する。異常終了時は自動で奪取せず安全側で停止する。
 */
function withStoreLock(workspaceRoot, operation) {
  const { locksDir } = getHandoffPaths(workspaceRoot);
  fs.mkdirSync(locksDir, { recursive: true });
  const mutexPath = path.join(locksDir, '.store-mutex');
  if (heldMutexes.has(mutexPath)) return operation();
  const started = Date.now();
  while (true) {
    try {
      fs.mkdirSync(mutexPath);
      break;
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      if (Date.now() - started > 20000) throw new Error('引き継ぎ状態の更新待ちがタイムアウトしました。異常終了したサーバーがある場合は全サーバー停止後に .agents/handoff/locks/.store-mutex を確認してください。');
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10);
    }
  }
  heldMutexes.add(mutexPath);
  try {
    return operation();
  } finally {
    heldMutexes.delete(mutexPath);
    releaseStoreMutex(mutexPath);
  }
}

/** Windows の一時的なファイル占有だけを約1秒まで待つ。取得済みロックの奪取はしない。 */
function releaseStoreMutex(mutexPath) {
  for (let attempt = 0; ; attempt++) {
    try {
      fs.rmdirSync(mutexPath);
      return;
    } catch (error) {
      if (!['EBUSY', 'EPERM'].includes(error.code) || attempt >= 19) throw error;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
    }
  }
}

/**
 * 区切り文字・大小文字・シンボリックリンクを正規化し、ルート外の対象を拒否する。
 */
function normalizeScopes(workspaceRoot, scopes) {
  if (!Array.isArray(scopes) || scopes.some((scope) => typeof scope !== 'string' || !scope.trim())) {
    throw new Error('scopePaths は空でないパス文字列の配列で指定してください。');
  }
  const root = fs.realpathSync.native(workspaceRoot);
  const canonicalize = (candidate) => {
    let existing = candidate;
    const suffix = [];
    while (!fs.existsSync(existing)) {
      const parent = path.dirname(existing);
      if (parent === existing) throw new Error('対象パスの実体を確認できません。');
      suffix.unshift(path.basename(existing));
      existing = parent;
    }
    return path.join(fs.realpathSync.native(existing), ...suffix);
  };
  return Array.from(new Set((scopes.length ? scopes : ['.']).map((scope) => {
    const inputPath = scope.replace(/[\\/]+/g, path.sep);
    const absolute = path.isAbsolute(inputPath) ? inputPath : `${root}${path.sep}${inputPath}`;
    const relative = path.relative(root, canonicalize(absolute));
    if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
      throw new Error('scopePaths にワークスペース外のパスは指定できません。');
    }
    const normalized = relative.split(path.sep).join('/') || '.';
    return process.platform === 'win32' ? normalized.toLowerCase() : normalized;
  })));
}

function scopesOverlap(left, right) {
  return left.some((a) => right.some((b) => a === '.' || b === '.' || a === b || a.startsWith(`${b}/`) || b.startsWith(`${a}/`)));
}

/**
 * ワークスペースのルートディレクトリ（.git または指定パス）を解決する。
 */
export function resolveWorkspaceRoot(explicitPath) {
  const candidates = [
    explicitPath,
    process.env.CITRUS_WORKSPACE_ROOT,
    process.cwd(),
  ].filter(Boolean);

  for (const base of candidates) {
    let current = path.resolve(base);
    while (true) {
      if (fs.existsSync(path.join(current, '.git')) || fs.existsSync(path.join(current, 'AGENTS.md'))) {
        return current;
      }
      const parent = path.dirname(current);
      if (parent === current) break;
      current = parent;
    }
  }
  return path.resolve(explicitPath || process.cwd());
}

/**
 * 引き継ぎ用ディレクトリ群のパスを返す。
 */
export function getHandoffPaths(workspaceRoot) {
  const handoffDir = path.join(workspaceRoot, '.agents', 'handoff');
  return {
    handoffDir,
    statePath: path.join(handoffDir, 'state.json'),
    markdownPath: path.join(handoffDir, 'HANDOFF.md'),
    locksDir: path.join(handoffDir, 'locks'),
    reportsDir: path.join(handoffDir, 'reports'),
  };
}

/**
 * タスクIDを検証し、パストラバーサルを防止する。
 */
export function validateTaskId(taskId) {
  if (typeof taskId !== 'string' || !TASK_ID_PATTERN.test(taskId)) {
    throw new Error(
      `不正な taskId です: "${String(taskId)}"。英数字で始まり、英数字・ハイフン・アンダースコア・ドットのみ（64文字以内）で指定してください。`
    );
  }
  return taskId;
}

/**
 * UTF-8 BOM を除去して文字列を返す。
 */
function stripBom(text) {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

/**
 * アトミック（一時ファイル経由）に UTF-8 ファイルへ書き込む。
 */
function writeTextAtomic(filePath, content) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tempPath = `${filePath}.${randomUUID()}.tmp`;
  fs.writeFileSync(tempPath, content, 'utf8');
  fs.renameSync(tempPath, filePath);
}

/**
 * デフォルトの初期状態オブジェクトを生成する。
 */
export function createDefaultState() {
  const now = new Date().toISOString();
  return {
    schemaVersion: 1,
    project: 'CitrusBrowser',
    updatedAt: now,
    updatedBy: 'system',
    phase: '未設定',
    activeTask: {
      id: 'initial',
      assignedAgent: 'any',
      objective: '引き継ぎ台帳の初期状態を読み込み、次タスクを設定してください。',
      summary: '初期化直後の状態です。',
      targetFiles: [],
      acceptanceCriteria: [],
      constraints: [
        'AGENTS.md の規約（完全日本語運用・Web検索での最新仕様裏付け・Dockerビルド検証・Apache 2.0著作権保持）を遵守すること。',
      ],
    },
    completedItems: [],
    blockers: [],
    lastVerification: null,
    history: [],
  };
}

/**
 * state.json を安全に読み込む（存在しない場合は初期化して返す）。
 */
export function readHandoffState(workspaceRoot) {
  const { statePath } = getHandoffPaths(workspaceRoot);
  if (!fs.existsSync(statePath)) {
    return { state: createDefaultState(), initialized: false };
  }
  try {
    const raw = stripBom(fs.readFileSync(statePath, 'utf8'));
    const parsed = JSON.parse(raw);
    return {
      state: normalizeState(parsed),
      initialized: true,
    };
  } catch (error) {
    throw new Error(`状態ファイル (${statePath}) の解析に失敗しました: ${error.message}`);
  }
}

/**
 * 読み込んだ state オブジェクトの欠損フィールドを補完する。
 */
function normalizeState(raw) {
  const base = createDefaultState();
  const active = raw?.activeTask ?? {};
  return {
    ...base,
    ...raw,
    activeTask: {
      ...base.activeTask,
      ...active,
      targetFiles: Array.isArray(active.targetFiles) ? active.targetFiles : [],
      acceptanceCriteria: Array.isArray(active.acceptanceCriteria) ? active.acceptanceCriteria : [],
      constraints: Array.isArray(active.constraints) ? active.constraints : base.activeTask.constraints,
    },
    completedItems: Array.isArray(raw?.completedItems) ? raw.completedItems : [],
    blockers: Array.isArray(raw?.blockers) ? raw.blockers : [],
    history: Array.isArray(raw?.history) ? raw.history : [],
  };
}

/**
 * 現在の Git 状態（ブランチ・HEAD・作業ツリー差分有無）を安全に取得する。
 */
export function getGitSnapshot(workspaceRoot) {
  const execOpts = {
    cwd: workspaceRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 5000,
  };
  try {
    const branch = execFileSync(
      'git',
      ['-c', 'safe.directory=*', 'rev-parse', '--abbrev-ref', 'HEAD'],
      execOpts
    ).trim();
    const headCommit = execFileSync(
      'git',
      ['-c', 'safe.directory=*', 'log', '-1', '--format=%h %s (%ci)'],
      execOpts
    ).trim();
    const statusOutput = execFileSync(
      'git',
      ['-c', 'safe.directory=*', 'status', '--short'],
      execOpts
    ).trim();
    const dirtyFiles = statusOutput ? statusOutput.split(/\r?\n/).filter(Boolean) : [];
    return {
      available: true,
      branch,
      headCommit,
      dirtyCount: dirtyFiles.length,
      dirtyFiles: dirtyFiles.slice(0, 30),
    };
  } catch (error) {
    return {
      available: false,
      error: error.message,
      branch: 'unknown',
      headCommit: 'unknown',
      dirtyCount: 0,
      dirtyFiles: [],
    };
  }
}

/**
 * 有効期限を確認しつつ全タスクロックを取得する（期限切れロックは自動クリーンアップ）。
 */
export function listActiveLocks(workspaceRoot) {
  return withStoreLock(workspaceRoot, () => listActiveLocksUnlocked(workspaceRoot));
}

function listActiveLocksUnlocked(workspaceRoot) {
  const { locksDir } = getHandoffPaths(workspaceRoot);
  if (!fs.existsSync(locksDir)) {
    return [];
  }
  const nowMs = Date.now();
  const active = [];
  for (const entry of fs.readdirSync(locksDir)) {
    if (!entry.endsWith('.lock.json')) continue;
    const fullPath = path.join(locksDir, entry);
    try {
      const lock = JSON.parse(stripBom(fs.readFileSync(fullPath, 'utf8')));
      const expiresAtMs = Date.parse(lock.expiresAt);
      if (Number.isFinite(expiresAtMs) && expiresAtMs <= nowMs) {
        fs.unlinkSync(fullPath);
        continue;
      }
      active.push(lock);
    } catch (error) {
      throw new Error(`ロック ${entry} を確認できません。競合防止のため処理を中止します: ${error.message}`);
    }
  }
  return active;
}

/**
 * 状態 JSON とロック一覧から人間・AI双方に読みやすい HANDOFF.md を生成する。
 */
export function renderHandoffMarkdown(state, locks = [], gitInfo = null) {
  const active = state.activeTask ?? {};
  const lines = [
    '# CitrusBrowser マルチAIエージェント引き継ぎ台帳 (`HANDOFF.md`)',
    '',
    '> **運用ルール**: 作業開始前に本ファイルまたは MCP `handoff_read` で最新状態を確認し、中断・交代時には必ず `handoff_update` で引き継ぎ情報を更新してください。',
    '',
    '## 1. 現在の引き継ぎサマリー',
    '',
    `- **最終更新日時**: ${state.updatedAt}`,
    `- **最終更新エージェント**: \`${state.updatedBy}\``,
    `- **現在の開発フェーズ**: ${state.phase}`,
    `- **次期担当エージェント**: \`${active.assignedAgent}\``,
    `- **アクティブタスクID**: \`${active.id}\``,
  ];

  if (gitInfo?.available) {
    lines.push(`- **Git ブランチ / HEAD**: \`${gitInfo.branch}\` / \`${gitInfo.headCommit}\``);
    lines.push(`- **未コミット変更ファイル数**: ${gitInfo.dirtyCount}`);
  }

  if (state.lastVerification) {
    const v = state.lastVerification;
    lines.push(`- **直近ビルド・テスト検証**: \`${v.task}\` → **${v.status}** (${v.verifiedAt}, by \`${v.agent}\`)`);
    if (v.note) {
      lines.push(`  - 補足: ${v.note}`);
    }
  }

  lines.push('', '## 2. 次のエージェントへの依頼内容', '');
  lines.push(`### 目標 (Objective)\n${active.objective || '（未設定）'}`, '');
  lines.push(`### 現状・直前作業の要約 (Summary)\n${active.summary || '（なし）'}`, '');

  lines.push('### 受け入れ基準 (Acceptance Criteria)');
  if (active.acceptanceCriteria?.length) {
    for (const item of active.acceptanceCriteria) lines.push(`- [ ] ${item}`);
  } else {
    lines.push('- （未指定）');
  }

  lines.push('', '### 関連ファイル・対象モジュール (Target Files)');
  if (active.targetFiles?.length) {
    for (const file of active.targetFiles) lines.push(`- \`${file}\``);
  } else {
    lines.push('- （指定なし）');
  }

  lines.push('', '### 厳守事項・技術的制約 (Constraints)');
  if (active.constraints?.length) {
    for (const item of active.constraints) lines.push(`- ${item}`);
  } else {
    lines.push('- プロジェクトルート `AGENTS.md` を遵守すること。');
  }

  lines.push('', '## 3. ブロッカー・未解決課題', '');
  if (state.blockers?.length) {
    for (const blocker of state.blockers) lines.push(`- **要対応**: ${blocker}`);
  } else {
    lines.push('- なし');
  }

  lines.push('', '## 4. アクティブな作業ロック (二重編集防止)', '');
  if (locks.length) {
    for (const lock of locks) {
      const scopes = lock.scopePaths?.length ? ` | 対象: ${lock.scopePaths.join(', ')}` : '';
      lines.push(`- \`${lock.taskId}\` — 担当: \`${lock.agent}\` (有効期限: ${lock.expiresAt}${scopes})`);
    }
  } else {
    lines.push('- 現在アクティブなロックはありません。');
  }

  lines.push('', '## 5. 完了済み項目・直近タイムライン', '');
  if (state.completedItems?.length) {
    lines.push('### 完了済みマイルストーン');
    for (const item of state.completedItems) lines.push(`- [x] ${item}`);
    lines.push('');
  }

  if (state.history?.length) {
    lines.push('### 引き継ぎ履歴（最新順・最大20件）');
    for (const h of state.history.slice(0, 10)) {
      lines.push(`- **${h.timestamp}** [\`${h.fromAgent}\` → \`${h.toAgent}\`] (${h.taskId}): ${h.summary}`);
    }
    lines.push('');
  }

  lines.push(
    '## 6. 次エージェント起動用コピペプロンプト例',
    '',
    '```text',
    'AGENTS.md と .agents/handoff/HANDOFF.md（または MCP handoff_read）を確認し、',
    `タスク「${active.id}」を引き継いで進めてください。完了または中断時は handoff_update を更新してください。`,
    '```',
    ''
  );

  return lines.join('\n');
}

/**
 * state.json と HANDOFF.md を同時に永続化する。
 */
export function saveHandoffState(workspaceRoot, state, { includeGit = true } = {}) {
  return withStoreLock(workspaceRoot, () => saveHandoffStateUnlocked(workspaceRoot, state, { includeGit }));
}

function saveHandoffStateUnlocked(workspaceRoot, state, { includeGit = true } = {}) {
  const paths = getHandoffPaths(workspaceRoot);
  fs.mkdirSync(paths.handoffDir, { recursive: true });
  fs.mkdirSync(paths.locksDir, { recursive: true });
  fs.mkdirSync(paths.reportsDir, { recursive: true });

  const locks = listActiveLocks(workspaceRoot);
  const gitInfo = includeGit ? getGitSnapshot(workspaceRoot) : null;
  const markdown = renderHandoffMarkdown(state, locks, gitInfo);

  writeTextAtomic(paths.statePath, JSON.stringify(state, null, 2) + '\n');
  writeTextAtomic(paths.markdownPath, markdown);

  return { state, markdown, locks, gitInfo, paths };
}

/**
 * 引き継ぎ状態を構造化して更新する。
 */
export function updateHandoffState(workspaceRoot, input) {
  return withStoreLock(workspaceRoot, () => updateHandoffStateUnlocked(workspaceRoot, input));
}

/**
 * 検証中に別プロセスが更新した引き継ぎ内容を上書きせず、検証結果だけを記録する。
 */
export function recordVerification(workspaceRoot, verificationRecord) {
  return withStoreLock(workspaceRoot, () => {
    const { state, initialized } = readHandoffState(workspaceRoot);
    if (!initialized) return;
    return saveHandoffState(workspaceRoot, { ...state, lastVerification: verificationRecord });
  });
}

function updateHandoffStateUnlocked(workspaceRoot, input) {
  const { state } = readHandoffState(workspaceRoot);
  const now = new Date().toISOString();
  const prevTaskId = state.activeTask?.id || 'initial';
  const nextTaskId = input.taskId ? validateTaskId(input.taskId) : prevTaskId;

  const updatedCompleted = Array.isArray(input.completedItems)
    ? Array.from(new Set([...state.completedItems, ...input.completedItems]))
    : state.completedItems;

  const historyEntry = {
    timestamp: now,
    fromAgent: input.agent,
    toAgent: input.nextAgent,
    taskId: nextTaskId,
    summary: input.summary,
  };

  const nextState = {
    ...state,
    updatedAt: now,
    updatedBy: input.agent,
    phase: input.phase ?? state.phase,
    activeTask: {
      id: nextTaskId,
      assignedAgent: input.nextAgent,
      objective: input.nextObjective,
      summary: input.summary,
      targetFiles: input.targetFiles ?? state.activeTask.targetFiles ?? [],
      acceptanceCriteria: input.acceptanceCriteria ?? state.activeTask.acceptanceCriteria ?? [],
      constraints: input.constraints ?? state.activeTask.constraints ?? [],
    },
    completedItems: updatedCompleted,
    blockers: Array.isArray(input.blockers) ? input.blockers : state.blockers,
    lastVerification: input.verificationRecord ?? state.lastVerification,
    history: [historyEntry, ...state.history].slice(0, MAX_HISTORY_ENTRIES),
  };

  return saveHandoffState(workspaceRoot, nextState);
}

/**
 * タスクの排他ロックを取得する。
 */
export function claimTaskLock(workspaceRoot, { taskId, agent, scopePaths = [], ttlMinutes = 120, force = false }) {
  return withStoreLock(workspaceRoot, () => claimTaskLockUnlocked(workspaceRoot, { taskId, agent, scopePaths, ttlMinutes, force }));
}

function claimTaskLockUnlocked(workspaceRoot, { taskId, agent, scopePaths, ttlMinutes, force }) {
  const safeId = validateTaskId(taskId);
  if (!['antigravity', 'codex', 'human'].includes(agent)) throw new Error('不正な agent です。');
  if (typeof force !== 'boolean') throw new Error('force は真偽値で指定してください。');
  const normalizedScopes = normalizeScopes(workspaceRoot, scopePaths);
  const { locksDir } = getHandoffPaths(workspaceRoot);
  fs.mkdirSync(locksDir, { recursive: true });

  // 期限切れロックを先に整理する
  const activeLocks = listActiveLocks(workspaceRoot);

  const lockPath = path.join(locksDir, `${safeId}.lock.json`);
  if (fs.existsSync(lockPath) && !force) {
    const existing = JSON.parse(stripBom(fs.readFileSync(lockPath, 'utf8')));
    return {
      acquired: false,
      reason: `タスク "${safeId}" は現在 "${existing.agent}" によってロック中です (有効期限: ${existing.expiresAt})。同じエージェント名でも再取得はできません。`,
      lock: existing,
    };
  }

  const overlapping = activeLocks.find((existing) => existing.taskId !== safeId &&
    scopesOverlap(normalizedScopes, normalizeScopes(workspaceRoot, existing.scopePaths ?? [])));
  if (overlapping) {
    return { acquired: false, reason: `対象パスがタスク "${overlapping.taskId}" と重複しています。`, lock: overlapping };
  }

  const clampedTtl = Math.max(5, Math.min(720, Number(ttlMinutes) || 120));
  const now = new Date();
  const expires = new Date(now.getTime() + clampedTtl * 60 * 1000);
  const lock = {
    taskId: safeId,
    agent,
    scopePaths: normalizedScopes,
    ownerId: SESSION_OWNER,
    claimedAt: now.toISOString(),
    expiresAt: expires.toISOString(),
  };

  writeTextAtomic(lockPath, JSON.stringify(lock, null, 2) + '\n');

  // ロック変更を HANDOFF.md にも即時反映する（state.json は存在する場合のみ再描画）
  const { state, initialized } = readHandoffState(workspaceRoot);
  if (initialized) {
    saveHandoffState(workspaceRoot, state, { includeGit: false });
  }

  return { acquired: true, lock };
}

/**
 * タスクの排他ロックを解放する。
 */
export function releaseTaskLock(workspaceRoot, { taskId, agent, outcome = 'completed', note = '' }) {
  return withStoreLock(workspaceRoot, () => releaseTaskLockUnlocked(workspaceRoot, { taskId, agent, outcome, note }));
}

function releaseTaskLockUnlocked(workspaceRoot, { taskId, agent, outcome, note }) {
  const safeId = validateTaskId(taskId);
  if (!['antigravity', 'codex', 'human'].includes(agent)) throw new Error('不正な agent です。');
  const { locksDir } = getHandoffPaths(workspaceRoot);
  const lockPath = path.join(locksDir, `${safeId}.lock.json`);

  if (!fs.existsSync(lockPath)) {
    return {
      released: false,
      reason: `タスク "${safeId}" のアクティブなロックは見つかりませんでした。`,
    };
  }

  const existing = JSON.parse(stripBom(fs.readFileSync(lockPath, 'utf8')));
  if (agent !== 'human' && (existing.agent !== agent || (existing.ownerId && existing.ownerId !== SESSION_OWNER))) {
    return {
      released: false,
      reason: `タスク "${safeId}" は別のエージェントまたはセッションが所有しているため解放できません。`,
      lock: existing,
    };
  }

  fs.unlinkSync(lockPath);

  const { state, initialized } = readHandoffState(workspaceRoot);
  if (initialized) {
    saveHandoffState(workspaceRoot, state, { includeGit: false });
  }

  return {
    released: true,
    taskId: safeId,
    previousAgent: existing.agent,
    outcome,
    note,
  };
}
