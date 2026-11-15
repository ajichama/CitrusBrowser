<#
.SYNOPSIS
  Dropbox の Cloud Files フィルタドライバによる .git/objects 書き込みロックを回避するため、
  Linux Docker コンテナ内でステージ済み差分だけをコミットします。
  -Push を指定した場合だけ、ホスト側から GitHub (origin) へ Push します。
.DESCRIPTION
  事前に公開対象のファイルだけを git add してください。このスクリプトはステージ範囲を変更しません。
.EXAMPLE
  pwsh -File scripts/git-commit-push.ps1 -Message "fix: 基盤スクリプトを修正"
  pwsh -File scripts/git-commit-push.ps1 -Message "fix: 基盤スクリプトを修正" -Push -Branch main
#>

param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Message,
    [string]$Branch,
    [switch]$Push
)

$ErrorActionPreference = "Stop"
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $WorkspaceRoot

if ($Branch -and -not $Push) {
    throw "-Branch は -Push と一緒に指定してください。"
}
if ($Push) {
    if (-not $Branch) {
        $Branch = git -c "safe.directory=$WorkspaceRoot" branch --show-current
        if ($LASTEXITCODE -ne 0 -or -not $Branch) {
            throw "Push 先ブランチを取得できません。-Branch を指定してください。"
        }
        $Branch = $Branch.Trim()
    }
    git check-ref-format "refs/heads/$Branch"
    if ($LASTEXITCODE -ne 0) {
        throw "Push 先のブランチ名が不正です。"
    }
}

$dockerInfoOut = docker info 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    if ($dockerInfoOut -match "Access is denied" -or $dockerInfoOut -match "permission denied") {
        throw "サンドボックス制限により Docker Engine パイプへ接続できません。ホスト権限で再実行してください。"
    }
    throw "Docker Engine が起動していません。"
}

Write-Host "[1/2] Docker コンテナ内でステージ済み差分をコミットしています..." -ForegroundColor Cyan
# コミット文は標準入力で渡し、シェルによる展開を防ぎます。
$ContainerGitCmd = @'
set -e
git config --global --add safe.directory /workspace
git config --global user.name "ajichama"
git config --global user.email "ajichama@users.noreply.github.com"
if git diff --cached --quiet; then
    echo "ステージ済み差分がないため新規コミットをスキップします。"
else
    diff_status=$?
    if [ "$diff_status" -ne 1 ]; then exit "$diff_status"; fi
    git commit --file=-
fi
'@ -replace "`r`n", "`n"

$Message | docker run --rm -i -v "${WorkspaceRoot}:/workspace" citrus-android-builder:latest bash -c $ContainerGitCmd
if ($LASTEXITCODE -ne 0) {
    throw "Docker コンテナ内での git commit に失敗しました。"
}

if (-not $Push) {
    Write-Host "[2/2] コミット処理が完了しました。Push は -Push 指定時だけ実行します。" -ForegroundColor Green
    exit 0
}

$LocalHead = git -c "safe.directory=$WorkspaceRoot" rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw "コミット後の HEAD を取得できませんでした。"
}
$LocalHead = $LocalHead.Trim()
Write-Host "[2/2] GitHub (origin/$Branch, HEAD=$LocalHead) へ Push しています..." -ForegroundColor Cyan

$MaxRetries = 3
for ($i = 1; $i -le $MaxRetries; $i++) {
    git -c "safe.directory=$WorkspaceRoot" push origin "HEAD:refs/heads/$Branch"
    $RemoteLine = git -c "safe.directory=$WorkspaceRoot" ls-remote origin "refs/heads/$Branch"
    if ($RemoteLine -match "^([0-9a-f]{40})") {
        $RemoteHead = $Matches[1]
        if ($RemoteHead -eq $LocalHead) {
            Write-Host "リモート (origin/$Branch) への反映を確認しました: $RemoteHead" -ForegroundColor Green
            exit 0
        }
    }
    Write-Host "Push リトライ中 ($i/$MaxRetries)..." -ForegroundColor Yellow
    Start-Sleep -Seconds 2
}

throw "git push がリモートに反映されませんでした。"
