param([switch]$OfflineRuntime,[switch]$Build)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if($Build){& (Join-Path $PSScriptRoot 'build.ps1');if($LASTEXITCODE-ne 0){throw 'Build verification failed'}}
if(-not(Test-Path (Join-Path $root 'target\polygate-1.0.0.jar'))){throw 'Run scripts/build.ps1 first'}
if(-not(Test-Path (Join-Path $root 'tools\release.env'))){throw 'Private tools/release.env is required; use .env.example to configure an isolated Java database'}
function Wsl-Path($value){$value=[IO.Path]::GetFullPath($value);if($value-match '^([A-Za-z]):\\(.*)$'){return '/mnt/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2].Replace('\','/')};throw 'A local Windows drive path is required'}
function Docker {& wsl.exe -d Ubuntu-24.04 -- docker @args;if($LASTEXITCODE-ne 0){throw 'Java deployment operation failed'}}
$linuxRoot=Wsl-Path $root
if($OfflineRuntime){& wsl.exe -d Ubuntu-24.04 -- env DOCKER_BUILDKIT=0 docker build -q --build-arg BASE_IMAGE=polygate:dev -t polygate:1.0.0 $linuxRoot|Out-Null;if($LASTEXITCODE-ne 0){throw 'Offline image build failed'}}else{Docker build -q -t polygate:1.0.0 $linuxRoot|Out-Null}
$existing=Docker ps -aq --filter 'name=^polygate$'
if($existing){$label=Docker inspect polygate --format '{{index .Config.Labels "dev.polygate.backend"}}';if($label-ne 'java'){throw 'The existing container named polygate is not owned by this deployment'}}
# The image is built before switching the explicitly authorized PolyGate services.
foreach($name in @('polygate-polygate-1','polygate-dashboard-1','polygate-dashboard','polygate-preview','polygate-release-init','polygate')){
  $running=Docker ps -q --filter "name=^$name`$"
  if($running){Docker stop -t 670 $name|Out-Null}
}
$existing=Docker ps -aq --filter 'name=^polygate$'
if($existing){$label=Docker inspect polygate --format '{{index .Config.Labels "dev.polygate.backend"}}';if($label-ne 'java'){throw 'The existing container named polygate is not owned by this deployment'};Docker rm polygate|Out-Null}
Docker run -d --name polygate --label dev.polygate.backend=java --restart unless-stopped --init --network polygate_default --env-file "$linuxRoot/tools/release.env" --read-only --tmpfs '/tmp:rw,nosuid,size=128m' --cap-drop ALL --security-opt no-new-privileges:true --memory 1g -e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65 -p 127.0.0.1:18080:18080 -p 127.0.0.1:33002:18080 -p 127.0.0.1:33003:18080 -p 127.0.0.1:38082:18080 polygate:1.0.0|Out-Null
$ready=$false
foreach($attempt in 1..60){try{if((Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 2).status-eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
if(-not $ready){throw 'The Java release did not become ready; inspect docker logs polygate'}
Write-Output 'PolyGate Java 1.0.0 is ready on http://127.0.0.1:18080; original PolyGate URLs remain available'
