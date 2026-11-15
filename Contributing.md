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

### ビルド・テストの実行
```powershell
# デバッグAPKのビルド（出力先: release/app-debug.apk）
pwsh -File scripts/docker-build.ps1

# 単体テストの実行
pwsh -File scripts/docker-build.ps1 -Task "testDebugUnitTest"

# Docker イメージの再構築を伴うビルド
pwsh -File scripts/docker-build.ps1 -RebuildImage
```

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
2. 変更を実装し、`pwsh -File scripts/docker-build.ps1` でビルドが通ることを確認します。
3. 変更内容と関連する Issue 番号を明記して Pull Request を送信してください。
