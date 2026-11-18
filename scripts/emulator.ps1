<#
.SYNOPSIS
  WSL Ubuntu 内の独立 Docker Engine で、KVM を使うヘッドレス Android エミュレーターを操作します。
.DESCRIPTION
  Docker Desktop のコンテナからは /dev/kvm が見えないため、WSL Ubuntu 内に専用 dockerd
  （別ソケット /run/citrus-emu/docker.sock・別 data-root /var/lib/citrus-emu-docker）を起動し、
  その上でエミュレーターコンテナ citrus-emulator を動かします。Docker Desktop の daemon・設定・
  既存コンテナ・Named Volume には書き込みません（-BuildCacheApk の読み取り専用参照だけを行います）。
  AVD・スナップショット・adb 鍵は専用エンジン側の Named Volume citrus-emu-home に置き、Dropbox 配下に生成物を作りません。
.EXAMPLE
  pwsh -File scripts/emulator.ps1 Start
  pwsh -File scripts/emulator.ps1 Install -Apk release/app-debug.apk
  pwsh -File scripts/emulator.ps1 Install -BuildCacheApk app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  pwsh -File scripts/emulator.ps1 Instrument -Runner io.github.ajichama.citrusbrowser.test/androidx.test.runner.AndroidJUnitRunner -TestClass foo.BarTest
  pwsh -File scripts/emulator.ps1 Adb -AdbArgs shell,getprop,ro.build.version.sdk
  pwsh -File scripts/emulator.ps1 Stop
#>

param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('Setup', 'Build', 'Start', 'Stop', 'Status', 'Install', 'Instrument', 'Adb')]
    [string]$Action,

    # api37: Android 17 (API 37.0) Google APIs・4KB ページ（既定）
    # api37-16k: 最新安定版 API 37.2 Google APIs・16KB ページ
    # api36: 比較・回避用の安定版 API 36 Google APIs・4KB ページ
    [ValidateSet('api37', 'api37-16k', 'api36')]
    [string]$Profile = 'api37',

    [switch]$ColdBoot,
    [switch]$Wipe,
    [switch]$RebuildImage,
    [switch]$KeepEngine,
    [switch]$KeepAnimations,
    [ValidateRange(60, 1800)]
    [int]$BootTimeoutSec = 600,
    [ValidateRange(2048, 16384)]
    [int]$MemoryMb = 4096,
    [ValidateRange(1, 16)]
    [int]$Cores = 4,

    # Install: Windows 側のファイル（例: release/app-debug.apk）
    [string]$Apk,
    # Install: Docker Desktop の Named Volume citrus-build-cache 内の相対パス（読み取り専用で取り出す）
    [string]$BuildCacheApk,
    [switch]$GrantPermissions,

    # Instrument
    [string]$Runner,
    [string]$TestClass,
    [hashtable]$InstrumentArgs = @{},
    [switch]$RawOutput,

    # Adb: エミュレーターを対象に adb へ渡す引数
    [string[]]$AdbArgs
)

$ErrorActionPreference = 'Stop'
$WorkspaceRoot = Split-Path -Parent $PSScriptRoot

$Distro = 'Ubuntu'
$EngineScriptSource = Join-Path $WorkspaceRoot 'docker/emulator/wsl-engine.sh'
$EngineScript = '/opt/citrus-emu/wsl-engine.sh'
$DockerBin = '/opt/citrus-emu/docker/docker'
$DockerSocket = 'unix:///run/citrus-emu/docker.sock'
$ImageName = 'citrus-android-emulator:latest'
$ContainerName = 'citrus-emulator'
$HomeVolume = 'citrus-emu-home'
$EmuPort = 5570
$Serial = "emulator-$EmuPort"
$BuilderImage = 'citrus-android-builder:latest'

$Profiles = @{
    'api37'     = @{ Avd = 'citrus-api37'; Image = 'system-images;android-37.0;google_apis;x86_64' }
    'api37-16k' = @{ Avd = 'citrus-api37-2-16k'; Image = 'system-images;android-37.2;google_apis_ps16k;x86_64' }
    'api36'     = @{ Avd = 'citrus-api36'; Image = 'system-images;android-36;google_apis;x86_64' }
}

