# 開発進捗と検証範囲

更新日: 2026-11-15。正式 API 37 ビルド構成、独自アイコン、OSS 通知生成、AdBlock 互換改善を含む実装スナップショットの状態です。実装、APK の生成、端末での動作確認を分けて記載します。Phase 5 全体は進行中です。

## フェーズ別の状態

| 対象 | 実装・完了事項 | 未完了・検証の限界 |
| --- | --- | --- |
| Phase 1: リポジトリ・ビルド基盤 | Yuzu Browser のコミット履歴・原典の帰属を継承。Docker と Named Volume でビルドを隔離 | 継続してホストへの中間生成物の混入を監視 |
| Phase 2: 命名・独自アイコン | 表示名、パッケージと物理配置、権限、内部識別子を Citrus へ移行。旧保存データの読み込み互換を維持。独自ランチャー・通知アイコンを実装し、APK の PNG 10点・148,608画素の一致と adaptive / round / monochrome・通知の参照を確認 | 各 OS・ランチャー・通知上の実機表示は未確認 |
| Phase 3: 正式 API 37 構成 | AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.4.21 / Hilt 2.60.1 / KSP 2.3.12。JDK 21、built-in Kotlin と legacy-kapt へ移行 | 下記の自動検証に成功。端末での起動・更新インストールは未確認 |
| Phase 3: 生成物・署名 | SDK 別名ディレクトリと非対応警告抑止を削除。Wrapper 更新と公式 SHA-256 照合。デバッグ署名鍵・adb 鍵を専用 Volume に保持 | APK の生成成功は全 OS での互換保証や本番署名の用意を意味しない |
| Phase 3: OSS 通知 | runtime 99 依存・102 文書とソース由来 18 項目を固定入力化。SHA-256 検証、固定入力からの通知生成、明示更新タスクを実装。通信なしの再生成と APK 内の新 GZIP の一致、NYSL 保持・旧 Firebase 通知の除去を確認 | ライセンス画面の実機表示は未確認 |
| Phase 4: OS 互換基盤 | compileSdk / targetSdk 37、Edge-to-Edge、Predictive Back、Intent/Bundle/Parcel 互換、JobIntentService 廃止、ストレージ・可視性対応を実装 | 旧 OS・IME・権限・バックグラウンド動作を含む実機確認は未実施 |
| Phase 4: ページ配色 | 既存4設定と現行 WebView API の整合を設計 | 新しいページ配色制御は未実装。UI テーマの刷新も継続課題 |
| Phase 5: AdBlock 互換・保存 | 初回のパーサ改善と一括保存の安全性を改善。キャッシュ v2 の 32bit 保存・v1 読取、旧 snapshot 保持、行単位ドメイン条件の最具体指定優先、elemhide / generichide、汎用要素フィルタ無視設定を実装 | 今回の追加互換回帰16件を含む単体テストに成功。実サイト・端末での確認と、拡張 uBlock 構文の対応拡大は残る |
| Phase 5: 更新ジョブ・性能 | 既存更新処理の競合・失敗時の保存安全性を改善 | WorkManager による永続化、再試行、リクエスト種別判定の精度、性能測定・改善は設計または未着手 |

## 自動検証の結果

| 検証 | 再現コマンド | 2026-11-15 の結果 |
| --- | --- | --- |
| 本体 APK・通常単体テスト・計測テスト APK | `pwsh -File scripts/docker-build.ps1 -Task "assembleDebug testDebugUnitTest assembleDebugAndroidTest"` | 3タスク成功。通常モジュールの単体テスト128件成功。計測テストは APK 生成まで |
| ビルド処理の単体テスト | `pwsh -File scripts/docker-build.ps1 -Task ":buildSrc:test"` | buildSrc の15件成功。通常モジュール128件とは別集計 |
| 識別子・パッケージ配置 | `pwsh -File scripts/verify-citrus-identifiers.ps1` | テキスト1,355件、package/物理パス799件で問題0。原典帰属と明示的な移行互換を許可 |
| ビルド・コミット補助処理 | `pwsh -File scripts/test-build-workflow.ps1` | モック検証成功。引数・APK 出力対象・明示 Push を検証し、実ビルド・公開操作は行わない |
| 通知のオフライン再生成 | 隔離コンテナのネットワークを切断し、`:app:generateDebugOssNotices :app:generateReleaseOssNotices` を `--offline --rerun-tasks` で実行 | 成功。通常生成が通知本文のネット取得に依存しないことを確認 |
| APK の梱包 | APK 内の画像・Manifest 参照・`assets/licenses.compressed` を生成元と照合 | アイコン一致、生成 GZIP 一致、runtime 99依存・ソース18項目・NYSL保持・旧Firebase通知0を確認 |

端末・エミュレータ上の計測テストや操作確認は実施していません。単体テストや計測テスト APK の生成成功から、ページ表示・ダウンロード・バックグラウンド更新・ジェスチャー等の端末上の動作成功は判断しません。

## ビルドと OSS 通知の運用

`minSdk` は23、`compileSdk` / `targetSdk` は37です。選定根拠は [AGP 9.1 の公式リリースノート](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[built-in Kotlin 移行手順](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlin の Gradle / AGP 対応表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Hilt 2.60.1](https://github.com/google/dagger/releases/tag/dagger-2.60.1)、[Gradle の公式チェックサム](https://gradle.org/release-checksums/)です。

JDK・SDK・Gradle は Docker 内だけで実行します。中間生成物・Kotlin 永続データ・依存キャッシュは Named Volume 内へ隔離し、デバッグ署名鍵と adb 鍵は `citrus-android-home:/root/.android` に保存します。テスト・設定確認・通知更新のみのタスクでは、古い APK を `release/` へ再出力しません。

通常の OSS 通知生成は固定入力だけを読み、通知本文をネット取得しません。Gradle の依存解決全体をオフラインにする設定ではありません。`:app:updateOssNotices` は候補を Volume 内へ生成し、レビュー後に固定入力へ反映します。`:app:checkOssNotices` で依存一覧と固定入力の不足を検出します。手順は [コントリビューションガイド](../Contributing.md) を参照してください。

## 次の作業

1. Phase 4 のページ配色制御を実装し、既存設定からの移行を検証する。
2. Phase 5 の永続更新・再試行、リクエスト種別判定と性能を順に改善する。
3. 実機で起動、更新インストール、配色、IME と戻る操作、権限、ダウンロード、広告ブロック、通知と OSS 画面を確認する。
4. 各変更を独立レビュー・自動検証し、GitHub へ反映する運用を継続する。

## 原典とライセンス

CitrusBrowser は Hazuki 氏による [Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードとコミット履歴を継承しています。既存の著作権表示と [Apache License 2.0](../LICENSE) を保持し、取り込んだソース・ライブラリの通知本文も維持します。
