# ページ配色

アプリUIのテーマと、ページの`prefers-color-scheme`を独立させます。各`NormalWebView`が専用の`ContextThemeWrapper`を持ち、ActivityのThemeや共有Resourcesは変更しません。通常・キャッシュ・制限付きキャッシュの各モードは、すべてこの生成経路を使います。

| 保存値 | 動作 | isLightTheme | 自動暗色化の許可 |
| --- | --- | --- | --- |
| 0 | 明るい配色 | true | false |
| 1 | サイトのダーク配色 | false | false |
| 2 | サイトを優先した自動暗色化 | false | true |
| 3（旧値） | 読込時に2へ読み替える | false | true |
| -1（サイト別のみ） | 上書きしない | 変更しない | 変更しない |

自動暗色化はWebViewのfeatureを確認して許可します。サイト独自の配色や暗色化拒否を上書きする保証はありません。旧「強制暗色化」のようにサイト自身の配色を無視する動作は、現行のtargetSdkで旧ForceDark APIが無効なため提供しません。新しい選択肢は0〜2の3件だけです。

`PageColorMode`を保存値の正規化に共用します。グローバル設定は`AppPrefs.load()`で該当keyだけを書き換え、サイト別JSONのkey `7`は読み書き時に同じ値へ揃えます。未指定`-1`を明るい配色へ変換しません。ダイアログとSpinnerは正規化済みの0〜2をindexとして使い、設定コピー・一時設定の復元も同じsetterを通ります。

配色変更は専用Themeへ適用し、公開`View.dispatchConfigurationChanged()`で既存WebViewへ通知します。`loadUrl()`、`reload()`、`saveState()/restoreState()`や全タブの再生成は行いません。OSからのconfiguration変更時もページ専用Themeを適用し直します。基底Activityへは標準`ContextWrapper.baseContext`の連鎖で到達できます。

## 検証

純粋な単体テストは旧値3の読替、不正値、サイト未指定、JSON roundtripを対象にします。`PageColorWebViewTest`は次の実WebView検証を行います。

- アプリUIと独立した明暗、`matchMedia`とサイトCSSの反映
- 設定を切り替えてもDOM入力・JavaScriptの状態・読込回数を保持すること
- コピーと復元、旧グローバル値の単一key移行、ActivityへのContext連鎖
- API26以上かつ対応WebViewで、ページ描画のPixelCopyによる自動暗色化確認

実機で`PageColorWebViewTest`の5件が初回・最終再実行とも失敗・スキップ0ですべて成功しました。既存DOMの入力・JavaScript状態・読込回数を保持した配色切替、アプリUIからの独立、旧key移行、コピー・復元、ActivityへのContext連鎖、PixelCopyでの暗色化を確認しています。

この結果は確認した実機とテストページに限ります。他OS・別WebView、実サイトやサイト別設定UIの操作、POST送信中の操作を含む全場面は未確認です。IMEは要求フラグの確認までで、表示・入力中レイアウト等の詳細動作は未検証です。APKのコンパイルと計測の端末実行、手動操作は引き続き分けて記録します。

## 公式資料

- [Android WebViewの暗色化仕様](https://developer.android.com/develop/ui/views/layout/webapps/dark-theme)
- [WebSettingsCompatの現行API](https://developer.android.com/reference/androidx/webkit/WebSettingsCompat#setAlgorithmicDarkeningAllowed(android.webkit.WebSettings,boolean))
- [Chromiumのconfiguration変更と配色テスト](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/javatests/src/org/chromium/android_webview/test/AwDarkModeTest.java)
- [ChromiumのView configuration変更通知](https://raw.githubusercontent.com/chromium/chromium/main/android_webview/java/src/org/chromium/android_webview/AwContents.java)

2026-11-16に確認。Chromiumはconfiguration通知でrenderer preference更新を要求します。公開APIによる通知を使い、Chromium内部クラスへは依存しません。
