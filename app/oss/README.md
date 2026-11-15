# OSS通知の生成と更新

アプリのライセンス画面は、現在のruntime依存と固定入力から生成した`assets/licenses.compressed`を表示します。通常ビルドはライセンスURLへアクセスしません。既存の表示経路とMikan Browser専用HTMLの著作権表示を保持しています。

## 固定入力

- `dependencies.xml`: 解決済みの外部依存のGAV、POMの宣言、LICENSE/NOTICE本文と出典。初回はdebug/release共通の99依存。
- `texts/`: UTF-8/LFの固定本文。本文のSHA-256をmetadataに記録します。同じ全文は共有できますが、異なる著作権行やNOTICEは省略しません。
- `sources.xml`と`sources/`: 旧`app/licenses.yml`の`forceGenerate: true`だったソース由来18件。コピー済みソースやスクリプトはMaven graphだけでは判定できないため、通常の依存とは別に維持します。
- `../html/license/MikanBrowserLicense.html`: Mikan Browserの専用NYSL本文。旧YAMLのBSD仮指定は採用しません。
- `../html/license/fixLicensesHtml.css`: 既存表示用CSS。

`app/licenses.yml`は旧生成器の履歴資料として残しますが、新タスクは読みません。過去のFirebase等、現在のruntimeにないMaven補足は通知へ出しません。旧固定GZIPはソース由来通知を取り出した上で削除しました。原典はGit履歴に残っています。

## 通常生成

```powershell
pwsh -File scripts/docker-build.ps1 -Task "generateDebugOssNotices generateReleaseOssNotices checkOssNotices"
```

アプリの各variantには公開Android Components APIで生成assetsを登録しているため、`assembleDebug`等でも自動生成されます。生成先は`layout.buildDirectory`配下で、Dockerの`init.gradle`により`/build-cache/app`へ隔離されます。本文はAPKへだけ組み込まれ、ワークスペースへ生成物を戻しません。

依存座標や本文が不足すると、`/build-cache/app/reports/oss/<variant>/missing.txt`を作って生成を失敗させます。未確認のライセンス名・本文を推測したり、不完全な通知をAPKへ入れたりしません。キャッシュが準備されたDocker内で生成タスクに`--offline`を指定しても生成できます。

## 明示的な更新

```powershell
pwsh -File scripts/docker-build.ps1 -Task "updateOssNotices"
```

この操作だけがライセンス本文を取得します。アプリvariantの`runtimeConfiguration`から、プロジェクト依存を除いた公開`artifactView`で実際のJAR/AARを選び、推移依存を含めて収集します。テスト・KSP・KAPT・Gradleプラグイン、配布物のないBOMは含めません。ローカルに取り込んだソースは別途`sources.xml`で扱います。

POMはGradle公開`MavenPomArtifact` APIで取得します。埋込LICENSE/NOTICEと、POMで宣言されたすべての本文URLを候補にします。HTML・JSON・XML・バイナリ応答を本文とみなしません。既知のApache URLとGitHubの`apache-2.0`ライセンスAPIだけをApache公式のプレーン本文へ正規化し、それ以外のライセンスを名前から推定しません。取得失敗もURLごとに保存し、同じ失敗を依存件数分繰り返しません。

更新候補は`/build-cache/app/oss-review/<variant>/oss/`、不足情報は同階層の`warnings.txt`に出します。固定入力は自動で上書きしません。

1. 各variantの候補metadataと警告を確認します。debug/releaseで依存が異なる場合は、両方のGAVを固定metadataへ保持します。
2. 新規・変更された宣言、本文、出典、著作権行、NOTICEを確認します。本文が足りない場合は、同じ依存バージョンの公式ソース等から取得して出典を記録します。
3. 確認できた`dependencies.xml`と参照される`texts/`を固定入力へ反映します。ソース由来18件とMikan専用HTMLは引き続き維持します。
4. 通常生成、`checkOssNotices`、オフライン生成、APK内assetの展開結果を確認し、親の標準ビルドへ合流します。

初回のRE2/J 1.8はPOMのURLがHTMLを返すため、[公式リリースタグのLICENSE](https://raw.githubusercontent.com/google/re2j/re2j-1.8/LICENSE)を`texts/re2j-1.8-LICENSE.txt`へ固定しました。更新候補で不足した場合も、このバージョンに対応する確認済み本文を失わないよう、差分を確認して反映してください。

## 保全と検証

本文のSHA-256はUTF-8/LFに正規化して照合し、Gitの改行変換による誤差を避けます。生成GZIPと更新候補metadataは、一時ファイルを閉じてから原子的に置き換えます。失敗時は以前の公開済みファイルを保持します。ライセンス画面はassetを毎回展開するため、古い展開キャッシュは残りません。

POMのDOCTYPE・外部entity・外部DTD/schemaアクセスを禁止し、HTMLへ出す取得文字列をエスケープします。外部リンクはhttp(s)に限定します。アーカイブはAAR直下のJAR一段だけを読み、entry数、総展開量、保持する本文量を制限します。非通知entryも上限付きで読み、無制限に展開しません。

生成ロジックの回帰は`buildSrc/src/test/java/OssNoticesTest.kt`にあります。Docker内で`buildSrc:test`を実行します。Android Componentsの内部APIには依存しません。POM取得の`ArtifactResolutionQuery`はGradle9.3.1で公開されていますがmaintenance扱いのため、将来の置換をresolver箇所に限定しています。
