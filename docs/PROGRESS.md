# 開発進捗と検証範囲

更新日: 2026-11-28。CitrusBrowser として 1.0.0〜1.0.10 を GitHub Releases に公開しました（Google Play で公開するまではプレリリースの扱い）。各版の内容と確認の範囲は、下の「公開した版と確認の範囲」にまとめています。その下の節は、11月19日までの検証の記録です。今後の進め方は [ROADMAP.md](ROADMAP.md) の「方向性と進める順番」と GitHub Issues #30 にあります。

## 公開した版と確認の範囲

| 版（公開日） | 主な内容 | 確認の範囲 |
| --- | --- | --- |
| 1.0.0（2026-11-20） | 広告ブロックの強化（独自の scriptlet・procedural・通信の条件・redirect-rule・removeparam/urlskip・代替資源）、Yuzu Browser の書き出しの取り込み、リンクを対応アプリで開く、ページ情報とサイトのデータの消去、画像のコピー、minSdk 24、リリース署名、プライバシーポリシー | Docker のビルドと単体テスト。広告ブロックはエミュレーター（API 37）と実機、取り込みは実機（下の各節） |
| 1.0.1（2026-11-20） | 閉じたタブの復元の初期値（#10）、ポップアップのブロックの初期値（#11）、画像の共有でダウンロードフォルダを使わない（#14）、画像リンクの長押しの不具合（#19）、ポップアップの許可を IP アドレス・社内ホストでも使える | Docker のビルドと単体テスト（端末での確認の範囲は各 Issue の対応コメント） |
| 1.0.2（2026-11-21） | プライベートタブ（#15。WebView のプロファイルで Cookie・データを分ける）、メニューの整理と PC 版サイトの切り替え（#16。既定のメニューは新しく入れたときだけ変える） | Docker のビルドと単体テスト（端末での確認の範囲は各 Issue の対応コメント） |
| 1.0.3（2026-11-21） | 証明書エラーの警告を2段階に（#12）、アップロードでのカメラ撮影（#13）、タブの一覧の札にプライベートの印（#15）、ズーム禁止の無視（#17、既定はオフ）、QR コードの読み取り（#18、CameraX と ZXing）、長押しの独自メニューの編集の不具合（#20・#21） | Docker のビルドと単体テスト、エミュレーター（API 37）。実機は未確認のものが多い（各 Issue の対応コメントに範囲を記録）。QR の実際の読み取りは実機での確認が要る |
| 1.0.4（2026-11-21） | 新しく入れたときは広告ブロックを有効にする | Docker のビルド。実機で 1.0.3 からの上書き更新と、新規インストールで設定に触れずに広告を遮断することを確認 |
| 1.0.5（2026-11-26） | タブを閉じると固まる不具合（#31。1.0.0 からの退行）、1.0.0 以降に足した文言を12言語に（#22）、取り込みに失敗したときの理由をログに残す | Docker のビルドと単体テスト。#31 はエミュレーター（API 37）で修正前の応答なしと修正後の解消、実機で 1.0.4 の応答なしと修正版での解消を確認。翻訳は画面では未確認 |
| 1.0.6（2026-11-26） | 横向きで検索欄が全画面入力にならない（#26）、ブックマークをフォルダごとに名前順に並べ替える（#33）、ブックマーク画面の中の検索（#34） | Docker のビルドと単体テスト、エミュレーター（API 37）。実機は未確認。#33 の日本語の題名の並びと、#34 の結果からの編集は画面では未確認 |
| 1.0.7（2026-11-26） | 設定の中の検索（#25）、クイックコントロールで指を置いた項目の名前を表示（#32、既定はオン）、画面の上を動かせるフローティングツールバー（#35、既定はオフ） | Docker のビルドと単体テスト、エミュレーター（API 37）。実機は未確認。#25 の日本語の画面は未確認 |
| 1.0.8（2026-11-26） | スピードダイヤルのタイルを長押しして並べ替える・専用のメニュー（#39）、フローティングツールバーでスワイプ中に操作の名前を表示（#40、既定はオン） | Docker のビルドと単体テスト、エミュレーター（API 37）。実機は未確認 |
| 1.0.9（2026-11-27） | クイックコントロールを使える状態に: 新しく入れたときの既定の項目と、空のまま有効にしたときの案内（#36）、項目の変更を起動し直さずに反映（#37）、ジェスチャーナビゲーションでも開ける（#38、端の帯だけで開く・少し内側で開くを選べる）。操作を選ぶ画面の検索（#42、ひらがなとカタカナを区別しない照合を設定の検索にも） | Docker のビルドと単体テスト、エミュレーター（API 37）。実機は未確認 |
| 1.0.10（2026-11-28） | ログインの状態（Cookie・サイトのデータ）とキャッシュを、バックアップと機種変更の移行から外す（#27。ブックマーク・履歴・設定などはこれまでどおり移る）。ボタンの設定の一覧で、カスタムアクションに付けた名前を出す（#43）。アプリの設定の画面を開くと使われない値が保存されていたのを直す。操作の配列の整合と、起動・タブ・設定の全画面の自動テストを追加（#23） | Docker のビルドと単体テスト、エミュレーター（API 37） |

