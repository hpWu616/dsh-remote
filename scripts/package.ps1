param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
$gradle = Join-Path $repo 'gradlew.bat'
$apkSource = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
$pluginSource = $repo
$dist = Join-Path $repo 'dist'
$appVersion = [regex]::Match(
    (Get-Content -LiteralPath (Join-Path $repo 'app\build.gradle.kts') -Raw),
    'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$pluginVersion = (Get-Content -LiteralPath (Join-Path $pluginSource 'package.json') -Raw |
    ConvertFrom-Json).version
if (-not $appVersion -or -not $pluginVersion) { throw 'Could not read package versions' }

if (-not $SkipBuild) {
    & $gradle :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
}
if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) {
    throw "APK not found: $apkSource"
}

New-Item -ItemType Directory -Path $dist -Force | Out-Null
$apkName = "dsh-remote-android-v$appVersion.apk"
$pluginName = "dsh-remote-host-v$pluginVersion.zip"
$apkTarget = Join-Path $dist $apkName
$pluginTarget = Join-Path $dist $pluginName
Copy-Item -LiteralPath $apkSource -Destination $apkTarget -Force

$stage = Join-Path $dist ('.plugin-stage-' + [guid]::NewGuid().ToString('N'))
$stagePlugin = Join-Path $stage 'dsh-remote-host'
New-Item -ItemType Directory -Path (Join-Path $stagePlugin 'locale') -Force | Out-Null
try {
    foreach ($name in @('package.json', 'cordis.patch.yml', 'index.js', 'client.js',
            'discovery.js', 'tls-identity.js', 'icon.svg', 'LICENSE')) {
        Copy-Item -LiteralPath (Join-Path $pluginSource $name) -Destination $stagePlugin
    }
    Copy-Item -LiteralPath (Join-Path $pluginSource 'locale\en.json') `
        -Destination (Join-Path $stagePlugin 'locale')
    Copy-Item -LiteralPath (Join-Path $pluginSource 'locale\zh.json') `
        -Destination (Join-Path $stagePlugin 'locale')
    Compress-Archive -LiteralPath $stagePlugin -DestinationPath $pluginTarget -Force
} finally {
    $resolvedStage = (Resolve-Path -LiteralPath $stage).Path
    if (-not $resolvedStage.StartsWith($dist + '\',
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete path outside dist: $resolvedStage"
    }
    Remove-Item -LiteralPath $resolvedStage -Recurse -Force
}

$checksum = Join-Path $dist 'SHA256SUMS.txt'
@($apkTarget, $pluginTarget) | ForEach-Object {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant()
    "$hash  $(Split-Path -Leaf $_)"
} | Set-Content -LiteralPath $checksum -Encoding ascii
Write-Output "Packaged $apkTarget"
Write-Output "Packaged $pluginTarget"
Write-Output "Checksums $checksum"
