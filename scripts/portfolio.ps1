param([Parameter(Mandatory=$true)][ValidateSet('doctor','list','verify','integration','up','demo','down','reset-demo')][string]$Action,[string]$Project)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$slugs = @('clearledger','durableflow','quorumkv','pulsestream','tenantshield','matchcore','indexforge','threadlens','byteguard','routeweaver')
Set-Location $root
switch ($Action) {
  'doctor' { & java -version; if ($LASTEXITCODE) { exit $LASTEXITCODE }; & javac -version; if ($LASTEXITCODE) { exit $LASTEXITCODE }; & .\mvnw.cmd -version; exit $LASTEXITCODE }
  'list' { $slugs | ForEach-Object { Write-Output $_ }; exit 0 }
  { $_ -in 'verify','integration' } {
    $mavenArgs = @('-B','-ntp')
    if ($Project -ne 'all') {
      $index = [Array]::IndexOf($slugs,$Project)
      if ($index -lt 0) { throw "Unknown project $Project" }
      $mavenArgs += @('-f',('projects/{0:D2}-{1}/pom.xml' -f ($index+1),$Project))
    }
    if ($Action -eq 'integration') { $mavenArgs += '-Pintegration' }
    & .\mvnw.cmd @mavenArgs verify
    exit $LASTEXITCODE
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