function ConvertTo-BashLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function ConvertTo-WslPath([string]$WindowsPath) {
    $full = [System.IO.Path]::GetFullPath($WindowsPath, (Get-Location).Path)
    if ($full -notmatch '\A([A-Za-z]):\\(.*)\z') {
        throw "ドライブ文字付きのローカルパスを指定してください: $full"
    }
    return '/mnt/' + $Matches[1].ToLowerInvariant() + '/' + ($Matches[2] -replace '\\', '/')
}

# bash スクリプトを base64 で渡し、wsl.exe の引数クォートや CRLF の影響を受けないようにする。
function Invoke-WslRoot([string]$Script, [switch]$AllowFailure) {
    $prelude = "set -euo pipefail`nexport PATH=/opt/citrus-emu/docker:`$PATH DOCKER_HOST=$DockerSocket`n"
    $text = ($prelude + $Script).Replace("`r`n", "`n")
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $encoded = [Convert]::ToBase64String($bytes)
    & wsl.exe -d $Distro -u root -- bash -c "echo $encoded | base64 -d | bash"
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "WSL 内の処理が失敗しました (exit $LASTEXITCODE)。"
    }
}

function Sync-EngineScript([switch]$ScriptOnly) {
    $wslSource = ConvertTo-WslPath $EngineScriptSource
    $install = if ($ScriptOnly) { '' } else {
        "test -e /dev/kvm || { echo '/dev/kvm がありません。' >&2; exit 1; }`n$EngineScript install"
    }
    Invoke-WslRoot @"
mkdir -p /opt/citrus-emu
sed 's/\r`$//' $(ConvertTo-BashLiteral $wslSource) > /opt/citrus-emu/wsl-engine.sh.new
install -m 755 /opt/citrus-emu/wsl-engine.sh.new $EngineScript
rm -f /opt/citrus-emu/wsl-engine.sh.new
$install
"@
}

function Test-EngineRunning {
    $statusOutput = Invoke-WslRoot "$EngineScript status" -AllowFailure
    if ($LASTEXITCODE -eq 2) {
        throw '専用 dockerd の PID を本人確認できません。既存プロセスへの操作を中止します。'
    }
    $out = $statusOutput | Out-String
    return $out -match 'running=yes'
}

function Start-Engine {
    Sync-EngineScript
    if (Test-EngineRunning) { return }
    Write-Host '[engine] WSL Ubuntu 内の専用 dockerd を起動します（非表示の wsl.exe が生存中だけ動作）。' -ForegroundColor Cyan
    Start-Process -FilePath 'wsl.exe' -ArgumentList @('-d', $Distro, '-u', 'root', '--', 'bash', $EngineScript, 'run') -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        Invoke-WslRoot 'docker info > /dev/null 2>&1' -AllowFailure
        if ($LASTEXITCODE -eq 0) {
            Write-Host '[engine] 起動を確認しました。' -ForegroundColor Green
            return
        }
    }
    throw '専用 dockerd が 60 秒以内に応答しません。WSL 内 /var/log/citrus-emu-dockerd.log を確認してください。'
}

function Build-Image([switch]$Force) {
    $wslContext = ConvertTo-WslPath (Join-Path $WorkspaceRoot 'docker/emulator')
    $check = if ($Force) { 'false' } else { "docker image inspect $ImageName > /dev/null 2>&1" }
    Invoke-WslRoot @"
if $check; then
  echo '[image] $ImageName は準備済みです。'
else
  echo '[image] $ImageName を構築します（system image の取得に数分かかります）。'
  # bridge 無効のエンジンなので RUN は host ネットワークで実行する
  DOCKER_BUILDKIT=0 docker build --network host -t $ImageName $(ConvertTo-BashLiteral $wslContext)
fi
"@
}

function Get-ContainerState {
    $out = Invoke-WslRoot "docker inspect -f '{{.State.Status}}' $ContainerName 2>/dev/null || echo missing" -AllowFailure | Out-String
    return $out.Trim()
}

