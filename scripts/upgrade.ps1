param([switch]$OfflineRuntime,[switch]$SkipBuild)
$ErrorActionPreference='Stop';$root=Split-Path -Parent $PSScriptRoot;$stamp=Get-Date -Format yyyyMMddHHmmss
if(-not $SkipBuild){& (Join-Path $PSScriptRoot 'build.ps1');if($LASTEXITCODE-ne 0){throw 'Upgrade build failed'}}
$backup=Join-Path $root "tools\backups\before-upgrade-$stamp.dump"
& (Join-Path $PSScriptRoot 'backup.ps1') -Action Export -Path $backup
& (Join-Path $PSScriptRoot 'backup.ps1') -Action Verify -Path $backup
$old=& wsl.exe -d Ubuntu-24.04 -- docker inspect polygate --format '{{.Image}}'
if($LASTEXITCODE-ne 0){throw 'Existing Java deployment not found'}
& wsl.exe -d Ubuntu-24.04 -- docker tag $old "polygate:rollback-$stamp";if($LASTEXITCODE-ne 0){throw 'Cannot preserve rollback image'}
[pscustomobject]@{createdAt=(Get-Date).ToUniversalTime().ToString('o');backup=$backup;previousImage="polygate:rollback-$stamp";database='polygate_release'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $root "tools\upgrade-$stamp.json")
& (Join-Path $PSScriptRoot 'start.ps1') -OfflineRuntime:$OfflineRuntime
if($LASTEXITCODE-ne 0){throw 'Upgrade startup failed; previous image and verified backup preserved in tools'}
Write-Output 'Upgrade complete; previous image, verified database backup and private upgrade record preserved'
