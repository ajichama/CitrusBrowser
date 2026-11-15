<#
.SYNOPSIS
  OpenAI Codex CLI (codex exec / codex exec review) を非対話モードで呼び出し、
  CitrusBrowser の作業やレビューを安全に委譲・レポート保存します。
.DESCRIPTION
  Antigravity（手動コマンドまたは citrus-collab MCP の codex_delegate ツール）および開発者から共通利用できます。
  長文プロンプトや日本語のコマンドライン崩れを防ぐため、プロンプトは UTF-8 標準入力経由で Codex へ渡します。
.EXAMPLE
  pwsh -File scripts/codex-delegate.ps1 -Mode exec -Prompt "HANDOFF.md を確認して Step 5 を進めて"
  pwsh -File scripts/codex-delegate.ps1 -Mode review -Uncommitted
#>

param(
    [ValidateSet("exec", "review")]
    [string]$Mode = "exec",

    [string]$Prompt = "",

    [string]$PromptFile = "",

    [switch]$PromptStdin,

    [ValidateSet("read-only", "workspace-write")]
    [string]$Sandbox = "workspace-write",

    [string]$Model = "",

    [string]$ReportName = "",

    [switch]$Uncommitted,

    [string]$BaseBranch = "",

    [string]$CommitSha = "",

    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $WorkspaceRoot

# 1. プロンプト文字列の解決（ファイル / 標準入力 / 引数）
$ResolvedPrompt = $Prompt
if ($PromptFile) {
    if (-not (Test-Path $PromptFile)) {
        throw "指定されたプロンプトファイルが見つかりません: $PromptFile"
    }
    $ResolvedPrompt = Get-Content -Path $PromptFile -Raw -Encoding UTF8
} elseif ($PromptStdin) {
    $ResolvedPrompt = [Console]::In.ReadToEnd()
}

if ($Mode -eq "exec" -and [string]::IsNullOrWhiteSpace($ResolvedPrompt)) {
    throw "exec モードでは -Prompt / -PromptFile / -PromptStdin のいずれかで指示を指定してください。"
}

# 2. レポート保存ディレクトリの準備（Dropbox 競合回避対象配下）
$ReportsDir = Join-Path $WorkspaceRoot ".agents/handoff/reports"
$LocksDir = Join-Path $WorkspaceRoot ".agents/handoff/locks"
foreach ($dir in @($ReportsDir, $LocksDir)) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

$Timestamp = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + [guid]::NewGuid().ToString("N")
$SafeSuffix = if ($ReportName) {
    "-" + (($ReportName -replace "[^a-zA-Z0-9._-]", "-").Trim("-"))
} else {
    ""
}
$ReportPath = Join-Path $ReportsDir "${Timestamp}-${Mode}${SafeSuffix}.md"

# 3. Codex CLI 実行バイナリ探索
$CodexCmd = Get-Command "codex.cmd", "codex" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $CodexCmd -and -not $DryRun) {
    throw "codex CLI が PATH 上に見つかりません。@openai/codex のインストールを確認してください。"
}
$CodexExe = if ($CodexCmd) { $CodexCmd.Source } else { "codex" }

# 4. 実行引数の構築
$CodexArgs = New-Object System.Collections.Generic.List[string]
$CodexArgs.Add("exec")
$CodexArgs.Add("-C")
$CodexArgs.Add($WorkspaceRoot)
$CodexArgs.Add("-s")
$CodexArgs.Add($Sandbox)
if ($Mode -eq "exec") {
    if ($Model) {
        $CodexArgs.Add("-m")
        $CodexArgs.Add($Model)
    }
    $CodexArgs.Add("-o")
    $CodexArgs.Add($ReportPath)
    $CodexArgs.Add("-")
} else {
    $CodexArgs.Add("review")
    if ($Model) {
        $CodexArgs.Add("-m")
        $CodexArgs.Add($Model)
    }
    if ($Uncommitted) {
        $CodexArgs.Add("--uncommitted")
    } elseif ($BaseBranch) {
        $CodexArgs.Add("--base")
        $CodexArgs.Add($BaseBranch)
    } elseif ($CommitSha) {
        $CodexArgs.Add("--commit")
        $CodexArgs.Add($CommitSha)
    }
    $CodexArgs.Add("-o")
    $CodexArgs.Add($ReportPath)
    if (-not [string]::IsNullOrWhiteSpace($ResolvedPrompt) -and -not $Uncommitted -and -not $BaseBranch -and -not $CommitSha) {
        $CodexArgs.Add("-")
    }
}

if ($DryRun) {
    $Preview = [ordered]@{
        dryRun        = $true
        workspaceRoot = $WorkspaceRoot
        codexBinary   = $CodexExe
        mode          = $Mode
        args          = $CodexArgs.ToArray()
        reportPath    = $ReportPath
        promptLength  = $ResolvedPrompt.Length
    }
    Write-Output ($Preview | ConvertTo-Json -Depth 4)
    exit 0
}

Write-Host "[Codex Delegate] モード: $Mode | サンドボックス: $Sandbox" -ForegroundColor Cyan
Write-Host "[Codex Delegate] レポート出力先: $ReportPath" -ForegroundColor DarkGray

# 5. UTF-8 標準入力パイプ付きで Codex CLI を呼び出す
$UseStdin = ($CodexArgs[$CodexArgs.Count - 1] -eq "-")
$RawOutput = ""
$ExitCode = 0

if ($UseStdin) {
    $ResolvedPrompt | & $CodexExe @CodexArgs
    $ExitCode = $LASTEXITCODE
} else {
    & $CodexExe @CodexArgs
    $ExitCode = $LASTEXITCODE
}

if ($ExitCode -ne 0) {
    Write-Warning "Codex CLI が終了コード $ExitCode で終了しました（サンドボックス環境下ではホスト側 ~/.codex へのアクセス権不足が原因の場合があります）。"
}

if (Test-Path $ReportPath) {
    $ReportContent = Get-Content -Path $ReportPath -Raw -Encoding UTF8
    Write-Host "`n--- Codex 応答サマリー ($ReportPath) ---" -ForegroundColor Green
    Write-Output $ReportContent
} else {
    Write-Host "レポートファイルは生成されませんでした（終了コード: $ExitCode）。" -ForegroundColor Yellow
}

exit $ExitCode