2026-11-28 時点の main では、Docker の中で `./gradlew assembleDebug testDebugUnitTest` が成功しています（通常93スイート・385件、失敗・error・スキップ0）。

## 11月19日までの検証の記録

以下は 2026-11-19 時点の記録です。本体の既定購読とネットワーク構文を拡張し、Docker の本体ビルド・通常単体298件が成功しました。従来本体の比較基準の版（2026-11-17）では実機の計測41件が成功しています。

新規利用者には EasyList・EasyPrivacy・AdGuard Japanese を有効登録し、既存利用者には旧版に無かった EasyPrivacy・AdGuard Japanese の未登録分だけを一度追加します。EasyList は旧版の既定リストに常に含まれていたため、既存利用者に無ければ削除とみなして復活させません。無効・未取得の EasyList を含め、既存の無効設定は維持します。種別を指定しない通信ルールは本文ページと popup に適用せず、パスを持たないホスト規則と `$document` 明示の規則だけを本文ページに適用します。別名・important・badfilter・限定した空資源への redirect と、安全な `!#include` の展開を実装しました。購読本文は実行時に取得し、完成APKにテスト用のリスト抜粋が含まれないことを確認しました。詳細と非対応範囲は[内蔵uBOの検証計画](ADBLOCK_ENGINE_PLAN.md)を参照してください。

固定した抜粋の破棄行数は EasyList 48行中5行（変更前と同じ）、AdGuard Japanese 54行中6行（変更前15行）、uBO filters 62行中29行（変更前47行）でした。単体テストで行数・破棄数を固定しています。未対応の制約は行ごと破棄し、scriptlet・procedural等の完全互換や、実サイトの広告残存解消を示す結果とは扱いません。

追加の `:app:assembleDebugAndroidTest :module:adblock:assembleDebugAndroidTest` を含む最終ビルドも成功しました。実Room・実アセットで初期購読と既存無効保持を確認する計測2件と、既存の更新回帰9件（Worker5件・本体の更新2件・HTTP購読2件）は、API37エミュレーターで11件すべて成功しました（失敗・スキップ0）。実機での再確認と実サイトでの誤遮断の確認は未実施です。

API37エミュレーターでの起動と描画を確認しました。API37.2・16KB版の起動は未確認です。

比較基準の版（2026-11-17）は独立レビューと通常単体247件・buildSrc15件、debug・全計測APK・releaseのR8/lintを通過しています。日本語・記号検索、メニュー・設定・IMEの一部も実機で確認しました。実際のYuzu Browserの書き出し（標準バックアップと別書き出しのスピードダイヤル）を本体へ取り込めることを実機で確認しました。広告対策はサイト個別ルールを追加する方針から、汎用処理を内蔵する方針へ修正しています。[内蔵uBOの検証計画](ADBLOCK_ENGINE_PLAN.md)と[改善一覧](IMPROVEMENT_PLAN.md)を参照してください。

