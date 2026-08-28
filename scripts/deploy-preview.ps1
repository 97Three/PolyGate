param([switch]$Build,[switch]$OfflineRuntime,[int]$Port=18084)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
function Wsl-Path($value){$value=[IO.Path]::GetFullPath($value);if($value-match '^([A-Za-z]):\\(.*)$'){return '/mnt/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2].Replace('\','/')};throw 'A local Windows drive path is required'}
$linuxRoot=Wsl-Path $projectRoot
if ($Build) {
    $env:JAVA_HOME = Join-Path $projectRoot 'tools\jdk-21.0.12.1+1'
    $env:TEMP = Join-Path $projectRoot 'tmp'
    $env:TMP = $env:TEMP
    $env:MAVEN_USER_HOME = Join-Path $projectRoot '.m2'
    $env:MAVEN_OPTS = "-Dmaven.repo.local=$projectRoot\.m2 -Djava.io.tmpdir=$env:TEMP -Djdk.net.unixdomain.tmpdir=$env:TEMP"
    Push-Location $projectRoot
    try { & '.\mvnw.cmd' -q verify "-DargLine=-Djava.io.tmpdir=$env:TEMP -Djdk.net.unixdomain.tmpdir=$env:TEMP"; if ($LASTEXITCODE -ne 0) { throw 'Java verification failed' } }
    finally { Pop-Location }
}
function Invoke-Docker {
    $dockerArguments = $args
    & wsl.exe -d Ubuntu-24.04 -- docker @dockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker operation failed: $($dockerArguments[0])" }
}
$running = & wsl.exe -d Ubuntu-24.04 -- docker ps -q --filter name=^polygate-preview$
if ($running) { Invoke-Docker stop -t 670 polygate-preview | Out-Null }
$mock = & wsl.exe -d Ubuntu-24.04 -- docker ps -q --filter name=^polygate-mock$
if(-not $mock){Invoke-Docker run --rm -d --name polygate-mock --network polygate_default -v $linuxRoot/scripts/mock_upstream.py:/mock.py:ro python:3.12-alpine python /mock.py | Out-Null}
if($OfflineRuntime){
  & wsl.exe -d Ubuntu-24.04 -- env DOCKER_BUILDKIT=0 docker build -q --build-arg BASE_IMAGE=polygate:dev -t polygate:dev $linuxRoot | Out-Null
  if($LASTEXITCODE-ne 0){throw 'Offline Java image build failed'}
}else{Invoke-Docker build -q -t polygate:dev $linuxRoot | Out-Null}
Invoke-Docker run --rm -d --name polygate-preview --network polygate_default --env-file $linuxRoot/tools/runtime-db.env `
    -e POLYGATE_BIND=0.0.0.0 -e POLYGATE_CLIENT_TOKEN=local-test-client-token `
    -e POLYGATE_UPSTREAM_KEY=local-mock-key -e POLYGATE_UPSTREAM_URL=http://polygate-mock:19090 `
    -e POLYGATE_UPSTREAM_ALLOW_HTTP_MOCK=true -e POLYGATE_PUBLIC_MODEL=demo-model `
    -e POLYGATE_UPSTREAM_MODEL=deepseek-flash -p "127.0.0.1:${Port}:18080" polygate:dev | Out-Null
$ready = $false
foreach ($attempt in 1..30) {
    try { $health = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2; if ($health.status -eq 'UP') { $ready = $true; break } } catch { }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Java preview did not become ready within 30 seconds' }
Write-Output "Java preview deployed on http://127.0.0.1:$Port"
