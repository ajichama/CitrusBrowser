---
name: docker-build-verify
description: >-
  CitrusBrowser の Android ビルドと単体テストを Docker 内で実行し、結果を JUnit XML で確かめる手順。
  Kotlin/Java/Gradle/リソースを変更した後、コミット前、または「ビルドして」「テストを通して」と頼まれたときに使う。
---

# Docker でのビルドと単体テスト

JDK・Android SDK・Gradle はホストで動かさない。すべて `scripts/docker-build.ps1` 経由で実行する（中間生成物は Named Volume に隔離され、Dropbox を汚さない）。

## 1. 変更内容に応じたタスク

| 変更したもの | 実行するタスク（`-Task "..."`） |
| --- | --- |
| 通常のコード・リソース（必須の最終確認） | `assembleDebug testDebugUnitTest` |
| 1モジュールだけを素早く確かめる | `:module:<名前>:testDebugUnitTest`（例 `:module:adblock:testDebugUnitTest :legacy:testDebugUnitTest`） |
| 計測テスト（androidTest）を変えた | 上記に加えて `:app:assembleDebugAndroidTest` やモジュールの `assembleDebugAndroidTest` |
| buildSrc | `:buildSrc:test`（件数は通常の単体テストと分けて報告） |
| release 構成・R8・lint に影響しうる | `assembleRelease` |
| Gecko プレビュー | `:gecko-preview:assembleDebug :gecko-preview:checkOssNotices` |
| docker/Dockerfile | `-RebuildImage` を付ける |

```powershell
pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest" *>&1 | Tee-Object .agents/handoff/reports/<作業名>.log | Select-String 'BUILD (SUCCESSFUL|FAILED)|actionable tasks'
```

- ログ全文はレポートへ保存し、会話へ貼らない。失敗時は `Select-String -Pattern '^e: |FAILED|tests completed|What went wrong' -Context 0,3` で要点だけを見る。
- APK は本体の既知タスクで指定した種類だけが `release/` へ取り出される。モジュール単独のタスクやテストでは取り出さない（古い APK を新しいものと誤認しない）。

## 2. 単体テストの件数を確かめる

`BUILD SUCCESSFUL` だけで件数を報告しない。Named Volume 内の JUnit XML を集計する（UP-TO-DATE のタスクは前回の結果が残る点に注意）。

```powershell
docker run --rm -v citrus-build-cache:/b:ro -v "${PWD}/.agents/skills/docker-build-verify/scripts:/s:ro" citrus-android-builder:latest sh /s/count-junit.sh
```

出力例: `suites=75 tests=298 skipped=0 failures=0 errors=0`。失敗・error・スキップが0であることを確認し、件数を記録する。

## 3. 守ること

- テストの期待値を弱めて通さない。失敗した試行を成功件数に含めない。
- Docker ビルドの成功と、実機・エミュレーターでの成功を分けて記録する（端末での計測は `emulator-measure` スキル）。
- 構成は AGENTS.md 第4節「維持すべき技術的決定」に従う（SDK 構成、legacy-kapt、Wrapper の SHA 固定など）。依存を目的なく更新しない。
- OSS 通知を更新する場合は `oss-notices` スキルを使う。
