# Docker エミュレーター検証環境

実機なしで計測テスト（`am instrument`）や広告ブロックの比較を行うための、ヘッドレス Android エミュレーター環境です。
操作は [`scripts/emulator.ps1`](../../scripts/emulator.ps1) から行います。

## 構成と理由

| 項目 | 内容 |
| --- | --- |
| 実行場所 | WSL2 の `Ubuntu` ディストリ内に置く**専用 dockerd**（静的バイナリ 29.10.0、SHA-256 固定） |
| ソケット / データ | `/run/citrus-emu/docker.sock` / `/var/lib/citrus-emu-docker`（Ubuntu の ext4 上。Dropbox 外） |
| バイナリ | `/opt/citrus-emu/docker/`、管理スクリプト `/opt/citrus-emu/wsl-engine.sh`、ログ `/var/log/citrus-emu-dockerd.log` |
| ネットワーク | `--bridge none --iptables=false`。コンテナは `--network host` のみ（共有ネットワーク名前空間のルールを変えない） |
| イメージ | `citrus-android-emulator:latest`（[Dockerfile](Dockerfile)） |
| コンテナ | `citrus-emulator`（`--device /dev/kvm --init --network host`） |
| 永続データ | 専用エンジン側の Named Volume `citrus-emu-home`（`/root/.android`：AVD・quick boot スナップショット・adb 鍵） |

- Docker Desktop のコンテナからは `/dev/kvm` が見えないため、KVM が使える WSL Ubuntu 側で動かします。
- Ubuntu には Docker Desktop の WSL 統合が入っていません。将来統合を有効にしても衝突しないよう、既定の
  `/var/run/docker.sock`・`/var/lib/docker`・systemd の `docker.service` は使いません。apt パッケージも入れていません。
- Docker Desktop の設定・daemon・既存コンテナ・Named Volume には書き込みません。例外は `Install -BuildCacheApk`
  で、`citrus-build-cache` を**読み取り専用**で mount した `--rm` の一時コンテナから APK を読み出します。
- WSL のディストリは利用中のセッションが無いと停止するため、`Start` は非表示の `wsl.exe` で dockerd を前面実行し、
  `Stop` で dockerd ごと終了します（`-KeepEngine` で dockerd だけ残せます）。
- イメージに鍵・個人情報は入れません。adb 鍵と AVD は実行時に `citrus-emu-home` へ作られます。

## system image（2026-11-19 に `sdkmanager --list` で実在確認）

| `-Profile` | パッケージ | 用途 |
| --- | --- | --- |
| `api37`（既定） | `system-images;android-37.0;google_apis;x86_64` | 本体 targetSdk 37 と同じ API・4KB ページ |
| `api37-16k` | `system-images;android-37.2;google_apis_ps16k;x86_64` | 最新安定版 37.2。x86_64 は 16KB ページ版のみ提供（起動は未確認） |
| `api36` | `system-images;android-36;google_apis;x86_64` | 比較・回避用の安定版 |

