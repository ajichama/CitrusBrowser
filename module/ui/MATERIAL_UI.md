# Material UI の変更と機能保持

2026-11-17 の変更。既存Viewsを [Material Components 1.14.0](https://github.com/material-components/material-components-android/releases/tag/1.14.0) のMaterial3へ段階的に更新する。画面遷移や高度な設定を置き換えず、日常画面の形状・余白・文字組みを整える。

| 対象・主なファイル | 外観の変更 | 保持した動作・設定 |
| --- | --- | --- |
| 共通テーマ `res/values/styles.xml` | 親を `Theme.Material3.DayNight` に変更。見出し・本文にMaterial3の文字組みを使用 | `BrowserThemeDayNight` と派生テーマ名、ActionBar有無、既存の昼夜配色を保持。新しいsurface等の役割は既存色を参照し、Dynamic Colorsで自動上書きしない |
| アドレス欄 `legacy/.../toolbar/main/UrlBar.kt`、`SwipeTextButton.kt`、`toolbar_url_box.xml` | 大きい角丸と横の内側余白。通常の文字色を入力欄の面に合わせる | 既存View・ID、左右ボタン、配置・高さ・文字サイズ、タップ/長押し/スワイプ、割り当てたActionを保持。テーマで明示した文字色・押下色を優先。ユーザー設定の高さを固定値で上書きしない |
| メニュー `legacy/res/layout/menu_list_item.xml`、`drop_down_list.xml`、`menu_drop_down_background.xml` | 角丸、行の余白と高さ、本文の文字組みを更新 | 既存PopupWindow、項目・順序・Action ID・アイコン表示切替・起動位置の処理はそのまま。明示したメニュー文字サイズは既存コードで優先適用 |
| 共通設定 `CitrusBasePreferenceFragment.kt` | 一覧に内側余白を加え、区切り線を整理 | Preferenceの項目・キー・依存関係・保存先・画面遷移を保持。再生成した一覧へ一度だけ余白を加え、再開のたびに累積させない |
| 検索設定 | Material3の入力欄・ダイアログ・追加ボタン・一覧余白 | 名称/URL/色/ファビコンの編集、選択・並べ替え、JSON形式を保持。[検索の互換性](../search/SEARCH_COMPATIBILITY.md)を参照 |
| ホーム | 検索リンクとタイルの形状、末尾操作とフォーカス表示 | 色・列数・画像寸法・並び・既存リンクは保持。[ホームの対応表](../../legacy/SPEED_DIAL_UI.md)を参照 |

toolbar/action/gestureの保存キー・ID・ファイル形式や、独自テーマのJSON形式は変更していない。ページ配色の保存値やWebView用Contextの方式も、このUI変更では変更していない。

## 検証と残る確認

Dockerの対象単体テストとUI/search計測APK生成は成功した。検索取り込み・選択IDの追加9件も対象単体テストで成功した。`MaterialThemeCompatibilityTest` と `SearchCompatibilityTest` は昼夜のテーマ属性、Material部品/検索フォームの生成、設定キーの限定移行、URLの扱いと保存復旧を確認する計測コードであり、現時点では端末実行前である。

実Activityでの上下InsetsとIME、狭い画面/大きい文字、縦横切替、独自テーマ、各toolbar位置やカスタム操作、メニューとホームの実描画は端末で別途確認する。ソースの経路を保持したこと、計測APKを生成できたこと、端末上で操作できたことは分けて記録し、この段階で全操作・全OSの成功とはしない。
