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
    [ValidatePattern('\A:?[A-Za-z0-9_][A-Za-z0-9_:-]*(?:[ \t]+:?[A-Za-z0-9_][A-Za-z0-9_:-]*)*\z')]
    [string]$Task = "assembleDebug",
    [ValidateSet(11, 17, 21)]
    [int]$JavaVersion = 21,
    [switch]$RebuildImage
)

$ErrorActionPreference = "Stop"
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -Path $WorkspaceRoot

$ImageName = "citrus-android-builder:latest"

# APK の名前は生成物を取り出す段階で付け、廃止された AGP Variant API に依存しない。
$VersionDefinitions = [System.IO.File]::ReadAllText((Join-Path $WorkspaceRoot 'buildSrc/src/main/java/Dependencies.kt'))
$AppBuildDefinition = [System.IO.File]::ReadAllText((Join-Path $WorkspaceRoot 'app/build.gradle'))
$VersionName = [regex]::Match($VersionDefinitions, 'const val version_name\s*=\s*"([^"]+)"').Groups[1].Value
$VersionCode = [regex]::Match($VersionDefinitions, 'const val version_code\s*=\s*([0-9]+)').Groups[1].Value
$ApplicationId = [regex]::Match($AppBuildDefinition, '(?m)^\s*applicationId\s+"([^"]+)"').Groups[1].Value
if ($VersionName -notmatch '\A[A-Za-z0-9._+-]+\z' -or $VersionCode -notmatch '\A[0-9]+\z' -or
    $ApplicationId -notmatch '\A[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\z') {
    throw "APK 出力名に使うアプリ識別子またはバージョンを読み取れません。"
}
$ReleaseApkName = "${ApplicationId}_v${VersionName}_${VersionCode}.apk"

# 0. Docker デーモンの起動確認とバックグラウンド起動
$dockerInfoOut = docker info 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    if ($dockerInfoOut -match "Access is denied" -or $dockerInfoOut -match "permission denied") {
        throw "サンドボックス制限により Docker Engine パイプへ接続できません。ホスト権限で再実行してください。"
    }
    $DockerDesktopExe = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $DockerDesktopExe) {
        Write-Host "[0/3] Docker Engine が停止しているため Docker Desktop を起動しています..." -ForegroundColor Yellow
        Start-Process -FilePath $DockerDesktopExe -WindowStyle Hidden
        $MaxWaitSec = 90
        $Elapsed = 0
        while ($Elapsed -lt $MaxWaitSec) {
            Start-Sleep -Seconds 3
            $Elapsed += 3
            docker info *> $null
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  -> Docker Engine の起動を確認しました (${Elapsed}秒)" -ForegroundColor Green
                break
            }
            Write-Host "  -> Docker Engine 起動待機中... (${Elapsed}/${MaxWaitSec}秒)" -ForegroundColor DarkGray
        }
        docker info *> $null
        if ($LASTEXITCODE -ne 0) {
            throw "Docker Engine が時間内に起動しませんでした。Docker Desktop の状態を確認してください。"
        }
    } else {
        throw "Docker Engine が起動しておらず、Docker Desktop も見つかりません。"
    }
}

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
if ($LASTEXITCODE -ne 0) {
    throw "Dropbox 同期除外の設定に失敗しました。"
}

# 3. コンテナ内で Gradle タスクを実行（中間生成物は Docker Volume へ隔離）
$JavaHome = switch ($JavaVersion) {
    11 { "/usr/lib/jvm/java-11-openjdk-amd64" }
    17 { "/usr/lib/jvm/java-17-openjdk-amd64" }
    21 { "/usr/lib/jvm/java-21-openjdk-amd64" }
}

Write-Host "[2/3] Docker コンテナ内で Gradle タスク ($Task, Java $JavaVersion) を実行しています..." -ForegroundColor Cyan

