# Windows counterpart of build-bootstrap.sh. Compiles only the local Java adapters.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $projectRoot

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot -and (Test-Path -LiteralPath 'local.properties')) {
    $sdkLine = Get-Content -LiteralPath 'local.properties' | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) { $sdkRoot = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':') }
}
if (-not $sdkRoot -or -not (Test-Path -LiteralPath $sdkRoot)) { throw 'Set ANDROID_HOME or sdk.dir in local.properties.' }

$platform = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'platforms') -Directory |
    Where-Object Name -Match '^android-[0-9]+(\.[0-9]+)?$' |
    Sort-Object { [version]($_.Name.Substring(8) + '.0') } | Select-Object -Last 1
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Where-Object Name -Match '^\d+\.\d+\.\d+$' | Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $platform -or -not $buildTools) { throw 'Install Android SDK platform and build-tools.' }
$androidJar = Join-Path $platform.FullName 'android.jar'
$d8 = Join-Path $buildTools.FullName 'd8.bat'
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$jar = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/jar.exe' } else { (Get-Command jar.exe).Source }

$work = [IO.Path]::GetFullPath((Join-Path $projectRoot 'app/build/generated/spotifyRuntime/bootstrap-build'))
$expectedWork = Join-Path $projectRoot 'app\build\generated\spotifyRuntime\bootstrap-build'
if ($work -ne $expectedWork) { throw 'Unexpected generated build directory.' }
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$assets = Join-Path $projectRoot 'app/build/generated/spotifyRuntime/assets/spotify-runtime'
New-Item -ItemType Directory -Path $classes, $dex, $assets -Force | Out-Null
$sourcesFile = Join-Path $work 'sources.txt'
$sourceFiles = Get-ChildItem -LiteralPath 'native-runtime/compile-stubs', 'native-runtime/bootstrap' -Recurse -Filter '*.java'
$sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } | Set-Content -LiteralPath $sourcesFile -Encoding ASCII
& $javac --release 8 -nowarn -classpath $androidJar -d $classes "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap javac failed.' }
$bootstrapJar = Join-Path $work 'bootstrap.jar'
& $jar cf $bootstrapJar -C $classes probe
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap jar failed.' }
& $d8 --min-api 26 --lib $androidJar --output $dex $bootstrapJar
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap D8 failed.' }
Copy-Item -LiteralPath (Join-Path $dex 'classes.dex') -Destination (Join-Path $assets 'bootstrap.dex')
