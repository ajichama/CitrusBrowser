# 要素非表示とサイト別広告の検証範囲

2026-11-17 の調査と実装。広告全体の遮断と特定の回帰の成功を区別して記録する。

## 再現条件と観測

確認対象は広告の多い実サイトのトップと各記事、特にスマートフォン版。ブラウザと広告ブロック方式によって遮断結果に差が見られた。購読・例外設定はこの観測だけでは確定できない。

Android Mobile UA と PC UA の公開 HTML、記事ページを読み取った。サイトが公開する HTTP canonical を使用し、HTTPS の証明書検証は緩めていない。ページ内容や広告は変化するため、取得 HTML 全文はソースへ含めない。

| 観測した対象 | 対応 |
| --- | --- |
| 広告配信用スクリプト | 対象の実サイトに限定し、script 要求だけを遮断 |
| `#ad` と `display:block !important` | 一致要素の inline display だけを補正。記事 DOM は削除しない |
| 広告枠を示す属性・クラス | サイト指定の CSS で広告枠を非表示 |
| PC 表示の広告枠 | 観測した枠だけにサイト指定 CSS を適用 |
| サイト内の広告設定スクリプト | 調査時は広告設定値が空。必要性を確認できない遮断は追加しない |

既存の内蔵リストには要素非表示ルールがなく、広告リストの既定構成は Citrus の基本リストのみ。EasyList・豆腐等の追加購読は既定では無効。広告ブロック自体の既定 OFF と利用者が保存した選択は変更しない。設定画面には ON/OFF と基本リストの範囲を表示し、`abpUseElementHide=true` の意味に合わせて要素非表示スイッチを肯定形にする。

## エンジンの変更

通常の要素非表示は DOMContentLoaded の一回だけ `remove()` する方式から、ページ中に残る CSS の `display:none!important` へ変更した。後挿入、class 変更、標準 `:has()` の条件変化は WebView の CSS 再照合へ任せる。本文・フォーム・JS 状態を保持し、無関係な引用や広告でなくなった要素を削除しない。

1. 各セレクターを空の DocumentFragment で個別に構文検証して CSSOM へ追加する。構文確認のために全実 DOM を selector ごとに走査しない。不正 CSS、拡張構文、CSS ルールを注入しようとする文字列は他の有効ルールを止めない。
2. `#@#`、`elemhide`、`generichide`、汎用フィルタ無視の選択は CSS の生成前に既存の規則で反映する。
3. 作者 origin の CSS は inline の `display:block!important` に勝てない。この実観測に対してだけ、inline 競合候補を確認し一致要素の display を補正する。CSP で style sheet が使えない場合も対象だけを補正する。
4. 補正用 Observer は pending 最大 512 要素、mutation callback の近傍候補採取は最大 128 record に制限する。近傍候補・既存補正の再確認・TreeWalker の巡回を合計して50ms ごとに最大64要素を処理する。上限超過分は候補を捨てて終わらず、再開可能な DOM 巡回で最後まで確認する。属性・子要素・文字データの変更は新しい巡回を予約し、`:has()`、兄弟結合、`:empty` の影響も拾う。進行中の巡回は巻き戻さず、候補経路を交互に処理して飢餓を避ける。通常 CSS の全 selector × 全 DOM の照合を mutation ごとに同期実行しない。
   同時に保持する inline 補正は512要素まで。大量の実広告・CSP でこの上限を超えるページや、DOM変更が永続的に続くページでの適用遅延・限界は残る。
5. 一致しなくなった自己所有の display 補正は戻す。再注入時は前の Observer・timer を停止して自己所有補正を戻し、style を一つに置換する。DOMContentLoaded を取りこぼすページには onPageFinished で再適用する。

内蔵 asset は Version 2 へ進める。更新処理は asset 版の違いを検出して既存束を再生成する。更新完了後、既存ページは再読み込みする。

## 対応を広く解釈しないための比較

[AdGuard 日本語フィルタ](https://github.com/AdguardTeam/AdguardFilters/blob/master/JapaneseFilter/sections/specific.txt) の該当サイトには通常の CSS と標準 `:has()` を使うルールがある。[豆腐フィルタ](https://github.com/tofukko/filter/blob/master/Adblock_Plus_list.txt) には通常 CSS に加え `#?#` と `:-abp-has()`、`:style()` が含まれる。ルール全文や第三者 JS は取り込んでいない。

今回、標準 `:has()` は WebView が対応していれば通常 CSS として適用する。`#?#`、`:-abp-has()`、`:has-text()`、`:style()`、`+js(...)`、scriptlet、HTML response の書換えは実装していない。uBlock Origin との表示差がすべて解消したという主張はしない。これらの必要性は実ページの残存広告と該当購読ルールを結びつけて判断する。

公式仕様は [ABP の要素非表示と remove の区別](https://help.adblockplus.org/adblock-plus-help-center/how-to-write-filters) と [uBO の通常 CSS / 手続き型の区別](https://github.com/gorhill/uBlock/wiki/Procedural-cosmetic-filters) を参照。第三者配信全体を無条件に止めず、一般記事・画像・コメント・他サイトへの影響を回帰で確認する。

## 回帰と実行

`BundledSiteRulesTest` は実 asset を読み、script/domain 制約、記事・画像・共通 JS・コメント非遮断、要素ルールのサイト境界、標準 `:has()` と未対応構文の分離を検証する。既存の実 asset による direct/index 同値比較は、network と cosmetic の両方をルール件数へ含めて維持する。

`CosmeticFilteringWebViewTest` は外部通信のない実 WebView fixture で次の 7 件を検証する。コンパイル成功と端末上の成功は別に記録する。

- 観測属性を使う広告枠、後挿入 inline important 広告、記事 DOM・入力・JS 状態の保持。
- class 変更時の補正復旧、要素例外、再注入しても style が増えないこと。
- 標準 `:has()` による後挿入カードだけの非表示と通常引用保持。未対応 WebView ではこの一件を skip とする。
- 不正セレクターや CSS 注入を隔離し、残りの有効ルールと記事を保つこと。
- head の CSP で style タグが制限されたページの初期要素と後挿入要素。
- 600個の styled 通常要素の後の初期広告、700件を超える mutation の最後に置く inline important 広告。
- 深い子の属性・前の兄弟の class・文字データ変更時の、文脈に依存する inline 補正と解除。

Docker による対象検証:

```sh
./gradlew :module:adblock:testDebugUnitTest :module:adblock:assembleDebugAndroidTest
```

計測対象クラスは `io.github.ajichama.citrusbrowser.adblock.core.CosmeticFilteringWebViewTest`。実サイトでの目視確認は購読の有効状態・更新日時・UA・WebView provider とともに別途記録し、制御 fixture の成功へ置き換えない。
