<#
.SYNOPSIS
  Docker・Git をモックし、ビルド／コミットの引数と公開範囲を検証します。
.DESCRIPTION
  実ビルド・コミット・Push・ホスト設定の変更は行いません。
#>
$ErrorActionPreference = 'Stop'
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
$DockerCalls = [System.Collections.Generic.List[object]]::new()
$GitCalls = [System.Collections.Generic.List[object]]::new()
$MockHead = 'a' * 40

function Assert-Condition([bool]$Condition, [string]$Description) {
    if (-not $Condition) {
        throw "検証失敗: $Description"
    }
}

function docker {
    begin {
        $callArgs = @($args)
        $inputLines = [System.Collections.Generic.List[string]]::new()
    }
    process {
        if ($null -ne $_) { $inputLines.Add([string]$_) }
    }
    end {
        $DockerCalls.Add(@{ Arguments = $callArgs; InputText = ($inputLines -join "`n") })
        $global:LASTEXITCODE = 0
        if ($callArgs[0] -eq 'images') { 'mock-image' }
    }
}

function git {
    $callArgs = @($args)
    $GitCalls.Add($callArgs)
    $global:LASTEXITCODE = 0
    if ($callArgs -contains '--show-current') { 'topic/workflow' }
    if ($callArgs -contains 'rev-parse') { $MockHead }
    if ($callArgs -contains 'ls-remote') { "$MockHead`trefs/heads/topic/workflow" }
}

function pwsh {
    # Dropbox 設定スクリプトを実行せず、成功応答だけを返します。
    $global:LASTEXITCODE = 0
}

function Test-Path {
    param([string]$Path)
    if ($Path -eq (Join-Path $WorkspaceRoot 'release')) { return $true }
    Microsoft.PowerShell.Management\Test-Path -Path $Path
}

$BuildScript = Join-Path $PSScriptRoot 'docker-build.ps1'
$CommitScript = Join-Path $PSScriptRoot 'git-commit-push.ps1'
foreach ($path in @($BuildScript, $CommitScript)) {
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$parseErrors)
    Assert-Condition ($parseErrors.Count -eq 0) "PowerShell 構文: $path"
}

$WrapperHash = (Get-FileHash (Join-Path $WorkspaceRoot 'gradlew')).Hash
& $BuildScript -Task 'assembleDebug :module:adblock:testDebugUnitTest'
$buildRun = @($DockerCalls | Where-Object { $_.Arguments[0] -eq 'run' })[-1]
$buildArgs = $buildRun.Arguments
Assert-Condition ($buildArgs[-2] -eq 'assembleDebug') '最初のタスクが独立引数であること'
Assert-Condition ($buildArgs[-1] -eq ':module:adblock:testDebugUnitTest') 'モジュール指定タスクが独立引数であること'
Assert-Condition ($buildArgs -contains 'citrus-android-home:/root/.android') 'デバッグ署名鍵と adb 鍵をコンテナ終了後も保持すること'
Assert-Condition ($buildArgs -contains 'CITRUS_EXPORT_APK=1') 'root の assemble では APK 出力を有効にすること'
Assert-Condition (@($buildArgs | Where-Object { $_ -match '^CITRUS_RELEASE_APK_NAME=[A-Za-z0-9._+-]+\.apk$' }).Count -eq 1) '公開用 APK 名を検証して環境変数で渡すこと'
$buildCommand = $buildArgs[[array]::IndexOf($buildArgs, '-c') + 1]
Assert-Condition ($buildCommand.Contains('"$@"')) 'タスクのシェル展開を避けること'
Assert-Condition ($buildCommand.Contains('--no-problems-report')) '問題レポートの HTML をホストへ生成しないこと'
Assert-Condition ($buildCommand.Contains('--warning-mode all')) '問題の診断をコンソールに保持すること'
Assert-Condition ($buildCommand.Contains('if [ "$CITRUS_EXPORT_APK" = 1 ] && [ -d /build-cache/app/outputs/apk ]; then')) 'APK コピー処理を固定の出力フラグで制御すること'
Assert-Condition (-not $buildCommand.Contains('sed -i')) 'gradlew を直接書き換えないこと'
Assert-Condition ($WrapperHash -eq (Get-FileHash (Join-Path $WorkspaceRoot 'gradlew')).Hash) 'gradlew の内容を保持すること'
Assert-Condition (-not ($buildArgs -contains '--no-parallel')) '通常タスクの並列設定を維持すること'

