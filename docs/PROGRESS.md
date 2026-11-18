# 開発進捗と検証範囲

更新日: 2026-11-19。本体の既定購読とネットワーク構文を拡張しました。Docker の本体ビルド・通常単体298件が成功しました。本変更の実機確認と、本体への内蔵uBO統合は継続中です。従来本体の比較基準`f5f2ae8b`ではAndroid15の計測41件が成功しています。

新規利用者には EasyList・EasyPrivacy・AdGuard Japanese を有効登録し、既存利用者には旧版に無かった EasyPrivacy・AdGuard Japanese の未登録分だけを一度追加します。EasyList は旧版の既定リストに常に含まれていたため、既存利用者に無ければ削除とみなして復活させません。無効・未取得の EasyList を含め、既存の無効設定は維持します。種別を指定しない通信ルールは本文ページと popup に適用せず、パスを持たないホスト規則と `$document` 明示の規則だけを本文ページに適用します。別名・important・badfilter・限定した空資源への redirect と、安全な `!#include` の展開を実装しました。購読本文は実行時に取得し、完成APKにテスト用のリスト抜粋が含まれないことを確認しました。詳細と非対応範囲は[内蔵uBOの検証計画](ADBLOCK_ENGINE_PLAN.md)を参照してください。

固定した抜粋の破棄行数は EasyList 48行中5行（変更前と同じ）、AdGuard Japanese 54行中6行（変更前15行）、uBO filters 62行中29行（変更前47行）でした。単体テストで行数・破棄数を固定しています。未対応の制約は行ごと破棄し、scriptlet・procedural等の完全互換や、実サイトの広告残存解消を示す結果とは扱いません。

追加の `:app:assembleDebugAndroidTest :module:adblock:assembleDebugAndroidTest` を含む最終ビルドも1分48秒・919 tasksで成功しました。実Room・実アセットで初期購読と既存無効保持を確認する計測2件を追加していますが、既存の更新回帰9件と合わせて実行は未完了です。API37エミュレーターは初回32.3秒で起動完了を表示したものの、SurfaceFlingerが `!rcEnc->featureInfo()->hasReadColorBufferDma` で繰り返し異常終了し、APK再導入も失敗しました。SwiftShaderへの変更とGLDirectMemの有効化試験でも解消せず、後者はソースの既定から除外しました。API37.2・16KB版も180秒で起動を確認できませんでした。起動表示を計測成功には数えません。

比較基準`f5f2ae8b`は独立レビューと通常単体247件・buildSrc15件、debug・全計測APK・releaseのR8/lintを通過しています。Google.comでの日本語・記号検索、メニュー・設定・IMEの一部も実機で確認しました。Yuzuの書出しは未作成で実データ取込は未確認です。広告対策はサイト個別ルールを追加する方針から、汎用処理を内蔵する方針へ修正しています。[内蔵uBOの検証計画](ADBLOCK_ENGINE_PLAN.md)と[改善一覧](IMPROVEMENT_PLAN.md)を参照してください。

11月18日の変更では AGP9.5.1 / Gradle9.6.1 と独立した Gecko/uBO プレビューを追加しました。全体ビルドは4分52秒・2,225 tasks、最終の標準タスク再検証も1分35秒・1,411 tasksで成功し、通常249件・buildSrc25件を保持しています。JUnit XMLは失敗・error・スキップ0で、全計測APKと明示指定のプレビューdebugも生成・出力しました。通信を切った通知再生成も16秒・11 tasksで成功しました。これは比較基準の公開`f5f2ae8b`の247件・15件・実機41件とは別の検証結果です。

プレビューは別 applicationId・保存領域の minSdk26 / compileSdk37.2 で、本体の minSdk23 / compileSdk37・targetSdk37 を維持します。GeckoView160 と原本 uBlock Origin1.76.4 を内蔵し、debug/release の最終 APK と生成資産672ファイルの一致を確認しました。端末では起動639msと「uBO1.76.4 有効」の表示に加え、修正後の計測3件も4.815秒・失敗/スキップ0で成功しました。保存済みprofileでの標準EasyListのnetwork/cosmetic・画面再生成等に限定した結果で、本体の既存全機能をGeckoへ統合済みという意味ではありません。上記の検証項目を完了しました。

