<#
.SYNOPSIS
  Dropbox の Cloud Files フィルタドライバによる .git/objects 書き込みロックを回避するため、
  Linux Docker コンテナ内で git add & commit を実行し、ホスト側から GitHub (origin) へ Push します。
.EXAMPLE
  pwsh -File scripts/git-commit-push.ps1 -Message "feat: update dependencies"
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Message,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $WorkspaceRoot

$dockerInfoOut = docker info 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    if ($dockerInfoOut -match "Access is denied" -or $dockerInfoOut -match "permission denied") {
        throw "サンドボックス制限により Docker Engine パイプへ接続できません。ホスト権限で再実行してください。"
    }
    throw "Docker Engine が起動していません。"
}

Write-Host "[1/2] Docker コンテナ内で git add & commit を確認・実行しています..." -ForegroundColor Cyan
$EscapedMsg = $Message.Replace('"', '\"')
$ContainerGitCmd = @"
set -e
git config --global --add safe.directory /workspace
git config --global user.name "ajichama"
git config --global user.email "ajichama@users.noreply.github.com"
git add -A
if ! git diff --cached --quiet; then
    git commit -m "$EscapedMsg"
else
    echo "変更差分がないため新規コミットをスキップします。"
fi
"@ -replace "`r`n", "`n"

docker run --rm -v "${WorkspaceRoot}:/workspace" yuzu-android-builder:latest bash -c $ContainerGitCmd
if ($LASTEXITCODE -ne 0) {
    throw "Docker コンテナ内での git commit に失敗しました。"
}

$LocalHead = (git -c safe.directory=* rev-parse HEAD).Trim()
Write-Host "[2/2] GitHub (origin/$Branch, HEAD=$LocalHead) へ Push しています..." -ForegroundColor Cyan

$MaxRetries = 3
for ($i = 1; $i -le $MaxRetries; $i++) {
    git -c safe.directory=* push origin "HEAD:refs/heads/$Branch"
    $RemoteLine = git -c safe.directory=* ls-remote origin "refs/heads/$Branch"
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
