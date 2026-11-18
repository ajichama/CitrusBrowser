#!/usr/bin/env node
/**
 * CitrusBrowser マルチAIエージェント協調用 MCP サーバー (Stdio / JSON-RPC 2.0)
 * Antigravity (Gemini) と OpenAI Codex 間で引き継ぎ台帳・排他ロック・Codex委譲・Docker検証を共有する。
 * 外部 npm パッケージ依存なし（Node.js 標準モジュールのみ）。
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import {
  claimTaskLock,
  getGitSnapshot,
  getHandoffPaths,
  listActiveLocks,
  readHandoffState,
  recordVerification,
  releaseTaskLock,
  renderHandoffMarkdown,
  resolveWorkspaceRoot,
  SUMMARY_SOFT_LIMIT,
  updateHandoffState,
} from './handoff-store.mjs';

const SUPPORTED_PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
const SERVER_INFO = {
  name: 'citrus-collab-mcp',
  version: '1.0.0',
};

const WORKSPACE_ROOT = parseWorkspaceArg(process.argv.slice(2));
const DEBUG_MODE = process.argv.includes('--debug') || process.env.CITRUS_MCP_DEBUG === '1';

function debugLog(message) {
  if (DEBUG_MODE) {
    process.stderr.write(`[citrus-collab-mcp] ${message}\n`);
  }
}

function parseWorkspaceArg(args) {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--workspace' && args[i + 1]) {
      return resolveWorkspaceRoot(args[i + 1]);
    }
    if (args[i].startsWith('--workspace=')) {
      return resolveWorkspaceRoot(args[i].slice('--workspace='.length));
    }
  }
  return resolveWorkspaceRoot();
}

/**
 * JSON-RPC メッセージを stdout へ 1行1JSON (NDJSON) で書き出す。
 */
function sendJsonRpc(payload) {
  process.stdout.write(JSON.stringify(payload) + '\n');
}

function sendResult(id, result) {
  sendJsonRpc({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message, data) {
  const error = data === undefined ? { code, message } : { code, message, data };
  sendJsonRpc({ jsonrpc: '2.0', id, error });
}

function toolTextResult(dataOrText, isError = false) {
  const text = typeof dataOrText === 'string' ? dataOrText : JSON.stringify(dataOrText, null, 2);
  return isError
    ? { content: [{ type: 'text', text }], isError: true }
    : { content: [{ type: 'text', text }] };
}

/**
 * PowerShell 実行バイナリを決定する (Windows 環境で pwsh 優先)。
 */
function resolvePowerShellBinary() {
  return process.platform === 'win32' ? 'pwsh.exe' : 'pwsh';
}

/**
 * 子プロセスを安全に実行し、タイムアウトと終了コードを管理する (shell: false 固定)。
 */
function runChildProcess(command, args, { cwd, stdinText = null, timeoutMs = 300000 } = {}) {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      cwd,
      shell: false,
      stdio: [stdinText !== null ? 'pipe' : 'ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });

    const stdoutChunks = [];
    const stderrChunks = [];
    let timedOut = false;

    const timer = setTimeout(() => {
      timedOut = true;
      try {
        if (process.platform === 'win32' && child.pid) {
          spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { shell: false, windowsHide: true });
        } else {
          child.kill('SIGTERM');
        }
      } catch {
        // 終了済みプロセスへの kill エラーは無視する
      }
    }, timeoutMs);

    child.stdout.on('data', (chunk) => stdoutChunks.push(chunk));
    child.stderr.on('data', (chunk) => stderrChunks.push(chunk));

    child.on('error', (err) => {
      clearTimeout(timer);
      resolve({
        exitCode: -1,
        timedOut,
        spawnError: err.message,
        stdout: Buffer.concat(stdoutChunks).toString('utf8'),
        stderr: Buffer.concat(stderrChunks).toString('utf8'),
      });
    });

    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({
        exitCode: code ?? -1,
        timedOut,
        spawnError: null,
        stdout: Buffer.concat(stdoutChunks).toString('utf8'),
        stderr: Buffer.concat(stderrChunks).toString('utf8'),
      });
    });

    if (stdinText !== null && child.stdin) {
      child.stdin.write(stdinText, 'utf8');
      child.stdin.end();
    }
  });
}

