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

Write-Host "[1/2] Docker コンテナ内で git add & commit を実行しています..." -ForegroundColor Cyan
$EscapedMsg = $Message.Replace('"', '\"')
$ContainerGitCmd = @"
set -e
git config --global --add safe.directory /workspace
git config --global user.name "ajichama"
git config --global user.email "ajichama@users.noreply.github.com"
git add -A
git commit -m "$EscapedMsg"
"@ -replace "`r`n", "`n"

docker run --rm -v "${WorkspaceRoot}:/workspace" yuzu-android-builder:latest bash -c $ContainerGitCmd
if ($LASTEXITCODE -ne 0) {
    throw "Docker コンテナ内での git commit に失敗しました。"
}

Write-Host "[2/2] GitHub (origin/$Branch) へ Push しています..." -ForegroundColor Cyan
git -c safe.directory=* push origin $Branch
if ($LASTEXITCODE -ne 0) {
    throw "git push に失敗しました。"
}

Write-Host "コミットおよび Push が完了しました！" -ForegroundColor Green