function Assert-EmulatorRunning {
    if (-not (Test-EngineRunning) -or (Get-ContainerState) -ne 'running') {
        throw 'エミュレーターが起動していません。先に Start を実行してください。'
    }
}

function Assert-ContainerProfile([hashtable]$ExpectedProfile) {
    $environment = (Invoke-WslRoot "docker inspect -f '{{json .Config.Env}}' $ContainerName" | Out-String) | ConvertFrom-Json
    if ($environment -notcontains "AVD_NAME=$($ExpectedProfile.Avd)" -or
        $environment -notcontains "SYSTEM_IMAGE=$($ExpectedProfile.Image)") {
        throw "起動済みの $ContainerName は要求した Profile ($Profile) と一致しません。Stop 後に指定した Profile で Start してください。"
    }
}

function Invoke-EmuAdb([string[]]$Arguments, [switch]$AllowFailure) {
    $quoted = ($Arguments | ForEach-Object { ConvertTo-BashLiteral $_ }) -join ' '
    Invoke-WslRoot "docker exec $ContainerName adb -s $Serial $quoted" -AllowFailure:$AllowFailure
}

function Wait-Boot {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $BootTimeoutSec) {
        if ((Get-ContainerState) -ne 'running') {
            Invoke-WslRoot "docker logs --tail 40 $ContainerName" -AllowFailure
            throw 'エミュレーターコンテナが起動途中で終了しました。'
        }
        $booted = (Invoke-WslRoot "docker exec $ContainerName adb -s $Serial shell getprop sys.boot_completed 2>/dev/null || true" -AllowFailure | Out-String).Trim()
        if ($booted -eq '1') {
            # sys.boot_completed だけでは system サービスの安定を保証しない。SurfaceFlinger が
            # 一定時間再起動せず、既知の描画 assertion が出ていないことも確認する。
            $sfBefore = (Invoke-WslRoot "docker exec $ContainerName adb -s $Serial shell pidof surfaceflinger 2>/dev/null || true" -AllowFailure | Out-String).Trim()
            Start-Sleep -Seconds 45
            $sfAfter = (Invoke-WslRoot "docker exec $ContainerName adb -s $Serial shell pidof surfaceflinger 2>/dev/null || true" -AllowFailure | Out-String).Trim()
            $fatal = (Invoke-WslRoot "docker exec $ContainerName adb -s $Serial logcat -d 2>/dev/null | grep -c -E 'hasReadColorBufferDma|Fatal signal 6.*surfaceflinger' || true" -AllowFailure | Out-String).Trim()
            if (-not $sfBefore -or $sfBefore -ne $sfAfter -or ($fatal -match '^\d+$' -and [int]$fatal -gt 0)) {
                throw "起動表示後に SurfaceFlinger が安定しませんでした（PID: '$sfBefore' → '$sfAfter'、描画 assertion: $fatal 件）。"
            }
            return $sw.Elapsed.TotalSeconds
        }
        Start-Sleep -Seconds 3
    }
    Invoke-WslRoot "docker logs --tail 40 $ContainerName" -AllowFailure
    throw "起動完了 (sys.boot_completed=1) を $BootTimeoutSec 秒以内に確認できませんでした。"
}

