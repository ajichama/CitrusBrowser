# Dropbox Ignore Setup Script for Android Project
# Excludes heavy cache/build directories and .git from Dropbox synchronization
# to prevent file locks and sync bottlenecks during Android Gradle builds.

param(
    [string[]]$Paths = @(
        ".git",
        ".gradle",
        ".idea",
        ".cxx",
        "build",
        "app/build",
        "browser/build",
        "module/build",
        "out",
        "release"
    )
)

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot

foreach ($relPath in $Paths) {
    $fullPath = Join-Path $WorkspaceRoot $relPath
    if (Test-Path $fullPath) {
        try {
            Set-Content -Path $fullPath -Stream com.dropbox.ignored -Value 1 -ErrorAction Stop
            Write-Host "[Dropbox Ignore] Ignored: $relPath" -ForegroundColor Green
        } catch {
            Write-Warning "Failed to set com.dropbox.ignored on $relPath : $_"
        }
    }
}

# Also scan any submodule build directories dynamically
Get-ChildItem -Path $WorkspaceRoot -Directory -Recurse -Depth 2 -Filter "build" -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        Set-Content -Path $_.FullName -Stream com.dropbox.ignored -Value 1 -ErrorAction Stop
        $rel = $_.FullName.Substring($WorkspaceRoot.Length + 1)
        Write-Host "[Dropbox Ignore] Ignored module build dir: $rel" -ForegroundColor Green
    } catch {
        # Ignore errors on already-ignored or inaccessible paths
    }
}
