$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$existing=& wsl.exe -d Ubuntu-24.04 -- docker ps -aq --filter name=^polygate-oidc-test$
if($existing){throw 'OIDC test container already exists; refusing to overwrite it'}
try {
  & wsl.exe -d Ubuntu-24.04 -- docker run --rm -d --name polygate-oidc-test -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=polygate_oidctest -p 127.0.0.1:15433:5432 postgres:18.4-alpine | Out-Null
  if($LASTEXITCODE-ne 0){throw 'Test PostgreSQL startup failed'}
  $ready=$false
  foreach($attempt in 1..20){& wsl.exe -d Ubuntu-24.04 -- docker exec polygate-oidc-test pg_isready -U postgres -d polygate_oidctest *> $null;if($LASTEXITCODE-eq 0){$ready=$true;break};Start-Sleep -Seconds 1}
  if(-not $ready){throw 'Test PostgreSQL did not become ready'}
  $env:JAVA_HOME=Join-Path $root 'tools\jdk-21.0.12.1+1';$env:TEMP=Join-Path $root 'tmp';$env:TMP=$env:TEMP
  $env:MAVEN_OPTS="-Dmaven.repo.local=$root\.m2 -Djava.io.tmpdir=$env:TEMP -Djdk.net.unixdomain.tmpdir=$env:TEMP"
  $env:POLYGATE_TEST_ADAPTER_TOKEN='fixture-runtime-token'
  $env:POLYGATE_TEST_PG_URL='jdbc:postgresql://127.0.0.1:15433/polygate_oidctest'
  Push-Location $root
  try{& '.\mvnw.cmd' -q test '-Dtest=OidcIntegrationTest,DashboardDatabaseIntegrationTest,RuntimeAdapterDatabaseIntegrationTest' "-DargLine=-Djava.io.tmpdir=$env:TEMP -Djdk.net.unixdomain.tmpdir=$env:TEMP";if($LASTEXITCODE-ne 0){throw 'OIDC verification failed'}}finally{Pop-Location}
} finally {
  Remove-Item Env:\POLYGATE_TEST_PG_URL -ErrorAction SilentlyContinue
  & wsl.exe -d Ubuntu-24.04 -- docker stop polygate-oidc-test | Out-Null
}
