# Gecko / uBlock Origin の独立実証

既存の API23 対応ブラウザへ依存を追加せず、`:gecko-preview` からだけこのライブラリを利用します。プレビューは既存の設定・ブックマーク・タブ・Cookie profile を変更しません。本体へのエンジン移植や既存全機能の対応完了を示す構成ではありません。

固定したエンジンは Mozilla 公式 Maven の `org.mozilla.geckoview:geckoview:160.0.20261116130455` です。公式 `.module` と実 AAR の SHA-256 は `dba77755f0d02ab4a19f4b8cd63e6a57accf5c44c95a040724ab4a92527e1193` で一致しました。AAR の要件は minSdk26 / compileSdk37.2 / Java17、実 ABI は arm64-v8a・armeabi-v7a・x86_64 です。32bit x86 は含まれません。Docker の既存 SDK37.2 / JDK21 を使い、NDK によるエンジンの自前ビルドは行いません。

SDK37.2 を対応範囲内で使うため、共有ビルドツールは AGP9.5.1 と Gradle9.6.1 を採用します。[AGP9.5 の必要 Gradle は9.6.0以上](https://developer.android.com/build/releases/agp-9-5-0-release-notes)のため、その最小対応9.6系列の修正版を選びました。Gradle 全体の最新版を無関係に追随する更新ではありません。Wrapper は Docker 内の独立一時プロジェクトで正規タスクを2回実行し、公式 ZIP/JAR の SHA-256 を照合して生成します。本体の minSdk23・compileSdk37.0 と Kotlin/Hilt は維持し、SDK 警告の抑止は追加しません。

内蔵 uBO は [AMO 公式配布 1.76.4](https://addons.mozilla.org/firefox/downloads/file/5257481/ublock_origin-1.76.4.xpi) を固定し、SHA-256 `66d0e3f21ce3b32b1bc0a9399b93cb2c9aa598ef18805d92118c76f66e266c1e` を検証します。原本の ID は `uBlock0@raymondhill.net`、GPL は v3.0 only、[対応ソース](https://github.com/gorhill/uBlock/tree/1.76.4)を出典として保持します。

`prepareBuiltinUblock` は `/build-cache/module_gecko/builtin-ublock/download` に XPI をキャッシュし、`/build-cache/module_gecko/generated/assets/builtin-ublock` に生成します。すべて Docker Named Volume 内です。リポジトリへ XPI、展開した第三者資産、生成キャッシュを追加しません。`--offline` 時は検証済みキャッシュだけを利用し、なければ失敗します。版・ハッシュ・ZIP のパス/重複/CRC/容量を確認してから出力を置き換え、失敗時に既存出力を消しません。

資産の包装では AAPT の既定除外から `<dir>_*` だけを外し、原本の `_locales` を保持します。プレビューの `assembleDebug` / `assembleRelease` は `verifyDebugBuiltinUblockPackaging` / `verifyReleaseBuiltinUblockPackaging` を必須とし、生成 assets の全ファイル名・サイズ・SHA-256 を最終 APK と照合します。原本 SHA 検証とこの照合により、生成時だけ正常で包装時に欠落する問題を検出します。検査記録は各 variant の Docker build ディレクトリ内 `reports/builtin-ublock` に出力します。

生成資産の契約は次のとおりです。

- `resource://android/assets/ublock/`：manifest・GPL・第三者ライセンス・署名エントリを含む原本の全内容。改変しません。
- `assets/ublock-provenance.txt`：版・作者・ライセンス・原本 URL/SHA・対応ソース・更新方式。
- `assets/ublock/LICENSE.txt`：GPL 本文。プレビューの通知画面から全文を表示します。

原本 SHA の照合は同梱する配布物の同一性の確認です。Gecko の通常拡張インストールが行う暗号署名検証とは区別します。内蔵コードの更新は APK 更新で配布し、フィルタリスト更新や利用者の uBO 設定は uBO 自身の画面と Gecko profile で管理します。

明示ビルドは `pwsh -File scripts/docker-build.ps1 -Task ":gecko-preview:assembleDebug"`、完成 APK は `release/gecko-preview-debug.apk` です。本体 APK を上書きしません。root/app タスクや未指定 variant からプレビューの古い APK を取り出しません。

Gecko 自身の通知はプレビューから `about:license` へ案内します。プレビュー全推移依存の固定通知は `gecko-preview/oss` の別 graph から生成し、通常ビルドでも不足を失敗として扱います。GMS の非 OSS ライセンスも含むため、表示名は「ライブラリのライセンス」です。既存 `app/oss` と本体の通知表示名は変更しません。

Runtime はプロセスごとに1回だけ初期化できるため、`GeckoRuntimeHolder` が唯一の Runtime を所有します。通常起動は従来どおり `GeckoRuntime.getDefault` を使います。計測だけが最初の生成前に設定を予約でき、使い捨て profile（`-profile`）で起動します。予約後に生成が失敗しても既定 profile へ切り替えません。`BuiltinUblockManager.showSubscribePage` は、uBO 自身が abp:subscribe で開く購読確認ページを表示するだけです。購読の確定はページ内の「購読」ボタンで行い、選択や storage を直接変更しません。