/**
 * 出力テキストが過度に膨らまないよう末尾中心にトリミングする。
 */
function trimOutputTail(text, maxChars = 6000) {
  if (!text || text.length <= maxChars) return text || '';
  return `... (先頭 ${text.length - maxChars} 文字省略)\n` + text.slice(-maxChars);
}

const TOOLS = [
  {
    name: 'handoff_read',
    description:
      'CitrusBrowser のマルチAIエージェント引き継ぎ台帳（現在フェーズ、次タスク目標、制約、アクティブロック、Git状態）を一括取得します。作業開始時やモデル切り替え直後に必ず呼び出してください。',
    inputSchema: {
      type: 'object',
      properties: {
        includeHistory: {
          type: 'boolean',
          description: '過去の引き継ぎ履歴を含めるか（既定: true）',
        },
        includeGitStatus: {
          type: 'boolean',
          description: '現在の Git ブランチ/HEAD/差分ファイル一覧を取得するか（既定: true）',
        },
      },
    },
  },
  {
    name: 'handoff_update',
    description:
      '引き継ぎ状態 JSON (.agents/handoff/state.json) と人間向け台帳 (.agents/handoff/HANDOFF.md) を同期更新します。別エージェントへの交代・作業中断・マイルストーン完了時に呼び出してください。',
    inputSchema: {
      type: 'object',
      properties: {
        agent: {
          type: 'string',
          enum: ['antigravity', 'codex', 'claude', 'human'],
          description: 'この更新を書き込む現在の作業エージェント',
        },
        taskId: {
          type: 'string',
          description: 'タスク識別子（英数字・ハイフン・アンダースコア、例: step5-adblock-filters）',
        },
        phase: {
          type: 'string',
          description: '現在の開発フェーズ名',
        },
        summary: {
          type: 'string',
          description:
            '直前作業の要約。人間が読める平文で600字程度まで。「完了したこと／未完了・未検証のこと／戻すべき一時状態（端末設定等）／次の一手」を分けて書き、担当記号や英単語の羅列による圧縮表現を避ける。詳細ログは .agents/handoff/reports/ のパスで示す。',
        },
        nextAgent: {
          type: 'string',
          enum: ['codex', 'antigravity', 'claude', 'any'],
          description: '次に担当すべきエージェント',
        },
        nextObjective: {
          type: 'string',
          description: '次のエージェントが着手すべき具体的な目標と手順',
        },
        acceptanceCriteria: {
          type: 'array',
          items: { type: 'string' },
          description: '次タスクの完了条件（受け入れ基準）リスト',
        },
        constraints: {
          type: 'array',
          items: { type: 'string' },
          description: '厳守すべき技術制約や注意事項リスト',
        },
        targetFiles: {
          type: 'array',
          items: { type: 'string' },
          description: '主な参照・編集対象の相対ファイルパス一覧',
        },
        completedItems: {
          type: 'array',
          items: { type: 'string' },
          description: '完了済みマイルストーンに追記する項目',
        },
        blockers: {
          type: 'array',
          items: { type: 'string' },
          description: '未解決のブロッカー一覧（解消時は空配列 [] を指定）',
        },
      },
      required: ['agent', 'summary', 'nextAgent', 'nextObjective'],
    },
  },
  {
    name: 'task_claim',
    description:
      '他エージェントとの同時編集・重複作業を防ぐため、指定タスクIDの排他ロック（TTL期限付き）を取得します。',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: {
          type: 'string',
          description: 'ロックを取得するタスクID',
        },
        agent: {
          type: 'string',
          enum: ['antigravity', 'codex', 'claude', 'human'],
          description: 'ロックを要求するエージェント',
        },
        scopePaths: {
          type: 'array',
          items: { type: 'string' },
          description: '編集予定のモジュール・ディレクトリパス一覧',
        },
        ttlMinutes: {
          type: 'number',
          description: 'ロックの有効期限（分、5〜720、既定: 120）',
        },
        force: {
          type: 'boolean',
          description: '他エージェントの既存ロックを強制上書きするか（既定: false）',
        },
      },
      required: ['taskId', 'agent'],
    },
  },
  {
    name: 'task_release',
    description: '取得済みのタスク排他ロックを解放します。タスク完了時または別エージェントへの交代時に呼び出してください。',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: {
          type: 'string',
          description: '解放するタスクID',
        },
        agent: {
          type: 'string',
          enum: ['antigravity', 'codex', 'claude', 'human'],
          description: 'ロックを解放するエージェント',
        },
        outcome: {
          type: 'string',
          enum: ['completed', 'paused', 'aborted'],
          description: '終了ステータス（既定: completed）',
        },
        note: {
          type: 'string',
          description: '解放時の補足メモ',
        },
      },
      required: ['taskId', 'agent'],
    },
  },
  {
    name: 'codex_delegate',
    description:
      'OpenAI Codex CLI (codex exec / review) を非対話モードで呼び出し、サブタスク実装やコードレビューを委譲します。出力は .agents/handoff/reports/ に保存されます。',
    inputSchema: {
      type: 'object',
      properties: {
        mode: {
          type: 'string',
          enum: ['exec', 'review'],
          description: '実行モード: exec（指示実装）または review（差分レビュー）。既定: exec',
        },
        prompt: {
          type: 'string',
          description: 'Codex に渡す具体的な日本語指示（review モード時は任意追加指示）',
        },
        sandbox: {
          type: 'string',
          enum: ['read-only', 'workspace-write'],
          description: 'Codex のサンドボックス権限（既定: workspace-write）',
        },
        model: {
          type: 'string',
          description: '使用するモデル名を上書きする場合に指定（省略時は Codex の既定設定）',
        },
        timeoutSec: {
          type: 'number',
          description: 'タイムアウト秒数（30〜900、既定: 300）',
        },
      },
      required: ['prompt'],
    },
  },
  {
    name: 'docker_verify',
    description:
      'scripts/docker-build.ps1 を呼び出して Docker コンテナ内で Android ビルド (assembleDebug) や単体テスト (testDebugUnitTest) を検証し、結果を引き継ぎ台帳に記録します。',
    inputSchema: {
      type: 'object',
      properties: {
        task: {
          type: 'string',
          enum: ['assembleDebug', 'testDebugUnitTest', 'assembleDebug testDebugUnitTest'],
          description: '実行する Gradle タスク（既定: assembleDebug testDebugUnitTest）',
        },
        agent: {
          type: 'string',
          enum: ['antigravity', 'codex', 'claude', 'human'],
          description: '検証を実行したエージェント名（既定: antigravity）',
        },
        timeoutSec: {
          type: 'number',
          description: 'タイムアウト秒数（60〜1200、既定: 600）',
        },
      },
    },
  },
];

