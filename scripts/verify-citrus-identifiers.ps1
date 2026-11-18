<#
.SYNOPSIS
  旧識別子の再混入と、主ソースの package 宣言・物理ディレクトリの不一致を検査します。
.DESCRIPTION
  ビルド生成物・バイナリ・Git 内部・引き継ぎ状態は読みません。ファイルは変更しません。
  出典表示と明示的な読み込み互換実装だけを理由付きで許可し、問題ファイルと件数を表示します。
#>
param()

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$sourceRoots = @('app', 'browser', 'legacy', 'languages', 'module', 'libraries', 'gecko-preview', 'gecko-ui-probe')
$allowed = @{
    'module/core/src/main/java/io/github/ajichama/citrusbrowser/core/utility/utils/BrowserIntentMigration.kt' = '保存済み Intent の旧クラス名・action・extra を現行名へ移行する読込口'
    'module/core/src/main/java/io/github/ajichama/citrusbrowser/core/utility/utils/BrowserSchemeCompat.kt' = '保存済み URI の旧 scheme を読み込む唯一の互換境界'
    'module/core/src/test/java/io/github/ajichama/citrusbrowser/core/utility/utils/BrowserIntentMigrationTest.kt' = '旧 Intent の復元互換を確認する入力データ'
    'module/core/src/test/java/io/github/ajichama/citrusbrowser/core/utility/utils/BrowserSchemeCompatTest.kt' = '旧 scheme の復元互換を確認する入力データ'
    'legacy/src/main/java/io/github/ajichama/citrusbrowser/legacy/backup/LegacyImportCompatibility.kt' = '旧バックアップの既知 Intent 識別子と置換トークンを移行する互換境界'
    'legacy/src/test/java/io/github/ajichama/citrusbrowser/legacy/backup/LegacyImportCompatibilityTest.kt' = '旧バックアップの識別子移行を検証する入力データ'
    'scripts/verify-citrus-identifiers.ps1' = '旧識別子の再混入を検出する検査パターン'
}
$issues = [Collections.Generic.List[object]]::new()
$allowedHits = [Collections.Generic.HashSet[string]]::new()
$checkedPackages = 0
$checkedText = 0

function Add-Issue([string]$File, [string]$Reason) {
    $issues.Add([pscustomobject]@{ File = $File; Reason = $Reason })
}

