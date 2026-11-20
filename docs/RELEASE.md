# リリース版のビルドと署名

本体のリリース版（applicationId `io.github.ajichama.citrusbrowser`）を、署名つきの APK と AAB（Google Play に上げる形式）として作る手順です。デバッグ版（`.debug` 付き）とは別のアプリとして入り、データは共有しません。

## 署名の鍵

- 鍵（PKCS12、RSA 4096、有効期間 10,000 日）と設定 `release.properties` は、Docker の専用ボリューム `citrus-release-key` の中だけに置きます。ソース・Dropbox・`release/` には置きません。
- `scripts/docker-build.ps1` は、タスク名に `Release` を含むときだけ、このボリュームを `/release-key` に読み取り専用でつなぎます。鍵がなければ、リリース版は署名なしで出力されます。
- 鍵をなくすと、同じ鍵で署名した版へ更新できなくなります。必ずバックアップを取り、同期されない安全な場所（パスワード管理ソフト、オフラインの記録媒体など）に保管します。

```powershell
pwsh -File scripts/release-key.ps1 Status    # 鍵の有無と証明書の SHA-256（パスワードは表示しない）
pwsh -File scripts/release-key.ps1 New       # 鍵がなければ作る（既にあれば作り直さない）
pwsh -File scripts/release-key.ps1 Backup    # 既定は %USERPROFILE%\.citrus\release-key-backup（作業フォルダ・Dropbox へは複製しない）
```

## 版番号

CitrusBrowser としての版は 1.0.0 から始めます（2026-11-21。Yuzu Browser の最終版 6.0.2 の番号は引き継がない）。内部の版番号（versionCode）は「主版×1000000＋副版×1000＋修正版」で、1.0.0 は 1000000 です。旧版の移行処理が Yuzu の番号（410010 等）と比べているため、内部の版番号は常にそれより大きく保ちます。定義は buildSrc/src/main/java/Dependencies.kt の AppVersions です。

## ビルド

```powershell
pwsh -File scripts/docker-build.ps1 -Task ":app:assembleRelease :app:bundleRelease"
```

`release/` に `io.github.ajichama.citrusbrowser_v<版>_<版番号>.apk` と `.aab` が出ます。リリース版は R8 で縮小するため、公開の前にエミュレーターか実機で、起動・ページ表示・設定画面・広告ブロックの読込を確かめます。

## Google Play の署名との関係

Play では、アップロードに使う鍵（この鍵）と、利用者の端末に届くアプリの署名の鍵（Play App Signing）が別になり得ます。GitHub などで配る APK と Play 版を互いに更新できるようにするには、最初のアップロードの前に、Play App Signing でこの鍵をアプリの署名の鍵として登録する必要があります。どちらにするかは、最初のアップロードの前に決めます。
