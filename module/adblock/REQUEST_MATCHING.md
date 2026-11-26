# リクエスト判定と索引

## 種別

`RequestClassification`は一つのリクエストに一つの種別を割り当てます。画像やscriptへ一律に`SUB_DOCUMENT`を付けたり、不明なリクエストにすべての種別を付けたりしません。種別限定の遮断と例外が、無関係な用途へ波及することを防ぎます。

| 手掛かり | 扱い |
| --- | --- |
| `isForMainFrame` | 現在表示中のURLとの一致にかかわらず`DOCUMENT` |
| 認識できる`Sec-Fetch-Dest` | image、script、style、frame等を優先して分類 |
| 明示されたXHR | `XMLHTTPREQUEST` |
| 拡張子・単一用途のAccept | 強い手掛かりがない場合に推定。JSONをscriptとはみなさない |
| 不明な用途・`Sec-Fetch-Dest: empty` | 明示XHR等の根拠がなければ`OTHER` |

ヘッダ名は大文字小文字を区別しません。[WebResourceRequest](https://developer.android.com/reference/android/webkit/WebResourceRequest)は完全な用途分類を提供せず、WebViewがすべての要求へFetch Metadataを公開する保証もありません。[Fetch Metadata仕様](https://www.w3.org/TR/fetch-metadata/)で`empty`はXHR専用ではありません。拡張子やAcceptも用途の確定情報ではないため、実際のWebViewで得られる情報の確認は残っています。

## 第三者

hostを小文字・末尾ドット・IDNについて正規化し、完全一致、IP、registrable domainの順に判定します。Public Suffixの結果がない単一ラベルのhostは元の正規化hostで比較します。`localhost`と`intranet`、異なるIPv6アドレスを、結果がどちらもnullという理由で同一サイトにしません。既存のPSLによる`co.jp`やprivate suffixの境界は維持します。

`Sec-Fetch-Site`はschemeやリダイレクト履歴も扱うため、その値を[ABPの第三者判定](https://eyeo.gitlab.io/adblockplus/abc/adblockpluscore/next/docs/url.js.html)の代わりにはしません。

## 候補の索引

`NetworkFilterTag`は、照合対象にも必ず現れる完全なtokenだけをネットワーク用の索引に使います。例えば部分一致`advert`を`advert`bucketへ置くと、URL中の`advertising`から候補に戻せません。安全なtokenがないルールはfallbackで確認します。遮断だけでなく`@@`例外も同じ方針です。Cosmetic用のTag処理は変更しません。

同じURL内に同じtokenが繰り返されても、そのbucketは一度だけ調べます。直接matcherとの結果一致、部分一致、境界、例外、型違いを`NetworkFilterIndexTest`で検証します。

## 測定範囲

合成データによる回帰は10,100ルール（安全に索引化できる10,000件と部分一致fallback100件）・100要求です。候補比較10,000回を検証します。同条件の全件走査は1,010,000回です。これは索引が働く条件の比較回数であり、実フィルタ一覧や端末での速度向上率を意味しません。

実際の内蔵`citrus_filter.txt`を直接読む回帰も用意しています。800ルールのうち索引472件、fallback328件（41%）でした。無関係・該当・部分語・第一者・ドメイン除外を含む43要求で、直接照合と索引の判定が一致し、候補比較はそれぞれ26,974回と12,564回でした。一部の要求では走査順の違いから索引側の比較が多くなるため、すべての要求が速くなるとはみなしません。部分一致を落とす旧索引よりfallbackが増える点も、正しさと性能を分けて評価します。

通常テストも経過時間を出力しますが、実行環境に左右されるため時間を合否条件にはしません。追加測定ではウォームアップ後に20回測定します。既存のテスト結果がキャッシュされている場合は、明示的にテストを再実行する必要があります。

実assetの測定は準備処理を除き、43要求を連続評価する1バッチを20回測定してp50/p95を出力します。ただし、デスクトップJVM上のMockitoによるUri getter、比較数の計数等のコストを含みます。この値を実WebView・端末の1要求あたりの遅延や、利用者の閲覧URL分布での速度とみなしません。

大規模な購読リスト・実サイトのURL分布、端末上のメモリ・遅延・誤遮断は別途測定します。WebViewの[要求interceptの制約](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,android.webkit.WebResourceRequest))を超える対応を、この判定だけで保証しません。