$TaskNames = $Task -split '[ \t]+'
# root/app の既知タスクから出力範囲の和集合を作り、別variantの古いキャッシュを取り出さない。
$ExportVariants = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($TaskName in $TaskNames) {
    # 独立プレビューは、この明示タスクだけを本体と別名で取り出す。
    if ($TaskName -cmatch '\A:?gecko-preview:assembleDebug\z') {
        [void]$ExportVariants.Add('geckoPreviewDebug')
        continue
    }
    if ($TaskName -cmatch '\A:?(?:app:)?(?<apkTask>[A-Za-z0-9_]+)\z') {
        switch -CaseSensitive -Regex ($Matches['apkTask']) {
            '\A(?:assemble|package)Debug\z' { [void]$ExportVariants.Add('debug'); break }
            '\A(?:assemble|package)Release\z' { [void]$ExportVariants.Add('release'); break }
            '\A(?:assembleDebugAndroidTest|packageDebugAndroidTest|assembleAndroidTest)\z' { [void]$ExportVariants.Add('androidTest'); break }
            '\A(?:assemble|build)\z' { [void]$ExportVariants.Add('debug'); [void]$ExportVariants.Add('release'); break }
        }
    }
}
$ExportVariantValue = (@('debug', 'release', 'androidTest', 'geckoPreviewDebug') | Where-Object { $ExportVariants.Contains($_) }) -join ','
$ExportApk = $ExportVariants.Count -gt 0
$GradleTaskArguments = @($TaskNames)
# versions-plugin の更新確認タスクだけ、公式要件の並列実行禁止を固定引数で付ける。
if (@($TaskNames | Where-Object { $_ -cmatch '(^|:)dependencyUpdates$' }).Count -gt 0) {
    $GradleTaskArguments += '--no-parallel'
}

# タスク名はシェル文字列へ埋め込まず、独立した引数として渡します。
# gradlew の改行は読み込み時だけ変換し、$0 を維持してラッパーの配置先を解決します。
# 構成失敗時の問題レポートが指定外へ出力されたため、HTML 生成だけ止めて診断はログへ残します。
$ContainerCmd = @'
set -euo pipefail
export PATH="$JAVA_HOME/bin:$PATH"
git config --global --add safe.directory /workspace
bash -c "$(sed 's/\r$//' ./gradlew)" ./gradlew "$@" --project-cache-dir /build-cache/.gradle-project-cache --init-script /workspace/docker/init.gradle --no-problems-report --warning-mode all --no-daemon --stacktrace
if [ -n "$CITRUS_EXPORT_VARIANTS" ]; then
    mkdir -p /workspace/release
    IFS=',' read -r -a export_variants <<< "$CITRUS_EXPORT_VARIANTS"
    for variant in "${export_variants[@]}"; do
        case "$variant" in
            debug|release) apk_dir="/build-cache/app/outputs/apk/$variant" ;;
            androidTest) apk_dir="/build-cache/app/outputs/apk/androidTest/debug" ;;
            geckoPreviewDebug) apk_dir="/build-cache/gecko-preview/outputs/apk/debug" ;;
            *) continue ;;
        esac
        if [ ! -d "$apk_dir" ]; then continue; fi
        find "$apk_dir" -maxdepth 1 -type f -name "*.apk" -print0 |
        while IFS= read -r -d '' apk; do
            filename=$(basename "$apk")
            if [ "$variant" = geckoPreviewDebug ] && [ "$filename" != gecko-preview-debug.apk ]; then continue; fi
            case "$apk" in
                */release/app-release.apk|*/release/app-release-unsigned.apk)
                    filename="$CITRUS_RELEASE_APK_NAME"
                    ;;
            esac
            cp -f -- "$apk" "/workspace/release/$filename"
            printf 'Copied APK: %s\n' "$filename"
        done
    done
fi
'@ -replace "`r`n", "`n"

docker run --rm `
    -v "${WorkspaceRoot}:/workspace" `
    -v "citrus-gradle-cache:/root/.gradle" `
    -v "citrus-android-home:/root/.android" `
    -v "citrus-build-cache:/build-cache" `
    -v "citrus-buildsrc-gradle:/workspace/buildSrc/.gradle" `
    -e "JAVA_HOME=$JavaHome" `
    -e "CITRUS_RELEASE_APK_NAME=$ReleaseApkName" `
    -e "CITRUS_EXPORT_VARIANTS=$ExportVariantValue" `
    $ImageName `
    bash -c $ContainerCmd -- @GradleTaskArguments

if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed inside Docker container."
}

if ($ExportApk) {
    Write-Host "[3/3] Gradle タスクが完了しました。APK 出力先: $ReleaseDir" -ForegroundColor Green
} else {
    Write-Host '[3/3] Gradle タスクが完了しました。APK 出力対象外のため、既存 APK はコピーしていません。' -ForegroundColor Green
}