## フェーズ別の状態

後続の共通操作境界では、既存WebViewの戻る・進む・更新・停止をAndroid/Gecko型を公開しないAPIへ接続しました。保存形式・操作ID・履歴ロック・既定操作を保持する独立レビューと、対象単体/コンパイルが成功しています。標準`assembleDebug testDebugUnitTest`も1分・806 tasksで成功し、通常71suite・255件のJUnit結果で失敗・error・スキップ0を確認しました。本体の実機回帰3件も8.537秒で失敗・スキップ0でした。履歴移動、明示/切替操作の更新・停止、履歴ロック、戻れない場合の既定操作を実`ActionExecutor`経由で確認した結果であり、Geckoへの本体差替えではありません。

内蔵uBOの標準MyFilters/popupの描画と、対象アプリのforce-stop後に別PIDで無効状態が保持されることも確認しました。有効状態は元へ復旧済みです。OS再起動やMyFilters本文の保持をまとめて証明したものではありません。

| 対象 | 実装・完了事項 | 未完了・検証の限界 |
| --- | --- | --- |
| Phase 1: リポジトリ・ビルド基盤 | Yuzu Browser のコミット履歴・原典の帰属を継承。Docker と Named Volume でビルドを隔離 | 継続してホストへの中間生成物の混入を監視 |
| Phase 2: 命名・独自アイコン | 表示名、パッケージと物理配置、権限、内部識別子を Citrus へ移行。旧保存データの読み込み互換を維持。独自ランチャー・通知アイコンを実装し、APK の PNG 10点・148,608画素の一致と adaptive / round / monochrome・通知の参照を確認 | 各 OS・ランチャー・通知上の実機表示は未確認 |
| Phase 3: 正式 API 37 構成 | 今回の構成は AGP9.5.1 / Gradle9.6.1。Kotlin2.4.21 / Hilt2.60.1 / KSP2.3.12・JDK21と built-in Kotlin / legacy-kapt を維持。本体23/37と独立プレビュー26/37.2を分離し、37.2非対応警告0でビルド成功。公開版はAndroid15でデータ保持更新・起動・HTTPS表示を確認 | 他OSでの起動と新プレビューの全機能移植は未確認 |
| Phase 3: 生成物・署名 | SDK 別名ディレクトリと非対応警告抑止を削除。Wrapper 更新と公式 SHA-256 照合。デバッグ署名鍵・adb 鍵を専用 Volume に保持 | APK の生成成功は全 OS での互換保証や本番署名の用意を意味しない |
| Phase 3: OSS 通知 | 本体runtime102依存・114文書とソース由来18項目を保持し、AGP更新でDataBinding5座標を9.5.1へ置換。独立プレビューは別の52依存・68文書。通常生成と両アプリの通信を切った再生成が成功。非OSSのGMS規約を含むプレビューは「ライブラリのライセンス」と表示 | 公開版のInsets回帰と新Material部品はAndroid15で成功。新プレビューの通知画面の実描画は別途確認する |
| Phase 4: OS 互換基盤 | compileSdk / targetSdk 37、Edge-to-Edge、Predictive Back、Intent/Bundle/Parcel 互換、JobIntentService 廃止、ストレージ・可視性対応を実装。公開版のAndroid15で計測41件と限定UIスモーク成功 | 他OS・詳細IME・API37権限・実再起動/Dozeを含む横断確認は未実施 |
| Phase 4: LAN権限 | API37以上だけに、OSの実許可状態を表示する明示設定を追加。利用者が選んだ時だけ要求し、拒否後は任意でOS設定へ進める | API37での許可・拒否・取消は未確認。通信先の自動判定は行わず、LAN利用前に設定する。[対応範囲](../legacy/LOCAL_NETWORK_PERMISSION.md) |
| Phase 4: ページ配色 | アプリ UI と独立した明色・サイト暗色・サイト優先の自動暗色を実装。旧値3を2へ移行し、サイト未指定−1を保持。Android 15の計測5件でDOM・入力・JS状態・読込回数の保持、独立配色、旧key移行、PixelCopyによる暗色化を確認 | 他OS・WebView providerでの描画、実サイトとサイト別設定UIの操作は未確認。UI テーマ全体の刷新も継続課題 |
| Phase 5: AdBlock 互換・保存 | 初回のパーサ改善と一括保存の安全性を改善。キャッシュ v2 の 32bit 保存・v1 読取、旧 snapshot 保持、行単位ドメイン条件の最具体指定優先、elemhide / generichide、汎用要素フィルタ無視設定を実装 | 今回の追加互換回帰16件を含む単体テストに成功。実サイト・端末での確認と、拡張 uBlock 構文の対応拡大は残る |
| Phase 5: 更新ジョブ | minSdk 23 を保ち WorkManager 2.11.2 へ移行。自動要求の重複抑止、手動要求の実 UUID、再試行、通信取消、画面再表示・復元時の Flow 観測を実装。Android 15でWorker等の計測5件と、本体Hilt経由のfile://購読統合2件が成功 | 外部HTTP購読、実プロセス再起動・Doze・Wi-Fi制約・実画面の復元は未確認。DB とファイルをまたぐ処理の強制終了時の限界は[更新仕様](../module/adblock/UPDATE_JOBS.md)へ記載 |
| Phase 5: 判定・索引 | main frame・画像・script・不明種別を分離。PSL の null、IPv6、IDN、単一ラベル host の第三者判定を修正。部分一致・例外の索引漏れと同一 bucket の重複走査を修正。11月16日の基準版では内蔵800ルール・43要求で直接照合との一致を確認 | 実サイトの誤遮断、大規模リスト・端末の性能測定が残る。基準版のfallbackは41%。今回の追加ルールへの測定値として扱わず、JVMの比較回数を実環境の速度向上率とみなさない |
| Phase 5: モバイル広告 | 公開版の実WebView回帰7件成功。今回の変更で設定反映と初回主文書判定を修正し、広告単体86件成功。設定リンク3aliasの実機1件は旧版失敗→修正版成功 | 公開版で既知枠の非表示を確認したが別系統広告が残存。汎用uBO互換の成功とは扱わず、独立プレビューで検証する。[対応仕様](../module/adblock/COSMETIC_FILTERING.md) |
| Phase 5: 内蔵uBOプレビュー | 本体と独立したGeckoView160/原本uBO1.76.4を内蔵。生成資産672ファイルを最終APKと照合。保存済みprofileの実機3件で標準EasyListのnetwork/cosmetic ON/OFF・top/child、同session/DOMの画面再生成、同process再launch時の無効状態等を確認 | 新規profileの初回cold起動・実OS/process再起動・scriptlet・procedural・MyFilters・popup実描画は未確認。本体統合完了とはしない。[検証計画](ADBLOCK_ENGINE_PLAN.md) |
| データ移行 | 旧設定・ブックマーク・別書出しのスピードダイヤル・検索・UA/URL別設定・スクリプト・ジェスチャーを検証後に統合。既存を保持し参照IDを対応付け、通常失敗時は自己所有の変更を復旧 | 実際のYuzu書出しは未確認。外部テーマ画像・他アプリのSAF権限等は移せない。跨る保存領域の電源断原子性は保証しない。[対応形式と制約](../legacy/IMPORT_COMPATIBILITY.md) |
| Google検索 | 既定をgoogle.comへ更新。検索単体21件と公開版の実機4件が成功。端末で日本語・記号の検索結果も確認 | 詳細な候補操作・他OSは未確認。独自URLは保持。[検索仕様](../module/search/SEARCH_COMPATIBILITY.md) |
| Material UI | Material3のViewsテーマ、検索・設定・メニュー・アドレス欄・ホームを更新。公開版で明暗部品の計測2件とメニュー・設定・IME表示の限定スモーク成功 | 文字拡大・日本語文字合成・全画面/全テーマ・既存カスタマイズの全操作は未確認。[機能対応表](../module/ui/MATERIAL_UI.md) |

