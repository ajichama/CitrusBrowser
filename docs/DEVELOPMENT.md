# 開発の規約と技術的な決定

全体像は [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) にあります。この資料は、開発するときに毎回守る規約と、維持する技術的な決定をまとめたものです。日付つきの検証結果などは、ここには書きません。[公開進捗](PROGRESS.md)・[内蔵uBO計画](ADBLOCK_ENGINE_PLAN.md)・[改善一覧](IMPROVEMENT_PLAN.md) に記録します。

## 1. 概要

**CitrusBrowser**（`ajichama/CitrusBrowser`）は、オープンソースの Android 向け Web ブラウザ **Yuzu Browser**（`hazuki0x0/YuzuBrowser`、Apache License 2.0）の全コミット履歴を引き継いだ後継です。新しい Android への対応、UI の刷新、広告ブロックなどの機能の強化を行います。

## 2. 開発の原則

1. **日本語で書く**: 資料、計画、新しく足すコードのコメント、コミットメッセージは日本語で書きます。識別子・コマンド・パスなどの固有名は元の表記のままにします。
2. **最新の仕様を確かめてから決める**: OS の版（Android 16 = API 36、**Android 17 = API 37**）、ビルドツール（AGP 9.x・Gradle 9.x）、言語・ライブラリ（Kotlin 2.4.x・Hilt 2.60.x・Room・OkHttp など）を選ぶ・移すときは、記憶で決めつけません。公式の資料と SDK リポジトリ（`sdkmanager --list`）で、その時点の最新の安定版と破壊的な変更を確かめてから設計します。
3. **ビルドの環境**:
   - JDK 21・Android SDK（API 37 を含む）があれば、Gradle でビルドできます（`./gradlew assembleDebug testDebugUnitTest`）。同じ環境を Docker で用意する [docker/Dockerfile](../docker/Dockerfile) もあります。
   - 署名の鍵・設定（`release.properties`）は、ソースや公開する成果物に含めません。鍵がなければ、リリース版は署名なしで出力されます。
4. **Apache License 2.0 を守る**:
   - 既存のソースの `Copyright (C) 2017-2021 Hazuki` と、Apache License 2.0 のヘッダは、そのまま残します。
   - 独自のアプリ名（**CitrusBrowser**）・パッケージ ID（`applicationId`）・アイコンを使います。そのうえで、Yuzu Browser（作者: Hazuki 氏）を元にしていることを明記します。
5. **`main` は常にビルドとテストが通る状態に保つ**: コミットと Push の前に、ビルドと単体テスト（`assembleDebug testDebugUnitTest`）が通ることを確かめます。
6. **個人の情報を公開しない**: 公開する資料・コード・コミット・Issue に、開発者個人や、その端末・PC の環境・利用データ・閲覧したサイトを特定できる情報を書きません。確認の範囲は「エミュレーター（API ○）」「実機」までにします。

## 3. ディレクトリの構成

```text
.
├── docker/
│   ├── Dockerfile            # Android SDK (API 30/36/37) と JDK 11/17/21 のビルド環境
│   └── init.gradle           # ビルドの中間生成物を Docker のボリューム (/build-cache) に置く設定
├── buildSrc/                 # ビルドの版と依存ライブラリの一元管理 (Dependencies.kt)
├── docs/                     # 全体像・進捗・計画・方針の資料、プライバシーポリシー
├── app/                      # アプリの入口とパッケージの定義
├── browser/                  # ブラウザの主画面
├── legacy/                   # 旧来の構成の資産（段階的に刷新する）
├── gecko-preview/            # min26/compile37.2 の、GeckoView と内蔵 uBO の検証用の別アプリ
├── gecko-ui-probe/           # 外部の runtime に依存しない、debug 専用の標準 UI の検証用アプリ
├── libraries/                # 内蔵のライブラリ (asyncpermissions, colorpicker, floatingactionbutton)
└── module/                   # 機能別のモジュール (adblock, bookmark, core, download, engine-api, favicon, gecko, history, search, ui, webview など)
```

## 4. ロードマップと、維持すべき技術的決定

### 段階の現在地（詳しい内容と検証の範囲は [PROGRESS.md](PROGRESS.md)）

