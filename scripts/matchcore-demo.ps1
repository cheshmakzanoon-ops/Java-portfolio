# Native Windows/PowerShell MatchCore showcase. No Git Bash, Docker or background server.
# Requires JDK 25, Maven wrapper and a standard PowerShell installation.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$project = Join-Path $repo 'projects/06-matchcore'
Set-Location $repo

$compilerVersion = (& javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch '^javac\s+25(?:\.|$)') {
  throw "MatchCore requires a JDK 25 installation on PATH; detected: $compilerVersion"
}

& (Join-Path $repo 'mvnw.cmd') -B -ntp -f 'projects/06-matchcore/pom.xml' verify
if ($LASTEXITCODE -ne 0) {
  throw "MatchCore verification failed (exit code $LASTEXITCODE)"
}

$paths = @()
foreach ($module in @('matching-core','journal','gateway','simulator','benchmarks')) {
  $classes = Join-Path (Join-Path $project $module) 'target/classes'
  if (!(Test-Path -LiteralPath $classes -PathType Container)) {
    throw "Required compiled module missing: $classes"
  }
  $paths += $classes
}

$runtimeFile = Join-Path $project 'benchmarks/target/runtime-classpath.txt'
if (!(Test-Path -LiteralPath $runtimeFile -PathType Leaf)) {
  throw "Maven did not produce the runtime classpath: $runtimeFile"
}
$dependencies = (Get-Content -LiteralPath $runtimeFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($dependencies)) {
  throw 'MatchCore runtime classpath is empty'
}
$paths += $dependencies
$classpath = $paths -join [IO.Path]::PathSeparator

# Unique synthetic state for each invocation; never reset or overwrite a previous demo.
$runRoot = Join-Path $project '.runtime'
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$runPath = Join-Path $runRoot ('powershell-demo-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runPath | Out-Null
$journal = Join-Path $runPath 'commands.log'
& java '-Xmx512m' '-cp' $classpath 'io.github.cheshmakzanoon.portfolio.matchcore.Demo' $journal
if ($LASTEXITCODE -ne 0) {
  throw "MatchCore demo assertions failed (exit code $LASTEXITCODE); state: $runPath"
}
Write-Output "MatchCore demo PASS; synthetic state: $runPath"
