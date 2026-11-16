---
name: docker-build-verify
description: >-
  CitrusBrowser の Docker 隔離ビルド検証（assembleDebug / testDebugUnitTest）、Dropbox 同期除外、
  および Git コミット＆Push の標準実行手順。ビルドやコミットを行う際に参照する。
---

# Docker Build & Git Verification Runbook

Windows ホスト環境を汚さず、Dropbox のファイルロック競合を回避して安全にビルド・テスト・Git Push を行う手順です。

## 1. デバッグAPKビルド検証 (`assembleDebug`)

```powershell
pwsh -File scripts/docker-build.ps1
```
- 既定構成は **JDK 21 / Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12**、`compileSdk` / `targetSdk` は **37**。SDK の別名配置・非対応警告抑止を使わない。
- ビルド中間生成物と Kotlin 永続データは `citrus-build-cache`、依存キャッシュは `citrus-gradle-cache`、buildSrc の Gradle キャッシュは `citrus-buildsrc-gradle` に隔離する。
- デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保持する。鍵をソースへコピーしない。Volume を作り直すとデバッグ APK の署名も変わり得るため、検証のたびに削除しない。
- Gradle Wrapper の配布 ZIP は `distributionSha256Sum` で固定する。Wrapper の更新・公式 JAR チェックサム照合も Docker 内で行う。
- 成功すると `release/app-debug.apk` が生成される。
- root/app の既知APKタスクで指定した種別だけを取り出す。Debugは本体debug、Releaseは本体release、DebugAndroidTestとassembleAndroidTestは計測テスト。generic assemble/buildは本体debug+release、複合指定は和集合とする。不明variant、`help`、単体テスト、OSS更新、module単独タスクではAPKを再出力しない。
- 構成失敗時のホストへの HTML 出力を防ぐため `--no-problems-report` を使用し、`--warning-mode all` とスタックトレースで診断をログに残す。失敗を無視して先へ進めない。

## 2. 全モジュール単体テスト検証 (`testDebugUnitTest`)

```powershell
pwsh -File scripts/docker-build.ps1 -Task "testDebugUnitTest"
```
- コード変更後は必ず `assembleDebug` と `testDebugUnitTest` の両方が `BUILD SUCCESSFUL` になることを確認する。
- buildSrc を変更した場合は `pwsh -File scripts/docker-build.ps1 -Task ":buildSrc:test"` も実行する。通常モジュールの単体テストとは件数・実行結果を分けて報告する。
- 名前空間・計測テスト関連の変更では、次の3タスクを確認する。計測テスト APK のコンパイル成功と実機での実行成功を区別する。

```powershell
pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest"
```

- 2026-11-16 の検証スナップショットはOSS修正後の標準3タスクとRelease（R8/lint）も成功し、通常モジュール165件・別実行のbuildSrc15件の単体テストが成功。SO-51DのAndroid 15（API35）・WebView 157.0.8103.38で配色5件・Worker等5件・本体Hiltローカルfile://購読2件・ライセンスInsets1件の計13件が、最終再実行まで失敗・スキップ0。Insetsは旧版失敗→修正版成功、縦画面・現在のテーマ・3ボタンナビで確認。本体UIは導線と見た目のスモーク確認、IMEは要求フラグの確認まで。他OS、外部HTTP購読、回線切替、実プロセス/OS再起動・Doze等は検証済みとしない。最新の結果と未完項目は [公開進捗](../../../docs/PROGRESS.md)を参照する。

## 3. Docker イメージ再構築が必要な場合

`docker/Dockerfile` を変更した場合は `-RebuildImage` スイッチを付与する：

```powershell
pwsh -File scripts/docker-build.ps1 -RebuildImage
```

## 4. OSS 通知と依存更新の検証

通常の通知生成は固定入力だけを読み、通知本文をネット取得しない。Gradle の依存解決は別であり、ビルド全体のオフライン実行を意味しない。WorkManager追加後の固定入力はruntimeの102依存・106文書、ソース由来18項目を保持している。

```powershell
# 明示操作で候補を取得する。固定入力は変更しない。
pwsh -File scripts/docker-build.ps1 -Task ":app:updateOssNotices"

# レビュー反映後の固定通知を検証する。
pwsh -File scripts/docker-build.ps1 -Task ":app:checkOssNotices"
```

候補は `/build-cache/app/oss-review/` 内に生成される。依存座標・出典・本文を確認してから `app/oss/` へ反映する。SHA-256 不一致や通知不足で通常ビルドが失敗すること、既存のソース通知と NYSL 本文が残ることを確認する。APK 内の `assets/licenses.compressed` の内容とライセンス画面の端末表示は別途確認する。

2026-11-15 はネットワーク切断と `--offline --rerun-tasks` による通知再生成、および APK 内の生成 GZIP の一致を確認済み。これらの検証用オプションは隔離コンテナで固定して渡し、スクリプトの `-Task` に任意オプションを許可しない。

依存の更新候補を調べる場合は `pwsh -File scripts/docker-build.ps1 -Task "dependencyUpdates"` を使用する。versions-plugin の要件に合わせ、スクリプトが `--no-parallel` を固定引数として追加する。`-Task` の検証を緩めて任意の Gradle オプションを許可しない。

## 5. Git Commit & Push

Dropbox のファイルロックを避けるため、コンテナ内でコミットするスクリプトを使用する。先に公開対象のファイルだけを明示してステージし、`git diff --cached` で確認する。スクリプトは自動で `git add` しない。

```powershell
git add -- <確認済みの対象パス>
git diff --cached
pwsh -File scripts/git-commit-push.ps1 -Message 'fix: 修正内容'
```

コミットと同時に Push することが依頼されている場合だけ `-Push` を明示する。未指定ではローカルコミットのみ実施する。

```powershell
pwsh -File scripts/git-commit-push.ps1 -Message 'fix: 修正内容' -Push
```
