# 汎用広告ブロックの導入と既存機能の保持

更新日: 2026-11-18。サイトごとの広告経路を個別に追加することを主目標にしない。利用者の優先順位は、uBlock Origin相当の処理を内蔵できれば優先し、それが難しい場合は拡張機能で対応すること。

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
