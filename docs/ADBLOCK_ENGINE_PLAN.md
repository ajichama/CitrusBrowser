# 汎用広告ブロックの導入と既存機能の保持

更新日: 2026-11-19。サイトごとの広告経路を個別に追加することを主目標にしない。利用者の優先順位は、uBlock Origin相当の処理を内蔵できれば優先し、それが難しい場合は拡張機能で対応すること。

### 本体の購読と構文の拡張（2026-11-19）

新規初期化では Citrus に加えて EasyList・EasyPrivacy・AdGuard Japanese を有効な購読として登録する。本文は実行時に取得する。既存利用者には、旧版の既定リストに無かった EasyPrivacy・AdGuard Japanese のうち未登録のものだけを一度追加し、利用者の無効設定・取得履歴は維持する。EasyList は旧版の既定リストに常に無効で含まれていたため、既存DBに無ければ利用者の削除とみなして追加しない。無効・未取得の EasyList も有効化しないことを利用者と合意した。移行後に削除した購読は再追加しない。

uBlock filters（`https://ublockorigin.github.io/uAssets/filters/filters.txt`）を既定の購読に加えた（2026-11-19、利用者の判断）。反広告ブロック（Ad-Shield 等）への対策がこのリストにだけ含まれるためである。本文は実行時に取得し、APK に同梱しない。既定購読の移行は版ごとに追加する URL を持ち、利用者の記録した版より新しい版の URL だけを一度追加する（版2で uBlock filters）。版1の利用者が削除した EasyPrivacy などは復活しない。

uBlock filters の対応状況（2026-11-19 取得分、子リスト展開後）: 23,710 行のうち 6,153 行（26%）を破棄している（EasyList は 77,942 行中 315 行）。主な内訳は、未実装の scriptlet（nowoif 856、no-fetch-if 366、rpnt 235、no-xhr-if 199、nano-sib 196、aost 194、noeval-if 144、json-prune 118、nano-stb 111、ra 106 など）、procedural の要素フィルタ（`:style()` 623、`:has-text()` 209、`:upward()` 154、`:remove()` 74 など）、通信の制約（`redirect-rule` 375、未知の `redirect` 資源 338、`$domain` の entity 290、`denyallow` 247、`to=` 104、`csp` 65、`removeparam` 43 など）、HTML フィルタ `##^` 139。WebView で再現できないのは、応答ヘッダーを使う `csp`・`header`、サブリソースの `removeparam`、HTML フィルタ（`##^script:has-text()` は文書開始時の削除で近似できる）。それ以外は対応する方針とし、件数の多い順に進める。

同じ方法で計り直した結果（scriptlet 第2弾・procedural の対応後、2026-11-19）: uBlock filters の破棄は 6,153 行から 2,564 行（10.8%）、EasyList は 315 行から 26 行に減った。残りの主な内訳は、redirect-rule 375、未知の redirect 資源 338、$domain の entity 290、denyallow 247、trusted の rpnt 235、HTML フィルタ 139、to= 104、trusted-replace-argument 73、:style 69、csp 65。通信の条件（redirect-rule・entity・denyallow・to=・method）は、フィルタの保存形式と照合処理の拡張が要る。

種別を指定しない通信ルールは、ABP と同じく本文ページ（document）と popup に適用しない。汎用のパス規則で記事ページ自体が遮断画面に置き換わることを防ぐためである。パスを持たないホスト規則（`||host^`）と、`$document`・`$all` を明示した規則だけを本文ページに適用する（uBO と同じ扱い）。

ネットワーク構文の別名、`important`、全有効購読を横断する `badfilter`、既知の空資源への `redirect=` を実装する。利用者の許可ページは最優先し、その後に important・通信例外・通常遮断を照合する。未知資源や `redirect-rule`、未対応の制約を含む行は丸ごと破棄する。空資源応答は限定した近似であり、uBO の資源ライブラリを移植したものではない。キャッシュのレコード形式は v2 を保ち、構文の版を4へ進めて旧キャッシュを再生成する。

