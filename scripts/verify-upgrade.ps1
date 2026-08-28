$ErrorActionPreference='Stop';$root=Split-Path -Parent $PSScriptRoot
function Wsl-Path($value){$value=[IO.Path]::GetFullPath($value);if($value-match '^([A-Za-z]):\\(.*)$'){return '/mnt/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2].Replace('\','/')};throw 'A local Windows drive path is required'}
$linuxRoot=Wsl-Path $root
$latest=Get-ChildItem (Join-Path $root 'tools\backups') -Filter 'java-parity-fixed-*.dump'|Sort-Object LastWriteTime -Descending|Select-Object -First 1
if(-not $latest){throw 'Create a pre-upgrade backup first'}
$target='polygate_restoreupgrade'+[guid]::NewGuid().ToString('N').Substring(0,12)
$file=Join-Path $root 'tools\upgrade-test.env';$linuxFile="$linuxRoot/tools/upgrade-test.env"
function Docker {& wsl.exe -d Ubuntu-24.04 -- docker @args;if($LASTEXITCODE-ne 0){throw 'Upgrade drill failed'}}
$created=$false;$running=$false
try{
 & (Join-Path $PSScriptRoot 'backup.ps1') -Action Restore -Path $latest.FullName -TargetDatabase $target;$created=$true
 $lines=Get-Content (Join-Path $root 'tools\release.env')|ForEach-Object{if($_.StartsWith('POLYGATE_DATABASE_URL=')){"POLYGATE_DATABASE_URL=jdbc:postgresql://postgres:5432/$target"}else{$_}}
 [IO.File]::WriteAllLines($file,$lines,[Text.UTF8Encoding]::new($false))
 foreach($image in @('polygate:dev','polygate:1.0.0')){
   Docker run --rm -d --name polygate-upgrade-test --network polygate_default --env-file $linuxFile -p 127.0.0.1:18086:18080 $image|Out-Null;$running=$true
   $ready=$false;foreach($n in 1..40){try{if((Invoke-RestMethod 'http://127.0.0.1:18086/actuator/health' -TimeoutSec 2).status-eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
   if(-not $ready){throw "Upgrade/rollback image did not start: $image"}
   $state=Docker exec polygate-postgres-1 psql -U polygate -d $target -Atqc 'SELECT (SELECT count(*) FROM gateway_request)>=24 AND (SELECT count(*) FROM gateway_api_key)=2 AND (SELECT max(installed_rank) FROM flyway_schema_history)>=23'
   if($state-ne 't'){throw 'Restored migration history or business records changed'}
   Docker stop -t 30 polygate-upgrade-test|Out-Null;$running=$false
 }
 Write-Output 'PASS V22 backup -> V23 Java upgrade -> previous Java image rollback on the restored database; original release database unchanged'
}finally{
 if($running){$present=Docker ps -aq --filter name=^polygate-upgrade-test$;if($present){Docker stop -t 30 polygate-upgrade-test|Out-Null}}
 if($created-and $target-match '^polygate_restoreupgrade[a-f0-9]{12}$'){Docker exec polygate-postgres-1 dropdb -U polygate $target}
}
