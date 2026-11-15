<#
.SYNOPSIS
  独自 SVG 原本から Android の各アイコンと配布用 PNG を決定論的に生成します。
.DESCRIPTION
  Windows 標準の GDI+ のみを使用し、既存ビットマップを入力にしません。
  原本は tools/branding/citrus-icon.svg。編集できるパス命令は絶対座標の M/L/C/Z です。
  -Check は一時ディレクトリで再生成し、共有済み出力のバイト一致を検査します。
.EXAMPLE
  pwsh -NoProfile -File scripts/generate-citrus-icons.ps1 -PreviewPath "$env:TEMP/citrus-preview.png"
  pwsh -NoProfile -File scripts/generate-citrus-icons.ps1 -Check
#>
param([switch]$Check, [string]$PreviewPath = '')

$ErrorActionPreference = 'Stop'
if ($Check -and $PreviewPath) { throw '-Check ではプレビューを出力しません。確認用画像は通常の生成時に明示してください。' }
if (-not $IsWindows) { throw 'この生成スクリプトは Windows の標準 GDI+ を使用します。生成済み資源のビルドに GDI+ は不要です。' }
Add-Type -AssemblyName System.Drawing
$iconRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$iconTemporary = Join-Path ([IO.Path]::GetTempPath()) ('citrus-icons-' + [guid]::NewGuid().ToString('N'))
$outputRoot = if ($Check) { $iconTemporary } else { $iconRoot }
$preview = $null
if ($PreviewPath) {
    $preview = if ([IO.Path]::IsPathRooted($PreviewPath)) { [IO.Path]::GetFullPath($PreviewPath) } else { [IO.Path]::GetFullPath((Join-Path $iconRoot $PreviewPath)) }
    $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $releaseBase = [IO.Path]::GetFullPath((Join-Path $iconRoot 'release'))
    $insideWorkspace = $preview.StartsWith($iconRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
    $insideTemp = $preview.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)
    if (-not ($insideWorkspace -or $insideTemp)) { throw 'プレビュー保存先はワークスペース内か標準の一時ディレクトリにしてください。' }
    if ($preview.StartsWith($releaseBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'release は完成 APK 専用です。プレビューはローカルレポートか標準の一時ディレクトリへ保存してください。' }
}
$generated = [Collections.Generic.List[string]]::new()
$drawPaths = [Collections.Generic.List[object]]::new()
$utf8 = [Text.UTF8Encoding]::new($false)
$invariant = [Globalization.CultureInfo]::InvariantCulture
[xml]$source = [IO.File]::ReadAllText((Join-Path $iconRoot 'tools/branding/citrus-icon.svg'))
$svg = $source.DocumentElement
$view = @($svg.GetAttribute('viewBox') -split '\s+' | ForEach-Object { [single]::Parse($_, $invariant) })
$adaptiveSize = [single]::Parse($svg.GetAttribute('data-adaptive-size'), $invariant)
$safeRadius = [single]::Parse($svg.GetAttribute('data-safe-radius'), $invariant)
$background = $svg.SelectSingleNode('*[local-name()="rect" and @id="background"]')
$backgroundColor = [Drawing.ColorTranslator]::FromHtml($background.GetAttribute('fill'))
$cornerRadius = [single]::Parse($background.GetAttribute('rx'), $invariant)
$markNodes = @($svg.SelectNodes('*[local-name()="g" and @id="mark"]/*[local-name()="path"]'))
$newHeader = @'
<?xml version="1.0" encoding="utf-8"?>
<!--
  Copyright (C) 2026 CitrusBrowser contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
'@ -replace "`r`n", "`n"

function Number([single]$Value) { return $Value.ToString('0.######', $invariant) }

function Destination([string]$Relative) {
    $full = [IO.Path]::GetFullPath((Join-Path $outputRoot $Relative))
    if (-not $full.StartsWith($outputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '生成先が許可範囲外です。' }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($full))
    $generated.Add($Relative)
    return $full
}

function Write-Resource([string]$Relative, [string]$Body) {
    $original = Join-Path $iconRoot $Relative
    $header = $newHeader + "`n"
    if ([IO.File]::Exists($original)) {
        $text = [IO.File]::ReadAllText($original)
        $match = [regex]::Match($text, '\A(?:<\?xml[^>]*\?>\s*)?(?:<!--.*?-->\s*)+', 'Singleline')
        if ($match.Success) { $header = $match.Value.Replace("`r`n", "`n") }
    }
    [IO.File]::WriteAllText((Destination $Relative), $header + $Body.Replace("`r`n", "`n").TrimEnd() + "`n", $utf8)
}

function Read-Geometry([string]$Data) {
    $unparsed = [regex]::Replace($Data, '[MLCZ]|-?(?:\d+(?:\.\d*)?|\.\d+)|[\s,]+', '')
    if ($unparsed) { throw 'SVG は絶対座標の M/L/C/Z と通常の数値だけに対応します。' }
    $tokens = @([regex]::Matches($Data, '[MLCZ]|-?(?:\d+(?:\.\d*)?|\.\d+)') | ForEach-Object { $_.Value })
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $current = [Drawing.PointF]::new(0, 0)
    $first = $current
    for ($i = 0; $i -lt $tokens.Count;) {
        $command = $tokens[$i++]
        $count = switch ($command) { 'M' { 2 } 'L' { 2 } 'C' { 6 } 'Z' { 0 } default { throw 'SVG のパス命令が不足しています。' } }
        $values = [single[]]::new($count)
        for ($j = 0; $j -lt $count; $j++) { $values[$j] = [single]::Parse($tokens[$i++], $invariant) }
        switch ($command) {
            'M' { $current = [Drawing.PointF]::new($values[0], $values[1]); $first = $current; $path.StartFigure() }
            'L' { $next = [Drawing.PointF]::new($values[0], $values[1]); $path.AddLine($current, $next); $current = $next }
            'C' {
                $next = [Drawing.PointF]::new($values[4], $values[5])
                $path.AddBezier($current, [Drawing.PointF]::new($values[0], $values[1]), [Drawing.PointF]::new($values[2], $values[3]), $next)
                $current = $next
            }
            'Z' { $path.CloseFigure(); $current = $first }
        }
    }
    return ,$path
}

function New-Bitmap([int]$Size, [string]$Mask = 'Rounded', [switch]$Monochrome, [switch]$Notification) {
    $bitmap = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.Clear([Drawing.Color]::Transparent)
    try {
        if (-not $Notification) {
            $brush = [Drawing.SolidBrush]::new($backgroundColor)
            try {
                switch ($Mask) {
                    'Square' { $graphics.FillRectangle($brush, 0, 0, $Size, $Size) }
                    'Circle' { $graphics.FillEllipse($brush, 0, 0, $Size, $Size) }
                    default {
                        $rounded = [Drawing.Drawing2D.GraphicsPath]::new()
                        try {
                            $d = 2 * $cornerRadius * $Size / $view[2]
                            $rounded.AddArc(0, 0, $d, $d, 180, 90)
                            $rounded.AddArc($Size - $d, 0, $d, $d, 270, 90)
                            $rounded.AddArc($Size - $d, $Size - $d, $d, $d, 0, 90)
                            $rounded.AddArc(0, $Size - $d, $d, $d, 90, 90)
                            $rounded.CloseFigure()
                            $graphics.FillPath($brush, $rounded)
                        } finally { $rounded.Dispose() }
                    }
                }
            } finally { $brush.Dispose() }
        }
        if ($Notification) {
            $scale = [single]($Size * 20 / 24 / [Math]::Max($markBounds.Width, $markBounds.Height))
            $x = [single]($Size / 2 - ($markBounds.X + $markBounds.Width / 2) * $scale)
            $y = [single]($Size / 2 - ($markBounds.Y + $markBounds.Height / 2) * $scale)
        } else {
            $scale = [single]($Size / $view[2]); $x = -$view[0] * $scale; $y = -$view[1] * $scale
        }
        $matrix = [Drawing.Drawing2D.Matrix]::new($scale, 0, 0, $scale, $x, $y)
        try { $graphics.Transform = $matrix } finally { $matrix.Dispose() }
        foreach ($part in $drawPaths) {
            $color = if ($Monochrome -or $Notification) { [Drawing.Color]::White } else { $part.Color }
            $brush = [Drawing.SolidBrush]::new($color)
            try { $graphics.FillPath($brush, $part.Path) } finally { $brush.Dispose() }
        }
    } catch { $bitmap.Dispose(); throw } finally { $graphics.Dispose() }
    return ,$bitmap
}

try {
    $markBounds = [Drawing.RectangleF]::Empty
    foreach ($node in $markNodes) {
        $geometry = Read-Geometry $node.GetAttribute('d')
        $drawPaths.Add([pscustomobject]@{ Path = $geometry; Color = [Drawing.ColorTranslator]::FromHtml($node.GetAttribute('fill')) })
        $flat = $geometry.Clone()
        try {
            $flat.Flatten($null, [single]0.02)
            foreach ($point in $flat.PathPoints) {
                $distance = [Math]::Sqrt([Math]::Pow($point.X - $adaptiveSize / 2, 2) + [Math]::Pow($point.Y - $adaptiveSize / 2, 2))
                if ($distance -gt $safeRadius) { throw '図案が adaptive icon の中央安全域を超えています。' }
            }
            $bounds = $flat.GetBounds()
            $markBounds = if ($markBounds.IsEmpty) { $bounds } else { [Drawing.RectangleF]::Union($markBounds, $bounds) }
        } finally { $flat.Dispose() }
    }

    foreach ($density in @(@('mdpi', 48), @('hdpi', 72), @('xhdpi', 96), @('xxhdpi', 144), @('xxxhdpi', 192))) {
        foreach ($variant in @(@('ic_launcher', 'Rounded'), @('ic_launcher_round', 'Circle'))) {
            $bitmap = New-Bitmap $density[1] $variant[1]
            try { $bitmap.Save((Destination "legacy/src/main/res/mipmap-$($density[0])/$($variant[0]).png"), [Drawing.Imaging.ImageFormat]::Png) } finally { $bitmap.Dispose() }
        }
    }
    # 配布用画像はストア側のマスク適用に備え、角を切り取らない 512px の正方形とする。
    $bitmap = New-Bitmap 512 'Square'
    try { $bitmap.Save((Destination 'app/src/main/ic_launcher-web.png'), [Drawing.Imaging.ImageFormat]::Png) } finally { $bitmap.Dispose() }

    $paths = @($markNodes | ForEach-Object { '    <path android:fillColor="' + $_.GetAttribute('fill') + '" android:pathData="' + ($_.GetAttribute('d') -replace '\s+', ' ').Trim() + '" />' })
    $whitePaths = @($markNodes | ForEach-Object { '    <path android:fillColor="#FFFFFF" android:pathData="' + ($_.GetAttribute('d') -replace '\s+', ' ').Trim() + '" />' })
    $vector = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">' + "`n"
    Write-Resource 'module/ui/src/main/res/drawable/ic_citrus_launcher_foreground.xml' ($vector + ($paths -join "`n") + "`n</vector>")
    Write-Resource 'module/ui/src/main/res/drawable/ic_citrus_launcher_monochrome.xml' ($vector + ($whitePaths -join "`n") + "`n</vector>")
    Write-Resource 'module/ui/src/main/res/values/citrus_icon_colors.xml' ("<resources>`n    <color name=`"citrus_launcher_background`">" + $background.GetAttribute('fill') + "</color>`n</resources>")
    $notificationScale = [single](20 / [Math]::Max($markBounds.Width, $markBounds.Height))
    $notificationX = [single](12 - ($markBounds.X + $markBounds.Width / 2) * $notificationScale)
    $notificationY = [single](12 - ($markBounds.Y + $markBounds.Height / 2) * $notificationScale)
    $notification = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">' + "`n"
    $notification += '    <group android:scaleX="' + (Number $notificationScale) + '" android:scaleY="' + (Number $notificationScale) + '" android:translateX="' + (Number $notificationX) + '" android:translateY="' + (Number $notificationY) + '">' + "`n"
    Write-Resource 'module/ui/src/main/res/drawable/ic_citrusbrowser_white.xml' ($notification + ($whitePaths -join "`n") + "`n    </group>`n</vector>")
    foreach ($version in @(26, 33)) {
        $adaptive = @('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">',
            '    <background android:drawable="@color/citrus_launcher_background" />',
            '    <foreground android:drawable="@drawable/ic_citrus_launcher_foreground" />')
        if ($version -ge 33) { $adaptive += '    <monochrome android:drawable="@drawable/ic_citrus_launcher_monochrome" />' }
        $adaptive += '</adaptive-icon>'
        foreach ($name in @('ic_launcher', 'ic_launcher_round')) { Write-Resource "legacy/src/main/res/mipmap-anydpi-v$version/$name.xml" ($adaptive -join "`n") }
    }

    if ($Check) {
        foreach ($relative in $generated) {
            $expected = Join-Path $iconRoot $relative
            if (-not [IO.File]::Exists($expected) -or (Get-FileHash -LiteralPath $expected).Hash -ne (Get-FileHash -LiteralPath (Join-Path $outputRoot $relative)).Hash) { throw "生成結果が一致しません: $relative" }
        }
        Write-Output "再生成一致: $($generated.Count) ファイル"
    } else {
        # 既存画像は入力にせず、新規 vector に置き換えた旧 foreground だけを削除する。
        foreach ($density in @('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')) {
            $obsolete = [IO.Path]::GetFullPath((Join-Path $iconRoot "legacy/src/main/res/mipmap-$density/ic_launcher_foreground.webp"))
            if (-not $obsolete.StartsWith($iconRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '削除先がワークスペース外です。' }
            if ([IO.File]::Exists($obsolete)) { [IO.File]::Delete($obsolete) }
        }
        Write-Output "生成完了: $($generated.Count) ファイル"
    }

    if ($PreviewPath) {
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($preview))
        $sheet = [Drawing.Bitmap]::new(960, 300)
        $graphics = [Drawing.Graphics]::FromImage($sheet)
        $font = [Drawing.Font]::new('Segoe UI', 14)
        $textBrush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#164D3A'))
        try {
            $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#F4F7ED'))
            $tiles = @(@('従来表示', 'Rounded', $false, $false), @('円形表示', 'Circle', $false, $false), @('単色表示', 'Circle', $true, $false), @('通知', 'Square', $true, $true))
            for ($i = 0; $i -lt $tiles.Count; $i++) {
                $tile = New-Bitmap 192 $tiles[$i][1] -Monochrome:$tiles[$i][2] -Notification:$tiles[$i][3]
                try {
                    if ($tiles[$i][3]) { $graphics.FillRectangle([Drawing.Brushes]::DimGray, 24 + $i * 240, 24, 192, 192) }
                    $graphics.DrawImage($tile, 24 + $i * 240, 24, 192, 192)
                    $graphics.DrawString($tiles[$i][0], $font, $textBrush, [single](24 + $i * 240), [single]235)
                } finally { $tile.Dispose() }
            }
            $sheet.Save($preview, [Drawing.Imaging.ImageFormat]::Png)
        } finally { $textBrush.Dispose(); $font.Dispose(); $graphics.Dispose(); $sheet.Dispose() }
        Write-Output "プレビュー: $preview"
    }
} finally {
    foreach ($part in $drawPaths) { $part.Path.Dispose() }
    if ($Check -and [IO.Directory]::Exists($iconTemporary)) {
        $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $resolvedTemp = [IO.Path]::GetFullPath($iconTemporary)
        if (-not $resolvedTemp.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolvedTemp) -notmatch '^citrus-icons-[0-9a-f]{32}$') { throw '一時ディレクトリの削除先が不正です。' }
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