`!#include` は同一 origin・同じディレクトリ配下の相対パスだけを展開する。循環・深さ・取得数・総文字数を制限し、子の取得失敗や取消では旧キャッシュを保持する。未知の `!#if` 条件は否定・else も適用しない。子を展開した購読は親の Last-Modified による条件付き取得を行わず、子だけの更新も取り込む。[uBO の公式構文仕様](https://github.com/gorhill/uBlock/wiki/Static-filter-syntax)を確認したが、scriptlet・procedural・HTML filtering 等の完全互換は対象外である。

ビルド・単体検証の結果は [公開進捗](PROGRESS.md) に記録する。本変更の実機検証と、汎用要素非表示ルール増加時の注入負荷測定は未実施である。

### scriptlet の独自実装（第3段階、2026-11-19）

`example.com##+js(名前, 引数...)` を解析し、全フレームの文書開始時に実行する。uBO と名前・引数だけを合わせた Apache-2.0 の独自実装で、仕様は uBO wiki の公開文書（Resources-Library と Static-filter-syntax）だけを参照し、uBO の scriptlet のソースは参照していない。

- 対応は10種類と別名: set-constant、abort-on-property-read、abort-on-property-write、abort-current-script、prevent-setTimeout、prevent-setInterval、remove-node-text、set-local-storage-item、set-session-storage-item、addEventListener-defuser。未対応の名前・ドメインを限定しない行・壊れた構文は行ごと破棄する。引数の引用符は wiki どおり文字として扱う。
- 例外は `#@#+js(名前, 引数)`（別名を正規化して一致する行だけ）と `#@#+js()`（そのホストのすべて）。`$elemhide` の例外と、利用者の許可ページでは適用しない。要素の非表示を無効にした場合も適用しない。
- 注入の仕組み: 固定スクリプト（`module/adblock/src/main/assets/adblock/scriptlets.js`）を `WebViewCompat.addDocumentStartJavaScript` で全フレームに登録する。スクリプトはフレームの URL を同期 JS ブリッジへ渡し、要素フィルタと同じドメイン照合（entity・除外を含む）の結果を受け取る。ブリッジ名と照合用の鍵はプロセスごとに乱数で決め、取得後にページから外す。文書開始時の注入に対応しない古い WebView では、ブリッジをページに残さないよう scriptlet を使わない。フィルタの読み込み中は最大200ミリ秒だけ待ち、間に合わなければ何も適用しない（同期呼び出しの間はレンダラーが止まるため）。scriptlet は読み込み時に別の入れ物へ分け、フレームごとの照合で汎用の CSS 規則をたどらない。
- 第2弾（2026-11-19）: prevent-window-open、prevent-fetch、prevent-xhr、adjust-setTimeout / adjust-setInterval、prevent-requestAnimationFrame、abort-on-stack-trace、noeval-if / noeval、json-prune、remove-attr / remove-class、nowebrtc、set-cookie、remove-cookie、window.name-defuser、disable-newtab-links、href-sanitizer（変換手順つきの指定は未対応）を追加し、構文の版を6へ進めた。trusted の scriptlet（rpnt 等）は、信頼できる出典（uBO 自身のリスト）だけに許す仕組みを作るまで破棄する。nobab・popads-dummy・prevent-refresh・close-window は仕様が不足しているため未対応。
- 配布元の予備: AdGuard のリスト（`filters.adtidy.org/extension/ublock/filters/*.txt`）は、接続できない・5xx 等の一時的な失敗のときだけ、AdGuard 公式の FiltersRegistry（GitHub）の同じファイルから取得する。購読の識別と保存は元の URL のまま、予備の更新日時は条件付き取得に使わない。404 等の恒久的な失敗や、利用者が追加した任意の URL では予備を使わない。
- procedural の要素フィルタ（2026-11-19）: :has-text・:upward・:nth-ancestor・:xpath・:matches-attr・:matches-css(-before/-after)・:matches-media・:matches-path・:matches-prop・:min-text-length・:others・:watch-attr と、procedural を含む :has・:not、末尾の :remove・:remove-attr・:remove-class・:style に対応した（別名 :-abp-has・:-abp-contains・:contains）。scriptlet と同じブリッジで全フレームに渡し、CSS だけの :style はスタイルシート、それ以外は要素の追加・削除のたびに再評価して、隠す要素には inline の display:none!important を付ける（条件から外れたら元に戻す）。ドメインの指定は必須、1行に複数の procedural セレクタを並べる行・HTML フィルタ（##^）・文書に説明のない :if・:if-not・:spath は破棄する。:style は url(・image-set(・コメント・バックスラッシュ・//・{}・< を含む宣言を破棄する。構文の版を7へ進めた。
- 通信の条件（2026-11-19）: `$domain=` の entity（`example.*`、除外を含む。公開サフィックスで照合）、`$denyallow=`、`$to=`（entity・除外を含む）、`$method=`（除外を含む）、`$redirect-rule=`（それ自体では遮断せず、別のルールで遮断したときの資源だけを決める）、`redirect` の優先度の付記（`:5` 等）と資源の別名（nooptext、2x2.png・3x2.png・32x32.png は空の画像で近似）に対応した。追加条件は既存のレコード形式の文字列欄に挟んで保存し、旧形式も読める。構文の版を8へ進めた。代替スクリプト（google-ima.js 等）・音声や動画の資源・要素非表示の例外での entity は未対応で、行ごと破棄する。
- trusted の scriptlet（2026-11-19）: wiki の規定どおり、信頼できる出典（uBO 自身の uAssets の配布先と本体同梱の Citrus filter）の購読からだけ受け付ける。利用者が追加した任意のリストの行は破棄する。対応は trusted-replace-node-text（rpnt）と trusted-set-local-storage-item / trusted-set-session-storage-item。構文の版を9へ進めた。trusted-replace-argument・trusted-json-edit 等は未対応。
- procedural の補足（2026-11-19）: 先頭の CSS はセレクタの並び（`#a, #b:others()`）でもよく、procedural の後ろに続く通常の CSS 疑似クラス（`:is()`・`:not(.a)` 等）は CSS の続きとして扱う。wiki が未対応とする procedural の連結（最初の procedural より後ろのカンマ）は引き続き破棄する。構文の版を10へ進めた。
- 無音の音声の資源（2026-11-19）: `redirect=noopmp3-0.1s` / `noop-0.1s.mp3` に、自作の約0.1秒の無音 MP3（MPEG-1 Layer III・128kbps・44.1kHz・モノラルの無音フレームを4つ並べた1,668バイト、assets/adblock/noop-0.1s.mp3）を返す。WebView で音声として読み込めることを計測テストで確認した。動画（noop-1s.mp4）と代替スクリプト（google-ima.js 等）は未対応。構文の版を11へ進めた。
- 独立監査で残した課題: ドメインのない `#@#+js(...)`（汎用の例外）は破棄している。`rmnt` に `stay` を付けると、要素の追加のたびに部分木をたどる。許可ページの判定は主フレームの通信の横取りで更新するため、横取りのない遷移（戻る操作など）では前のページの判定が残る。about:blank の iframe で固定スクリプトが動くかは未確認。
- 保存は要素フィルタの束に同居させ、構文の版を5へ進めて旧キャッシュを再生成する。
- 反広告ブロック（Ad-Shield）: livedoor 系の対策（`html-load.com` の遮断と `rmnt`）は uBlock filters にあり、既定の3リスト（EasyList・EasyPrivacy・AdGuard Japanese）には無い。uBlock filters を購読すれば本体でも効くことをエミュレーターで確認した（[実機確認](DEVICE_CHECKS.md)）。既定の購読に加えるかは未決定。

## 現在地

公開基準は`14685f9e`。比較基準`f5f2ae8b`で移行・Google検索・Material UI・広告遮断の基礎改善を公開済み。通常単体247件・buildSrc15件とrelease構成、Android15のSO-51D、WebView157.0.8103.50で計測41件が成功した。

ただし、これはuBlock相当の広告遮断を完成した結果ではない。広告を有効にして既知の枠が非表示になっても、別系統の追従広告が残った。Yuzuの実バックアップも未作成であり、実データ取込は未確認。

内蔵方式は独立APKの実機起動まで進んだ。公式uBO原本671ファイルの内容とAPKを照合し、言語資産も保持している。通常単体249件・buildSrc25件、全体ビルドとrelease、通信を切った通知生成が成功した。SO-51Dの試作ではuBO 1.76.4の有効表示とページ表示を確認した。

実機計測3件は、テスト条件の修正後に4.815秒で全成功した。標準EasyListによる広告通信遮断と要素非表示を、親文書・子フレームの両方でON/OFF比較した。正常scriptと本文の表示も必須とし、全通信の失敗を遮断成功と誤認しない。画面再作成でSession・DOM・入力・JS状態・読込回数を保持し、危険な外部Intentを拒否すること、同じプロセス内で画面を再起動しても無効状態を保持すること、通知gzipの境界も確認した。

初回失敗は原本EasyListのloopback向けgenerichide例外と、外部Intent試験後のActivityScenario照合が原因だった。原本や製品側の挙動を変えず、例外と衝突しないloopbackと検証後のIntent復元でテストを修正した。今回の成功は既存プロファイルでの計測であり、新規プロファイルの冷間起動、OS再起動、scriptlet・procedural・MyFilters、実サイト全般、本体への機能移植は未完了。

後続では標準MyFiltersタブとpopupの描画を確認した。また、対象アプリのforce-stop後に別PIDで再起動し、uBOの無効状態を保持したことと元の有効状態への復旧を手動確認した。有効状態1項目の保持であり、フィルタ本文・タブ状態等をまとめて復元確認済みとはしない。

### scriptlet・procedural 検証の方式変更（2026-11-19）

別UIDのclipboard経由でMyFiltersを読み書きする`gecko-ui-probe`は読み取り段階で凍結した。代わりに`gecko-preview`の計測`GeckoDisposableProfileScriptletTest`を、既存3件とは別の`am instrument -e class`で実行する。

- profile: GeckoViewは1プロセスでGeckoを1回しか初期化できない（`GeckoRuntime.create`は稼働中のGeckoがあると失敗する）。そのため`GeckoRuntimeHolder`で最初のRuntime生成前だけ設定を予約し、`-profile <アプリcache内の新規dir>`と`configFilePath("")`で使い捨てprofileを使う。通常起動は従来の`getDefault`のまま。既にRuntimeがあるプロセスでは失敗させ、利用者のprofileを使わない。
- フィルタ投入: uBO自身がabp:subscribeで開く購読確認ページ（asset-viewerの`subscribe=1`）を表示し、a11yで「購読」を押す。`set-constant`の`true`と`:has-text`は原本の`requiresTrust`・`trustedSource`の対象外のため、MyFiltersの信頼設定に依存しない。private RPC・storage直書きは使わない。
- 判定: `127.0.0.2`のloopback fixtureで、nonce付きのプロパティ・class・照合文字列を親文書と同一originの子フレームで測る。購読前・購読後・uBO無効をON/OFF比較し、文書の再読込なしと正常JS・controlの表示を必須にする。
- 初回cold起動: 有効表示ではなく、同梱EasyListの`/microad.js$script`の実遮断（正常scriptの到達も必須）で準備完了を判定する。購読リストの反映も、nonce付きsentinel規則の遮断で確認する。

あわせて、プレビューのdebug APKだけarm64-v8a・x86_64へ絞った（release は3 ABIのまま）。debug APKは541MBから403MBになった。

SO-51D（Android15）での実機結果は次のとおり。ABIを絞ったAPKで既存3件が成功し、新しい計測1件も別プロセスで成功した（29.4秒、失敗・スキップ0）。

- 使い捨てprofileが実際に使われ、初回cold起動から同梱EasyListの実遮断が始まるまで約7.8秒だった。
- 標準の購読確認ページからテスト用リストを購読でき、sentinel規則の遮断で反映を確認した。
- 親文書・子フレームの両方で、uBO有効時は`set-constant`と`:has-text`が効き、無効時は効かなかった。controlの表示と正常JSの実行も保持した。

初回の実行（48.2秒）は`:has-text`の判定だけ失敗した。原因は、fixtureが`textContent`で文字だけを書き換えていたことにある。uBO 1.76.4の`contentscript.js`（`safeObserverHandler`）は、要素ノードの追加・削除だけを再判定の契機にし、テキストノードだけの変化は無視する。実際の広告と同じく、一致する文字を持つ要素を追加する形にfixtureを直した。製品とuBO原本は変えていない。初回の失敗は成功件数に含めない。

## 方式の判断

| 方式 | 確認した範囲 | 今回の扱い |
| --- | --- | --- |
| 現WebViewへuBO coreを追加 | 公式npm版は静的ネットワーク照合が中心。uBO全体の代替ではない | これだけで「uBO相当」としない |
| 現WebViewへadblock-rust等を追加 | 汎用コアを再利用できるが、フレーム別実行・注入時期・スクリプトレット等の接続は別途必要 | 単なる差替えでの同等性は確認できていない |
| GeckoViewに本物のuBOを同梱 | 公式のbuilt-in WebExtension APIでアプリに含められる。利用者の手動インストールを必須にしない | まず独立構成で実動作を検証する内蔵候補 |
| 利用者が拡張を導入 | 拡張の署名・権限・更新・設定UI等を扱う必要がある | 内蔵候補の実証結果を見て必要な範囲を判断する |

GeckoViewでWebExtensions機構を内部利用することと、利用者に拡張の手動導入を要求することは区別する。今回の初段は内蔵uBOであり、任意のFirefox拡張への対応完了を意味しない。

この検証の成立だけで本体エンジンの変更を決定しない。既存WebViewを保つ汎用コア方式も、実装可能な接続範囲と共通fixtureで比較する。遮断結果に加え、既存機能を維持する負担と更新性を判断材料にする。

## 独立した検証構成

- `:module:gecko`がRuntime・Session・内蔵uBO制御を持ち、`:gecko-preview`から実行する。
- GeckoViewの現安定版はAPI26以上とcompileSdk37.2を要求する。既存API23以上の本体と依存グラフ・保存領域を保ち、検証版だけを別applicationIdで構築する。
- 公式配布のGeckoViewとuBOを版・SHA-256で固定する。取得・展開した資産とビルド出力はDocker Volumeへ置き、ソースツリーへ大量の展開物を作らない。
- uBOの原典・GPL本文・対応ソースとビルド入力を示す。アプリ同梱uBOの更新と、uBO自身のフィルタリスト更新を混同しない。

独立版は、本体機能を移植し終えた代替アプリではない。本体への統合前に技術的な成立性を確認するためのもの。

## 既存機能を保持する接続の第一段階

`module:engine-api`にAndroid/Geckoの型を公開しない操作契約を置き、最初は戻る・進む・更新・停止だけを扱う。既存`TabData`が同じ`CustomWebView`を参照するadapterを所有し、`ActionExecutor`の該当操作をその契約へ接続する。履歴キャッシュのwrapperを通し、内部のWebViewへ直接展開しない。

保存形式・操作ID・ナビゲーションロック・戻れない場合の既定操作は維持する。読込中かどうかは既存タブの状態から取得し、進捗100%を読込完了と推定しない。View生成/破棄・保存・進捗購読・URL読込・Gecko接続はこの最初の契約へ含めていない。標準ビルドと通常単体255件が成功し、本体の実機回帰3件でも実際の操作経路を確認した。

後続ではURL/POST/新window、版付きタブ復元、download/SAF filechooser、UserJSとサイト設定、証明書・認証・権限、印刷を順に分離する。旧タブBundleは既存WebViewで読み続け、ログイン状態やPOSTを別エンジンへ無言で移さない。未実装の操作を成功扱いせず、共通fixtureと既存データの保持を確認してから接続範囲を増やす。

## 完了条件

1. 起動・導入成功だけでなく、本物uBOによる通信遮断、要素非表示、スクリプトレット、フレーム内の処理を確認する。
2. 特定サイト専用ルールに頼らず、共通fixtureと複数サイトで有効時・無効時を比較する。
3. popup・設定・有効切替が実際に動き、再起動後も設定と有効状態を保持する。
4. 失敗や無効状態を保護中と表示しない。更新時も既存の設定・データを失わない。
5. 既存のツールバー・操作ID・ジェスチャー・ブックマーク等を保持するエンジン境界を作り、機能ごとに本体統合を検証する。

Cookie、POST、DOM状態、WebView履歴を別エンジンへ無言で移し替えない。ダウンロード、印刷・PDF、UserJS、サイト別設定等は対応表を作り、未移植の機能を黙って削らない。

## 一次資料

- [Mozilla: アプリ同梱拡張とWebExtensions](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html)
- [Mozilla: WebExtensionController](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html)（NightlyのAPI表であり、採用安定版とは別に照合する）
- [Mozilla: GeckoViewのAPI26要件](https://bugzilla.mozilla.org/show_bug.cgi?id=1982312)
- [uBO coreの範囲](https://github.com/gorhill/uBlock/tree/master/platform/npm)
- [adblock-rustの公式仕様](https://github.com/brave/adblock-rust)
- [uBlock Originの原典とライセンス](https://github.com/gorhill/uBlock)
