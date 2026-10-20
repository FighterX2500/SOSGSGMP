# SOS-10 spike: compile against the pinned game jar and install as a script mod.
# Writes only to build\ (git-ignored) and %APPDATA%\songsofsyx\mods\SosMpSpike\. Never to the game folder.
$ErrorActionPreference = 'Stop'
if (-not $env:SOS_GAME_DIR) { throw 'SOSMP-SPIKE: SOS_GAME_DIR is not set' }
if (-not $env:JAVA_HOME) { throw 'SOSMP-SPIKE: JAVA_HOME is not set (JDK 21+)' }
$gameJar = Join-Path $env:SOS_GAME_DIR 'SongsOfSyx.jar'
$build = Join-Path $PSScriptRoot 'build'
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force (Join-Path $build 'classes') | Out-Null
$sources = Get-ChildItem -Recurse (Join-Path $PSScriptRoot 'src') -Filter *.java | ForEach-Object { $_.FullName }
$argFile = Join-Path $build 'javac.args'
# javac argument files treat backslashes inside quotes as escapes, so use forward slashes.
function Quote([string] $path) { '"' + $path.Replace('\', '/') + '"' }
$lines = @('--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-cp', (Quote $gameJar), '-d', (Quote (Join-Path $build 'classes'))) + ($sources | ForEach-Object { Quote $_ })
[IO.File]::WriteAllLines($argFile, $lines)
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') ('@' + $argFile)
if ($LASTEXITCODE -ne 0) { throw 'SOSMP-SPIKE: javac failed' }
$jar = Join-Path $build 'sosmp-spike.jar'
& (Join-Path $env:JAVA_HOME 'bin\jar.exe') --create --file $jar -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'SOSMP-SPIKE: jar failed' }
$mod = Join-Path $env:APPDATA 'songsofsyx\mods\SosMpSpike'
$scriptDir = Join-Path $mod 'V71\script'
New-Item -ItemType Directory -Force $scriptDir | Out-Null
Copy-Item (Join-Path $PSScriptRoot '_Info.txt') (Join-Path $mod '_Info.txt') -Force
Copy-Item $jar (Join-Path $scriptDir 'sosmp-spike.jar') -Force
Write-Output "SOSMP-SPIKE: installed $scriptDir\sosmp-spike.jar"
