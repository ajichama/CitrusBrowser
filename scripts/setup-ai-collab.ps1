<#
.SYNOPSIS
  Antigravity (Gemini) と OpenAI Codex のデュアルAI協調環境をローカルおよびホスト設定へセットアップします。
.DESCRIPTION
  1. ローカル引き継ぎディレクトリ (.agents/handoff/) と Dropbox 除外設定を整備
  2. ローカル .codex/config.toml（Git管理外）を生成
  3. -RegisterHost 指定時のみ Antigravity と Codex のホスト側 MCP 設定へマージ登録
     プロジェクト信頼設定はさらに -TrustWorkspace を指定した場合のみ追加
.EXAMPLE
  pwsh -File scripts/setup-ai-collab.ps1
  pwsh -File scripts/setup-ai-collab.ps1 -LocalOnly
  pwsh -File scripts/setup-ai-collab.ps1 -RegisterHost
#>

param(
    [switch]$LocalOnly,
    [switch]$RegisterHost,
    [switch]$TrustWorkspace,
    [switch]$SkipGemini,
    [switch]$SkipCodex
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($LocalOnly -and $RegisterHost) { throw "-LocalOnly と -RegisterHost は同時に指定できません。" }
if ($TrustWorkspace -and (-not $RegisterHost -or $SkipCodex)) {
    throw "-TrustWorkspace には -RegisterHost と Codex 登録が必要です。"
}
$SetupFailed = $false

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$McpServerScript = Join-Path $WorkspaceRoot "tools\citrus-mcp\server.mjs"

if (-not (Test-Path $McpServerScript)) {
    throw "MCP サーバーファイルが見つかりません: $McpServerScript"
}

$NodeCmd = Get-Command "node.exe", "node" -ErrorAction SilentlyContinue | Select-Object -First 1
$NodeExe = if ($NodeCmd) { $NodeCmd.Source } else { "node" }

function Escape-TomlBasicString {
    param([string]$Value)
    return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Content)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $tempPath = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [System.IO.File]::WriteAllText($tempPath, $Content, $Utf8NoBom)
        [System.IO.File]::Move($tempPath, $Path, $true)
    } finally {
        if ([System.IO.File]::Exists($tempPath)) { [System.IO.File]::Delete($tempPath) }
    }
}

function Backup-IfNotEmpty {
    param([string]$Path)
    if ((Test-Path $Path) -and ((Get-Item $Path).Length -gt 0)) {
        $stamp = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
        $bak = "$Path.bak.$stamp"
        Copy-Item -Path $Path -Destination $bak -Force
        Write-Host "  -> バックアップ作成: $bak" -ForegroundColor DarkGray
    }
}