## 自動検証の結果

| 検証 | 再現コマンド | 対象と結果（比較基準版／今回の結果を区別） |
| --- | --- | --- |
| 本体の購読・構文拡張（11月19日） | `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest"` | 独立監査の指摘（本文ページへの適用範囲、EasyList の移行）を修正後、1分2秒・843 tasksで成功。通常75suite・298件、失敗・error・スキップ0。既存無効保持・重複/再試行、important優先・全購読badfilter・空資源選択、include境界・子更新・失敗/取消時の旧束保持等を検証。実機は未実施 |
| 公開版の本体・通常単体・全計測APK・release・buildSrc | `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest assembleRelease :buildSrc:test"` | 11月17日、公開`f5f2ae8b`で全成功（4分50秒・1,965 tasks）。通常69suite・247件、buildSrc15件は別集計で失敗・error・スキップ0 |
| 今回の全体・プレビュー | `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest assembleRelease :buildSrc:test :gecko-preview:assembleDebug :gecko-preview:checkOssNotices"` | 11月18日、全成功（4分52秒・2,225 tasks）。通常69suite・249件、buildSrc3suite・25件のXMLで失敗・error・スキップ0。buildSrcの最終実行はUP-TO-DATEで検証済みXMLを確認 |
| 最終の標準タスク再検証 | 上記の標準3タスク・buildSrc・明示プレビューdebug等を再実行 | 11月18日、成功（1分35秒・1,411 tasks）。通常249件・buildSrc25件を保持し、全計測APK・プレビューdebugも生成・出力 |
| 公開版の計測回帰 | 上記の`assembleDebugAndroidTest`後、実機でクラスを指定して実行 | 移行13・広告7・検索4・UI2・HTTP更新2の新28件と既存13件の計41件が、公開`f5f2ae8b`で失敗・スキップ0。以下の新回帰とは別集計 |
| 今回の本体設定反映回帰 | 実機で`AdBlockSettingsRoutingTest`を実行 | 1件でsettings/setting/adblockの3aliasを確認。旧`f5f2ae8b`はlive未更新で失敗（11.651秒）、データ保持更新した修正版は成功（2.132秒） |
| 今回の内蔵uBO回帰 | プレビュー計測APKから`GeckoUblockAcceptanceTest`を実行 | 保存済みprofileで3件成功（4.815秒・失敗/スキップ0）。標準EasyListのON/OFF top/child遮断、画面再生成・同process再launch・危険IPC拒否、gzip境界。新規profile/実process再起動等は対象外 |
| リリース構成・縮小・出力名 | 上記の`assembleRelease` | R8/lint成功、版付き未署名APK生成。反射対象の操作定数138/138・設定Container155/155をmappingで照合、欠落0。本番配布用署名は行っていない |
| ビルド処理の単体テスト | 上記の`:buildSrc:test` | 公開版15件、今回25件成功。通常モジュールの件数に含めない |
| 識別子・パッケージ配置 | `pwsh -File scripts/verify-citrus-identifiers.ps1` | テキスト1,434件、package/物理パス864件で問題0。原典帰属と明示的な移行互換を理由付き許可 |
| ビルド・コミット補助処理 | `pwsh -File scripts/test-build-workflow.ps1` | 既存モックにプレビューの明示debug選択・非対象6ケースを追加して成功。previewは明示タスクだけを別名で取り出す |
| 通知のオフライン再生成 | 隔離コンテナのネットワークを切断し、本体とプレビューのdebug/release通知生成・checkを `--offline --rerun-tasks` で実行 | 11月18日、成功（16秒・11 tasks）。通常生成が通知本文のネット取得に依存しないことを確認 |
| APK の梱包 | APK内Manifest・`assets/licenses.compressed`を生成元と照合 | min23/target37とLAN権限宣言、debug/releaseの通知GZIP一致、NYSL・WorkManager・Material1.14保持、旧Firebase通知0を確認。アイコン全画素・参照照合は11月15日に実施 |
| プレビュー原本資産の梱包 | assemble必須の`verifyDebugBuiltinUblockPackaging` / `verifyReleaseBuiltinUblockPackaging` | 11月18日、debug/releaseとも生成資産672ファイルの名前・サイズ・SHA-256一致。AAPTによる`_locales`除外を修正済み。原本SHA確認と最終APK照合を分けて実施 |