11月18日の変更では AGP9.5.1 / Gradle9.6.1 と独立した Gecko/uBO プレビューを追加しました。全体ビルドと最終の標準タスク再検証が成功し、通常249件・buildSrc25件を保持しています。JUnit XMLは失敗・error・スキップ0で、全計測APKと明示指定のプレビューdebugも生成しました。通信を切った通知再生成も成功しました。これは比較基準の版（2026-11-17）の247件・15件・実機41件とは別の検証結果です。

プレビューは別 applicationId・保存領域の minSdk26 / compileSdk37.2 で、本体の minSdk24（2026-11-20 に 23 から変更）/ compileSdk37・targetSdk37 を維持します。GeckoView160 と原本 uBlock Origin1.76.4 を内蔵し、debug/release の最終 APK と生成資産672ファイルの一致を確認しました。実機では起動と「uBO1.76.4 有効」の表示に加え、修正後の計測3件も失敗/スキップ0で成功しました。保存済みprofileでの標準EasyListのnetwork/cosmetic・画面再生成等に限定した結果で、本体の既存全機能をGeckoへ統合済みという意味ではありません。上記の検証項目を完了しました。

## フェーズ別の状態

後続の共通操作境界では、既存WebViewの戻る・進む・更新・停止をAndroid/Gecko型を公開しないAPIへ接続しました。保存形式・操作ID・履歴ロック・既定操作を保持する独立レビューと、対象単体/コンパイルが成功しています。標準`assembleDebug testDebugUnitTest`も成功し、通常71suite・255件のJUnit結果で失敗・error・スキップ0を確認しました。本体の実機回帰3件も失敗・スキップ0でした。履歴移動、明示/切替操作の更新・停止、履歴ロック、戻れない場合の既定操作を実`ActionExecutor`経由で確認した結果であり、Geckoへの本体差替えではありません。

内蔵uBOの標準MyFilters/popupの描画と、対象アプリのforce-stop後に別PIDで無効状態が保持されることも確認しました。有効状態は元へ復旧済みです。OS再起動やMyFilters本文の保持をまとめて証明したものではありません。