foreach ($nonApkTask in @('help testDebugUnitTest', ':module:adblock:assembleDebug', ':buildSrc:test', ':app:updateDebugOssNotices')) {
    & $BuildScript -Task $nonApkTask
    $nonApkArgs = @($DockerCalls | Where-Object { $_.Arguments[0] -eq 'run' })[-1].Arguments
    Assert-Condition ($nonApkArgs -contains 'CITRUS_EXPORT_APK=0') "APK を生成しないタスクでは既存成果物をコピーしないこと: $nonApkTask"
}

& $BuildScript -Task ':app:assembleDebug'
$appAssembleArgs = @($DockerCalls | Where-Object { $_.Arguments[0] -eq 'run' })[-1].Arguments
Assert-Condition ($appAssembleArgs -contains 'CITRUS_EXPORT_APK=1') 'app の assemble では APK 出力を有効にすること'

& $BuildScript -Task ':app:dependencyUpdates'
$updatesArgs = @($DockerCalls | Where-Object { $_.Arguments[0] -eq 'run' })[-1].Arguments
Assert-Condition ($updatesArgs[-2] -eq ':app:dependencyUpdates') '接頭付き更新確認タスクを独立引数で渡すこと'
Assert-Condition ($updatesArgs[-1] -eq '--no-parallel') '更新確認だけ固定の並列実行禁止オプションを追加すること'

foreach ($invalidTask in @('assembleDebug; touch marker', '$(touch marker)', 'assembleDebug --init-script bad', "assembleDebug`nhelp", '')) {
    $callCount = $DockerCalls.Count
    $rejected = $false
    try { & $BuildScript -Task $invalidTask } catch { $rejected = $true }
    Assert-Condition $rejected '不正なタスク入力を拒否すること'
    Assert-Condition ($DockerCalls.Count -eq $callCount) '入力拒否後に Docker を実行しないこと'
}

$literalMessage = 'fix: 日本語 $(touch marker) `touch marker` "引用" $HOME'
& $CommitScript -Message $literalMessage
$commitRun = @($DockerCalls | Where-Object { $_.Arguments[0] -eq 'run' })[-1]
$commitArgs = $commitRun.Arguments
$commitCommand = $commitArgs[[array]::IndexOf($commitArgs, '-c') + 1]
Assert-Condition ($commitRun.InputText -eq $literalMessage) 'コミット文を文字列のまま標準入力へ渡すこと'
Assert-Condition (-not $commitCommand.Contains($literalMessage)) 'コミット文をシェルプログラムへ埋め込まないこと'
Assert-Condition ($commitCommand.Contains('git commit --file=-')) 'Git が標準入力からコミット文を読むこと'
Assert-Condition (-not $commitCommand.Contains('git add')) 'ステージ範囲を変更しないこと'
Assert-Condition (@($GitCalls | Where-Object { $_ -contains 'push' }).Count -eq 0) '既定では Push しないこと'

& $CommitScript -Message $literalMessage -Push
$pushCalls = @($GitCalls | Where-Object { $_ -contains 'push' })
Assert-Condition ($pushCalls.Count -eq 1) '明示指定時だけ Push すること'
Assert-Condition ($pushCalls[0] -contains 'HEAD:refs/heads/topic/workflow') '現在のブランチを Push 先に使うこと'

Write-Host 'ビルド／コミット処理のモック検証に成功しました。実ビルド・公開操作は行っていません。' -ForegroundColor Green
