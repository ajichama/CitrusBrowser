# 開発進捗と検証範囲

更新日: 2026-11-17。データ移行・Google検索・Material UI・広告遮断の改善と、正式API37構成を含む状態です。今回の全体ビルドと単体テストは成功しました。実機結果は2026-11-16の旧版に対するもので、今回の変更は再実機確認前です。Phase 5全体は進行中です。

今回の変更は独立レビューを通過し、Dockerで通常単体247件・buildSrc15件、debug・全計測APK・releaseのR8/lintが成功しました。[改善一覧](IMPROVEMENT_PLAN.md)に各要望と残課題を整理しています。実際のYuzu書出し、はちま起稿のモバイル版、日本語検索、刷新した画面の操作は、修正版で改めて確認します。

## フェーズ別の状態

| 対象 | 実装・完了事項 | 未完了・検証の限界 |
| --- | --- | --- |
| Phase 1: リポジトリ・ビルド基盤 | Yuzu Browser のコミット履歴・原典の帰属を継承。Docker と Named Volume でビルドを隔離 | 継続してホストへの中間生成物の混入を監視 |
| Phase 2: 命名・独自アイコン | 表示名、パッケージと物理配置、権限、内部識別子を Citrus へ移行。旧保存データの読み込み互換を維持。独自ランチャー・通知アイコンを実装し、APK の PNG 10点・148,608画素の一致と adaptive / round / monochrome・通知の参照を確認 | 各 OS・ランチャー・通知上の実機表示は未確認 |
| Phase 3: 正式 API 37 構成 | AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12。JDK 21、built-in Kotlin と legacy-kapt へ移行。Android 15で本体の新規インストール・BrowserActivity起動・HTTPS表示を確認 | 更新インストールによる保存内容の維持、他OSでの起動は未確認 |
| Phase 3: 生成物・署名 | SDK 別名ディレクトリと非対応警告抑止を削除。Wrapper 更新と公式 SHA-256 照合。デバッグ署名鍵・adb 鍵を専用 Volume に保持 | APK の生成成功は全 OS での互換保証や本番署名の用意を意味しない |
| Phase 3: OSS 通知 | Material 1.14.0更新後のruntime102依存・114文書とソース由来18項目を固定入力化。通常生成・通信を切った再生成、debug/release APKとの一致、NYSL保持・旧Firebase通知0を確認 | 旧版のInsets回帰はAndroid15・縦画面・現在のテーマ・3ボタンナビで成功。新テーマでの実描画は再確認前 |
| Phase 4: OS 互換基盤 | compileSdk / targetSdk 37、Edge-to-Edge、Predictive Back、Intent/Bundle/Parcel 互換、JobIntentService 廃止、ストレージ・可視性対応を実装 | 旧 OS・IME・権限・バックグラウンド動作を含む実機確認は未実施 |
| Phase 4: LAN権限 | API37以上だけに、OSの実許可状態を表示する明示設定を追加。利用者が選んだ時だけ要求し、拒否後は任意でOS設定へ進める | API37での許可・拒否・取消は未確認。通信先の自動判定は行わず、LAN利用前に設定する。[対応範囲](../legacy/LOCAL_NETWORK_PERMISSION.md) |
| Phase 4: ページ配色 | アプリ UI と独立した明色・サイト暗色・サイト優先の自動暗色を実装。旧値3を2へ移行し、サイト未指定−1を保持。Android 15の計測5件でDOM・入力・JS状態・読込回数の保持、独立配色、旧key移行、PixelCopyによる暗色化を確認 | 他OS・WebView providerでの描画、実サイトとサイト別設定UIの操作は未確認。UI テーマ全体の刷新も継続課題 |
| Phase 5: AdBlock 互換・保存 | 初回のパーサ改善と一括保存の安全性を改善。キャッシュ v2 の 32bit 保存・v1 読取、旧 snapshot 保持、行単位ドメイン条件の最具体指定優先、elemhide / generichide、汎用要素フィルタ無視設定を実装 | 今回の追加互換回帰16件を含む単体テストに成功。実サイト・端末での確認と、拡張 uBlock 構文の対応拡大は残る |
| Phase 5: 更新ジョブ | minSdk 23 を保ち WorkManager 2.11.2 へ移行。自動要求の重複抑止、手動要求の実 UUID、再試行、通信取消、画面再表示・復元時の Flow 観測を実装。Android 15でWorker等の計測5件と、本体Hilt経由のfile://購読統合2件が成功 | 外部HTTP購読、実プロセス再起動・Doze・Wi-Fi制約・実画面の復元は未確認。DB とファイルをまたぐ処理の強制終了時の限界は[更新仕様](../module/adblock/UPDATE_JOBS.md)へ記載 |
| Phase 5: 判定・索引 | main frame・画像・script・不明種別を分離。PSL の null、IPv6、IDN、単一ラベル host の第三者判定を修正。部分一致・例外の索引漏れと同一 bucket の重複走査を修正。11月16日の基準版では内蔵800ルール・43要求で直接照合との一致を確認 | 実サイトの誤遮断、大規模リスト・端末の性能測定が残る。基準版のfallbackは41%。今回の追加ルールへの測定値として扱わず、JVMの比較回数を実環境の速度向上率とみなさない |
| Phase 5: モバイル広告 | 観測した配信・広告枠の限定ルール、持続CSS、inline重要指定への対処、無効selector分離、有界DOM追跡を実装。広告単体84件成功、実WebView回帰7件のAPK生成 | はちま起稿の実サイト、誤遮断と性能は再確認前。拡張uBlock構文・scriptletの完全互換はない。[対応仕様](../module/adblock/COSMETIC_FILTERING.md) |
| データ移行 | 旧設定・ブックマーク・別書出しのスピードダイヤル・検索・UA/URL別設定・スクリプト・ジェスチャーを検証後に統合。既存を保持し参照IDを対応付け、通常失敗時は自己所有の変更を復旧 | 実際のYuzu書出しは未確認。外部テーマ画像・他アプリのSAF権限等は移せない。跨る保存領域の電源断原子性は保証しない。[対応形式と制約](../legacy/IMPORT_COMPATIBILITY.md) |
| Google検索 | 既定をgoogle.comへ更新。旧既定だけの移行、UTF-8クエリ、候補URL、安定ID、旧編集画面からの再上書き、旧負IDを修正。検索単体21件成功 | 端末での日本語検索・候補表示は再確認前。独自URLは保持。[検索仕様](../module/search/SEARCH_COMPATIBILITY.md) |
| Material UI | Material3のViewsテーマ、検索・設定・メニュー・アドレス欄・ホームを更新。ツールバー配置・操作・ジェスチャー・明示配色を保持 | 明暗・文字拡大・IME・既存カスタマイズの実操作は再確認前。[機能対応表](../module/ui/MATERIAL_UI.md) |