| 対象 | 実装・完了事項 | 未完了・検証の限界 |
| --- | --- | --- |
| Phase 1: リポジトリ・ビルド基盤 | Yuzu Browser のコミット履歴・原典の帰属を継承。Docker の中でビルド | ビルド生成物を公開ソースへ混入させない |
| Phase 2: 命名・独自アイコン | 表示名、パッケージと物理配置、権限、内部識別子を Citrus へ移行。旧保存データの読み込み互換を維持。独自ランチャー・通知アイコンを実装し、APK の PNG 10点・148,608画素の一致と adaptive / round / monochrome・通知の参照を確認 | 各 OS・ランチャー・通知上の実機表示は未確認 |
| Phase 3: 正式 API 37 構成 | 今回の構成は AGP9.5.1 / Gradle9.6.1。Kotlin2.4.21 / Hilt2.60.1 / KSP2.3.12・JDK21と built-in Kotlin / legacy-kapt を維持。本体24/37（当時は23/37）と独立プレビュー26/37.2を分離し、37.2非対応警告0でビルド成功。公開版は実機でデータ保持更新・起動・HTTPS表示を確認 | 他OSでの起動と新プレビューの全機能移植は未確認 |
| Phase 3: 生成物・署名 | SDK 別名ディレクトリと非対応警告抑止を削除。Wrapper 更新と公式 SHA-256 照合 | APK の生成成功は全 OS での互換保証や本番署名の用意を意味しない |
| Phase 3: OSS 通知 | 本体runtime102依存・114文書とソース由来18項目を保持し、AGP更新でDataBinding5座標を9.5.1へ置換。独立プレビューは別の52依存・68文書。通常生成と両アプリの通信を切った再生成が成功。非OSSのGMS規約を含むプレビューは「ライブラリのライセンス」と表示 | 公開版のInsets回帰と新Material部品は実機で成功。新プレビューの通知画面の実描画は別途確認する |
| Phase 4: OS 互換基盤 | compileSdk / targetSdk 37、Edge-to-Edge、Predictive Back、Intent/Bundle/Parcel 互換、JobIntentService 廃止、ストレージ・可視性対応を実装。公開版の実機で計測41件と限定UIスモーク成功 | 他OS・詳細IME・API37権限・実再起動/Dozeを含む横断確認は未実施 |
| Phase 4: LAN権限 | API37以上だけに、OSの実許可状態を表示する明示設定を追加。利用者が選んだ時だけ要求し、拒否後は任意でOS設定へ進める | API37での許可・拒否・取消は未確認。通信先の自動判定は行わず、LAN利用前に設定する。[対応範囲](../legacy/LOCAL_NETWORK_PERMISSION.md) |
| Phase 4: ページ配色 | アプリ UI と独立した明色・サイト暗色・サイト優先の自動暗色を実装。旧値3を2へ移行し、サイト未指定−1を保持。実機の計測5件でDOM・入力・JS状態・読込回数の保持、独立配色、旧key移行、PixelCopyによる暗色化を確認 | 他OS・WebView providerでの描画、実サイトとサイト別設定UIの操作は未確認。UI テーマ全体の刷新も継続課題 |
| Phase 5: AdBlock 互換・保存 | 初回のパーサ改善と一括保存の安全性を改善。キャッシュ v2 の 32bit 保存・v1 読取、旧 snapshot 保持、行単位ドメイン条件の最具体指定優先、elemhide / generichide、汎用要素フィルタ無視設定を実装 | 今回の追加互換回帰16件を含む単体テストに成功。実サイト・端末での確認と、拡張 uBlock 構文の対応拡大は残る |
| Phase 5: 更新ジョブ | minSdk 23 を保ち WorkManager 2.11.2 へ移行。自動要求の重複抑止、手動要求の実 UUID、再試行、通信取消、画面再表示・復元時の Flow 観測を実装。実機でWorker等の計測5件と、本体Hilt経由のfile://購読統合2件が成功 | 外部HTTP購読、実プロセス再起動・Doze・Wi-Fi制約・実画面の復元は未確認。DB とファイルをまたぐ処理の強制終了時の限界は[更新仕様](../module/adblock/UPDATE_JOBS.md)へ記載 |
| Phase 5: 判定・索引 | main frame・画像・script・不明種別を分離。PSL の null、IPv6、IDN、単一ラベル host の第三者判定を修正。部分一致・例外の索引漏れと同一 bucket の重複走査を修正。11月16日の基準版では内蔵800ルール・43要求で直接照合との一致を確認 | 実サイトの誤遮断、大規模リスト・端末の性能測定が残る。基準版のfallbackは41%。今回の追加ルールへの測定値として扱わず、JVMの比較回数を実環境の速度向上率とみなさない |
| Phase 5: モバイル広告 | 公開版の実WebView回帰7件成功。今回の変更で設定反映と初回主文書判定を修正し、広告単体86件成功。設定リンク3aliasの実機1件は旧版失敗→修正版成功 | 公開版で既知枠の非表示を確認したが別系統広告が残存。汎用uBO互換の成功とは扱わず、独立プレビューで検証する。[対応仕様](../module/adblock/COSMETIC_FILTERING.md) |
| Phase 5: 内蔵uBOプレビュー | 本体と独立したGeckoView160/原本uBO1.76.4を内蔵。生成資産672ファイルを最終APKと照合。保存済みprofileの実機3件で標準EasyListのnetwork/cosmetic ON/OFF・top/child、同session/DOMの画面再生成、同process再launch時の無効状態等を確認 | 新規profileの初回cold起動・実OS/process再起動・scriptlet・procedural・MyFilters・popup実描画は未確認。本体統合完了とはしない。[検証計画](ADBLOCK_ENGINE_PLAN.md) |
| データ移行 | 旧設定・ブックマーク・別書出しのスピードダイヤル・検索・UA/URL別設定・スクリプト・ジェスチャーを検証後に統合。既存を保持し参照IDを対応付け、通常失敗時は自己所有の変更を復旧 | 実際のYuzu書き出しを実機で取り込み、全カテゴリを確認。外部テーマ画像・他アプリのSAF権限等は移せない。跨る保存領域の電源断原子性は保証しない。[対応形式と制約](../legacy/IMPORT_COMPATIBILITY.md) |
| Google検索 | 既定をgoogle.comへ更新。検索単体21件と公開版の実機4件が成功。端末で日本語・記号の検索結果も確認 | 詳細な候補操作・他OSは未確認。独自URLは保持。[検索仕様](../module/search/SEARCH_COMPATIBILITY.md) |
| Material UI | Material3のViewsテーマ、検索・設定・メニュー・アドレス欄・ホームを更新。公開版で明暗部品の計測2件とメニュー・設定・IME表示の限定スモーク成功 | 文字拡大・日本語文字合成・全画面/全テーマ・既存カスタマイズの全操作は未確認。[機能対応表](../module/ui/MATERIAL_UI.md) |

