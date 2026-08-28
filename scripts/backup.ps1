param([ValidateSet('Export','Validate','Restore','Verify')][string]$Action='Export',[Parameter(Mandatory=$true)][string]$Path,[string]$TargetDatabase,[string]$EnvFile,[string]$Container="polygate-postgres-1",[string]$AdminUser="polygate")
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major-lt 7){throw 'PowerShell 7 or newer is required'}
$root=Split-Path -Parent $PSScriptRoot
if(-not $EnvFile){$EnvFile=Join-Path $root 'tools\release.env'}
$envValues=@{};foreach($line in Get-Content -LiteralPath $EnvFile){$p=$line-split '=',2;if($p.Count-eq 2){$envValues[$p[0]]=$p[1]}}
$sourceDb=([uri]($envValues.POLYGATE_DATABASE_URL-replace '^jdbc:','')).AbsolutePath.TrimStart('/')
if($sourceDb-notmatch '^polygate(?:_[a-z0-9]+)*$'){throw 'An isolated Java database is required'}
$Path=[IO.Path]::GetFullPath($Path)
if($Container-notmatch '^[a-zA-Z0-9_.-]+$'-or $AdminUser-notmatch '^[a-zA-Z0-9_]+$'){throw 'Invalid database container or role'}
function Docker {if([OperatingSystem]::IsWindows()){& wsl.exe -d Ubuntu-24.04 -- docker @args}else{& docker @args};if($LASTEXITCODE-ne 0){throw 'Database backup/restore operation failed'}}
function LinuxPath($p){if(-not [OperatingSystem]::IsWindows()){return $p};if($p-match '^([A-Za-z]):\\(.*)$'){return '/mnt/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2].Replace('\','/')};throw 'A local drive path is required'}
$temporary='/tmp/polygate_backup_'+[guid]::NewGuid().ToString('N')+'.dump'
$created=$false;$target=$null
try {
 if($Action-eq 'Export'){
   if(Test-Path -LiteralPath $Path){throw 'Backup output already exists; use a new file name'}
   $parent=Split-Path -Parent $Path;New-Item -ItemType Directory -Force -Path $parent|Out-Null
   Docker exec $Container pg_dump -U $AdminUser -d $sourceDb --format=custom -f $temporary
   Docker cp "${Container}:$temporary" (LinuxPath $Path)|Out-Null
   (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()|Set-Content -LiteralPath ($Path+'.sha256')
   Write-Output 'Backup exported with SHA256; contains confidential database records, keep it private'
   return
 }
 if(-not(Test-Path -LiteralPath $Path)-or -not(Test-Path -LiteralPath ($Path+'.sha256'))){throw 'Backup and SHA256 sidecar are required'}
 $expected=(Get-Content -LiteralPath ($Path+'.sha256') -Raw).Trim();if((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash-ne $expected){throw 'Backup checksum mismatch'}
 Docker cp (LinuxPath $Path) "${Container}:$temporary"|Out-Null
 $toc=Docker exec $Container pg_restore --list $temporary
 if(-not($toc-match 'gateway_request')-or -not($toc-match 'flyway_schema_history')){throw 'Archive is not a complete Java gateway database'}
 if($Action-eq 'Validate'){Write-Output 'Backup checksum and PostgreSQL archive structure validated';return}
 $target=if($Action-eq 'Verify'){'polygate_verify'+[guid]::NewGuid().ToString('N')}else{$TargetDatabase}
 if($target.Length-gt 63-or $target-notmatch '^polygate_(restore|verify)[a-z0-9_]{1,48}$'-or $target-eq $sourceDb){throw 'Restore target must be a NEW polygate_restore_* database'}
 $exists=Docker exec $Container psql -U $AdminUser -d postgres -Atqc "SELECT count(*) FROM pg_database WHERE datname='$target'"
 if($exists-ne '0'){throw 'Target database already exists; refusing to overwrite it'}
 $role=$envValues.POLYGATE_DATABASE_USER;if($role-notmatch '^polygate(?:_[a-z0-9]+)*$'){throw 'Invalid isolated Java database role'}
 Docker exec $Container createdb -U $AdminUser -O $role $target;$created=$true
 Docker exec $Container pg_restore -U $AdminUser --role=$role -d $target --no-owner --no-acl --exit-on-error --single-transaction $temporary
 $balanced=Docker exec $Container psql -U $AdminUser -d $target -Atqc 'SELECT count(*) FROM gateway_budget_account a WHERE settled_microunits<>(SELECT COALESCE(sum(settled_delta_microunits),0) FROM gateway_budget_event e WHERE e.organization_id=a.organization_id AND e.project_id=a.project_id AND e.environment_id=a.environment_id) OR reserved_microunits<>(SELECT COALESCE(sum(reserved_delta_microunits),0) FROM gateway_budget_event e WHERE e.organization_id=a.organization_id AND e.project_id=a.project_id AND e.environment_id=a.environment_id)'
 if($balanced-ne '0'){throw 'Restored budget ledger did not reconcile'}
 if($Action-eq 'Verify'){Write-Output 'PASS full backup restore drill and financial reconciliation in an isolated scratch database'}else{Write-Output "Restored database: $target; source/live database unchanged. Point a separately configured Java process at the restored database to switch."}
}finally{
 Docker exec $Container rm -f $temporary|Out-Null
 if($created-and $Action-eq 'Verify'){
   if($target.Length-gt 63-or $target-notmatch '^polygate_verify[a-f0-9]{32}$'){throw 'Unexpected scratch database name'}
   Docker exec $Container dropdb -U $AdminUser $target
 }
}