# 引用文字列の中にある # を保ち、行末コメントだけを除く。
function Get-TomlValueWithoutComment {
    param([string]$Line)
    $quote = ""
    $escaped = $false
    for ($i = 0; $i -lt $Line.Length; $i++) {
        $char = [string]$Line[$i]
        if ($quote) {
            if ($quote -eq '"' -and $escaped) { $escaped = $false; continue }
            if ($quote -eq '"' -and $char -eq '\') { $escaped = $true; continue }
            if ($char -eq $quote) { $quote = "" }
        } elseif ($char -eq '#') {
            return $Line.Substring(0, $i).Trim()
        } elseif ($char -eq '"' -or $char -eq "'") {
            $quote = $char
        }
    }
    return $Line.Trim()
}

# TOML の対象セクションの管理対象キーだけを更新する。安全に扱えない複数行値は変更せず停止する。
function Set-TomlSection {
    param(
        [string]$TomlText,
        [string]$SectionHeader,
        [string[]]$BodyLines
    )
    if ($TomlText.Contains('"""') -or $TomlText.Contains("'''")) {
        throw "複数行文字列を含む TOML は自動更新できません。既存設定を保ったまま手動で統合してください。"
    }
    $normalized = $TomlText -replace "`r`n", "`n"
    $lines = if ($normalized) { @($normalized -split "`n") } else { @() }

    $targetIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $headerPattern = if ($SectionHeader -eq '[mcp_servers.citrus-collab]') {
            '^\s*\[\s*mcp_servers\s*\.\s*(?:"citrus-collab"|''citrus-collab''|citrus-collab)\s*\]\s*(#.*)?$'
        } else { '^\s*' + [regex]::Escape($SectionHeader) + '\s*(#.*)?$' }
        if ($lines[$i] -match $headerPattern) {
            $targetIdx = $i
            break
        }
    }

    $newSection = @($SectionHeader) + $BodyLines

    if ($targetIdx -ge 0) {
        $endIdx = $lines.Count
        for ($j = $targetIdx + 1; $j -lt $lines.Count; $j++) {
            if ($lines[$j].Trim() -match '^\[.+\]\s*(#.*)?$') {
                $endIdx = $j
                break
            }
        }
        $managed = [ordered]@{}
        foreach ($line in $BodyLines) {
            if ($line -notmatch '^\s*([A-Za-z0-9_-]+)\s*=') { throw "更新キーを解析できません。" }
            $managed[$Matches[1]] = $line
        }
        $sectionLines = [System.Collections.Generic.List[string]]::new()
        $sectionLines.Add($lines[$targetIdx])
        for ($j = $targetIdx + 1; $j -lt $endIdx; $j++) {
            $line = $lines[$j]
            $keyLine = $line -replace '^\s*["'']([A-Za-z0-9_-]+)["'']\s*=', '$1 ='
            if ($keyLine -match '^\s*([A-Za-z0-9_-]+)\s*=\s*(.*)$' -and $managed.Contains($Matches[1])) {
                $key = $Matches[1]
                $value = Get-TomlValueWithoutComment $Matches[2]
                if ($value.StartsWith('"""') -or $value.StartsWith("'''") -or
                    ($value.StartsWith('[') -and $value -notmatch '\]\s*$')) {
                    throw "複数行 TOML 値 '$key' は自動更新できません。既存設定を保ったまま手動で統合してください。"
                }
                $sectionLines.Add($managed[$key])
                $managed.Remove($key)
            } else {
                $sectionLines.Add($line)
            }
        }
        foreach ($line in $managed.Values) { $sectionLines.Add($line) }
        $newSection = $sectionLines.ToArray()
        $before = if ($targetIdx -gt 0) { $lines[0..($targetIdx - 1)] } else { @() }
        $after = if ($endIdx -lt $lines.Count) { $lines[$endIdx..($lines.Count - 1)] } else { @() }
        if ($after.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($after[0])) {
            $newSection = @($newSection) + @("")
        }
        $merged = @($before) + $newSection + @($after)
    } else {
        while ($lines.Count -gt 0 -and [string]::IsNullOrWhiteSpace($lines[$lines.Count - 1])) {
            $lines = if ($lines.Count -gt 1) { $lines[0..($lines.Count - 2)] } else { @() }
        }
        $merged = if ($lines.Count -gt 0) { @($lines) + @("") + $newSection } else { $newSection }
    }

    return ($merged -join "`n").TrimEnd("`n") + "`n"
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  CitrusBrowser - Dual-AI (Antigravity x Codex) Setup" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. ローカル引き継ぎディレクトリ & Dropbox Ignore の整備
Write-Host "[1/4] ローカル引き継ぎディレクトリと Dropbox 同期除外を設定しています..." -ForegroundColor Cyan
foreach ($rel in @(".agents\handoff", ".agents\handoff\locks", ".agents\handoff\reports", ".codex")) {
    $full = Join-Path $WorkspaceRoot $rel
    if (-not (Test-Path $full)) {
        New-Item -ItemType Directory -Path $full -Force | Out-Null
    }
}
pwsh -NoProfile -File (Join-Path $PSScriptRoot "setup-dropbox-ignore.ps1")

# 2. プロジェクトローカル .codex/config.toml (Git管理外) の生成
Write-Host "[2/4] プロジェクトローカル .codex/config.toml を生成しています..." -ForegroundColor Cyan
$EscapedServer = Escape-TomlBasicString $McpServerScript
$EscapedWorkspace = Escape-TomlBasicString $WorkspaceRoot
$EscapedNode = Escape-TomlBasicString $NodeExe

$LocalCodexToml = @"
# CitrusBrowser プロジェクトローカル Codex 設定（.gitignore 対象・環境固有パス含む）
approval_policy = "on-request"
sandbox_mode = "workspace-write"
project_doc_max_bytes = 65536

"@ -replace "`r`n", "`n"

$LocalCodexPath = Join-Path $WorkspaceRoot ".codex\config.toml"
if (Test-Path $LocalCodexPath) {
    $LocalCodexToml = [System.IO.File]::ReadAllText($LocalCodexPath, $Utf8NoBom)
}
$LocalCodexToml = Set-TomlSection -TomlText $LocalCodexToml -SectionHeader "[mcp_servers.citrus-collab]" -BodyLines @(
    "command = `"$EscapedNode`"",
    "args = [`"$EscapedServer`", `"--workspace`", `"$EscapedWorkspace`"]",
    "cwd = `"$EscapedWorkspace`"",
    "startup_timeout_sec = 10"
)
Backup-IfNotEmpty -Path $LocalCodexPath
Write-Utf8NoBom -Path $LocalCodexPath -Content $LocalCodexToml
Write-Host "  -> 出力完了: $LocalCodexPath" -ForegroundColor Green

if (-not $RegisterHost) {
    Write-Host "[完了] ローカル設定のみを更新しました。ホスト登録には -RegisterHost を指定してください。" -ForegroundColor Yellow
    exit 0
}

# 3. Antigravity グローバル MCP (~/.gemini/config/mcp_config.json) への登録
if (-not $SkipGemini) {
    Write-Host "[3/4] Antigravity グローバル MCP (~/.gemini/config/mcp_config.json) を設定しています..." -ForegroundColor Cyan
    $GeminiMcpPath = Join-Path $env:USERPROFILE ".gemini\config\mcp_config.json"
    try {
        Backup-IfNotEmpty -Path $GeminiMcpPath
        $configObj = [ordered]@{}
        if (Test-Path $GeminiMcpPath) {
            $rawJson = [System.IO.File]::ReadAllText($GeminiMcpPath, $Utf8NoBom).Trim()
            if ($rawJson) {
                $parsed = $rawJson | ConvertFrom-Json -AsHashtable
                if ($parsed -is [hashtable] -or $parsed -is [System.Collections.IDictionary]) {
                    foreach ($k in $parsed.Keys) {
                        $configObj[$k] = $parsed[$k]
                    }
                }
            }
        }
        if (-not $configObj.Contains("mcpServers") -or -not ($configObj["mcpServers"] -is [System.Collections.IDictionary])) {
            $configObj["mcpServers"] = [ordered]@{}
        }
        $configObj["mcpServers"]["citrus-collab"] = [ordered]@{
            command = $NodeExe
            args    = @($McpServerScript, "--workspace", $WorkspaceRoot)
        }
        $jsonOut = ($configObj | ConvertTo-Json -Depth 10) + "`n"
        Write-Utf8NoBom -Path $GeminiMcpPath -Content $jsonOut
        Write-Host "  -> Antigravity MCP (citrus-collab) を登録しました: $GeminiMcpPath" -ForegroundColor Green
    } catch {
        $SetupFailed = $true
        Write-Warning "Antigravity 設定 ($GeminiMcpPath) への書き込みに失敗しました: $_"
        Write-Warning "権限と設定形式を確認して -RegisterHost を指定して再実行してください。"
    }
}

