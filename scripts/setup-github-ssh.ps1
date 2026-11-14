<#
.SYNOPSIS
  このAndroidプロジェクト専用の GitHub SSH Deploy Key を発行し、安全にリモート接続とUpstream追跡をセットアップします。
.DESCRIPTION
  アカウント全体ではなく「このリポジトリ1つだけ」に読み書き権限を限定した Deploy Key (Ed25519) を生成し、
  ~/.ssh/config の専用エイリアス (github-android-browser) と known_hosts を構成して origin を設定します。
  （※GitHubの仕様上、Deploy Keyは1リポジトリごとに別々の鍵が必要なため、Android専用の鍵を生成します。
    もしアカウント全体のSSH鍵としてGameの鍵を登録済みの場合は -UseGameKey スイッチで流用も可能です）
.EXAMPLE
  pwsh -File scripts/setup-github-ssh.ps1 -PrepareOnly
  pwsh -File scripts/setup-github-ssh.ps1 -Repo "ajichama/CitrusBrowser" -Push
#>

param(
    [string]$Repo = "",
    [switch]$PrepareOnly,
    [switch]$Push,
    [switch]$UseGameKey
)

$ErrorActionPreference = "Stop"
Set-Location -Path (Split-Path -Parent $PSScriptRoot)

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Android Browser Project - GitHub SSH Setup" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. SSH ディレクトリと専用鍵の準備
$SshDir = Join-Path $HOME ".ssh"
if (-not (Test-Path $SshDir)) {
    New-Item -ItemType Directory -Path $SshDir -Force | Out-Null
}

if ($UseGameKey) {
    $KeyName = "id_ed25519_steam_game_studio"
    $HostAlias = "github-game-studio"
} else {
    $KeyName = "id_ed25519_android_browser"
    $HostAlias = "github-android-browser"
}

$KeyPath = Join-Path $SshDir $KeyName
$PubKeyPath = "$KeyPath.pub"

if (-not (Test-Path $KeyPath)) {
    Write-Host "[1/4] リポジトリ専用の SSH キー ($KeyName) を生成しています..." -ForegroundColor Yellow
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "ssh-keygen"
    $psi.Arguments = "-t ed25519 -C `"android-browser-deploy-key`" -f `"$KeyPath`" -N `"`""
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) {
        throw "ssh-keygen failed with exit code $($proc.ExitCode)"
    }
    Write-Host "  -> 生成完了: $KeyPath" -ForegroundColor Green
} else {
    Write-Host "[1/4] 既存の専用 SSH キーを使用します: $KeyPath" -ForegroundColor Green
}

# 2. ~/.ssh/config に専用 Host エイリアスを登録し、GitHub公式 Ed25519 ホスト鍵を known_hosts に確認・登録
$SshConfigPath = Join-Path $SshDir "config"
$KeyPathForSsh = $KeyPath -replace "\\", "/"

$ConfigExists = $false
if (Test-Path $SshConfigPath) {
    $CurrentConfig = Get-Content $SshConfigPath -Raw -ErrorAction SilentlyContinue
    if ($CurrentConfig -match "(?m)^Host\s+$HostAlias\b") {
        $ConfigExists = $true
    }
}

if (-not $ConfigExists) {
    Write-Host "[2/4] ~/.ssh/config に専用エイリアス ($HostAlias) を追記しています..." -ForegroundColor Yellow
    $Snippet = @"

# Android Browser Project Dedicated Deploy Key
Host $HostAlias
    HostName github.com
    User git
    IdentityFile "$KeyPathForSsh"
    IdentitiesOnly yes
"@
    Add-Content -Path $SshConfigPath -Value $Snippet -Encoding UTF8
    Write-Host "  -> 追記完了" -ForegroundColor Green
} else {
    Write-Host "[2/4] ~/.ssh/config の専用エイリアス ($HostAlias) は設定済みです" -ForegroundColor Green
}

