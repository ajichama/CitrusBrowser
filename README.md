# CitrusBrowser

[![license](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

<img src="app/src/main/ic_launcher-web.png" width="192" height="192">

**CitrusBrowser** (`io.github.ajichama.citrusbrowser`) は、オープンソースのAndroid向け高機能・高カスタマイズWebブラウザ **[Yuzu Browser](https://github.com/hazuki0x0/YuzuBrowser)**（作者: Hazuki氏、Mikan Browser 派生）をベースに、最新のAndroid OS（Android 16 / 17）対応・モダンなUIデザイン・広告ブロック等の機能強化を行う後継ブラウザプロジェクトです。

---

## 主な特徴・開発方針

- **高いカスタマイズ性の継承**: Yuzu Browser の強みであるカスタムUI・ツールバー・ジェスチャー・アクション設定を継承
- **最新 Android OS 対応**: Android 16 (API 36) / Android 17 (API 37) 世代のシステム仕様（Edge-to-Edge、Predictive Back、最新権限モデル等）への対応
- **モダンなUIと機能進化**: Material Design 3 (Material You) 対応および広告ブロック（AdBlock）機能の最新化

---

## ビルド方法 (Docker 隔離ビルド)

ホスト環境を汚さずに Docker コンテナ内でビルド・単体テストを実行できます。

```powershell
pwsh -File scripts/docker-build.ps1
```

ビルドが完了すると、`release/` ディレクトリに APK ファイルが出力されます。

---

## クレジット・謝辞 (Credits)

本プロジェクトは [Hazuki0x0/YuzuBrowser](https://github.com/hazuki0x0/YuzuBrowser) のソースコードおよびコミット履歴をベースに改変・開発されています。素晴らしいブラウザを開発・公開された Hazuki 氏およびコントリビューターの皆様に深く感謝いたします。

## ライセンス (License)

    Copyright (C) 2017-2021 Hazuki
    Copyright (C) 2026 CitrusBrowser Contributors

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