# 4. Codex グローバル (~/.codex/config.toml) への信頼設定 & MCP 登録
if (-not $SkipCodex) {
    Write-Host "[4/4] Codex グローバル (~/.codex/config.toml) を設定しています..." -ForegroundColor Cyan
    $UserCodexConfig = Join-Path $env:USERPROFILE ".codex\config.toml"
    try {
        Backup-IfNotEmpty -Path $UserCodexConfig
        $existingToml = if (Test-Path $UserCodexConfig) {
            [System.IO.File]::ReadAllText($UserCodexConfig, $Utf8NoBom)
        } else {
            ""
        }

        $trustHeader = "[projects.`"$EscapedWorkspace`"]"
        $slashHeader = "[projects.`"$($WorkspaceRoot.Replace('\', '/'))`"]"
        if ($TrustWorkspace -and ($existingToml -notmatch [regex]::Escape($trustHeader)) -and ($existingToml -notmatch [regex]::Escape($slashHeader))) {
            $existingToml = Set-TomlSection -TomlText $existingToml -SectionHeader $trustHeader -BodyLines @(
                'trust_level = "trusted"'
            )
            Write-Host "  -> プロジェクト信頼設定を追加しました: $trustHeader" -ForegroundColor Green
        } elseif ($TrustWorkspace) {
            Write-Host "  -> プロジェクト信頼設定は既に存在します。" -ForegroundColor DarkGray
        }

        $existingToml = Set-TomlSection -TomlText $existingToml -SectionHeader "[mcp_servers.citrus-collab]" -BodyLines @(
            "command = `"$EscapedNode`"",
            "args = [`"$EscapedServer`", `"--workspace`", `"$EscapedWorkspace`"]",
            "cwd = `"$EscapedWorkspace`"",
            "startup_timeout_sec = 10"
        )

        Write-Utf8NoBom -Path $UserCodexConfig -Content $existingToml
        Write-Host "  -> Codex MCP (citrus-collab) を登録しました: $UserCodexConfig" -ForegroundColor Green
    } catch {
        $SetupFailed = $true
        Write-Warning "Codex グローバル設定 ($UserCodexConfig) への書き込みに失敗しました: $_"
        Write-Warning "権限と設定形式を確認して -RegisterHost -SkipGemini を指定して再実行してください。"
    }
}

if ($SetupFailed) { throw "一部のホスト設定を更新できませんでした。上記の詳細を確認してください。" }
Write-Host "`nセットアップ処理が完了しました。" -ForegroundColor Green
