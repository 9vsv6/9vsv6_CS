# Runs a .cs3 through Harbor's own capstan runtime (the same loader Harbor uses),
# so provider behaviour can be tested without clicking through the app.
#
# Usage:
#   .\run.ps1 -Cs3 ..\..\ArabicMulti\build\ArabicMulti.cs3 -Url "https://www.themoviedb.org/movie/969681"
#   .\run.ps1 -Cs3 ..\..\Witanime\build\Witanime.cs3 -Url "https://witanime.site/watch/one-piece/1180"
#   .\run.ps1 -Cs3 ..\..\ArabicMulti\build\ArabicMulti.cs3 -Url "https://www.themoviedb.org/tv/1396" -Season 1 -Episode 1

param(
    [Parameter(Mandatory = $true)][string]$Cs3,
    [Parameter(Mandatory = $true)][string]$Url,
    [int]$Season = -1,
    [int]$Episode = -1
)

$ErrorActionPreference = "Stop"

$capstan = Join-Path $env:LOCALAPPDATA "Harbor\capstan"
if (-not (Test-Path (Join-Path $capstan "capstan.jar"))) {
    throw "Harbor capstan runtime not found at $capstan"
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $javaHome = (Get-Command java).Source | Split-Path | Split-Path
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here "build"
New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $work "cache") | Out-Null

$classpath = "$(Join-Path $capstan 'capstan.jar');$(Join-Path $capstan 'libs\*')"

& (Join-Path $javaHome "bin\javac.exe") -cp $classpath -d $work (Join-Path $here "Harness.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$seasonArg = if ($Season -ge 0) { "$Season" } else { "null" }
$episodeArg = if ($Episode -ge 0) { "$Episode" } else { "null" }

& (Join-Path $javaHome "bin\java.exe") `
    "-Dcapstan.dextools=$(Join-Path $capstan 'dex-tools')" `
    "-Dcapstan.cache=$(Join-Path $work 'cache')" `
    -cp "$work;$classpath" Harness $Cs3 $Url $seasonArg $episodeArg
