# 開発進捗と検証範囲

更新日: 2026-11-16。正式 API 37 ビルド構成、独自アイコン、OSS 通知生成、ページ配色、永続フィルタ更新、AdBlock 判定改善を含む実装スナップショットの状態です。実装、APK の生成、端末での動作確認を分けて記載します。Phase 5 全体は進行中です。

## フェーズ別の状態

| 対象 | 実装・完了事項 | 未完了・検証の限界 |
| --- | --- | --- |
| Phase 1: リポジトリ・ビルド基盤 | Yuzu Browser のコミット履歴・原典の帰属を継承。Docker と Named Volume でビルドを隔離 | 継続してホストへの中間生成物の混入を監視 |
| Phase 2: 命名・独自アイコン | 表示名、パッケージと物理配置、権限、内部識別子を Citrus へ移行。旧保存データの読み込み互換を維持。独自ランチャー・通知アイコンを実装し、APK の PNG 10点・148,608画素の一致と adaptive / round / monochrome・通知の参照を確認 | 各 OS・ランチャー・通知上の実機表示は未確認 |
| Phase 3: 正式 API 37 構成 | AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12。JDK 21、built-in Kotlin と legacy-kapt へ移行 | 下記の自動検証に成功。端末での起動・更新インストールは未確認 |
| Phase 3: 生成物・署名 | SDK 別名ディレクトリと非対応警告抑止を削除。Wrapper 更新と公式 SHA-256 照合。デバッグ署名鍵・adb 鍵を専用 Volume に保持 | APK の生成成功は全 OS での互換保証や本番署名の用意を意味しない |
| Phase 3: OSS 通知 | WorkManager 追加後の runtime 102 依存・106 文書とソース由来 18 項目を固定入力化。SHA-256 検証、固定入力からの通知生成、明示更新タスクを実装。通信なしの再生成、NYSL 保持・旧 Firebase 通知の除去を確認 | ライセンス画面の実機表示は未確認 |
| Phase 4: OS 互換基盤 | compileSdk / targetSdk 37、Edge-to-Edge、Predictive Back、Intent/Bundle/Parcel 互換、JobIntentService 廃止、ストレージ・可視性対応を実装 | 旧 OS・IME・権限・バックグラウンド動作を含む実機確認は未実施 |
| Phase 4: ページ配色 | アプリ UI と独立した明色・サイト暗色・サイト優先の自動暗色を実装。旧値3を2へ移行し、サイト未指定−1を保持。公開 configuration 通知で配色を変え、ページを再生成しない | 保存値・JSON の単体回帰を追加し、計測テスト APK 生成を確認。入力・DOM の保持や WebView 別の描画反映は実機未確認。UI テーマ全体の刷新も継続課題 |
| Phase 5: AdBlock 互換・保存 | 初回のパーサ改善と一括保存の安全性を改善。キャッシュ v2 の 32bit 保存・v1 読取、旧 snapshot 保持、行単位ドメイン条件の最具体指定優先、elemhide / generichide、汎用要素フィルタ無視設定を実装 | 今回の追加互換回帰16件を含む単体テストに成功。実サイト・端末での確認と、拡張 uBlock 構文の対応拡大は残る |
| Phase 5: 更新ジョブ | minSdk 23 を保ち WorkManager 2.11.2 へ移行。自動要求の重複抑止、手動要求の実 UUID、再試行、通信取消、画面再表示・復元時の Flow 観測を実装。更新直前の DB 再確認、世代を確認した rollback と列限定更新で競合を防ぐ | 実際の OS による停止・再起動・Wi-Fi 制約、UI の状態復元は端末での検証が残る。DB とファイルをまたぐ処理の強制終了時の限界は[更新仕様](../module/adblock/UPDATE_JOBS.md)へ記載 |
| Phase 5: 判定・索引 | main frame・画像・script・不明種別を分離。PSL の null、IPv6、IDN、単一ラベル host の第三者判定を修正。部分一致・例外の索引漏れと同一 bucket の重複走査を修正。内蔵800ルール・43要求で直接照合との一致を確認 | 取得できない用途情報の判定、実サイトの誤遮断、大規模リスト・端末での性能測定が残る。内蔵ルールの41%が fallback。JVM の mock を含むバッチ時間や比較回数を実環境の速度向上率とみなさない |