## 実機で確認した範囲

2026-11-17、公開`f5f2ae8b`から生成したAPKをSO-51Dへデータ保持で更新しました。Android15/API35、WebView157.0.8103.50で次の41件が成功し、失敗・スキップ0でした。

| 実行群 | 件数 | 範囲 |
| --- | --- | --- |
| 移行 | 13 | 人工ZIP/設定/ブックマーク/SQLite/ジェスチャー等、参照ID・失敗時復旧。実Yuzu書出しとは別 |
| 検索 | 4 | 既定移行・独自値保持・保存失敗等 |
| UI | 2 | 明暗のMaterialテーマと部品生成 |
| 広告・更新 | 12 | 動的DOM等7件と既存Worker等5件 |
| ページ配色 | 5 | 既存の実WebView回帰 |
| 本体統合 | 5 | file://更新2・HTTP200/304/503の2・Insets1 |

実操作では本体WEB_SEARCH入口へ「日本語 + & %」を渡し、google.comの検索結果を確認しました。メニュー12項目・設定・アドレス欄のIME表示も確認しました。日本語IMEでの文字合成・全画面全操作・他OSを確認した意味ではありません。UIAutomator接続後にpopupが閉じる事象があったため、直接の画面取得とwindow寸法でも表示を確認しました。