Push-Location -LiteralPath $taskRoot
try {
    $files = @(rg --files --hidden -g '!**/build/**' -g '!**/.gradle/**' @sourceRoots)
    if ($LASTEXITCODE -gt 1) { throw 'ソース一覧の取得に失敗しました。rg の利用環境を確認してください。' }
    foreach ($file in $files) {
        $relative = $file.Replace('\', '/')
        if ($relative -match '(^|/)jp/hazuki/(yuzubrowser|bookmark)(/|$)') { Add-Issue $relative '旧物理ディレクトリが残っています' }
        if ([IO.Path]::GetExtension($file) -notin @('.kt', '.java', '.xml', '.gradle', '.kts', '.pro')) { continue }
        $checkedText++
        $text = [IO.File]::ReadAllText((Join-Path $taskRoot $file))
        if ([IO.Path]::GetExtension($file) -in @('.kt', '.java')) {
            $declarations = [regex]::Matches($text, '(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;?')
            $layout = [regex]::Match($relative, '/src/[^/]+/(?:java|kotlin)/(.*)/[^/]+\.(?:kt|java)$')
            # colorpicker は Gradle で src/ を Java ソースルートに指定した既存の外部ライブラリ。
            if (-not $layout.Success -and $relative.StartsWith('libraries/colorpicker/src/')) {
                $layout = [regex]::Match($relative, '^libraries/colorpicker/src/(.*)/[^/]+\.java$')
            }
            if ($declarations.Count -ne 1 -or -not $layout.Success) {
                Add-Issue $relative 'package 宣言またはソースルートを一意に特定できません'
            } else {
                $checkedPackages++
                $package = $declarations[0].Groups[1].Value
                if ($package -match '^jp\.hazuki\.(yuzubrowser|bookmark)(\.|$)') { Add-Issue $relative '旧 package 宣言です' }
                if ($package.Replace('.', '/') -cne $layout.Groups[1].Value) { Add-Issue $relative 'package 宣言と物理ディレクトリが一致しません' }
            }
        }
        foreach ($line in $text -split '\r?\n') {
            if ($line -notmatch '(?i)jp\.hazuki\.(?:yuzubrowser|bookmark)|\byuzu\b') { continue }
            if ($allowed.ContainsKey($relative)) { [void]$allowedHits.Add($relative); continue }
            if ($relative -eq 'legacy/src/test/java/io/github/ajichama/citrusbrowser/legacy/backup/ImportUserSettingsTest.kt' -and
                $line.Trim() -ceq 'val old = "intent:#Intent;component=jp.hazuki.yuzubrowser/jp.hazuki.yuzubrowser.bookmark.view.BookmarkActivity;l.id=7;S.note=%E4%BF%9D%E6%8C%81;end"') {
                $label = "$relative#旧ブックマークIntent入力"
                $allowed[$label] = 'URL別設定の旧ブックマーク参照を移行する専用回帰入力（この行だけ許可）'
                [void]$allowedHits.Add($label)
                continue
            }
            if ($relative -eq 'legacy/src/main/res/values/strings_pref.xml' -and
                $line -match 'Based on Yuzu Browser.*Hazuki') {
                [void]$allowedHits.Add('原典の著作権表示')
                continue
            }
            # 移行元を示す製品名だけを許可し、リソース全体や旧コード識別子は除外しない。
            if ($relative -in @('legacy/src/main/res/values/portable_import_strings.xml', 'legacy/src/main/res/values-ja/portable_import_strings.xml') -and
                $line -match '^\s*<string name="legacy_import_(?:title|help)"[^>]*>[^<]*Yuzu Browser[^<]*</string>\s*$' -and
                $line -notmatch '(?i)jp\.hazuki\.(?:yuzubrowser|bookmark)|\byuzu:') {
                $label = "$relative#legacy_import_title/help"
                $allowed[$label] = '移行元アプリを示す製品名の表示（旧コード識別子は許可しない）'
                [void]$allowedHits.Add($label)
                continue
            }
            Add-Issue $relative '旧識別子が互換境界以外に残っています'
            break
        }
    }

    # 空の旧パッケージディレクトリも検出する。生成物を再帰走査しない。
    foreach ($root in $sourceRoots) {
        foreach ($src in @(Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 1 | Where-Object { $_.Name -eq 'src' })) {
            foreach ($set in @(Get-ChildItem -LiteralPath $src.FullName -Directory)) {
                foreach ($language in @('java', 'kotlin')) {
                    foreach ($oldPackage in @('jp/hazuki/yuzubrowser', 'jp/hazuki/bookmark')) {
                        $old = Join-Path $set.FullName "$language/$oldPackage"
                        if (Test-Path -LiteralPath $old -PathType Container) {
                            Add-Issue ([IO.Path]::GetRelativePath($taskRoot, $old).Replace('\', '/')) '旧パッケージディレクトリが存在します'
                        }
                    }
                }
            }
        }
    }

    foreach ($toolFile in @(rg --files --hidden -g '!**/build/**' -g '!**/.gradle/**' scripts tools docker)) {
        if ($toolFile.Replace('\', '/') -eq 'scripts/verify-citrus-identifiers.ps1') {
            [void]$allowedHits.Add('scripts/verify-citrus-identifiers.ps1')
            continue
        }
        if ([IO.Path]::GetExtension($toolFile) -notin @('.ps1', '.mjs', '.json', '.md', '.gradle') -and [IO.Path]::GetFileName($toolFile) -ne 'Dockerfile') { continue }
        if ([IO.File]::ReadAllText((Join-Path $taskRoot $toolFile)) -match 'yuzu-(?:android-builder|gradle-cache|build-cache|buildsrc-gradle)') {
            Add-Issue $toolFile '旧 Docker 自己識別子が残っています'
        }
    }
} finally {
    Pop-Location
}

Write-Output "識別子検査: テキスト $checkedText 件、package/物理パス $checkedPackages 件"
foreach ($hit in @($allowedHits | Sort-Object)) {
    $reason = if ($allowed.ContainsKey($hit)) { $allowed[$hit] } else { 'Apache ライセンスに基づく原典・作者への帰属表示' }
    Write-Output "許可: $hit — $reason"
}
$grouped = @($issues | Group-Object File)
foreach ($group in $grouped) { Write-Output ("問題: {0} — {1}" -f $group.Name, (($group.Group.Reason | Sort-Object -Unique) -join '、')) }
if ($issues.Count) {
    Write-Output "不合格: $($grouped.Count) ファイル/ディレクトリ、$($issues.Count) 件"
    exit 1
}
Write-Output '合格: 旧識別子の混入と package/物理パスの不一致はありません。'
