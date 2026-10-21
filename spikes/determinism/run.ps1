# SOS-11 spike: start the patched build (build\out\SongsOfSyx-rebuilt.jar) with the game's JRE and JVM args.
# The shipped SongsOfSyx.jar is not touched; the game passes java.class.path on to the process it starts.
#   -AppData DIR    run with its own data folder (DIR\songsofsyx: settings, mods, saves, results), so several copies can run
#   -Autoload NAME  load this save at start-up (patch 50-autoload), so no clicks are needed
#   -Jar PATH       another build of the game (SOS-23 baseline: build-baseline\out\SongsOfSyx-rebuilt.jar)
param([string] $AppData = '', [string] $Autoload = '', [string] $Jar = '')
$ErrorActionPreference = 'Stop'
if (-not $env:SOS_GAME_DIR) { throw 'SOSMP-SPIKE: SOS_GAME_DIR is not set' }
$jar = if ($Jar) { $Jar } else { Join-Path $PSScriptRoot 'build\out\SongsOfSyx-rebuilt.jar' }
if (-not (Test-Path $jar)) { throw "SOSMP-SPIKE: $jar not found; run build.py prepare + compile first" }
$jvm = (Get-Content (Join-Path $env:SOS_GAME_DIR 'jvmargs-launcher.txt') | Where-Object { $_.Trim() }) -join ' '
# Start-Process passes this process's environment on, and the launcher passes it on to the game process.
if ($AppData) { $env:APPDATA = $AppData }
if ($Autoload) { $env:SOSMP_AUTOLOAD = $Autoload }
Start-Process -FilePath (Join-Path $env:SOS_GAME_DIR 'jre\bin\java.exe') -ArgumentList "$jvm -cp `"$jar`" init.Main" -WorkingDirectory $env:SOS_GAME_DIR