async function handleToolCall(name, args = {}) {
  // クライアント側のスキーマ検証に依存せず、実行前に型と許可値を確認する。
  const definition = TOOLS.find((tool) => tool.name === name);
  if (!definition) throw new Error(`未知のツール名です: ${name}`);
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('ツール引数はオブジェクトで指定してください。');
  for (const required of definition.inputSchema.required ?? []) {
    if (args[required] === undefined) throw new Error(`必須パラメータが不足しています: ${required}`);
  }
  for (const [key, value] of Object.entries(args)) {
    const schema = definition.inputSchema.properties[key];
    if (!schema) throw new Error(`未対応のパラメータです: ${key}`);
    const validType = schema.type === 'array' ? Array.isArray(value) && value.every((item) => typeof item === schema.items.type)
      : typeof value === schema.type && (schema.type !== 'number' || Number.isFinite(value));
    if (!validType || (schema.enum && !schema.enum.includes(value))) throw new Error(`不正なパラメータです: ${key}`);
  }
  switch (name) {
    case 'handoff_read': {
      const includeHistory = args.includeHistory !== false;
      const includeGitStatus = args.includeGitStatus !== false;
      const { state, initialized } = readHandoffState(WORKSPACE_ROOT);
      const locks = listActiveLocks(WORKSPACE_ROOT);
      const git = includeGitStatus ? getGitSnapshot(WORKSPACE_ROOT) : null;
      const response = {
        workspaceRoot: WORKSPACE_ROOT,
        initialized,
        state: includeHistory ? state : { ...state, history: undefined },
        activeLocks: locks,
        git,
      };
      return toolTextResult(response);
    }

    case 'handoff_update': {
      const { agent, summary, nextAgent, nextObjective } = args;
      if (!agent || !summary || !nextAgent || !nextObjective) {
        return toolTextResult(
          '必須パラメータ (agent, summary, nextAgent, nextObjective) が不足しています。',
          true
        );
      }
      const saved = updateHandoffState(WORKSPACE_ROOT, args);
      return toolTextResult({
        updated: true,
        statePath: saved.paths.statePath,
        markdownPath: saved.paths.markdownPath,
        activeTask: saved.state.activeTask,
        updatedAt: saved.state.updatedAt,
        ...(Array.from(summary).length > SUMMARY_SOFT_LIMIT
          ? { warning: `summary が ${SUMMARY_SOFT_LIMIT} 字を超えています。次回は要点を絞り、詳細はレポートのパスで示してください。` }
          : {}),
      });
    }

    case 'task_claim': {
      if (!args.taskId || !args.agent) {
        return toolTextResult('必須パラメータ (taskId, agent) が不足しています。', true);
      }
      const res = claimTaskLock(WORKSPACE_ROOT, args);
      return toolTextResult(res, !res.acquired);
    }

    case 'task_release': {
      if (!args.taskId || !args.agent) {
        return toolTextResult('必須パラメータ (taskId, agent) が不足しています。', true);
      }
      const res = releaseTaskLock(WORKSPACE_ROOT, args);
      return toolTextResult(res, !res.released);
    }

    case 'codex_delegate': {
      if (!args.prompt || typeof args.prompt !== 'string' || !args.prompt.trim()) {
        return toolTextResult('codex_delegate には空でない prompt が必要です。', true);
      }
      const mode = args.mode === 'review' ? 'review' : 'exec';
      const sandbox = args.sandbox === 'read-only' ? 'read-only' : 'workspace-write';
      const timeoutSec = Math.max(30, Math.min(900, Number(args.timeoutSec) || 300));
      const scriptPath = path.join(WORKSPACE_ROOT, 'scripts', 'codex-delegate.ps1');

      if (!fs.existsSync(scriptPath)) {
        return toolTextResult(`連携スクリプトが見つかりません: ${scriptPath}`, true);
      }

      const psArgs = [
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        scriptPath,
        '-Mode',
        mode,
        '-Sandbox',
        sandbox,
        '-PromptStdin',
      ];
      if (args.model) {
        psArgs.push('-Model', String(args.model));
      }

      const result = await runChildProcess(resolvePowerShellBinary(), psArgs, {
        cwd: WORKSPACE_ROOT,
        stdinText: args.prompt,
        timeoutMs: timeoutSec * 1000,
      });

      const failed = result.exitCode !== 0 || result.timedOut || result.spawnError !== null;
      return toolTextResult(
        {
          ok: !failed,
          mode,
          sandbox,
          exitCode: result.exitCode,
          timedOut: result.timedOut,
          spawnError: result.spawnError,
          stdout: trimOutputTail(result.stdout),
          stderr: trimOutputTail(result.stderr, 2000),
          fallbackCommand: 'pwsh -File scripts/codex-delegate.ps1 -Mode ' + mode,
        },
        failed
      );
    }

    case 'docker_verify': {
      const task = args.task || 'assembleDebug testDebugUnitTest';
      const agent = args.agent || 'antigravity';
      const timeoutSec = Math.max(60, Math.min(1200, Number(args.timeoutSec) || 600));
      const scriptPath = path.join(WORKSPACE_ROOT, 'scripts', 'docker-build.ps1');

      if (!fs.existsSync(scriptPath)) {
        return toolTextResult(`Docker ビルドスクリプトが見つかりません: ${scriptPath}`, true);
      }

      const result = await runChildProcess(
        resolvePowerShellBinary(),
        ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', scriptPath, '-Task', task],
        {
          cwd: WORKSPACE_ROOT,
          timeoutMs: timeoutSec * 1000,
        }
      );

      const passed = result.exitCode === 0 && !result.timedOut && !result.spawnError;
      const statusLabel = passed ? 'PASS' : result.timedOut ? 'TIMEOUT' : 'FAIL';

      recordVerification(WORKSPACE_ROOT, {
        task,
        status: statusLabel,
        agent,
        verifiedAt: new Date().toISOString(),
        note: passed
          ? 'docker-build.ps1 検証成功'
          : trimOutputTail(result.stderr || result.stdout, 300),
      });

      return toolTextResult(
        {
          passed,
          task,
          exitCode: result.exitCode,
          timedOut: result.timedOut,
          spawnError: result.spawnError,
          outputTail: trimOutputTail(result.stdout || result.stderr, 5000),
        },
        !passed
      );
    }

    default:
      throw new Error(`未知のツール名です: ${name}`);
  }
}