## 自動検証の結果

| 検証 | 再現コマンド | 対象と結果（比較基準版／今回の結果を区別） |
| --- | --- | --- |
| 無音の音声の資源（11月19日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 成功。通常78suite・314件、失敗0。エミュレーター（API 37）で ScriptletWebViewTest 6件（無音ファイルの再生時間が約0.1秒）・CosmeticFilteringWebViewTest 7件が成功。実機は未実施 |
| 第2弾以降の独立監査の反映（11月20日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 成功。通常78suite・315件、失敗0。エミュレーター（API 37）で計測16件成功。複数の実サイトのトップと記事ページで誤遮断なし（サイト自前の広告枠は残る）。実機は未実施 |
| trusted の scriptlet（11月19日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 成功。通常78suite・314件、失敗・error・スキップ0（出典による受け付けの可否）。エミュレーター（API 37）で ScriptletWebViewTest 5件・CosmeticFilteringWebViewTest 7件が成功。この変更分の実サイト確認と実機は未実施 |
| 通信の条件（entity・denyallow・to・method・redirect-rule）（11月19日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 成功。通常78suite・313件、失敗・error・スキップ0（各条件の照合・キャッシュの往復・不正な値の破棄・redirect-rule が単独で遮断しないこと）。エミュレーター（API 37）で実サイトの誤遮断なし。実機は未実施 |
| procedural の要素フィルタ（11月19日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 成功。通常77suite・310件、失敗・error・スキップ0（判定・検証・ドメイン必須・例外・抜粋の件数更新）。エミュレーター（API 37）で ScriptletWebViewTest 4件（procedural を含む）・CosmeticFilteringWebViewTest 7件が成功。実サイトの誤遮断なし。実機は未実施 |
| scriptlet 第2弾・uBlock filters の既定化・配布元の予備（11月19日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 成功。通常76suite・307件、失敗・error・スキップ0（既定購読の版2の移行、予備からの取得と恒久的な失敗での不使用を追加）。エミュレーター（API 37）で ScriptletWebViewTest 3件・CosmeticFilteringWebViewTest 7件・AbpDefaultSubscriptionsIntegrationTest 2件が成功。実機は未実施 |
| scriptlet の独自実装（11月19日） | `./gradlew assembleDebug testDebugUnitTest :module:adblock:assembleDebugAndroidTest` | 独立監査の指摘（非対応 WebView でのブリッジ残留、期限なしの待ち、汎用規則の走査）を修正後、成功。通常76suite・305件、失敗・error・スキップ0（解析・別名・例外・`$elemhide`・キャッシュ往復・JSON・入れ物の分離・期限つきの待ちの新規7件と、scriptlet を受け付けたことによる既存4件の期待値更新）。エミュレーター（API 37）で ScriptletWebViewTest 2件と CosmeticFilteringWebViewTest 7件が成功。実機は未実施 |
| 本体の購読・構文拡張（11月19日） | `./gradlew assembleDebug testDebugUnitTest` | 独立監査の指摘（本文ページへの適用範囲、EasyList の移行）を修正後、成功。通常75suite・298件、失敗・error・スキップ0。既存無効保持・重複/再試行、important優先・全購読badfilter・空資源選択、include境界・子更新・失敗/取消時の旧束保持等を検証。実機は未実施 |
| 公開版の本体・通常単体・全計測APK・release・buildSrc | `./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest assembleRelease :buildSrc:test` | 11月17日、比較基準の版（2026-11-17）で全成功。通常69suite・247件、buildSrc15件は別集計で失敗・error・スキップ0 |
| 今回の全体・プレビュー | `./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest assembleRelease :buildSrc:test :gecko-preview:assembleDebug :gecko-preview:checkOssNotices` | 11月18日、全成功。通常69suite・249件、buildSrc3suite・25件のXMLで失敗・error・スキップ0。buildSrcの最終実行はUP-TO-DATEで検証済みXMLを確認 |
| 最終の標準タスク再検証 | 上記の標準3タスク・buildSrc・明示プレビューdebug等を再実行 | 11月18日、成功。通常249件・buildSrc25件を保持し、全計測APK・プレビューdebugも生成・出力 |
| 公開版の計測回帰 | 上記の`assembleDebugAndroidTest`後、実機でクラスを指定して実行 | 移行13・広告7・検索4・UI2・HTTP更新2の新28件と既存13件の計41件が、比較基準の版（2026-11-17）で失敗・スキップ0。以下の新回帰とは別集計 |
| 今回の本体設定反映回帰 | 実機で`AdBlockSettingsRoutingTest`を実行 | 1件でsettings/setting/adblockの3aliasを確認。比較基準の版はlive未更新で失敗、データ保持更新した修正版は成功 |
| 今回の内蔵uBO回帰 | プレビュー計測APKから`GeckoUblockAcceptanceTest`を実行 | 保存済みprofileで3件成功（失敗/スキップ0）。標準EasyListのON/OFF top/child遮断、画面再生成・同process再launch・危険IPC拒否、gzip境界。新規profile/実process再起動等は対象外 |
| リリース構成・縮小・出力名 | 上記の`assembleRelease` | R8/lint成功、版付き未署名APK生成。反射対象の操作定数138/138・設定Container155/155をmappingで照合、欠落0。本番配布用署名は行っていない |
| ビルド処理の単体テスト | 上記の`:buildSrc:test` | 公開版15件、今回25件成功。通常モジュールの件数に含めない |
| 識別子・パッケージ配置 | 識別子とパッケージ配置の静的検査 | テキスト1,434件、package/物理パス864件で問題0。原典帰属と明示的な移行互換を理由付き許可 |
| APK の出力対象 | 出力対象の選択に関する回帰検証 | プレビューの明示debug選択・非対象6ケースを追加して成功。指定した種類の APK だけを出力することを確認 |
| 通知のオフライン再生成 | 隔離コンテナのネットワークを切断し、本体とプレビューのdebug/release通知生成・checkを `--offline --rerun-tasks` で実行 | 11月18日、成功。通常生成が通知本文のネット取得に依存しないことを確認 |
| APK の梱包 | APK内Manifest・`assets/licenses.compressed`を生成元と照合 | min23/target37とLAN権限宣言、debug/releaseの通知GZIP一致、NYSL・WorkManager・Material1.14保持、旧Firebase通知0を確認。アイコン全画素・参照照合は11月15日に実施 |
| プレビュー原本資産の梱包 | assemble必須の`verifyDebugBuiltinUblockPackaging` / `verifyReleaseBuiltinUblockPackaging` | 11月18日、debug/releaseとも生成資産672ファイルの名前・サイズ・SHA-256一致。AAPTによる`_locales`除外を修正済み。原本SHA確認と最終APK照合を分けて実施 |