- **段階1: リポジトリと Docker のビルド基盤**: 完了。
- **段階2: アプリ名・パッケージ名の刷新**: 名前の移行と独自アイコンは完了。各 OS・ランチャーでの実機での表示は未確認。
- **段階3: ビルド基盤と依存の刷新**: 完了。今の構成は JDK21・Gradle9.6.1・AGP9.5.1・Kotlin2.4.21・Hilt2.60.1・KSP2.3.12。
- **段階4: Android 16・17（API 36・37）への対応**: 基盤は実装済み。他の OS・Edge-to-Edge・IME などの横断の確認を続けている。
- **段階5: 広告ブロックと機能の強化**（現在）: 本体の広告遮断の改善、別アプリでの GeckoView と uBO 原本の内蔵の実証、`module:engine-api` による本体からの段階的な接続を進めている。

### 維持すべき技術的決定（変えるときはオーナーが判断し、この節を更新する）

- **SDK の構成**: 本体は minSdk24・compileSdk37・targetSdk37（minSdk は 2026-11-20 のオーナーの決定で 23 から 24 へ。理由と各段階の比較は [ADBLOCK_STRATEGY.md](ADBLOCK_STRATEGY.md) 第4節）。別の applicationId の `:gecko-preview`・`:module:gecko` だけが minSdk26・compileSdk37.2 を使い、本体と保存領域を共有しない。
- **ビルド**: AGP の built-in Kotlin と、Data Binding を使うモジュールの `com.android.legacy-kapt` を保つ。依存は目的のある更新だけ行い、関係なく Kotlin・Hilt などを上げない。Gradle Wrapper の配布 ZIP の SHA-256 の固定と、Wrapper JAR の公式のチェックサムとの照合を保つ。
- **OSS 通知**: 固定の入力から生成し、通常の生成では通信しない。取得候補の更新は、明示したタスクとレビューで行う。本体とプレビューの通知は別に管理し、Gecko の `about:license` と uBO 原本の GPL の通知も保つ。
- **内蔵 uBO**: SHA を確かめた原本を、改造せずに同梱する。private RPC や拡張の storage を直接書き換えない。uBO の有効の表示を、フィルタの準備完了や初回起動の保護の証拠にしない。
- **広告遮断の判定**: 成功の条件は汎用の処理（実際の通信・実際の DOM）で判定し、サイトごとのルールの追加を成功の条件にしない。uBlock の拡張構文の完全な互換や、段階5全体の完了は宣言しない。
- **scriptlet のライセンス（2026-11-19 オーナーの決定）**: 本体は Apache-2.0 を保つ。scriptlet は、uBO と名前・引数だけを合わせた独自の実装とし、uBO の scriptlet のソース（GPLv3）を参照も流用もしない。uBO wiki の公開の仕様と、実際のサイトの挙動だけを元に書く（クリーンルーム）。uBO 原本との完全な互換が必要な用途は、GeckoView と uBO 原本の別アプリが受け持つ。GPLv3 にする、または実行時の取得に変える場合は、改めて判断する。
- **エンジン共通 API**: `module:engine-api` は Android・Gecko の型を公開しない。保存の形式・操作 ID・履歴のロック・既定の操作を保ち、実装していない操作を成功として扱わない。旧タブの Bundle・ログインの状態・POST を、別のエンジンへ黙って移さない。
- **ページの配色**: 専用の WebView Theme で、アプリの UI と切り離す（明色・サイトの暗色・サイト優先の自動暗色の3択）。

### 検証結果の記録の規則

- ビルド・単体テストの成功と、実機での成功を分けて記録する。失敗した試行を成功の件数に含めず、確かめていない OS・端末・条件に一般化しない。
- 公開する記録には、端末の機種・実機の利用データ・接続情報・個人の画面・鍵・非公開のログを含めない。端末の設定を一時的に変えたときは、作業の終わりに元へ戻す。

## 5. Git と GitHub の運用

- **リモート**: `upstream` は `https://github.com/hazuki0x0/YuzuBrowser.git`。
- **コミットの書き方**: Conventional Commits の形式（`feat:`・`fix:`・`build:`・`refactor:`・`docs:`・`chore:`）を使う。
- **ステージ**: 公開する範囲を差分で確かめてから、パスを明示してステージする。未コミットのものを全部まとめて足さない。
- **個人のファイル**: 個人だけのメモや補助のファイルは `.git/info/exclude` に書く。ホームの下のアプリの設定や秘密鍵は共有しない。
