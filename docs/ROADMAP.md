# ロードマップ（要望の候補）

更新日: 2026-11-20（同日夜に、一次資料で再精査）。現代のブラウザ（Firefox・Vivaldi・Brave・Chrome 等）との比較調査を元にした、今後の機能の候補です。需要と実装量を見て、着手するかを決めます。調査の詳細は、ローカルのレポート `.agents/handoff/reports/browser-feature-survey-2026-11-20.md` にあります（Git 管理外）。

GitHub の Issue に登録できるようになったら、各項目を Issue へ移します。

## 実装済み（2026-11-20）

- Cookie 同意バナー・迷惑要素のリストを、無効の購読候補として追加（uBlock filters の Cookie Notices・Annoyances、EasyList Cookie List。破棄行はそれぞれ 2,406行中1行・3,449行中27行・28,220行中6行）
- ページ翻訳の https 化
- ページ情報: 接続（https と証明書の発行者。警告を無視して進んだホストではその旨）、このページで遮断した数
- サイト単位のデータ消去（手動、またはタブを閉じたら自動。サイトごとに選択）。エミュレーターで、Cookie と localStorage が消えることを確認済み

## 候補

本体は minSdk 23 のため androidx.webkit 1.15.0 を使っています。WebView の機能は、端末の WebView の版によっても使えるかが変わります（Android 6 の WebView は 106、Android 7 は 119、Android 8・9 は 138 で更新が止まる）。

| 番号 | 機能 | 内容 | WebView での実現性 | 実装量 | 利便性への影響 |
|---|---|---|---|---|---|
| 4 | HTTPS 優先モード | http のページを https で開き、失敗したら http に戻す | 主文書の URL の書き換えで可（WebView に専用の設定はない） | 中 | 古いサイトで開くのが遅くなる場合がある。初期値はオフを想定 |
| 5 | ピクチャーインピクチャー | 動画の全画面表示から PiP に切り替える | onShowCustomView と Activity の PiP の組み合わせで可 | 小〜中 | なし（利用者の操作で切り替える） |
| 6a | タブのミュート | タブごとに音を消す | MUTE_AUDIO（webkit 1.15.0 で可、WebView 122 以降） | 小 | なし |
| 6b | メディア通知とバックグラウンド再生 | 通知から再生・停止。画面を消しても再生を続ける | JS で再生状態を拾い、Media3 で通知を出す（条件付きで可。minSdk 23 のままなら Media3 は 1.11.x まで） | 中 | 電池の消費が増える。初期値はオフを想定 |
| 8a | プロファイル・プライベートタブの Cookie 分離 | ログイン状態をプロファイルごとに分ける | MULTI_PROFILE（webkit 1.9 以降で可、WebView 119 以降。minSdk とは関係しない） | 大 | 既存のタブの保存形式に影響する。移行の設計が必要 |
| 8b | タブグループ | タブをまとめて名前を付ける | 可（UI とタブの保存形式の拡張） | 大 | タブの保存形式に版付けが必要。タブの復元を壊さないこと |
| 9 | Global Privacy Control | サイトに「追跡しないで」を伝える | Profile の addCustomHeader（webkit 1.15.0 で可、WebView 142 以降）で全リクエストにヘッダーを付けられる。Sec- で始まるヘッダー（Sec-GPC）を付けられるかは未確認 | 小 | なし |
| 10 | uBO が既定で有効にしている追加のリスト | unbreak・quick-fixes・privacy・badware・urlhaus と、URL の追跡用パラメータを消すリストの購読 | 可（購読の候補に加える。破棄行の計測が必要） | 小 | 誤遮断を直すリスト（unbreak・quick-fixes）は利便性を上げる |

ほかの候補:

- 未使用タブの自動クローズ
- ページの読み上げ（リーダーモードの本文を TTS で読む）
- リーダーモードの本文抽出を Readability.js（Apache-2.0）に置き換える
- PDF の内蔵表示（androidx.pdf は minSdk 28 のため、28 未満の端末は OS 標準の PdfRenderer か pdf.js（Apache-2.0）で代わりに表示する）
- デスクトップ表示時の UA と Client Hints の一致（setMobile 等は webkit 1.9 から使え、1.15.0 のままでもほぼ揃えられる。フォームファクタの上書きは 1.16 以降で minSdk 24 が必要）
- ユーザースクリプトの GM_* API（隔離ワールドへの注入は webkit 1.16 以降で minSdk 24 が必要）
- Dynamic Color の任意適用
- パスワードの自動入力が動くかの確認と、OS の自動入力の設定への案内
- Custom Tabs の提供元になる（他のアプリから Citrus でページを開く）
- 同期の代わりに、選んだ保存先へ定期的に書き出す
- ダウンロードの前に、ファイル名と保存先を確認する

## WebView では実現できないもの

- 拡張機能（代わりにユーザースクリプトと、GeckoView の別アプリで対応）
- DNS over HTTPS（OS のプライベート DNS を案内する）
- 任意のサイトのパスキー（技術的に不可能なのではなく、Google の承認が必要。Android 14 以降に限られる）
- WebAPK（ショートカットで代替）
- Web Push（サイトからの通知）
- 通信のヘッダーの変更・削除と、応答ヘッダーの書き換え（uBO の csp・header の構文も含む）。ヘッダーの追加は、上の 9 のとおり可能

## 関連する議題

- 広告ブロックの作り方と、minSdk の範囲: [ADBLOCK_STRATEGY.md](ADBLOCK_STRATEGY.md)
