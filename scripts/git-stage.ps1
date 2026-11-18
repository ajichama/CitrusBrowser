<#
.SYNOPSIS
  指定したパスだけを Docker コンテナ内で git add する（ホストの git add は Dropbox の
  .git/objects 書込ロックで失敗するため）。ステージ前に個人情報・秘密情報の混入を検査する。
.EXAMPLE
  pwsh -File scripts/git-stage.ps1 -Path module/adblock,docs/PROGRESS.md
  pwsh -File scripts/git-commit-push.ps1 -Message "fix: ..." [-Push -Branch main]
#>
param(
    [Parameter(Mandatory = $true)]
    [string[]]$Path
)

$ErrorActionPreference = 'Stop'
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $WorkspaceRoot

$paths = @($Path | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($p in $paths) {
    if ($p -in @('.', '*', '-A', '--all', ':/') -or $p.StartsWith('-') -or [System.IO.Path]::IsPathRooted($p) -or $p -match '(^|[\\/])\.\.([\\/]|$)') {
        throw "ステージ対象はリポジトリ内の相対パスを明示してください（全件追加は禁止）: $p"
    }
    if (-not (Test-Path -LiteralPath $p)) {
        # 削除をステージする場合に備え、Git が追跡しているパスなら許可する。
        if (-not (git -c "safe.directory=$WorkspaceRoot" ls-files -- $p)) { throw "パスが見つかりません: $p" }
    }
}

# 変更・未追跡ファイルを列挙し、公開してはいけない情報を検査する。
$files = @(git -c "safe.directory=$WorkspaceRoot" diff --name-only -- @paths) +
    @(git -c "safe.directory=$WorkspaceRoot" ls-files --others --exclude-standard -- @paths) |
    Sort-Object -Unique | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
$patterns = @(
    '\b(?:10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])|192\.168)\.\d{1,3}\.\d{1,3}\b',  # 端末などのプライベート IP
    'BEGIN [A-Z ]*PRIVATE KEY',
    [regex]::Escape("\Users\$($env:USERNAME)\"),
    [regex]::Escape("/Users/$($env:USERNAME)/")
)
$hits = foreach ($file in $files) {
    Select-String -LiteralPath $file -Pattern $patterns -ErrorAction SilentlyContinue |
        ForEach-Object { "{0}:{1}" -f $file, $_.LineNumber }
}
if ($hits) {
    $hits | ForEach-Object { Write-Host "  検出: $_" -ForegroundColor Yellow }
    throw '個人情報・秘密情報の可能性がある記述を検出したため、ステージしません。内容を確認してください。'
}
Write-Host "[stage] 検査済み: $($files.Count) ファイル" -ForegroundColor Cyan

$script = 'git config --global --add safe.directory /workspace && git add -- "$@" && git diff --cached --stat'
docker run --rm -v "${WorkspaceRoot}:/workspace" -w /workspace citrus-android-builder:latest bash -c $script stage @paths
if ($LASTEXITCODE -ne 0) { throw 'Docker コンテナ内での git add に失敗しました。' }
