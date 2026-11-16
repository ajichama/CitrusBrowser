# CitrusBrowser コントリビューションガイドライン

**CitrusBrowser** ([ajichama/CitrusBrowser](https://github.com/ajichama/CitrusBrowser)) に関心をお寄せいただきありがとうございます。
本ドキュメントでは、不具合報告（Bug Report）、機能要望（Feature Request）、およびプルリクエスト（Pull Request）の作成手順と開発ルールについて説明します。

---

## 1. Issue（不具合報告・機能要望）の作成について

新しい Issue を作成する前に、[既存の Issue 一覧](https://github.com/ajichama/CitrusBrowser/issues)（Open および Closed）に同じ内容がすでに報告されていないか確認してください。

### 不具合報告 (Bug Report)
不具合を報告する際は、以下の情報を含めてください：
- **再現手順**: 問題が発生するまでの具体的な操作手順（対象URL、設定状態など）
- **期待される動作**: 本来どのように動作すべきか
- **環境情報**:
  - 端末名（例: Google Pixel 9 等）
  - Android OS バージョン（例: Android 16 / Android 17）
  - CitrusBrowser のバージョン（設定 ＞ ブラウザについて から確認・コピー可能）
- **ログ・スクリーンショット**: クラッシュログ（logcat）や画面キャプチャがあれば添付してください。

### 機能要望・改善提案 (Feature Request)
- 1つの Issue につき 1つの機能要望を記載してください（複数の要望を1つの Issue にまとめないでください）。
- どのような場面で必要となるか（ユースケース）と、期待するUI・挙動を具体的に記述してください。

---

## 2. 開発環境とビルド手順

本プロジェクトでは、ホスト環境を汚さずに一貫した環境でビルド・テストを行うため、**Docker コンテナ (`citrus-android-builder`)** による隔離ビルドを採用しています。

### 必要環境

- Docker Engine / Docker Desktop
- PowerShell (`pwsh`)

ビルドの既定構成は JDK 21 / Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12、`compileSdk` / `targetSdk` は 37 です。JDK・SDK・Gradle をホストへ導入せず、Docker 内で実行してください。SDK の別名配置や非対応警告の抑止は使用しません。

### ビルド・テストの実行
```powershell
# デバッグAPKのビルド（出力先: release/app-debug.apk）
pwsh -File scripts/docker-build.ps1

# 単体テストの実行
pwsh -File scripts/docker-build.ps1 -Task "testDebugUnitTest"

# 名前空間・計測テストを含む変更の検証（端末実行は別途必要）
pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest"

# buildSrc の変更に対応するテスト
pwsh -File scripts/docker-build.ps1 -Task ":buildSrc:test"

# Docker イメージの再構築を伴うビルド
pwsh -File scripts/docker-build.ps1 -RebuildImage
```

`build/`・`.gradle`・Kotlin 永続データは Volume 内へ隔離します。`citrus-android-home` はデバッグ署名鍵と adb 鍵の保存先です。鍵を Git 管理せず、Volume の再作成で既存 APK と署名が変わることに注意してください。

APK の取り出しは root/app の `assemble*`・`package*`・`build` を明示した場合だけ行います。`help`、単体テスト、OSS 通知更新や module 単独のタスクは古い APK を再出力しません。`dependencyUpdates` はスクリプトが必要な `--no-parallel` を固定で追加します。`-Task` にはタスク名だけを指定し、任意の Gradle オプションを渡さないでください。

### OSS 通知の変更

通常ビルドは `app/oss/` と `app/html/license/` の固定入力だけから通知を生成します。通知本文をネット取得しませんが、Gradle の依存解決までオフラインになる意味ではありません。現在の固定入力は runtime の 99 依存・102 文書とソース由来 18 項目です。既存の帰属表示・NYSL 本文を保持してください。

```powershell
# runtime依存から更新候補を取得（固定入力は書き換えない）
pwsh -File scripts/docker-build.ps1 -Task ":app:updateOssNotices"

# レビュー反映済みの固定入力とruntime依存の整合を検証
pwsh -File scripts/docker-build.ps1 -Task ":app:checkOssNotices"
```

候補は Volume 内の `/build-cache/app/oss-review/` に出力されます。依存座標・出典・本文をレビューしてから固定入力へ反映し、SHA-256 を更新してください。不足した通知や本文のハッシュ不一致は通常ビルドを失敗させます。APK 内の `assets/licenses.compressed` とアプリのライセンス画面も確認します。

実装済み・自動検証済み・実機未検証の区別は [公開進捗](docs/PROGRESS.md) にまとめています。PR では実施した検証範囲を記載してください。

端末での操作確認は [実機確認手順](docs/DEVICE_CHECKS.md) に沿って記録します。ページ配色は [仕様と計測テスト](module/webview/PAGE_COLORS.md)、広告判定は [種別・索引と測定範囲](module/adblock/REQUEST_MATCHING.md) を参照してください。

---

## 3. コーディング・コミット規約

- **言語・ドキュメント**: ドキュメント、Issue、PR、および新規追加するコード内コメントは日本語で記述してください。
- **フォーマッティング**: 変更に関係のない箇所への無用なフォーマット変更やインデント変更は避けてください（差分のレビューを容易にするため）。
- **コミットメッセージ**: Conventional Commits 形式を推奨します。
  - `feat:` 新機能の追加
  - `fix:` 不具合修正
  - `build:` ビルド設定・依存関係の更新
  - `refactor:` リファクタリング
  - `docs:` ドキュメントの更新
  - `chore:` その他の雑務・整理
- **ライセンスヘッダ**: 既存ファイルの `Copyright (C) 2017-2021 Hazuki` および Apache License 2.0 ヘッダは削除せず維持してください。

---

## 4. プルリクエスト (Pull Request) の手順

1. [ajichama/CitrusBrowser](https://github.com/ajichama/CitrusBrowser) を Fork し、作業用のトピックブランチを作成します。
2. 変更を実装し、`pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest"` の成功を確認します。buildSrc を変更した場合は `:buildSrc:test`、名前空間・計測テスト関連を変更した場合は `assembleDebugAndroidTest` も確認します。
3. 変更内容と関連する Issue 番号を明記して Pull Request を送信してください。
