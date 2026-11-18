---
name: emulator-measure
description: >-
  CitrusBrowser の計測テスト（am instrument）や画面確認を、Docker 上の Android エミュレーターまたは実機（SO-51D）で実行する手順。
  androidTest を実行するとき、APK を端末に入れて動作や画面を確かめるとき、実サイトで広告の残り方を比べるときに使う。
---

# エミュレーター・実機での計測

## エミュレーター（既定。実機が不要な検証はこちら）

WSL2 Ubuntu の KVM を使う専用 dockerd 上で動く。詳細と制約は `docker/emulator/README.md`。

```powershell
pwsh -File scripts/emulator.ps1 Start -Profile api37        # api37（既定）/ api36 / api37-16k（起動未確認）
pwsh -File scripts/emulator.ps1 Install -BuildCacheApk app/outputs/apk/debug/app-debug.apk
pwsh -File scripts/emulator.ps1 Install -BuildCacheApk app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
pwsh -File scripts/emulator.ps1 Instrument -Runner <テストpkg>/androidx.test.runner.AndroidJUnitRunner -TestClass <完全修飾クラス名>[,<クラス名>...]
pwsh -File scripts/emulator.ps1 Stop                         # 作業の最後に必ず止め、Status で running=no を確かめる
```

- APK は先に `docker-build-verify` でビルドする（例 `-Task ":app:assembleDebug :app:assembleDebugAndroidTest :module:adblock:assembleDebugAndroidTest"`）。`-BuildCacheApk` は `citrus-build-cache` 内の相対パス。
- テストパッケージ: 本体 `io.github.ajichama.citrusbrowser.debug.test`、広告モジュール `io.github.ajichama.citrusbrowser.adblock.test`、Gecko プレビュー `io.github.ajichama.citrusbrowser.geckopreview.test`。API 37 では `pm list instrumentation` が使えないので `pm list packages` で確かめる。
- Gecko プレビューの使い捨て profile の計測は、既存の計測と同じプロセスで動かせない。`-TestClass` で分けて別々に実行する。
- `Start` は起動完了の表示に加え、45秒間 SurfaceFlinger が落ちないことを確かめる。失敗したら `docker/emulator/README.md` の原因（古い cmdline-tools で作った AVD の `target=android-0`）をまず疑う。
- 結果は `OK (n tests)` の件数と所要時間をログ（`.agents/handoff/reports/`）に残す。

## 実機（SO-51D、Android 15）

日本語入力（IME）、描画性能、メーカー固有の挙動は実機でしか確かめられない。

- 端末は Docker コンテナ `citrus-device-check-*` 経由の adb で接続する。接続先の IP や端末の個人画面を記録・公開しない。
- 画面が消えるとテストが止まるため、`settings put system screen_off_timeout` を一時的に延ばす場合は、元の値を `release/` 以下に保存し、作業終了・交代の前に必ず戻して台帳に記録する。
- 画面ロック中は Activity が前面に来ず失敗する。ユーザーに解除を頼み、失敗した試行は成功件数に含めない。

## 守ること

- エミュレーターでの成功を実機の成功として報告しない。端末・API・WebView の版を結果に添える。
- ユーザーのアプリデータ・全 DB を消さない。テスト用のデータは自分で作ったものだけを片付ける。
