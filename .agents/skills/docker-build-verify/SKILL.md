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
- デフォルトで **Java 21** および **Android 17 (API 37)** SDK を使用し、ビルド中間生成物は Docker Named Volume (`citrus-build-cache`, `citrus-gradle-cache`) に隔離される。
- 成功すると `release/app-debug.apk` が生成される。

## 2. 全モジュール単体テスト検証 (`testDebugUnitTest`)

```powershell
pwsh -File scripts/docker-build.ps1 -Task "testDebugUnitTest"
```
- コード変更後は必ず `assembleDebug` と `testDebugUnitTest` の両方が `BUILD SUCCESSFUL` になることを確認する。

## 3. Docker イメージ再構築が必要な場合

`docker/Dockerfile` を変更した場合は `-RebuildImage` スイッチを付与する：

```powershell
pwsh -File scripts/docker-build.ps1 -RebuildImage
```

## 4. Git Commit & Push

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