エミュレーター本体は `emulator` パッケージの最新版（構築時点 37.2.13）、GPU は `swiftshader`（ソフトウェア描画）です。[公式の描画方式一覧](https://developer.android.com/studio/run/emulator-acceleration)に従い、非推奨の `swiftshader_indirect` を使わず、保存済み AVD の `hw.gpu.mode` も起動時に `swiftshader` へ更新します。

### API 37 で SurfaceFlinger が異常終了した原因（解決済み）

当初は cmdline-tools 12.0 で AVD を作っていたため、`android-37.0` のような小数付き API の AVD に `target=android-0` が書かれました。エミュレーターは API を誤認して描画機能を有効にせず、SurfaceFlinger が `hasReadColorBufferDma` の assertion で繰り返し異常終了していました（同じ事例: [ReactiveCircus/android-emulator-runner#482](https://github.com/ReactiveCircus/android-emulator-runner/issues/482)）。cmdline-tools を 23.0 に上げ、`target=android-0` の AVD は起動時に自動で作り直します。修正後は `target=android-37.0` となり、2026-11-19 に API 37 が72.9秒で起動、SurfaceFlinger の異常終了0件で、広告関連の計測11件が成功しました。

`Start` は `sys.boot_completed=1` に加えて、45秒間 SurfaceFlinger の PID が変わらず、既知の描画 assertion が出ていないことを確認してから完了とします。

## 使い方

```powershell
pwsh -File scripts/emulator.ps1 Setup                     # 初回: dockerd 導入・起動とイメージ構築
pwsh -File scripts/emulator.ps1 Start                     # 起動し sys.boot_completed=1 まで待つ（アニメーションは 0 に設定）
pwsh -File scripts/emulator.ps1 Install -Apk release/app-debug.apk
pwsh -File scripts/emulator.ps1 Install -BuildCacheApk app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
pwsh -File scripts/emulator.ps1 Instrument -Runner <テストpkg>/androidx.test.runner.AndroidJUnitRunner -TestClass <クラス>
pwsh -File scripts/emulator.ps1 Adb -AdbArgs shell,getprop,ro.build.version.sdk
pwsh -File scripts/emulator.ps1 Status
pwsh -File scripts/emulator.ps1 Stop                      # スナップショット保存後に停止。イメージ・volume は残る
```

主なオプション: `-Profile api37-16k`、`-ColdBoot`（スナップショットを使わない）、`-Wipe`（ユーザーデータ初期化）、
`-MemoryMb` / `-Cores`（AVD 作成時の値は初回のみ config.ini へ書き込み）、`-GrantPermissions`（install -g）、
`-InstrumentArgs @{ key = 'value' }`、`-RawOutput`（am instrument -r）。

起動中のコンテナが別の Profile なら `Start` は明示的に失敗します。`Stop` は PID だけで判断せず、専用 dockerd の実行ファイルと起動引数を照合してから停止します。

- `Install -Apk` は Windows 側のファイルを WSL の `/mnt/<drive>` 経由で読み、コンテナへ流し込んで `adb install -r -t` します。
  Dropbox 配下へは書き込みません（`release/` は Dropbox 同期除外済み）。
- `Install -BuildCacheApk` は `citrus-build-cache` 内の相対パス（例: `gecko-preview/outputs/apk/debug/gecko-preview-debug.apk`）
  を指定し、転送前後の SHA-256 一致を確認してから導入します。Docker Desktop の Named Volume は専用エンジンからは見えないため、
  Windows 側の一時フォルダ（Dropbox 外、終了時に削除）へ取り出してから転送します。WSL の root からは Windows の
  `docker.exe` を実行できない（exit 126）ため、取り出しは Windows 側で行います。
- `Instrument` は `OK (n tests)` を確認できない場合、または `FAILURES!!!` / `INSTRUMENTATION_FAILED` / `Process crashed`
  を含む場合に失敗終了します。
- adb はコンテナ内（サーバーポート 5039）で動かし、エミュレーターの serial は `emulator-5570`（adb ポート 5571）です。
  Windows 側に adb は不要です。

## 前提

- Windows 11 + WSL2 の `Ubuntu`（systemd 有効）で `/dev/kvm` が存在し、入れ子仮想化が有効であること。
- 初回の `Setup` で download.docker.com と dl.google.com へ接続します。

## 制約（実機が必要な検証の範囲）

エミュレーターでの成功は実機の成功を意味しません。以下は従来どおり実機（SO-51D 等）で確認します。

- 実機固有の WebView / GeckoView 版、OEM の省電力・Doze・バックグラウンド制限、実回線・Wi-Fi 制約・回線切替。
- GPU 描画・PixelCopy・配色の見た目（エミュレーターは SwiftShader のソフトウェア描画）、実機の性能・電力測定。
- 実 IME（物理/ソフトキーボード差）、ランチャー・通知アイコンの各 OEM 表示、本番署名・Play 配布経路。
- ARM 専用ネイティブライブラリ（x86_64 を含まない APK は導入できません）。
- 16KB ページの確認は `api37-16k` で可能ですが、実機の 16KB カーネルと同一ではありません。