## 自動検証の結果

| 検証 | 再現コマンド | 2026-11-17の結果（過去の結果は日付を明記） |
| --- | --- | --- |
| 本体・通常単体・全計測APK・release・buildSrc | `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest assembleRelease :buildSrc:test"` | 全成功（4分50秒・1,965 tasks）。通常69suite・247件、buildSrc15件は別集計で失敗・error・スキップ0 |
| 新しい計測回帰 | 上記の`assembleDebugAndroidTest` | 移行13・広告7・検索4・UI2・HTTP更新2の計28件を追加しAPK生成成功。端末実行は未実施。既存13件の実機成功とは別 |
| リリース構成・縮小・出力名 | 上記の`assembleRelease` | R8/lint成功、版付き未署名APK生成。反射対象の操作定数138/138・設定Container155/155をmappingで照合、欠落0。本番配布用署名は行っていない |
| ビルド処理の単体テスト | 上記の`:buildSrc:test` | buildSrc15件成功。通常247件に含めない |
| 識別子・パッケージ配置 | `pwsh -File scripts/verify-citrus-identifiers.ps1` | テキスト1,434件、package/物理パス864件で問題0。原典帰属と明示的な移行互換を理由付き許可 |
| ビルド・コミット補助処理 | `pwsh -File scripts/test-build-workflow.ps1` | 11月16日までにAPK種別選択27ケースを含むモック成功。今回は補助処理本体に変更なし |
| 通知のオフライン再生成 | 隔離コンテナのネットワークを切断し、`:app:generateDebugOssNotices :app:generateReleaseOssNotices` を `--offline --rerun-tasks` で実行 | 成功。通常生成が通知本文のネット取得に依存しないことを確認 |
| APK の梱包 | APK内Manifest・`assets/licenses.compressed`を生成元と照合 | min23/target37とLAN権限宣言、debug/releaseの通知GZIP一致、NYSL・WorkManager・Material1.14保持、旧Firebase通知0を確認。アイコン全画素・参照照合は11月15日に実施 |

## 実機で確認した範囲

