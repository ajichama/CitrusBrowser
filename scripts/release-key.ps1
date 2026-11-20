<#
.SYNOPSIS
  CitrusBrowser のリリース署名の鍵を、Docker の専用ボリューム（citrus-release-key）で作成・確認・バックアップします。

.DESCRIPTION
  鍵と設定（release.properties）はボリュームの中だけに置き、ソース・Dropbox・release/ には置きません。
  docker-build.ps1 は、Release を含むタスクのときだけ、このボリュームを /release-key に読み取り専用でつなぎます。

  Status  鍵の有無と、証明書の SHA-256 フィンガープリントを表示する（パスワードは表示しない）。
  New     鍵がなければ作る。既にあれば何もしない（作り直すと、配布済みのアプリを更新できなくなるため）。
  Backup  鍵と設定を、指定したフォルダへ複製する。作業フォルダ（Dropbox 配下）への複製は拒否する。

  鍵をなくすと、同じ鍵で署名した版へ更新できなくなります。Backup の複製は、パスワード管理ソフトや
  オフラインの記録媒体など、同期されない安全な場所へ移して保管してください。
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('Status', 'New', 'Backup')]
    [string]$Action,
    [string]$Destination = (Join-Path $env:USERPROFILE '.citrus\release-key-backup')
)

$ErrorActionPreference = 'Stop'
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
$ImageName = 'citrus-android-builder:latest'
$Volume = 'citrus-release-key'
$KeyTool = '/usr/lib/jvm/java-21-openjdk-amd64/bin/keytool'

function Invoke-KeyContainer([string]$Script, [string[]]$ExtraArguments = @()) {
    $arguments = @('run', '--rm', '-v', "${Volume}:/release-key") + $ExtraArguments + @(
        '-e', "KEYTOOL=$KeyTool", $ImageName, 'bash', '-c', ($Script -replace "`r`n", "`n"))
    docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "鍵の操作に失敗しました（$Action）。" }
}

switch ($Action) {
    'Status' {
        Invoke-KeyContainer @'
set -euo pipefail
if [ ! -f /release-key/release.properties ]; then echo "リリース署名の鍵はまだありません（New で作成します）。"; exit 0; fi
pass=$(sed -n 's/^storePassword=//p' /release-key/release.properties)
alias=$(sed -n 's/^keyAlias=//p' /release-key/release.properties)
file=$(sed -n 's/^storeFile=//p' /release-key/release.properties)
echo "鍵: /release-key/$file（別名 $alias）"
"$KEYTOOL" -list -v -keystore "/release-key/$file" -storepass "$pass" -alias "$alias" | grep -E 'SHA256:|Owner:|Valid from'
'@
    }
    'New' {
        Invoke-KeyContainer @'
set -euo pipefail
umask 077
if [ -f /release-key/release.properties ] || [ -f /release-key/citrus-release.p12 ]; then
    echo "鍵は既にあります。作り直しはしません（配布済みの版を更新できなくなるため）。"; exit 0
fi
pass=$(head -c 32 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-32)
"$KEYTOOL" -genkeypair -keystore /release-key/citrus-release.p12 -storetype PKCS12 \
    -alias citrus -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=CitrusBrowser" -storepass "$pass" -keypass "$pass" >/dev/null
printf 'storeFile=citrus-release.p12\nstorePassword=%s\nkeyAlias=citrus\nkeyPassword=%s\n' "$pass" "$pass" > /release-key/release.properties
echo "鍵を作成しました。必ず Backup で複製し、安全な場所に保管してください。"
"$KEYTOOL" -list -v -keystore /release-key/citrus-release.p12 -storepass "$pass" -alias citrus | grep -E 'SHA256:|Owner:|Valid from'
'@
    }
    'Backup' {
        $target = [System.IO.Path]::GetFullPath($Destination)
        $workspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)
        if ($target.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase) -or $target -match '(?i)\\Dropbox(\\|$)') {
            throw "作業フォルダや Dropbox の中には複製しません: $target"
        }
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        Invoke-KeyContainer @'
set -euo pipefail
if [ ! -f /release-key/release.properties ]; then echo "鍵がありません。" >&2; exit 1; fi
cp /release-key/release.properties /release-key/citrus-release.p12 /backup/
echo "複製しました。"
'@ @('-v', "${target}:/backup")
        Write-Host "複製先: $target" -ForegroundColor Green
        Write-Host 'release.properties にはパスワードが書かれています。同期されない安全な場所へ移して保管してください。' -ForegroundColor Yellow
    }
}
