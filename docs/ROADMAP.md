# ロードマップ（要望の候補）

更新日: 2026-11-26（進める順番を見直し）。2026-11-20 に一次資料で再精査。現代のブラウザ（Firefox・Vivaldi・Brave・Chrome 等）との比較調査を元にした、今後の機能の候補です。需要と実装量を見て、着手するかを決めます。

各候補は GitHub の Issue に登録しました（2026-11-20）。表の番号の右の # が Issue の番号です。議論や優先度の変更は Issue で行います。

## 方向性と進める順番（2026-11-26 見直し）

Yuzu Browser の後継として「自分好みに組み替えられるブラウザ」を軸にします（拡張機能を使える Gecko 版も視野に残す）。進める順番は [#30](https://github.com/ajichama/CitrusBrowser/issues/30) にもまとめています。

2026-11-22 に決めた順番のうち、#22・#25・#26 は済みました。2026-11-26 に、開いている Issue の優先度と作業の重さを見直し、次の順番にしました（作業の重さは、小＝1つの画面や数個のファイル、中＝複数の画面やモジュールにまたがるか実機での確認が要る、大＝保存の形式やアプリ全体に及ぶ）。

1. **クイックコントロールを使える状態にする・操作の検索**（次の版）
   - [#37](https://github.com/ajichama/CitrusBrowser/issues/37) 項目の変更をすぐ反映する（小）
   - [#36](https://github.com/ajichama/CitrusBrowser/issues/36) 新しく入れたときの既定の項目（小。中身は案を示して決める）
   - [#38](https://github.com/ajichama/CitrusBrowser/issues/38) ジェスチャーナビゲーションとの衝突（中）
   - [#42](https://github.com/ajichama/CitrusBrowser/issues/42) 操作を選ぶ画面の検索（小〜中）
2. **安全網と安全設定**
   - [#23](https://github.com/ajichama/CitrusBrowser/issues/23) 主要な操作の自動テスト（中〜大。まず起動・タブ・設定・操作の配列の整合を確かめる最小の版から）
   - [#27](https://github.com/ajichama/CitrusBrowser/issues/27) WebView の安全設定の点検（小〜中。Google Play の審査の前に）
3. **軽くて効果のある機能**: [#8](https://github.com/ajichama/CitrusBrowser/issues/8) 追加のフィルタリスト（小）→ [#7](https://github.com/ajichama/CitrusBrowser/issues/7) GPC（小）→ [#3](https://github.com/ajichama/CitrusBrowser/issues/3) タブのミュート（小）→ [#2](https://github.com/ajichama/CitrusBrowser/issues/2) PiP（小〜中）
4. **計測と中くらいの機能**: [#24](https://github.com/ajichama/CitrusBrowser/issues/24) 性能の計測（中）の後に、[#1](https://github.com/ajichama/CitrusBrowser/issues/1) HTTPS 優先・[#4](https://github.com/ajichama/CitrusBrowser/issues/4) メディア通知・[#41](https://github.com/ajichama/CitrusBrowser/issues/41) 長押しの並べ替えを広げる・[#9](https://github.com/ajichama/CitrusBrowser/issues/9) の小さな候補から選ぶ
5. **大きな作業**: [#28](https://github.com/ajichama/CitrusBrowser/issues/28) コードの近代化（大、#23 の後）→ [#29](https://github.com/ajichama/CitrusBrowser/issues/29) Material 3 の統一（大、議論の後）、[#6](https://github.com/ajichama/CitrusBrowser/issues/6) タブグループ・[#5](https://github.com/ajichama/CitrusBrowser/issues/5) Cookie の分離（大）

Google Play の公開は並行して進めます。議論・調査してから決める項目と、調べる順番の方針は #30 にあります。

## 実装済み（2026-11-20）

- Cookie 同意バナー・迷惑要素のリストを、無効の購読候補として追加（uBlock filters の Cookie Notices・Annoyances、EasyList Cookie List。破棄行はそれぞれ 2,406行中1行・3,449行中27行・28,220行中6行）
- ページ翻訳の https 化
- ページ情報: 接続（https と証明書の発行者。警告を無視して進んだホストではその旨）、このページで遮断した数
- サイト単位のデータ消去（手動、またはタブを閉じたら自動。サイトごとに選択）。エミュレーターで、Cookie と localStorage が消えることを確認済み

## 候補

本体は minSdk 24 です（2026-11-20 に 23 から変更）。androidx.webkit は今は 1.15.0 で、1.16 以降の機能が必要になった時点で更新します。WebView の機能は、端末の WebView の版によっても使えるかが変わります（Android 6 の WebView は 106、Android 7 は 119、Android 8・9 は 138 で更新が止まる）。

| 番号 | 機能 | 内容 | WebView での実現性 | 実装量 | 利便性への影響 |
|---|---|---|---|---|---|
| 4 [#1](https://github.com/ajichama/CitrusBrowser/issues/1) | HTTPS 優先モード | http のページを https で開き、失敗したら http に戻す | 主文書の URL の書き換えで可（WebView に専用の設定はない） | 中 | 古いサイトで開くのが遅くなる場合がある。初期値はオフを想定 |
| 5 [#2](https://github.com/ajichama/CitrusBrowser/issues/2) | ピクチャーインピクチャー | 動画の全画面表示から PiP に切り替える | onShowCustomView と Activity の PiP の組み合わせで可 | 小〜中 | なし（利用者の操作で切り替える） |
| 6a [#3](https://github.com/ajichama/CitrusBrowser/issues/3) | タブのミュート | タブごとに音を消す | MUTE_AUDIO（webkit 1.15.0 で可、WebView 122 以降） | 小 | なし |
| 6b [#4](https://github.com/ajichama/CitrusBrowser/issues/4) | メディア通知とバックグラウンド再生 | 通知から再生・停止。画面を消しても再生を続ける | JS で再生状態を拾い、Media3 で通知を出す（条件付きで可。minSdk 24 なので Media3 1.12 以降も使える） | 中 | 電池の消費が増える。初期値はオフを想定 |
| 8a [#5](https://github.com/ajichama/CitrusBrowser/issues/5) | プロファイル・プライベートタブの Cookie 分離 | ログイン状態をプロファイルごとに分ける | MULTI_PROFILE（webkit 1.9 以降で可、WebView 119 以降。minSdk とは関係しない） | 大 | 既存のタブの保存形式に影響する。移行の設計が必要 |
| 8b [#6](https://github.com/ajichama/CitrusBrowser/issues/6) | タブグループ | タブをまとめて名前を付ける | 可（UI とタブの保存形式の拡張） | 大 | タブの保存形式に版付けが必要。タブの復元を壊さないこと |
| 9 [#7](https://github.com/ajichama/CitrusBrowser/issues/7) | Global Privacy Control | サイトに「追跡しないで」を伝える | Profile の addCustomHeader（webkit 1.15.0 で可、WebView 142 以降）で全リクエストにヘッダーを付けられる。Sec- で始まるヘッダー（Sec-GPC）を付けられるかは未確認 | 小 | なし |
| 10 [#8](https://github.com/ajichama/CitrusBrowser/issues/8) | uBO が既定で有効にしている追加のリスト | unbreak・quick-fixes・privacy・badware・urlhaus と、URL の追跡用パラメータを消すリストの購読 | 可（購読の候補に加える。破棄行の計測が必要） | 小 | 誤遮断を直すリスト（unbreak・quick-fixes）は利便性を上げる |

ほかの候補（まとめて [#9](https://github.com/ajichama/CitrusBrowser/issues/9)）:

- 未使用タブの自動クローズ
- ページの読み上げ（リーダーモードの本文を TTS で読む）
- リーダーモードの本文抽出を Readability.js（Apache-2.0）に置き換える
- PDF の内蔵表示（androidx.pdf は minSdk 28 のため、28 未満の端末は OS 標準の PdfRenderer か pdf.js（Apache-2.0）で代わりに表示する）
- デスクトップ表示時の UA と Client Hints の一致（setMobile 等は webkit 1.9 から使え、1.15.0 のままでもほぼ揃えられる。フォームファクタの上書きは webkit 1.16 以降）
- ユーザースクリプトの GM_* API（隔離ワールドへの注入は webkit 1.16 以降。minSdk 24 にしたため、webkit の更新で使える）
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