## 実機で確認した範囲

比較基準の版（2026-11-17）から生成したAPKで、実機のデータを保持して更新できることを確認しました。次の41件が成功し、失敗・スキップ0でした。

| 実行群 | 件数 | 範囲 |
| --- | --- | --- |
| 移行 | 13 | 人工ZIP/設定/ブックマーク/SQLite/ジェスチャー等、参照ID・失敗時復旧。実Yuzu書出しとは別 |
| 検索 | 4 | 既定移行・独自値保持・保存失敗等 |
| UI | 2 | 明暗のMaterialテーマと部品生成 |
| 広告・更新 | 12 | 動的DOM等7件と既存Worker等5件 |
| ページ配色 | 5 | 既存の実WebView回帰 |
| 本体統合 | 5 | file://更新2・HTTP200/304/503の2・Insets1 |

実操作では本体WEB_SEARCH入口へ日本語・記号を渡し、検索結果を確認しました。メニュー12項目・設定・アドレス欄のIME表示も確認しました。日本語IMEでの文字合成・全画面全操作・他OSを確認した意味ではありません。UIAutomator接続後にpopupが閉じる事象があったため、画面とwindow寸法でも表示を確認しました。

広告ブロックをONに保存しても、設定画面の開き方によってlive状態が更新されない経路を発見し、通常メニューで設定から戻ると反映されました。反映後は既知ルールの適用と枠の非表示を確認できましたが、別系統の追従広告は残っています。サイト別の調整を成功条件にせず、内蔵uBOの汎用処理を別途実装・検証中です。

