# フィルタ更新ジョブ

WorkManager 2.11.2 の標準 `CoroutineWorker` を使用する。導入時は minSdk23 だったため、minSdk24 の 2.12 系は採用しなかった（2026-11-20 に本体を minSdk24 にしたので、2.12 系へ更新できる）。依存取得は既存の Hilt EntryPoint を使い、WorkManager 専用の Hilt 拡張は追加していない。

- Application 初期化後とブラウザ再開時に更新要否を確認し、約 1 時間ごとの永続 periodic ジョブでも期限・parser revision・内蔵 asset 版を確認する。OS の省電力制御等により実行時刻は遅れる。
- 自動更新は購読 ID ごとの unique `KEEP` で重複を抑える。広告ブロック OFF は取得せず終了し、次の設定変更や定期確認で再評価する。Wi-Fi 専用設定を実行前にも確認し、未接続なら指数 backoff で再試行する。無課金回線と Wi-Fi は同一視しない。
- 手動更新は別々の実 Work ID を持ち、画面では同じ購読への連打と一括更新中の重複要求を抑える。明示的な手動更新は Wi-Fi 専用設定を越えて実行できる。自動処理の Wi-Fi 待ちで手動要求を止めない。
- IO・HTTP 408/429/5xx は再試行する。手動処理は初回を含め最大 5 回、自動処理は WorkManager の backoff と次回の定期確認に従う。その他の 4xx・不正 URL・不正フィルタヘッダーは失敗として終了する。304 と変更なしは成功として扱う。Worker のキャンセルは HTTP Call のキャンセルへ伝える。
- 入力へ保存するのは購読 ID・手動フラグ・要求時刻だけで、entity や ResultReceiver は保存しない。DB の最新行を再取得し、削除・無効化・URL 編集を尊重する。手動一括更新の再試行では、同じ要求以降に成功した購読を繰り返し取得しない。
- 画面は enqueue 完了後の UUID を SavedStateHandle に保存し、Lifecycle に紐づく Flow で再接続する。保存との隙間で終了した場合は未完の手動 Work の永続タグからも復元する。失敗・取消・skip・削除済み Work 記録は成功と区別する。購読一覧は古い entity を差し戻さず DB から読み直す。

4 種類のフィルタは 1 ZIP として AtomicFile 経由で公開する。公開直前にキャンセルを確認し、公開から条件付き DB metadata 更新までの短い区間を NonCancellable にする。DB 例外では自分の commitId が現行の場合だけ直前の束へ戻し、新しい公開を消さない。正常終了・復旧成功時は退避ファイルを清掃し、復旧自体の保存が失敗した場合だけ `.rollback` を復旧用に残す。

ZIP と Room は別ストレージであり、強制終了・電源断まで含む完全な一括トランザクションではない。公開後・DB 確定前の強制終了では、有効な新 ZIP と古い DB metadata、または退避ファイルが残る場合がある。次の実行で現在の URL・期限・parser revision を再確認して再試行する。購読を再挿入したり無効化を解除したりしない。

JVM テストは HTTP キャンセル、typed outcome、失敗時の旧 ZIP、公開所有権、停止要求と DB 確定を検証する。

実機で`AbpUpdateWorkerTest`の5件が初回・最終再実行とも失敗・スキップ0で成功した。実Roomの列限定SQLによるmetadata・無効状態の保全、標準WorkerFactoryでの不正入力のFAILED、広告OFF時の自動DispatcherのSUCCEEDED/Skipped、要求UUIDと入力データ、SavedStateHandleを再構成したモデルの復元・重複完了抑止を確認した。

加えて、実機で本体Hiltローカル購読の統合2件が最終再実行まで成功した。実CitrusBrowserApplication・注入DB・公開手動更新窓口から標準Workerを通し、file://購読の更新、metadataとZIP4カテゴリ・本文、無効購読のskip、専用fixtureの後始末を確認した。

外部HTTP購読、回線切替、実プロセス停止・再起動、OS再起動、Doze、Wi-Fi制約、通信復帰、実画面の再表示・状態復元は別途確認が必要。SavedStateHandleを再構成したモデルのテストは、実Activity・プロセスを再起動した確認ではない。本体UIの手動スモーク確認も、購読更新・再試行の全操作を検証したものではない。

公式資料: [WorkManager release](https://developer.android.com/jetpack/androidx/releases/work)、[Work の定義](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、[重複管理と観測](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)。