## 自動検証の結果

| 検証 | 再現コマンド | 2026-11-16 の結果 |
| --- | --- | --- |
| 本体 APK・通常単体テスト・計測テスト APK | `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest"` | 3タスク成功。通常モジュールの単体テスト165件成功、失敗・スキップ0。新しい配色5件・更新5件の計測テストは APK 生成まで |
| リリース構成・縮小・出力名 | `pwsh -File scripts/docker-build.ps1 -Task "assembleRelease"` | R8 とリリースチェックを含め成功。バージョン名付きの未署名 APK を生成。本番配布用署名は行っていない |
| ビルド処理の単体テスト | `pwsh -File scripts/docker-build.ps1 -Task ":buildSrc:test"` | buildSrc の15件成功、失敗・スキップ0。通常モジュール165件とは別集計 |
| 識別子・パッケージ配置 | `pwsh -File scripts/verify-citrus-identifiers.ps1` | テキスト1,380件、package/物理パス818件で問題0。原典帰属と明示的な移行互換を許可 |
| ビルド・コミット補助処理 | `pwsh -File scripts/test-build-workflow.ps1` | APK 種別選択27ケースを含むモック成功。引数・明示 Push の安全性を保持。実ビルドでも debug 指定時に古い release APK の更新日時が変わらないことを確認 |
| 通知のオフライン再生成 | 隔離コンテナのネットワークを切断し、`:app:generateDebugOssNotices :app:generateReleaseOssNotices` を `--offline --rerun-tasks` で実行 | 成功。通常生成が通知本文のネット取得に依存しないことを確認 |
| APK の梱包 | APK 内の画像・Manifest 参照・`assets/licenses.compressed` を生成元と照合 | 今回は生成 GZIP 一致、runtime 102依存・ソース18項目・NYSL保持・旧Firebase通知0を確認。アイコンの全画素・参照照合は11月15日に実施 |

端末・エミュレータ上の計測テストや操作確認は実施していません。単体テストや計測テスト APK の生成成功から、ページ表示・ダウンロード・バックグラウンド更新・ジェスチャー等の端末上の動作成功は判断しません。

リリース APK の生成確認と、本番配布用の署名・公開は別の作業です。現在のリリース成果物は未署名であり、端末での確認にはデバッグ APK を使用します。

## ビルドと OSS 通知の運用

`minSdk` は23、`compileSdk` / `targetSdk` は37です。選定根拠は [AGP 9.1 の公式リリースノート](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[built-in Kotlin 移行手順](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlin の Gradle / AGP 対応表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Hilt 2.60.1](https://github.com/google/dagger/releases/tag/dagger-2.60.1)、[Gradle の公式チェックサム](https://gradle.org/release-checksums/)です。

JDK・SDK・Gradle は Docker 内だけで実行します。中間生成物・Kotlin 永続データ・依存キャッシュは Named Volume 内へ隔離し、デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保存します。テスト・設定確認・通知更新のみのタスクでは、古い APK を `release/` へ再出力しません。

通常の OSS 通知生成は固定入力だけを読み、通知本文をネット取得しません。Gradle の依存解決全体をオフラインにする設定ではありません。`:app:updateOssNotices` は候補を Volume 内へ生成し、レビュー後に固定入力へ反映します。`:app:checkOssNotices` で依存一覧と固定入力の不足を検出します。手順は [コントリビューションガイド](../Contributing.md) を参照してください。

## 次の作業

1. [実機確認手順](DEVICE_CHECKS.md)に沿って、起動・更新インストール・配色・戻る操作・IME・権限・ダウンロード・広告判定・永続更新・通知・OSS 画面を確認し、計測テストを端末上で実行する。
2. 実リスト・実 URL・端末上で候補比較数、遅延、メモリを測定し、正しい判定を保ったまま性能を改善する。
3. 拡張 uBlock 構文等の対応範囲を広げ、実サイトで互換性を確認する。
4. アプリ UI 全体のテーマ刷新、本番用署名・配布準備を進める。各変更の独立レビュー・Docker 検証・GitHub 反映を継続する。

## 原典とライセンス

CitrusBrowser は Hazuki 氏による [Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードとコミット履歴を継承しています。既存の著作権表示と [Apache License 2.0](../LICENSE) を保持し、取り込んだソース・ライブラリの通知本文も維持します。
