# Spike SOS-2: run the rebuilt jar with the game's bundled JRE and JVM args.
# The shipped SongsOfSyx.jar is not touched: the rebuilt jar is put on the classpath,
# and the game passes java.class.path on to the process it starts.
# Usage: .\run-rebuilt.ps1 [-Compiler javac|ecj] [-Launcher]
param(
    [ValidateSet('javac', 'ecj')] [string] $Compiler = 'javac',
    [switch] $Launcher
)
$ErrorActionPreference = 'Stop'
if (-not $env:SOS_GAME_DIR) { throw 'REBUILD-CHECK: SOS_GAME_DIR is not set' }
$jar = Join-Path $PSScriptRoot "build\$Compiler\SongsOfSyx-rebuilt.jar"
if (-not (Test-Path $jar)) { throw "REBUILD-CHECK: $jar not found; run rebuild.py --compiler $Compiler first" }
$java = Join-Path $env:SOS_GAME_DIR 'jre\bin\java.exe'
$jvmArgs = Get-Content (Join-Path $env:SOS_GAME_DIR 'jvmargs-launcher.txt') | Where-Object { $_.Trim() }
$gameArgs = @()
if ($Launcher) { $gameArgs = @('launcher') }
Push-Location $env:SOS_GAME_DIR
try {
    & $java @jvmArgs -cp $jar init.Main @gameArgs
} finally {
    Pop-Location
}
