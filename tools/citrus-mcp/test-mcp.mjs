import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { claimTaskLock, releaseTaskLock } from './handoff-store.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER_PATH = path.join(__dirname, 'server.mjs');

class McpTestClient {
  constructor(workspaceDir) {
    this.proc = spawn(process.execPath, [SERVER_PATH, '--workspace', workspaceDir], {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.pending = new Map();
    this.nextId = 1;
    this.stderrLogs = [];

    this.rl = readline.createInterface({ input: this.proc.stdout, crlfDelay: Infinity });
    this.rl.on('line', (line) => {
      const trimmed = line.trim();
      if (!trimmed) return;
      const msg = JSON.parse(trimmed);
      if (msg.id !== undefined && this.pending.has(msg.id)) {
        const { resolve } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        resolve(msg);
      }
    });

    this.proc.stderr.on('data', (chunk) => {
      this.stderrLogs.push(chunk.toString('utf8'));
    });
  }

  request(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Timeout waiting for ${method} (id=${id})`));
      }, 5000);
      this.pending.set(id, {
        resolve: (msg) => {
          clearTimeout(timer);
          resolve(msg);
        },
      });
      this.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  notify(method, params = {}) {
    this.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');
  }

  async close() {
    if (this.proc.exitCode !== null || this.proc.signalCode !== null) return;
    this.proc.stdin.end();
    await new Promise((resolve) => this.proc.once('close', resolve));
    this.rl.close();
  }
}

test('citrus-collab MCP サーバーのプロトコル通信・状態引き継ぎ・排他ロック検証', async (t) => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'citrus-mcp-test-'));
  fs.writeFileSync(path.join(tmpRoot, 'AGENTS.md'), '# test\n', 'utf8');

  t.after(() => {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  });

  const client = new McpTestClient(tmpRoot);

  // 1. initialize & notifications/initialized
  const initRes = await client.request('initialize', {
    protocolVersion: '2025-11-25',
    capabilities: {},
    clientInfo: { name: 'test-runner', version: '1.0.0' },
  });
  assert.equal(initRes.result.protocolVersion, '2025-11-25');
  assert.equal(initRes.result.serverInfo.name, 'citrus-collab-mcp');
  client.notify('notifications/initialized');

  // 2. ping & tools/list
  const pingRes = await client.request('ping');
  assert.deepEqual(pingRes.result, {});

  const listRes = await client.request('tools/list');
  const toolNames = listRes.result.tools.map((x) => x.name);
  assert.deepEqual(toolNames, [
    'handoff_read',
    'handoff_update',
    'task_claim',
    'task_release',
    'codex_delegate',
    'docker_verify',
  ]);

  // 3. handoff_update & handoff_read
  const updateRes = await client.request('tools/call', {
    name: 'handoff_update',
    arguments: {
      agent: 'antigravity',
      taskId: 'step5-adblock',
      phase: 'Phase 5: AdBlock 強化',
      summary: 'AdBlock デフォルトリストの調査を完了し Codex へ実装を引き継ぎ',
      nextAgent: 'codex',
      nextObjective: 'default_filter_list.txt を 2026年最新フィルタへ更新する',
      acceptanceCriteria: ['assembleDebug が通ること'],
      targetFiles: ['module/adblock/src/main/assets/adblock/default_filter_list.txt'],
      completedItems: ['Step 4-3 完了'],
    },
  });
  assert.equal(updateRes.result.isError, undefined);

  const readRes = await client.request('tools/call', {
    name: 'handoff_read',
    arguments: { includeGitStatus: false },
  });
  const readPayload = JSON.parse(readRes.result.content[0].text);
  assert.equal(readPayload.initialized, true);
  assert.equal(readPayload.state.activeTask.id, 'step5-adblock');
  assert.equal(readPayload.state.activeTask.assignedAgent, 'codex');

  // 4. task_claim 重複ロック拒否と task_release
  const claimCodex = await client.request('tools/call', {
    name: 'task_claim',
    arguments: { taskId: 'step5-adblock', agent: 'codex', scopePaths: ['module/adblock'] },
  });
  assert.equal(JSON.parse(claimCodex.result.content[0].text).acquired, true);

  const conflictClaim = await client.request('tools/call', {
    name: 'task_claim',
    arguments: { taskId: 'step5-adblock', agent: 'antigravity' },
  });
  assert.equal(conflictClaim.result.isError, true);

  const releaseCodex = await client.request('tools/call', {
    name: 'task_release',
    arguments: { taskId: 'step5-adblock', agent: 'codex', outcome: 'completed' },
  });
  assert.equal(JSON.parse(releaseCodex.result.content[0].text).released, true);

  // 5. resources/read & prompts/get
  const mdRes = await client.request('resources/read', { uri: 'citrus://handoff/markdown' });
  assert.match(mdRes.result.contents[0].text, /step5-adblock/);

  const promptRes = await client.request('prompts/get', { name: 'resume-handoff' });
  assert.match(promptRes.result.messages[0].content.text, /step5-adblock/);

  await client.close();
  assert.equal(client.stderrLogs.length, 0);
});

function createWorkspace(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'citrus-collab-regression-'));
  fs.writeFileSync(path.join(root, 'AGENTS.md'), '# テスト用\n');
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

function callTool(client, name, args) {
  return client.request('tools/call', { name, arguments: args });
}

test('共通排他を同期除外配下に置き、一時的な EBUSY と EPERM から解放を回復する', (t) => {
  const root = createWorkspace(t);
  const mutexPath = path.join(root, '.agents', 'handoff', 'locks', '.store-mutex');
  const nativeRmdir = fs.rmdirSync;
  let attempts = 0;
  t.mock.method(fs, 'rmdirSync', (target, ...args) => {
    if (target === mutexPath) {
      attempts++;
      if (attempts <= 2) {
        const error = new Error('テスト用の一時的な占有');
        error.code = attempts === 1 ? 'EBUSY' : 'EPERM';
        throw error;
      }
    }
    return nativeRmdir(target, ...args);
  });
  try {
    assert.equal(claimTaskLock(root, { taskId: 'retry', agent: 'codex' }).acquired, true);
    assert.equal(attempts, 3);
    assert.equal(fs.existsSync(mutexPath), false);
    assert.equal(fs.existsSync(path.join(root, '.agents', 'handoff', '.store-mutex')), false);
    assert.equal(releaseTaskLock(root, { taskId: 'retry', agent: 'codex' }).released, true);
  } finally {
    t.mock.restoreAll();
  }
});

test('共通排他の永続的な占有は有限回で失敗し、残ったロックを勝手に奪取しない', { timeout: 4000 }, (t) => {
  const root = createWorkspace(t);
  const mutexPath = path.join(root, '.agents', 'handoff', 'locks', '.store-mutex');
  const nativeRmdir = fs.rmdirSync;
  let attempts = 0;
  t.mock.method(fs, 'rmdirSync', (target, ...args) => {
    if (target === mutexPath) {
      attempts++;
      const error = new Error('テスト用の永続的な占有');
      error.code = 'EBUSY';
      throw error;
    }
    return nativeRmdir(target, ...args);
  });
  try {
    assert.throws(() => claimTaskLock(root, { taskId: 'busy', agent: 'codex' }), { code: 'EBUSY' });
    assert.ok(attempts > 1 && attempts <= 25);
    assert.equal(fs.existsSync(mutexPath), true);
    assert.equal(fs.existsSync(path.join(root, '.agents', 'handoff', 'locks', 'busy.lock.json')), true);
  } finally {
    t.mock.restoreAll();
    // このテストだけが作成した空ディレクトリであり、別プロセスの所有物ではない。
    nativeRmdir(mutexPath);
  }
});

test('異なるサーバーでも同じ対象を二重取得せず、別セッションから解放しない', async (t) => {
  const root = createWorkspace(t);
  fs.mkdirSync(path.join(root, 'module', 'adblock'), { recursive: true });
  const clients = Array.from({ length: 4 }, () => new McpTestClient(root));
  t.after(async () => { await Promise.all(clients.map((client) => client.close())); });
  const claims = await Promise.all(clients.map((client, index) => callTool(client, 'task_claim', {
    taskId: `task-${index}`, agent: 'codex', scopePaths: [index % 2 ? 'module\\adblock/.' :
      (process.platform === 'win32' ? 'MODULE/ADBLOCK' : 'module/adblock')],
  })));
  const winners = claims.map((result, index) => ({ result, index })).filter(({ result }) => !result.result.isError);
  assert.equal(winners.length, 1);
  const winner = winners[0].index;
  const loser = (winner + 1) % clients.length;
  const foreignRelease = await callTool(clients[loser], 'task_release', { taskId: `task-${winner}`, agent: 'codex' });
  assert.equal(foreignRelease.result.isError, true);
  const repeated = await callTool(clients[winner], 'task_claim', { taskId: `task-${winner}`, agent: 'codex', scopePaths: ['module/adblock'] });
  assert.equal(repeated.result.isError, true);
  const parentClaim = await callTool(clients[loser], 'task_claim', { taskId: 'parent-task', agent: 'antigravity', scopePaths: ['module'] });
  assert.equal(parentClaim.result.isError, true);
  const unrelatedClaim = await callTool(clients[loser], 'task_claim', { taskId: 'unrelated', agent: 'codex', scopePaths: ['module/adblock-other'] });
  assert.equal(unrelatedClaim.result.isError, undefined);
  const release = await callTool(clients[winner], 'task_release', { taskId: `task-${winner}`, agent: 'codex' });
  assert.equal(release.result.isError, undefined);
  const updates = await Promise.all(clients.map((client, index) => callTool(client, 'handoff_update', {
    agent: 'codex', summary: `担当${index}の更新`, nextAgent: 'codex', nextObjective: '確認を続ける', completedItems: [`完了${index}`],
  })));
  updates.forEach((result) => assert.equal(result.result.isError, undefined));
  const snapshot = await callTool(clients[0], 'handoff_read', { includeGitStatus: false });
  const state = JSON.parse(snapshot.result.content[0].text).state;
  assert.equal(state.completedItems.length, 4);
  assert.equal(state.history.length, 4);
});

test('ルート外の対象と不正な実行引数を拒否し、期限切れロックだけを解放する', async (t) => {
  const root = createWorkspace(t);
  const client = new McpTestClient(root);
  t.after(() => client.close());
  for (const scope of ['../outside', '..\\outside', path.dirname(root)]) {
    const result = await callTool(client, 'task_claim', { taskId: 'escape', agent: 'codex', scopePaths: [scope] });
    assert.equal(result.result.isError, true);
  }
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'citrus-outside-'));
  t.after(() => fs.rmSync(outside, { recursive: true, force: true }));
  fs.symlinkSync(outside, path.join(root, 'linked-outside'), process.platform === 'win32' ? 'junction' : 'dir');
  const linked = await callTool(client, 'task_claim', { taskId: 'linked', agent: 'codex', scopePaths: ['linked-outside/new-child'] });
  assert.equal(linked.result.isError, true);
  for (const task of ['assembleDebug; echo unwanted', '--init-script anything', 123]) {
    const result = await callTool(client, 'docker_verify', { task });
    assert.equal(result.result.isError, true);
    assert.match(result.result.content[0].text, /不正なパラメータ.*task/);
  }
  const badAgent = await callTool(client, 'task_claim', { taskId: 'invalid', agent: 'other' });
  assert.equal(badAgent.result.isError, true);
  const locksDir = path.join(root, '.agents', 'handoff', 'locks');
  fs.mkdirSync(locksDir, { recursive: true });
  fs.writeFileSync(path.join(locksDir, 'expired.lock.json'), JSON.stringify({
    taskId: 'expired', agent: 'antigravity', scopePaths: ['.'], expiresAt: '2000-01-01T00:00:00Z',
  }));
  const claim = await callTool(client, 'task_claim', { taskId: 'new', agent: 'codex', scopePaths: ['module'] });
  assert.equal(claim.result.isError, undefined);
  assert.equal(fs.existsSync(path.join(locksDir, 'expired.lock.json')), false);
  fs.writeFileSync(path.join(locksDir, 'broken.lock.json'), '{');
  const broken = await callTool(client, 'task_claim', { taskId: 'unsafe', agent: 'codex', scopePaths: ['scripts'] });
  assert.equal(broken.result.isError, true);
  assert.equal(fs.existsSync(path.join(locksDir, 'broken.lock.json')), true);
});

const hasPowerShell = spawnSync('pwsh', ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], { windowsHide: true }).status === 0;

function setupFixture(t) {
  const root = createWorkspace(t);
  fs.mkdirSync(path.join(root, 'scripts'));
  fs.mkdirSync(path.join(root, 'tools', 'citrus-mcp'), { recursive: true });
  fs.mkdirSync(path.join(root, '.codex'));
  fs.writeFileSync(path.join(root, 'tools', 'citrus-mcp', 'server.mjs'), '// テスト用\n');
  fs.writeFileSync(path.join(root, 'scripts', 'setup-dropbox-ignore.ps1'), '# テストでは ADS を変更しない\n');
  for (const name of ['setup-ai-collab.ps1', 'codex-delegate.ps1']) {
    fs.copyFileSync(path.join(__dirname, '..', '..', 'scripts', name), path.join(root, 'scripts', name));
  }
  return root;
}

test('ローカル設定を保全し、ホスト登録と信頼登録を明示指定に限定する', { skip: !hasPowerShell }, (t) => {
  const root = setupFixture(t);
  const configPath = path.join(root, '.codex', 'config.toml');
  const original = 'model = "existing-model"\nsandbox_mode = "read-only"\n[mcp_servers."citrus-collab"] # 既存設定\nenabled = false\n"command" = "old-node"\nargs = ["old-server#文字列内の記号"] # 既存の引数\n[mcp_servers.other]\ncommand = "existing-server"\n';
  fs.writeFileSync(configPath, original);
  const fakeHome = path.join(root, 'fake-home');
  fs.mkdirSync(fakeHome);
  const runSetup = (...args) => {
    const result = spawnSync('pwsh', ['-NoProfile', '-File', path.join(root, 'scripts', 'setup-ai-collab.ps1'), ...args], {
      encoding: 'utf8', windowsHide: true, env: { ...process.env, USERPROFILE: fakeHome },
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);
  };
  runSetup();
  const updated = fs.readFileSync(configPath, 'utf8');
  assert.match(updated, /model = "existing-model"/);
  assert.match(updated, /sandbox_mode = "read-only"/);
  assert.match(updated, /enabled = false/);
  assert.match(updated, /command = "existing-server"/);
  assert.equal((updated.match(/\[mcp_servers\."?citrus-collab"?\]/g) ?? []).length, 1);
  assert.doesNotMatch(updated, /old-node|old-server/);
  const backup = fs.readdirSync(path.join(root, '.codex')).find((name) => name.startsWith('config.toml.bak.'));
  assert.equal(fs.readFileSync(path.join(root, '.codex', backup), 'utf8'), original);
  assert.equal(fs.existsSync(path.join(fakeHome, '.codex')), false);
  assert.equal(fs.existsSync(path.join(fakeHome, '.gemini')), false);
  runSetup('-RegisterHost', '-SkipGemini');
  const hostConfig = path.join(fakeHome, '.codex', 'config.toml');
  assert.doesNotMatch(fs.readFileSync(hostConfig, 'utf8'), /trust_level/);
  runSetup('-RegisterHost', '-TrustWorkspace', '-SkipGemini');
  assert.match(fs.readFileSync(hostConfig, 'utf8'), /trust_level = "trusted"/);
});

test('複数行の既存 TOML 値を壊さず停止し、レビューにもサンドボックスを渡す', { skip: !hasPowerShell }, (t) => {
  const root = setupFixture(t);
  const configPath = path.join(root, '.codex', 'config.toml');
  for (const arrayStart of ['args = [', 'args = [ # [引数一覧]', 'args = [ "#文字列内の記号", # ]']) {
    const original = `[mcp_servers.citrus-collab]\n${arrayStart}\n  "existing",\n]\n`;
    fs.writeFileSync(configPath, original);
    const setupResult = spawnSync('pwsh', ['-NoProfile', '-File', path.join(root, 'scripts', 'setup-ai-collab.ps1')], { encoding: 'utf8', windowsHide: true });
    assert.notEqual(setupResult.status, 0);
    assert.equal(fs.readFileSync(configPath, 'utf8'), original);
  }
  const review = spawnSync('pwsh', ['-NoProfile', '-File', path.join(root, 'scripts', 'codex-delegate.ps1'),
    '-Mode', 'review', '-Sandbox', 'read-only', '-Uncommitted', '-DryRun'], { encoding: 'utf8', windowsHide: true });
  assert.equal(review.status, 0, review.stderr);
  const preview = JSON.parse(review.stdout);
  assert.deepEqual(preview.args.slice(0, 6), ['exec', '-C', root, '-s', 'read-only', 'review']);
  assert.ok(preview.args.includes('--uncommitted'));
});