内部設定リンクの `citrus:settings` / `citrus:setting` / `citrus:adblock` から戻った時のlive状態を再読込するよう修正しました。実機の新回帰1件は旧公開版で失敗し、データ保持更新した修正版で成功しました。公開版で成功済みの41件へ混ぜず、1件で3aliasを検証した結果として記録します。

独立プレビューの実機回帰3件は、保存済みGecko profileで失敗・スキップ0でした。標準EasyListのnetwork/cosmeticをON/OFFで比較し、top/childの両文書で広告と正常な通信・本文を区別して確認しました。画面再生成で同じsession・DOM・要求1回を保持し、危険な外部IPCを拒否、同じOSプロセス内の再launchで無効状態を維持しました。生成通知のgzip読込・上限境界も対象です。

初回の計測失敗は、原本uBOのlocalhost向けgenerichide例外と、ActivityScenarioの保存Intent照合に対するfixture側の前提が原因でした。runner不足を解消した後のこれらの調整はテスト側だけで、原本uBOやリストを試験用に改変していません。今回の成功を新規profileの初回cold起動、OS/process再起動、scriptlet、procedural、MyFilters、popup実描画の成功へ広げません。本体の機能移植と実Yuzu書出しの確認も残っています。

以下は旧基準版に対する結果です。

実機で、配色5件・Worker等5件・本体Hiltローカル購読統合2件・ライセンスInsets回帰1件の計測テスト計13件が、最終再実行まで失敗・スキップ0ですべて成功しました。本体デバッグAPKの新規インストール、BrowserActivityの起動、HTTPSページ表示も確認しました。

本体ではタブ切替、スピードダイヤルのSettingsリンクから設定・About・OSSへの導線と実描画を確認しました。AboutのCitrus表示と原典帰属も確認しています。メニューは描画完了後に12項目とSettings項目、正常な表示位置を確認しました。これは導線と見た目のスモーク確認で、全操作の成功を示すものではありません。

配色5件は実WebViewのサイトCSS・明暗切替、DOM入力・JavaScript状態・読込回数の保持、アプリUIからの独立、旧設定keyの移行、PixelCopyによる自動暗色化を対象にしています。更新5件は実Roomの列限定SQL、標準WorkerFactoryの不正入力拒否、広告OFF時の自動DispatcherのSkipped結果、要求UUIDとSavedStateHandleを再構成したモデルの復元を対象にしています。