function Copy-ApkIntoContainer([string]$Destination) {
    if ($Apk -and $BuildCacheApk) { throw '-Apk と -BuildCacheApk はどちらか一方を指定してください。' }
    if ($Apk) {
        $full = [System.IO.Path]::GetFullPath($Apk, (Get-Location).Path)
        if (-not (Test-Path -LiteralPath $full -PathType Leaf) -or $full -notmatch '\.apk\z') {
            throw "APK ファイルが見つかりません: $full"
        }
        $wslApk = ConvertTo-WslPath $full
        Write-Host "[install] $full をコンテナへ転送します。" -ForegroundColor Cyan
        Invoke-WslRoot "docker exec -i $ContainerName sh -c 'cat > $Destination' < $(ConvertTo-BashLiteral $wslApk)"
        return
    }
    if ($BuildCacheApk) {
        if ($BuildCacheApk -notmatch '\A[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)*\.apk\z' -or $BuildCacheApk -match '(?:\A|/)\.\.(?:/|\z)') {
            throw "-BuildCacheApk は citrus-build-cache 内の相対パスで指定してください（例: app/outputs/apk/debug/app-debug.apk）。"
        }
        Write-Host "[install] citrus-build-cache:/$BuildCacheApk を読み取り専用で取り出して転送します。" -ForegroundColor Cyan
        # WSL の root からは Windows の docker.exe を実行できない（exit 126）ため、取り出しは Windows 側で行う。
        # Docker Desktop の --rm 一時コンテナで volume を読み取り専用にし、Dropbox 外の一時フォルダだけへ書く。
        $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("citrus-emu-apk-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $tempDir | Out-Null
        try {
            $name = [System.IO.Path]::GetFileName($BuildCacheApk)
            & docker run --rm --network none -v citrus-build-cache:/build-cache:ro -v "${tempDir}:/out" --entrypoint cp $BuilderImage "/build-cache/$BuildCacheApk" "/out/$name"
            if ($LASTEXITCODE -ne 0) { throw "citrus-build-cache から $BuildCacheApk を取り出せませんでした。" }
            $localApk = Join-Path $tempDir $name
            $srcSum = (Get-FileHash -Algorithm SHA256 -LiteralPath $localApk).Hash.ToLowerInvariant()
            $wslApk = ConvertTo-WslPath $localApk
            Invoke-WslRoot @"
docker exec -i $ContainerName sh -c 'cat > $Destination' < $(ConvertTo-BashLiteral $wslApk)
dst_sum=`$(docker exec $ContainerName sha256sum $Destination | cut -d' ' -f1)
if [ "`$dst_sum" != "$srcSum" ]; then echo '[install] 転送後の SHA-256 が一致しません。' >&2; exit 1; fi
echo "[install] SHA-256 一致: `$dst_sum"
"@
        } finally {
            Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
        return
    }
    throw 'Install には -Apk または -BuildCacheApk が必要です。'
}

switch ($Action) {
    'Setup' {
        Sync-EngineScript
        Start-Engine
        Build-Image -Force:$RebuildImage
        Write-Host '[setup] 完了しました。Start でエミュレーターを起動できます。' -ForegroundColor Green
    }
    'Build' {
        Start-Engine
        Build-Image -Force
    }
    'Start' {
        Start-Engine
        Build-Image -Force:$RebuildImage
        $state = Get-ContainerState
        $p = $Profiles[$Profile]
        if ($state -eq 'running') {
            Assert-ContainerProfile $p
            Write-Host "[start] $ContainerName は起動済みです。" -ForegroundColor Green
        } else {
            if ($state -ne 'missing') {
                Invoke-WslRoot "docker rm -f $ContainerName > /dev/null"
            }
            $boot = if ($ColdBoot) { 'cold' } else { 'quick' }
            $wipeValue = if ($Wipe) { '1' } else { '0' }
            Write-Host "[start] $Profile ($($p.Image)) を起動します。" -ForegroundColor Cyan
            Invoke-WslRoot @"
docker run -d --name $ContainerName --init --device /dev/kvm --network host \
  -v ${HomeVolume}:/root/.android \
  -e AVD_NAME=$($p.Avd) -e $(ConvertTo-BashLiteral "SYSTEM_IMAGE=$($p.Image)") \
  -e EMU_PORT=$EmuPort -e EMU_MEMORY=$MemoryMb -e EMU_CORES=$Cores -e EMU_BOOT=$boot -e EMU_WIPE=$wipeValue \
  $ImageName > /dev/null
"@
        }
        $elapsed = Wait-Boot
        if (-not $KeepAnimations) {
            foreach ($key in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
                Invoke-EmuAdb @('shell', 'settings', 'put', 'global', $key, '0')
            }
        }
        $sdk = (Invoke-WslRoot "docker exec $ContainerName adb -s $Serial shell getprop ro.build.version.sdk" | Out-String).Trim()
        Write-Host ("[start] 起動完了: {0:N1} 秒, {1}, API {2}" -f $elapsed, $Serial, $sdk) -ForegroundColor Green
    }
    'Stop' {
        # 既に起動中の場合も、本人確認を行う最新版の補助スクリプトへ置き換える。
        Sync-EngineScript -ScriptOnly
        if (Test-EngineRunning) {
            $state = Get-ContainerState
            if ($state -eq 'running') {
                Write-Host '[stop] エミュレーターを終了します（quick boot 用スナップショットを保存）。' -ForegroundColor Cyan
                Invoke-WslRoot @"
docker exec $ContainerName adb -s $Serial emu kill > /dev/null 2>&1 || true
for i in `$(seq 1 60); do
  [ "`$(docker inspect -f '{{.State.Running}}' $ContainerName)" = false ] && break
  sleep 2
done
docker stop -t 20 $ContainerName > /dev/null 2>&1 || true
"@
            }
            if ($state -ne 'missing') {
                Invoke-WslRoot "docker rm -f $ContainerName > /dev/null"
            }
            if (-not $KeepEngine) {
                Invoke-WslRoot "$EngineScript stop"
            }
        }
        Write-Host '[stop] 完了しました（イメージと Named Volume は保持）。' -ForegroundColor Green
    }
    'Status' {
        Invoke-WslRoot "test -x $EngineScript && $EngineScript status || echo 'installed=no'" -AllowFailure
        if (Test-EngineRunning) {
            Invoke-WslRoot @"
docker image ls --format 'image={{.Repository}}:{{.Tag}} size={{.Size}}' $ImageName
echo "container=`$(docker inspect -f '{{.State.Status}}' $ContainerName 2>/dev/null || echo missing)"
if [ "`$(docker inspect -f '{{.State.Running}}' $ContainerName 2>/dev/null)" = true ]; then
  docker exec $ContainerName adb devices | sed -n '2,`$p'
  echo "sdk=`$(docker exec $ContainerName adb -s $Serial shell getprop ro.build.version.sdk 2>/dev/null)"
  echo "boot_completed=`$(docker exec $ContainerName adb -s $Serial shell getprop sys.boot_completed 2>/dev/null)"
fi
"@ -AllowFailure
        }
    }
    'Install' {
        Assert-EmulatorRunning
        $dest = '/tmp/citrus-install.apk'
        Copy-ApkIntoContainer $dest
        $installArgs = @('install', '-r', '-t')
        if ($GrantPermissions) { $installArgs += '-g' }
        try {
            Invoke-EmuAdb ($installArgs + $dest)
        } finally {
            Invoke-WslRoot "docker exec $ContainerName rm -f $dest" -AllowFailure
        }
    }
    'Instrument' {
        Assert-EmulatorRunning
        if ($Runner -notmatch '\A[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+\z') {
            throw '-Runner は <テストパッケージ>/<Runner クラス> 形式で指定してください。'
        }
        $amArgs = @('shell', 'am', 'instrument', '-w')
        if ($RawOutput) { $amArgs += '-r' }
        if ($TestClass) { $amArgs += @('-e', 'class', $TestClass) }
        foreach ($key in $InstrumentArgs.Keys) {
            $amArgs += @('-e', [string]$key, [string]$InstrumentArgs[$key])
        }
        $amArgs += $Runner
        $output = Invoke-EmuAdb $amArgs -AllowFailure | Out-String
        Write-Output $output
        $ok = if ($RawOutput) {
            $output -match 'INSTRUMENTATION_CODE: -1' -and $output -notmatch 'INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_FAILED|Process crashed'
        } else {
            $output -match '(?m)^OK \(\d+ tests?\)' -and $output -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
        }
        if (-not $ok) { throw '計測テストが成功しませんでした（出力を確認してください）。' }
    }
    'Adb' {
        Assert-EmulatorRunning
        if (-not $AdbArgs) { throw '-AdbArgs を指定してください。' }
        Invoke-EmuAdb $AdbArgs
    }
}
