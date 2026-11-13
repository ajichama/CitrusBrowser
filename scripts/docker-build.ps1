<#
.SYNOPSIS
  Docker コンテナ内で Android ビルド・単体テストを実行し、完成した APK のみを release/ に出力します。
.DESCRIPTION
  Windows ホスト環境を一切汚さず、さらに中間ビルドファイル (build/) や Gradle キャッシュ (.gradle) を
  すべて Docker Volume 側 (Linux ext4) に隔離することで、Dropbox の同期ロックや速度低下を完全に防ぎます。
.EXAMPLE
  pwsh -File scripts/docker-build.ps1
  pwsh -File scripts/docker-build.ps1 -Task "testDebugUnitTest"
  pwsh -File scripts/docker-build.ps1 -JavaVersion 11
#>

param(
    [string]$Task = "assembleDebug",
    [ValidateSet(11, 17)]
    [int]$JavaVersion = 11,
    [switch]$RebuildImage
)

$ErrorActionPreference = "Stop"
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $WorkspaceRoot

$ImageName = "yuzu-android-builder:latest"

# 1. Docker イメージの確認・ビルド
$ImageExists = docker images -q $ImageName
if (-not $ImageExists -or $RebuildImage) {
    Write-Host "[1/3] Android ビルド用 Docker イメージ ($ImageName) を構築しています..." -ForegroundColor Cyan
    docker build -t $ImageName -f docker/Dockerfile docker
    if ($LASTEXITCODE -ne 0) {
        throw "Docker image build failed."
    }
} else {
    Write-Host "[1/3] Docker イメージ ($ImageName) は準備済みです。" -ForegroundColor Green
}

# 2. release 出力先フォルダの準備と Dropbox Ignore 適用
$ReleaseDir = Join-Path $WorkspaceRoot "release"
if (-not (Test-Path $ReleaseDir)) {
    New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null
}
pwsh -File scripts/setup-dropbox-ignore.ps1

# 3. コンテナ内で Gradle タスクを実行（中間生成物は Docker Volume へ隔離）
$JavaHome = if ($JavaVersion -eq 11) { "/usr/lib/jvm/java-11-openjdk-amd64" } else { "/usr/lib/jvm/java-17-openjdk-amd64" }

Write-Host "[2/3] Docker コンテナ内で Gradle タスク ($Task, Java $JavaVersion) を実行しています..." -ForegroundColor Cyan

$ContainerCmd = @"
export JAVA_HOME=$JavaHome
export PATH=`$JAVA_HOME/bin:`$PATH
git config --global --add safe.directory /workspace
chmod +x ./gradlew
./gradlew $Task --init-script /workspace/docker/init.gradle --no-daemon --stacktrace
if [ -d /build-cache/app/outputs/apk ]; then
    mkdir -p /workspace/release
    find /build-cache/app/outputs/apk -name "*.apk" -exec cp -f {} /workspace/release/ \;
    echo "Copied APK(s) to release/:"
    ls -lh /workspace/release/*.apk
fi
"@ -replace "`r`n", "`n"

docker run --rm `
    -v "${WorkspaceRoot}:/workspace" `
    -v "yuzu-gradle-cache:/root/.gradle" `
    -v "yuzu-build-cache:/build-cache" `
    $ImageName `
    bash -c $ContainerCmd

if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed inside Docker container."
}

Write-Host "[3/3] ビルドが完了しました！出力先: $ReleaseDir" -ForegroundColor Green
