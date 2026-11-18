# Gecko 検証アプリの OSS 通知

このディレクトリは `:gecko-preview` の Maven runtime 依存専用です。既存本体の `app/oss` は変更せず、コピー済みソース由来18件や Mikan Browser の専用 HTML は取り込みません。

## 固定入力と表示範囲

- `dependencies.xml` は解決済みの外部 runtime 依存52件の座標、宣言、LICENSE/NOTICE 本文の出典と SHA-256 を保持します。debug/release の座標と取得候補は同一です。
- `texts/` はレビュー済みの5種類の本文です。68文書参照を UTF-8/LF に正規化して SHA-256 を照合します。同じ本文は共有しますが、異なる配布物内の著作権表示を省略しません。
- `sources.xml` は空です。この検証アプリから既存本体のコピー済みソースへの依存はありません。
- `../html/license/fixLicensesHtml.css` は生成通知の表示用です。

この一覧は Maven 配布物の通知です。Gecko 内部に含まれるソフトウェアの通知は Gecko の `about:license` で、同梱 uBlock Origin の GPL 本文と原本・対応ソースの出典は別の専用画面で表示します。Maven の POM が宣言するライセンスだけで、ネイティブ配布物や拡張に含まれるすべての権利表示を代替するものとは扱いません。

初回候補の Apache 本文は公式プレーン本文と配布物内の本文を保持しました。Gecko の MPL 2.0 は [Mozilla 公式プレーン本文](https://www.mozilla.org/media/MPL/2.0/index.txt) を補っています。Google Play 開発者サービス4件は POM が Android Software Development Kit License を宣言しており、Apache 等の OSS ライセンスに置き換えません。[Google 公式英語ページ](https://developer.android.com/studio/terms?hl=en) の `sdk-terms` 本文全体（2026年4月28日付、1節〜14.7節）から見出し・強調用の HTML タグだけを除いた全文を固定しました。サイトのナビゲーションやフッターは本文に含めません。

`geckoview-160-provenance.txt` は CitrusBrowser 側が追記した出典情報です。Mozilla 公式 POM の SCM が示す対応ソースの入手先と AAR の SHA-256 を通知画面にも表示します。原典のライセンス本文とは区別しています。

## 明示的な更新

```powershell
pwsh -File scripts/docker-build.ps1 -Task ":gecko-preview:updateOssNotices"
```

候補は `/build-cache/gecko-preview/oss-review/<variant>/oss/`、取得警告は同じ variant 配下の `warnings.txt` に生成します。更新操作は固定入力を自動で上書きしません。debug/release 両方の候補を確認し、実配布物、POM 宣言、埋込 LICENSE/NOTICE、取得本文をレビューしてから固定します。テスト、Gradle プラグイン、KSP/KAPT、配布物のない BOM は対象外です。

通常ビルドでは固定入力から `licenses.compressed` を生成し、ライセンス本文を取得するための通信を行いません。依存や本文が不足する場合は生成を失敗させ、不完全な通知を APK に入れません。生成物とレポートは `layout.buildDirectory` 配下に置き、Docker の設定で `/build-cache/gecko-preview` へ隔離します。

## 検証

```powershell
pwsh -File scripts/docker-build.ps1 -Task ":gecko-preview:generateDebugOssNotices :gecko-preview:generateReleaseOssNotices :gecko-preview:checkOssNotices"
```

固定入力を更新した後は、全参照本文の SHA-256、両 variant の runtime 座標との一致、通常生成とオフライン再生成、APK 内の通知を確認します。
