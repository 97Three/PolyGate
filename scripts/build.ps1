param([switch]$SkipFrontendChecks)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'bootstrap.ps1')
$nodeRoot=Join-Path $projectRoot 'tools\node-v24.19.0-win-x64'
$env:PATH="$nodeRoot;"+$env:PATH
$env:npm_config_cache=Join-Path $projectRoot '.npm'
$env:JAVA_HOME=Join-Path $projectRoot 'tools\jdk-21.0.12.1+1'
$env:TEMP=Join-Path $projectRoot 'tmp';$env:TMP=$env:TEMP
$env:MAVEN_USER_HOME=Join-Path $projectRoot '.m2'
$env:MAVEN_OPTS="-Dmaven.repo.local=$projectRoot\.m2 -Djava.io.tmpdir=$env:TEMP -Djdk.net.unixdomain.tmpdir=$env:TEMP"
Push-Location (Join-Path $projectRoot 'dashboard')
try {
    if(-not(Test-Path 'node_modules')){& "$nodeRoot\npm.cmd" ci --no-audit --no-fund;if($LASTEXITCODE-ne 0){throw 'Frontend dependency installation failed'}}
    if($SkipFrontendChecks){& "$nodeRoot\npm.cmd" run build}else{& "$nodeRoot\npm.cmd" run check}
    if($LASTEXITCODE-ne 0){throw 'Frontend verification failed'}
}finally{Pop-Location}
Push-Location $projectRoot
try{& '.\mvnw.cmd' -q verify "-DargLine=-Djava.io.tmpdir=$env:TEMP -Djdk.net.unixdomain.tmpdir=$env:TEMP";if($LASTEXITCODE-ne 0){throw 'Java verification failed'}}finally{Pop-Location}
Write-Output 'Frontend and Java verified; the executable JAR includes the management interface'