広告は元の本体設定がOFFでした。ON保存後も設定画面の開き方によってlive状態が更新されない経路を発見し、通常メニューで設定から戻ると反映されました。反映後は既知6ルールの適用と枠の非表示を確認できましたが、別系統の追従広告は残っています。サイト別の調整を成功条件にせず、内蔵uBOの汎用処理を別途実装・検証中です。

11月18日の本体修正は、内部設定リンクの `citrus:settings` / `citrus:setting` / `citrus:adblock` から戻った時のlive状態を再読込します。実機の新回帰1件は旧公開版で失敗（11.651秒）し、データ保持更新した修正版で成功（2.132秒）しました。公開版で成功済みの41件へ混ぜず、1件で3aliasを検証した結果として記録します。

独立プレビューの実機回帰3件は、SO-51DのAndroid15/API35・保存済みGecko profileで4.815秒、失敗・スキップ0でした。標準EasyListのnetwork/cosmeticをON/OFFで比較し、top/childの両文書で広告と正常な通信・本文を区別して確認しました。画面再生成で同じsession・DOM・要求1回を保持し、危険な外部IPCを拒否、同じOSプロセス内の再launchで無効状態を維持しました。生成通知のgzip読込・上限境界も対象です。

初回の計測失敗は、原本uBOのlocalhost向けgenerichide例外と、ActivityScenarioの保存Intent照合に対するfixture側の前提が原因でした。runner不足を解消した後のこれらの調整はテスト側だけで、原本uBOやリストを試験用に改変していません。今回の成功を新規profileの初回cold起動、OS/process再起動、scriptlet、procedural、MyFilters、popup実描画の成功へ広げません。本体の機能移植と実Yuzu書出しの確認も残っています。

以下は11月16日の旧基準版に対する結果です。

2026-11-16、SO-51DのAndroid 15（API35）、WebView 157.0.8103.38で、配色5件・Worker等5件・本体Hiltローカル購読統合2件・ライセンスInsets回帰1件の計測テスト計13件が、最終再実行まで失敗・スキップ0ですべて成功しました。本体デバッグAPKの新規インストール、BrowserActivityの起動、HTTPSページ表示も確認しました。

本体ではタブ切替、スピードダイヤルのSettingsリンクから設定・About・OSSへの導線と実描画を確認しました。AboutのCitrus表示と原典帰属も確認しています。メニューは描画完了後に12項目とSettings項目、正常な表示位置を確認しました。これは導線と見た目のスモーク確認で、全操作の成功を示すものではありません。

配色5件は実WebViewのサイトCSS・明暗切替、DOM入力・JavaScript状態・読込回数の保持、アプリUIからの独立、旧設定keyの移行、PixelCopyによる自動暗色化を対象にしています。更新5件は実Roomの列限定SQL、標準WorkerFactoryの不正入力拒否、広告OFF時の自動DispatcherのSkipped結果、要求UUIDとSavedStateHandleを再構成したモデルの復元を対象にしています。

追加の本体統合2件は、実CitrusBrowserApplicationと注入DB、公開の手動更新窓口から標準Workerを通し、file://購読の更新、metadataとZIP4カテゴリ・本文の公開、無効購読のskip、専用fixtureの後始末を確認しました。