function handleResourceRead(uri) {
  const { state } = readHandoffState(WORKSPACE_ROOT);
  const locks = listActiveLocks(WORKSPACE_ROOT);
  if (uri === 'citrus://handoff/state') {
    return {
      contents: [
        {
          uri,
          mimeType: 'application/json',
          text: JSON.stringify(state, null, 2),
        },
      ],
    };
  }
  if (uri === 'citrus://handoff/markdown') {
    const git = getGitSnapshot(WORKSPACE_ROOT);
    return {
      contents: [
        {
          uri,
          mimeType: 'text/markdown',
          text: renderHandoffMarkdown(state, locks, git),
        },
      ],
    };
  }
  throw new Error(`未登録のリソース URI です: ${uri}`);
}

function handlePromptGet(name, args = {}) {
  const { state } = readHandoffState(WORKSPACE_ROOT);
  const paths = getHandoffPaths(WORKSPACE_ROOT);
  const targetAgent = args.agent || state.activeTask?.assignedAgent || 'codex';

  if (name === 'resume-handoff') {
    return {
      description: '現在の引き継ぎ台帳から作業を再開するための標準プロンプト',
      messages: [
        {
          role: 'user',
          content: {
            type: 'text',
            text: [
              `あなたは ${targetAgent} として CitrusBrowser の開発タスクを引き継ぎます。`,
              `1. まず AGENTS.md および引き継ぎ台帳 (${paths.markdownPath} / MCP handoff_read) を確認してください。`,
              `2. 現在のタスクIDは "${state.activeTask.id}"、目標は「${state.activeTask.objective}」です。`,
              '3. 重複作業防止のため必要なら task_claim を取得し、作業後は Docker ビルド検証と handoff_update を実施してください。',
            ].join('\n'),
          },
        },
      ],
    };
  }

  if (name === 'handover-to-peer') {
    const nextAgent = args.nextAgent || 'codex';
    return {
      description: '別エージェントへ現在作業を安全に引き継ぐためのチェックリストプロンプト',
      messages: [
        {
          role: 'user',
          content: {
            type: 'text',
            text: [
              `作業を "${nextAgent}" へ引き継ぎます。以下を実施してください:`,
              '1. 編集中のファイルにビルドエラーや中途半端な状態がないか確認する。',
              '2. MCP handoff_update を呼び出し、完了事項・次目標・受け入れ条件・関連ファイル・注意点を構造化して記録する。',
              '3. 保有している task_claim ロックがあれば task_release で解放する。',
            ].join('\n'),
          },
        },
      ],
    };
  }

  throw new Error(`未登録のプロンプト名です: ${name}`);
}

