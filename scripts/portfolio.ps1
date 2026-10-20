param([Parameter(Mandatory=$true)][ValidateSet('doctor','list','verify','integration','mutation','up','demo','down','reset-demo')][string]$Action,[string]$Project)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$slugs = @('clearledger','durableflow','quorumkv','pulsestream','tenantshield','matchcore','indexforge','threadlens','byteguard','routeweaver')
Set-Location $root
switch ($Action) {
  'doctor' { & java -version; if ($LASTEXITCODE) { exit $LASTEXITCODE }; & javac -version; if ($LASTEXITCODE) { exit $LASTEXITCODE }; & .\mvnw.cmd -version; exit $LASTEXITCODE }
  'list' { $slugs | ForEach-Object { Write-Output $_ }; exit 0 }
  { $_ -in 'verify','integration','mutation' } {
    $env:PORTFOLIO_TEST_RUN_STARTED = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0).ToString('F3', [Globalization.CultureInfo]::InvariantCulture)
    if ($Action -eq 'verify' -and $Project -in @('indexforge','routeweaver')) {
      # Reuse the supported, nonmutating receipt preparation and report gates.
      if (!(Get-Command bash -ErrorAction SilentlyContinue)) { throw 'Git Bash is required to prepare this project for its lifecycle commands' }
      & bash (Join-Path $PSScriptRoot 'portfolio.sh') verify $Project
      exit $LASTEXITCODE
    }
    $mavenArgs = @('-B','-ntp')
    if ($Project -ne 'all') {
      $index = [Array]::IndexOf($slugs,$Project)
      if ($index -lt 0) { throw "Unknown project $Project" }
      $mavenArgs += @('-f',('projects/{0:D2}-{1}/pom.xml' -f ($index+1),$Project))
    }
    if ($Action -eq 'integration') { $mavenArgs += '-Pintegration' }
    if ($Action -eq 'mutation') { $mavenArgs += @('-Pmutation','-DoutputFormats=XML,HTML') }
    & .\mvnw.cmd @mavenArgs verify
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    $selectedProjects = if ($Project -eq 'all') { $slugs } else { @($Project) }
    if ($Action -eq 'verify' -and $Project -eq 'all') {
      if (!(Get-Command bash -ErrorAction SilentlyContinue)) { throw 'Git Bash is required to prepare IndexForge and RouteWeaver for their lifecycle commands' }
      foreach ($prepared in @('indexforge','routeweaver')) {
        & bash (Join-Path $PSScriptRoot 'portfolio.sh') verify $prepared
        if ($LASTEXITCODE) { exit $LASTEXITCODE }
      }
    }
    foreach ($selected in $selectedProjects) {
      $selectedIndex = [Array]::IndexOf($slugs,$selected)
      $selectedPath = 'projects/{0:D2}-{1}' -f ($selectedIndex+1),$selected
      & python scripts/check-tests.py $selectedPath $Action
      if ($LASTEXITCODE) { exit $LASTEXITCODE }
      if ($Action -eq 'mutation') {
        & python scripts/check-mutations.py $selectedPath
        if ($LASTEXITCODE) { exit $LASTEXITCODE }
      }
    }
    exit 0
  }
  default {
    $index = [Array]::IndexOf($slugs,$Project)
    if ($index -lt 0) { throw "Unknown project $Project" }
    $script = Join-Path $root ('projects/{0:D2}-{1}/scripts/{2}.sh' -f ($index+1),$Project,$Action)
    if (!(Test-Path $script)) { throw "Command not implemented: $script" }
    # Git Bash is required by the project-owned shell demos on Windows.
    $proc = Start-Process bash -ArgumentList @($script) -NoNewWindow -PassThru
    if (!$proc.WaitForExit(600000)) { $proc.Kill(); throw 'Project command exceeded 600 seconds' }
    exit $proc.ExitCode
  }
}