$KnownHostsPath = Join-Path $SshDir "known_hosts"
$OfficialGitHubEd25519 = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"
$KnownHostsContent = if (Test-Path $KnownHostsPath) { Get-Content $KnownHostsPath -Raw -ErrorAction SilentlyContinue } else { "" }
if ($KnownHostsContent -notmatch [regex]::Escape("AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl")) {
    Add-Content -Path $KnownHostsPath -Value "`n$OfficialGitHubEd25519" -Encoding ASCII
    Write-Host "  -> ~/.ssh/known_hosts に GitHub 公式 Ed25519 ホスト鍵を登録しました" -ForegroundColor Green
} else {
    Write-Host "  -> ~/.ssh/known_hosts の GitHub 公式ホスト鍵を確認済み" -ForegroundColor Green
}

# 3. 公開鍵をクリップボードへコピーし、ローカル Git 基本設定を実施
$PubKey = (Get-Content $PubKeyPath -Raw).Trim()
try {
    Set-Clipboard -Value $PubKey
    $CopiedMsg = "クリップボードにもコピー済み"
} catch {
    $CopiedMsg = "下の文字列をコピーしてください"
}

if (Test-Path ".git") {
    git config user.name "Android Browser Studio"
    git config user.email "studio@local.dev"
}

Write-Host ""
Write-Host "----------------------------------------------------------" -ForegroundColor Magenta
Write-Host "📋 公開鍵（Deploy Key / $CopiedMsg）:" -ForegroundColor Magenta
Write-Host "----------------------------------------------------------" -ForegroundColor Magenta
Write-Host $PubKey -ForegroundColor White
Write-Host "----------------------------------------------------------" -ForegroundColor Magenta

if ($PrepareOnly) {
    Write-Host ""
    Write-Host "[PrepareOnly] ローカルの SSH 鍵・config・known_hosts の準備まで完了しました。" -ForegroundColor Green
    exit 0
}

# 4. リポジトリURLの設定と初回Push
if ([string]::IsNullOrWhiteSpace($Repo)) {
    Write-Host ""
    Write-Host "【GitHub側の設定手順（1分）】" -ForegroundColor Cyan
    Write-Host "  1. GitHub で新規リポジトリを作成します（README等は空のままでOK）"
    Write-Host "  2. そのリポジトリの [Settings] -> [Deploy keys] -> [Add deploy key] を開きます"
    Write-Host "  3. Title に 'Antigravity Android Studio' 等を入力し、上の公開鍵を貼り付けます"
    Write-Host "  4. ⚠️ 必ず [Allow write access]（書き込み許可）にチェックを入れて [Add key] を押します"
    Write-Host ""
    $Repo = Read-Host "作成した GitHub リポジトリの URL または 'ユーザー名/リポジトリ名' を入力してください"
}

if ($Repo -match "(?i)(?:github\.com[:/]|^)([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?$") {
    $OwnerRepo = $Matches[1]
} else {
    throw "リポジトリ名の形式を認識できませんでした（例: https://github.com/ajichama/my-browser または ajichama/my-browser）: $Repo"
}

$RemoteUrl = "git@${HostAlias}:${OwnerRepo}.git"

Write-Host ""
Write-Host "[3/4] origin リモートを登録しています..." -ForegroundColor Yellow
$ExistingRemotes = git remote
if ($ExistingRemotes -contains "origin") {
    git remote set-url origin $RemoteUrl
} else {
    git remote add origin $RemoteUrl
}
Write-Host "  -> origin = $RemoteUrl" -ForegroundColor Green

$DoPush = $Push.IsPresent
if (-not $DoPush) {
    Write-Host ""
    $Confirm = Read-Host "[4/4] GitHub側への Deploy Key 登録（Allow write access チェック済み）は完了しましたか？ 今すぐ初回 Push しますか？ (Y/n)"
    $DoPush = ($Confirm -eq "" -or $Confirm -match "^[Yy]")
}

if ($DoPush) {
    Write-Host "[4/4] GitHub へ初回 Push を実行しています..." -ForegroundColor Yellow
    $CurrentBranch = (git branch --show-current).Trim()
    if ([string]::IsNullOrWhiteSpace($CurrentBranch)) {
        $CurrentBranch = "main"
    }
    git push -u origin $CurrentBranch
    Write-Host ""
    Write-Host "🎉 GitHub 連携＆初回 Push が完了しました！ https://github.com/$OwnerRepo" -ForegroundColor Green
} else {
    Write-Host "準備完了です！Deploy Key 登録後に 'git push -u origin main' を実行してください。" -ForegroundColor Cyan
}
