---
name: adblock-verify
description: >-
  CitrusBrowser の広告ブロック（module/adblock とフィルタの構文・購読・注入）を変更したときの検証手順と判定基準。
  フィルタの解析・照合・購読の移行・要素非表示や scriptlet の注入を変えたとき、広告が消えない・ページが壊れると報告されたときに使う。
---

# 広告ブロックの検証

背景と現状の不足は `.agents/handoff/reports/adblock-root-cause-analysis.md`、方式の判断は `docs/ADBLOCK_ENGINE_PLAN.md` を参照する。

## 1. 単体テスト

```powershell
pwsh -File scripts/docker-build.ps1 -Task ":module:adblock:testDebugUnitTest :legacy:testDebugUnitTest"
```

- 構文を変えたら `RealListExcerptTest`（EasyList・AdGuard JP・uBO filters の実リスト抜粋）で、破棄した行数の変化を確認し、期待値を更新した理由を記録する。
- 抜粋は `module/adblock/src/test/resources/adblock/excerpts/` に置き、出典とライセンス（GPLv3 等）をファイル先頭と README に書く。APK に含めない。

## 2. 計測テスト（`emulator-measure` スキルで実行）

- 広告モジュール: `AbpDefaultSubscriptionsIntegrationTest`、`AbpUpdateWorkerTest`
- scriptlet: `ScriptletWebViewTest`（広告モジュール。文書開始時の注入と各 scriptlet の効果）
- 本体: `AbpUpdateIntegrationTest`、`AbpHttpSubscriptionIntegrationTest`、`CosmeticFilteringWebViewTest`

## 3. 実サイトでの比較

広告は表示のたびに変わるため、1回の観察を一般化しない。

- 対象の例: やらおん！、オタコム、はちま起稿（トップと記事1件、モバイル表示）。
- 条件は (A) 本体・広告ブロック OFF、(B) 本体・ON、(C) Gecko プレビュー（uBO 原本、Firefox＋uBO 相当）の3つ。同じ URL で上部・中部・下部のスクリーンショットを取る。
- 判定:
  - (B) で残った広告の種類（バナー、記事内、追従・オーバーレイ、反広告ブロックのダイアログ）。それぞれ、通信の遮断で消せるのか、要素の非表示や scriptlet が必要なのかを推定する。
  - 誤遮断がないか。本文・画像・ナビゲーション・コメントの欠け、記事ページ自体が遮断画面になっていないかを (A) と比べる。

## 判定基準（変更してはいけない性質）

- 未対応のオプションを含む行は、行ごと破棄する。制約を落として遮断の範囲を広げない。
- 種別を指定しない規則は、本文ページ（document）と popup に適用しない。適用するのは `||host^` と、`$document` を明示した規則だけ。
- 利用者の無効設定と、削除した購読を復活させない。購読の重複登録をしない。
- フィルタリストの本文や GPL のコードを APK に同梱しない。scriptlet は uBO と名前・引数だけを合わせた Apache-2.0 の独自実装とし、uBO の scriptlet のソースを開かない（wiki の公開仕様と実サイトの挙動だけを参照する。参照した資料を PR・コミット本文に記録する）。
- サイトごとのルールを追加することを、汎用の互換性の成功条件にしない。
