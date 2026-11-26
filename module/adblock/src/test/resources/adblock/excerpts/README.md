# テスト用フィルタリスト抜粋のライセンス

このディレクトリのファイルは、単体テスト（`RealListExcerptTest`）で構文の受理・破棄を確かめるために、公開フィルタリストから数十行ずつ抜き出したものです。本リポジトリの Apache License 2.0 は適用されず、各ファイル先頭に記載した元リストのライセンスに従います。

| ファイル | 元リスト | ライセンス |
| --- | --- | --- |
| `easylist.txt` | EasyList | GPLv3 または CC BY-SA 3.0（https://easylist.to/pages/licence.html） |
| `adguard-jp.txt` | AdGuard Japanese filter | GPLv3（https://github.com/AdguardTeam/AdguardFilters/blob/master/LICENSE） |
| `ubo-filters.txt` | uBlock filters（uAssets） | GPLv3（https://github.com/uBlockOrigin/uAssets/blob/master/LICENSE） |

テストのリソースだけで使い、アプリの APK には含めません。アプリは購読するリストの本文を実行時に取得し、同梱しません。