2026-11-16、SO-51DのAndroid 15（API35）、WebView 157.0.8103.38で、配色5件・Worker等5件・本体Hiltローカル購読統合2件・ライセンスInsets回帰1件の計測テスト計13件が、最終再実行まで失敗・スキップ0ですべて成功しました。本体デバッグAPKの新規インストール、BrowserActivityの起動、HTTPSページ表示も確認しました。

本体ではタブ切替、スピードダイヤルのSettingsリンクから設定・About・OSSへの導線と実描画を確認しました。AboutのCitrus表示と原典帰属も確認しています。メニューは描画完了後に12項目とSettings項目、正常な表示位置を確認しました。これは導線と見た目のスモーク確認で、全操作の成功を示すものではありません。

配色5件は実WebViewのサイトCSS・明暗切替、DOM入力・JavaScript状態・読込回数の保持、アプリUIからの独立、旧設定keyの移行、PixelCopyによる自動暗色化を対象にしています。更新5件は実Roomの列限定SQL、標準WorkerFactoryの不正入力拒否、広告OFF時の自動DispatcherのSkipped結果、要求UUIDとSavedStateHandleを再構成したモデルの復元を対象にしています。

追加の本体統合2件は、実CitrusBrowserApplicationと注入DB、公開の手動更新窓口から標準Workerを通し、file://購読の更新、metadataとZIP4カテゴリ・本文の公開、無効購読のskip、専用fixtureの後始末を確認しました。

ライセンスInsets回帰は、旧本体で本文領域がバーへ重なる失敗を確認し、最小修正後に成功することを確認しました。本体Hilt統合2件と同時実行した3件も、失敗・スキップ0です。実描画でも先頭見出しと本文がActionBarの下から表示され、ナビゲーションバーの内側に収まることを確認しました。非重なりの確認条件は同端末の縦画面・現在のテーマ・3ボタンナビゲーションに限ります。

この13件には、外部HTTP購読、回線切替、実際のプロセス・OS再起動、Doze、画面再生成による復元は含まれません。IMEは要求フラグの確認までで、表示・入力中レイアウト等の詳細動作は未検証です。Android 16/17や対応下限を含む他OS、別WebView、ダウンロード・戻る操作等の確認も残ります。確認手順と未完項目は[実機確認](DEVICE_CHECKS.md)へ記載します。

リリース APK の生成確認と、本番配布用の署名・公開は別の作業です。現在のリリース成果物は未署名であり、端末での確認にはデバッグ APK を使用します。

## ビルドと OSS 通知の運用

`minSdk` は23、`compileSdk` / `targetSdk` は37です。選定根拠は [AGP 9.1 の公式リリースノート](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[built-in Kotlin 移行手順](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlin の Gradle / AGP 対応表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Hilt 2.60.1](https://github.com/google/dagger/releases/tag/dagger-2.60.1)、[Gradle の公式チェックサム](https://gradle.org/release-checksums/)です。

JDK・SDK・Gradle は Docker 内だけで実行します。中間生成物・Kotlin 永続データ・依存キャッシュは Named Volume 内へ隔離し、デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保存します。テスト・設定確認・通知更新のみのタスクでは、古い APK を `release/` へ再出力しません。

通常の OSS 通知生成は固定入力だけを読み、通知本文をネット取得しません。Gradle の依存解決全体をオフラインにする設定ではありません。`:app:updateOssNotices` は候補を Volume 内へ生成し、レビュー後に固定入力へ反映します。`:app:checkOssNotices` で依存一覧と固定入力の不足を検出します。手順は [コントリビューションガイド](../Contributing.md) を参照してください。

## 次の作業

1. 検証済み変更をGitHubへ反映し、[実機確認手順](DEVICE_CHECKS.md)に沿ってSO-51Dをデータ保持更新する。新計測28件と既存13件を実行し、実Yuzu書出し・Google・はちま起稿モバイル版・Material UIの既存操作を優先確認する。
2. Android17のLAN許可・拒否、他OS、HTTPの実時間再試行・回線切替・プロセス/OS再起動、Doze、IME・権限・ダウンロード・戻る操作を確認する。人工HTTP2件は端末内だけのテストであり、外部購読や実際の再起動を代用しない。
3. 実リスト・実URL・端末上の遅延とメモリを測定し、拡張uBlock構文等の対応範囲を広げる。
4. UIの残る画面と本番用署名・配布準備を進める。独立レビュー・Docker検証・GitHub反映を継続する。

## 原典とライセンス

CitrusBrowser は Hazuki 氏による [Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードとコミット履歴を継承しています。既存の著作権表示と [Apache License 2.0](../LICENSE) を保持し、取り込んだソース・ライブラリの通知本文も維持します。
