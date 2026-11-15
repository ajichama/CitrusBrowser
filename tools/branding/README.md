# CitrusBrowser アイコン原本

`citrus-icon.svg` が形状・配色の原本です。柑橘を示す黄橙の C と黄緑の葉を曲線から新規に作成しています。既存の Yuzu 画像を入力にした加工は行っていません。

```powershell
pwsh -NoProfile -File scripts/generate-citrus-icons.ps1 -PreviewPath "$env:TEMP/citrus-preview.png"
pwsh -NoProfile -File scripts/generate-citrus-icons.ps1 -Check
```

生成には Windows 標準の GDI+ を使い、追加ツールやネットワーク接続は不要です。SVG の対応命令は絶対座標の `M`・`L`・`C`・`Z` です。PNG を編集する代わりに SVG を編集し、スクリプトを再実行してください。`-Check` は一時ディレクトリで再生成し、既存出力との SHA-256 一致を検査します。

プレビューは `-PreviewPath` を明示した場合だけ書き出します。保存先には標準の一時ディレクトリや Git 管理外の `.agents/handoff/reports/` を使ってください。`release/` は完成 APK 専用のため指定できません。`-Check` とプレビュー出力は併用できません。

| 用途 | 生成内容 |
| --- | --- |
| API 23 以降の従来表示 | 5密度の 48・72・96・144・192px PNG |
| 円形表示 | 同じ5密度の round PNG と Manifest の roundIcon 参照 |
| API 26 以降 | 108dp の vector 前景と専用背景色による adaptive icon |
| API 33 以降 | 同じ輪郭の monochrome レイヤー |
| 通知 | 24dp の白一色 vector。透明部分は透明のまま保持 |
| 配布用 | マスクを焼き込まない 512px 正方形 PNG |

adaptive 前景は中央66dpの円内に収まることを曲線の座標から確認します。従来表示では原本の72dp表示領域を各密度へ写し、通知では同じ輪郭を24dpの内側20dpへ収めます。背景形状の違い以外に、別図案や既存ビットマップを混在させません。

ショートカット用の既存 `ic_launcher_background` は変更せず、ランチャーには `citrus_launcher_background` を使います。置き換えた既存 XML の著作権・ライセンスヘッダは保持します。SVG 原本と新規資源は Apache License 2.0 です。