async function dispatchRequest(msg) {
  const { id, method, params } = msg;
  const isNotification = id === undefined || id === null;

  try {
    switch (method) {
      case 'initialize': {
        const requested = params?.protocolVersion;
        const protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.includes(requested)
          ? requested
          : '2025-03-26';
        return sendResult(id, {
          protocolVersion,
          capabilities: {
            tools: { listChanged: false },
            resources: { listChanged: false },
            prompts: { listChanged: false },
          },
          serverInfo: SERVER_INFO,
        });
      }

      case 'notifications/initialized':
      case 'notifications/cancelled':
      case 'notifications/roots/list_changed':
        return;

      case 'ping':
        return sendResult(id, {});

      case 'tools/list':
        return sendResult(id, { tools: TOOLS });

      case 'tools/call': {
        const result = await handleToolCall(params?.name, params?.arguments);
        return sendResult(id, result);
      }

      case 'resources/list':
        return sendResult(id, {
          resources: [
            {
              uri: 'citrus://handoff/state',
              name: 'CitrusBrowser Handoff State (JSON)',
              description: '現在の開発フェーズ・タスク・ロック・履歴の構造化 JSON 状態',
              mimeType: 'application/json',
            },
            {
              uri: 'citrus://handoff/markdown',
              name: 'CitrusBrowser Handoff Ledger (Markdown)',
              description: '人間と AI エージェント向けの最新引き継ぎ台帳ドキュメント',
              mimeType: 'text/markdown',
            },
          ],
        });

      case 'resources/read':
        return sendResult(id, handleResourceRead(params?.uri));

      case 'prompts/list':
        return sendResult(id, {
          prompts: [
            {
              name: 'resume-handoff',
              description: '引き継ぎ台帳を読み込んで次タスクを開始するプロンプト',
              arguments: [
                { name: 'agent', description: '担当エージェント名 (codex / antigravity)', required: false },
              ],
            },
            {
              name: 'handover-to-peer',
              description: '作業を中断して相手エージェントへ綺麗に引き継ぐプロンプト',
              arguments: [
                { name: 'nextAgent', description: '引き継ぎ先エージェント名 (codex / antigravity / claude)', required: false },
              ],
            },
          ],
        });

      case 'prompts/get':
        return sendResult(id, handlePromptGet(params?.name, params?.arguments));

      default:
        if (isNotification) return;
        return sendError(id, -32601, `未対応のメソッドです: ${method}`);
    }
  } catch (error) {
    debugLog(`Error in ${method}: ${error.stack || error.message}`);
    if (isNotification) return;
    if (method === 'tools/call') {
      return sendResult(id, toolTextResult(`ツール実行エラー: ${error.message}`, true));
    }
    return sendError(id, -32603, error.message);
  }
}

function startServer() {
  debugLog(`Started for workspace: ${WORKSPACE_ROOT}`);
  const rl = readline.createInterface({
    input: process.stdin,
    crlfDelay: Infinity,
  });

  rl.on('line', (rawLine) => {
    const line = rawLine.replace(/\r$/, '').trim();
    if (!line) return;

    let parsed;
    try {
      parsed = JSON.parse(line);
    } catch (err) {
      sendError(null, -32700, `JSON パースエラー: ${err.message}`);
      return;
    }

    if (Array.isArray(parsed)) {
      for (const item of parsed) {
        void dispatchRequest(item);
      }
      return;
    }

    void dispatchRequest(parsed);
  });

  rl.on('close', () => {
    debugLog('stdin closed, exiting.');
    process.exit(0);
  });
}

startServer();
