# SOS-11 spike: start the patched build (build\out\SongsOfSyx-rebuilt.jar) with the game's JRE and JVM args.
# The shipped SongsOfSyx.jar is not touched; the game passes java.class.path on to the process it starts.
$ErrorActionPreference = 'Stop'
if (-not $env:SOS_GAME_DIR) { throw 'SOSMP-SPIKE: SOS_GAME_DIR is not set' }
$jar = Join-Path $PSScriptRoot 'build\out\SongsOfSyx-rebuilt.jar'
if (-not (Test-Path $jar)) { throw "SOSMP-SPIKE: $jar not found; run build.py prepare + compile first" }
$jvm = (Get-Content (Join-Path $env:SOS_GAME_DIR 'jvmargs-launcher.txt') | Where-Object { $_.Trim() }) -join ' '
Start-Process -FilePath (Join-Path $env:SOS_GAME_DIR 'jre\bin\java.exe') -ArgumentList "$jvm -cp `"$jar`" init.Main" -WorkingDirectory $env:SOS_GAME_DIR