ライセンスInsets回帰は、旧本体で本文領域がバーへ重なる失敗を確認し、最小修正後に成功することを確認しました。本体Hilt統合2件と同時実行した3件も、失敗・スキップ0です。実描画でも先頭見出しと本文がActionBarの下から表示され、ナビゲーションバーの内側に収まることを確認しました。非重なりの確認条件は同端末の縦画面・現在のテーマ・3ボタンナビゲーションに限ります。

この13件には、外部HTTP購読、回線切替、実際のプロセス・OS再起動、Doze、画面再生成による復元は含まれません。IMEは要求フラグの確認までで、表示・入力中レイアウト等の詳細動作は未検証です。Android 16/17や対応下限を含む他OS、別WebView、ダウンロード・戻る操作等の確認も残ります。確認手順と未完項目は[実機確認](DEVICE_CHECKS.md)へ記載します。

リリース APK の生成確認と、本番配布用の署名・公開は別の作業です。現在のリリース成果物は未署名であり、端末での確認にはデバッグ APK を使用します。

## ビルドと OSS 通知の運用

本体の `minSdk` は23、`compileSdk` / `targetSdk` は37です。今回の共有ツールは AGP9.5.1 / Gradle9.6.1 で、独立プレビューだけが minSdk26 / compileSdk37.2 を使います。[AGP9.5の必要Gradleは9.6.0以上](https://developer.android.com/build/releases/agp-9-5-0-release-notes)のため、その最小対応9.6系列の修正版を採用しました。Kotlin/Hiltは維持し、[built-in Kotlin移行手順](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlinの対応表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Hilt2.60.1](https://github.com/google/dagger/releases/tag/dagger-2.60.1)、[Gradle公式チェックサム](https://gradle.org/release-checksums/)も確認しています。SDK別名配置や非対応警告の抑止は追加しません。

JDK・SDK・Gradle は Docker 内だけで実行します。中間生成物・Kotlin 永続データ・依存キャッシュは Named Volume 内へ隔離し、デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保存します。テスト・設定確認・通知更新のみのタスクでは、古い APK を `release/` へ再出力しません。

通常の OSS 通知生成は固定入力だけを読み、通知本文をネット取得しません。Gradle の依存解決全体をオフラインにする設定ではありません。`:app:updateOssNotices` は候補を Volume 内へ生成し、レビュー後に固定入力へ反映します。`:app:checkOssNotices` で依存一覧と固定入力の不足を検出します。手順は [コントリビューションガイド](../Contributing.md) を参照してください。

プレビューの候補更新・固定通知は `:gecko-preview:updateOssNotices` / `:gecko-preview:checkOssNotices` と `gecko-preview/oss` で分離します。Gecko本体の `about:license`、uBO原本のGPL/対応ソース、Maven推移依存の通知は別の導線で保持します。GMSの存在からFirebase追加や収集ゼロを推測せず、送信挙動の全経路は未確認として扱います。

## 次の作業

1. 比較基準版の41件と新しい設定反映1件・独立プレビュー3件の実機結果を保持する。[内蔵uBO検証](ADBLOCK_ENGINE_PLAN.md)では、新規profile・実process再起動・拡張構文等の未検証範囲を確認し、本体の既存機能を保持する統合を段階的に進める。
2. Android17のLAN許可・拒否、他OS、HTTPの実時間再試行・回線切替・プロセス/OS再起動、Doze、IME・権限・ダウンロード・戻る操作を確認する。人工HTTP2件は端末内だけのテストであり、外部購読や実際の再起動を代用しない。
3. Yuzuの実書出しを端末上で作成・選択して取込確認する。既存機能を維持するエンジン境界と、実リスト・端末での遅延/メモリを検証する。
4. UIの残る画面と本番用署名・配布準備を進める。独立レビュー・Docker検証・GitHub反映を継続する。

## 原典とライセンス

CitrusBrowser は Hazuki 氏による [Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードとコミット履歴を継承しています。既存の著作権表示と [Apache License 2.0](../LICENSE) を保持し、取り込んだソース・ライブラリの通知本文も維持します。
