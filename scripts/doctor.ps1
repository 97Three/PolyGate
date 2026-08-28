$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'bootstrap.ps1') -CheckOnly
& wsl.exe -d Ubuntu-24.04 -- docker version --format '{{.Server.Version}}';if($LASTEXITCODE-ne 0){throw 'Docker is unavailable'}
foreach($p in @('tools\release.env','target\polygate-1.0.0.jar')){if(-not(Test-Path (Join-Path $root $p))){throw "Missing required local file: $p"}}
if((Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health').status-ne 'UP'){throw 'Gateway is not ready'}
Write-Output 'Java dependencies, Docker, local configuration and gateway health passed'
