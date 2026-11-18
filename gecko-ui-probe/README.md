# 開発専用の MyFilters コピー probe

> **凍結（2026-11-19）**: この probe は読み取り段階（実機 PASS 済み）で凍結し、機能を追加しません。別 UID からクリップボード経由で MyFilters を読み書きする方式は利用者の clipboard と既存 profile に依存して脆いため、scriptlet / procedural の検証は `gecko-preview` の計測 `GeckoDisposableProfileScriptletTest`（同一 UID・同一プロセス・使い捨て Gecko profile）へ移しました。以下は凍結時点の記録です。

`gecko-ui-probe` は Java と Android framework だけを使う、debug 専用・外部 runtime 依存なしの検査アプリです。自己 target の `Instrumentation` から、別 UID の既存 Citrus Gecko Preview を accessibility と通常キーで操作します。本体や preview の起動・終了・URI 再読込は行いません。通常の preview 計測 **3 JUnit 件とは別**で、JUnit 総数には加算しません。

`android.permission.INTERNET` は、scriptlet/procedural 判定用の fixture が `127.0.0.2` の loopback で待ち受けるためだけに宣言しています。外部への通信やライブラリの取得は行わず、外部 runtime 依存なしの方針は変わりません。

目的は、CodeMirror 文書の通常 Ctrl+A/C を2回行い、全文のコピー結果が一致するかを確認することです。MyFilters の追記・Apply・復旧貼付はまだ実装していません。

## 構築と実行

JDK・SDK・Gradle・adb は Docker 内で使用します。

```powershell
pwsh -File scripts/docker-build.ps1 -Task ":gecko-ui-probe:assembleDebug"
```

APK は Named Volume 内の次の場所に生成されます。通常の配布 APK 抽出対象には追加していません。

```text
/build-cache/gecko-ui-probe/outputs/apk/debug/gecko-ui-probe-debug.apk
```

`<SDK_CONTAINER>` は、この Volume と端末用 adb を使える検証コンテナへ置き換えます。複数端末がある場合は、使用する端末だけを明示選択してください。

```text
docker exec <SDK_CONTAINER> adb install -r -t /build-cache/gecko-ui-probe/outputs/apk/debug/gecko-ui-probe-debug.apk
docker exec <SDK_CONTAINER> adb shell am instrument -w io.github.ajichama.citrusbrowser.geckouiprobe/io.github.ajichama.citrusbrowser.geckouiprobe.ClipboardProbeRunner
```

`testOnly=true` のため導入には `-t` が必要です。通常の `AndroidJUnitRunner` や `-e class` は使いません。

## 実行条件と保全

- 既存の preview を前面にしてください。別 UID が入力焦点を持つ間は操作しません。
- 既存の設定画面を勝手に置き換えません。必要なら通常 UI でマイフィルターを開いてください。未適用の編集がある場合は停止します。
- 検索欄・URL 欄を除外し、MyFilters 文書 node への焦点一致を要求します。コピー無応答を空本文として扱いません。
- 元 clipboard の全 item を確認し、URI／Intent が含まれれば上書き前に停止します。本文やハッシュをログ・結果へ出力しません。
- 一時的な clipboard 操作は、非公開の透明 Activity が焦点を得た間だけ行います。元の `ClipData` は RAM に保持し、自分の結果が変わっていない場合だけ復旧します。他の更新を検出した場合は上書きしません。
- 原本 uBO・拡張 storage・MyFilters 本文・有効／信頼 checkbox は変更しません。自分が開いた設定 overlay と relay window だけを片付けます。

結果は固定項目 `probeStatus=PASS/FAIL`、`stage`、`clipboardRestore`、`copyResponseUnresolved` などで返します。`clipboardRestore` の値は次のとおりで、主処理と後始末の失敗も別に記録します。

- `RESTORED`: 自分の sentinel／確定したコピー結果を確認し、元 clipboard へ戻した。
- `UNTOUCHED`: clipboard へ書き込んでいない（書込前に `clipboardTouched` を立てるため、書込済みをこの値にしない）。
- `FOREIGN_CHANGE_PRESERVED`: コピー結果を確定した後に他の更新を検出したため、上書きしなかった。
- `PROBE_COPY_LEFT_UNVERIFIED`: 2回目のコピー不一致やコピー応答の遅延で、probe 自身が送ったコピーの内容が残っている可能性がある。他の更新と判別できないため上書きしない。
- `PENDING`／`FAILED`: 書込後に復旧処理まで到達していない／復旧処理が失敗した。

`copyResponseUnresolved=true` は、コピー応答を観測できないまま後始末へ進んだことを示します。`RESTORED` でも、その後に遅れて届くコピーまでは除外できません。公開 clipboard API に書込者の厳密な証明や CAS はないため、端末で他のコピー操作を並行しないでください。

## 現在の検証範囲

独立 APK の構築、自己 target、外部依存なしを確認済みです。Android 15 の実機で文書への焦点付与・通常キーによる2回のコピー一致（2文字）・元 clipboard の `RESTORED`・後始末の失敗なし・preview PID の維持を確認し、**読み取り専用 probe は実機 PASS** しました。この端末の結果を、任意サイズの文書や全 OS の成功には広げません。

明示 proof mode、MyFilters の一時追記・Apply・元全文復旧、procedural／scriptlet の fixture 自動判定はまだ接続していません。ルール書換えや Apply の実機試験も未実施です。