追加の本体統合2件は、実CitrusBrowserApplicationと注入DB、公開の手動更新窓口から標準Workerを通し、file://購読の更新、metadataとZIP4カテゴリ・本文の公開、無効購読のskip、専用fixtureの後始末を確認しました。

ライセンスInsets回帰は、旧本体で本文領域がバーへ重なる失敗を確認し、最小修正後に成功することを確認しました。本体Hilt統合2件と同時実行した3件も、失敗・スキップ0です。実描画でも先頭見出しと本文がActionBarの下から表示され、ナビゲーションバーの内側に収まることを確認しました。非重なりの確認条件は実機の縦画面・検証時のテーマとナビゲーション方式に限ります。

この13件には、外部HTTP購読、回線切替、実際のプロセス・OS再起動、Doze、画面再生成による復元は含まれません。IMEは要求フラグの確認までで、表示・入力中レイアウト等の詳細動作は未検証です。Android 16/17や対応下限を含む他OS、別WebView、ダウンロード・戻る操作等の確認も残ります。

リリース APK は署名して配布します。過去の記録にある「未署名」は当時の状態です。

## ビルドと OSS 通知の運用

本体の `minSdk` は24（2026-11-20 に23から変更）、`compileSdk` / `targetSdk` は37です。今回の共有ツールは AGP9.5.1 / Gradle9.6.1 で、独立プレビューだけが minSdk26 / compileSdk37.2 を使います。[AGP9.5の必要Gradleは9.6.0以上](https://developer.android.com/build/releases/agp-9-5-0-release-notes)のため、その最小対応9.6系列の修正版を採用しました。Kotlin/Hiltは維持し、[built-in Kotlin移行手順](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlinの対応表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Hilt2.60.1](https://github.com/google/dagger/releases/tag/dagger-2.60.1)、[Gradle公式チェックサム](https://gradle.org/release-checksums/)も確認しています。SDK別名配置や非対応警告の抑止は追加しません。

ビルドは Docker の中で行います。テスト・設定確認・通知更新のみのタスクでは、古い APK を再出力しません。

通常の OSS 通知生成は固定入力だけを読み、通知本文をネット取得しません。Gradle の依存解決全体をオフラインにする設定ではありません。`:app:updateOssNotices` は候補をビルドディレクトリ内へ生成し、レビュー後に固定入力へ反映します。`:app:checkOssNotices` で依存一覧と固定入力の不足を検出します。手順は [コントリビューションガイド](../Contributing.md) を参照してください。

プレビューの候補更新・固定通知は `:gecko-preview:updateOssNotices` / `:gecko-preview:checkOssNotices` と `gecko-preview/oss` で分離します。Gecko本体の `about:license`、uBO原本のGPL/対応ソース、Maven推移依存の通知は別の導線で保持します。GMSの存在からFirebase追加や収集ゼロを推測せず、送信挙動の全経路は未確認として扱います。

## 次の作業

1. 比較基準版の41件と新しい設定反映1件・独立プレビュー3件の実機結果を保持する。[内蔵uBO検証](ADBLOCK_ENGINE_PLAN.md)では、新規profile・実process再起動・拡張構文等の未検証範囲を確認し、本体の既存機能を保持する統合を段階的に進める。
2. Android17のLAN許可・拒否、他OS、HTTPの実時間再試行・回線切替・プロセス/OS再起動、Doze、IME・権限・ダウンロード・戻る操作を確認する。人工HTTP2件は端末内だけのテストであり、外部購読や実際の再起動を代用しない。
3. 既存機能を維持するエンジン境界と、実リスト・端末での遅延/メモリを検証する。
4. UIの残る画面と本番用署名・配布準備を進める。独立レビュー・Docker検証・GitHub反映を継続する。

## 原典とライセンス

CitrusBrowser は Hazuki 氏による [Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードとコミット履歴を継承しています。既存の著作権表示と [Apache License 2.0](../LICENSE) を保持し、取り込んだソース・ライブラリの通知本文も維持します。
