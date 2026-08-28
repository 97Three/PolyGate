$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object { $_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=') }
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'}
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
function Write-Json($path,$method,$value){Invoke-RestMethod "$base$path" -Method $method -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body ($value|ConvertTo-Json -Depth 15)}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8)
$providerId="verify-$suffix"
$model="${providerId}:mock-model"
$provider=Write-Json '/admin/providers' Post @{id=$providerId;displayName='Budget Verification';protocol='anthropic';baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('mock-model')}
$team=$null;$user=$null;$originalSettings=$null
try{
    $team=Write-Json '/admin/teams' Post @{name="Verify $suffix";slug="verify-$suffix";dailyLimitUsd=1;monthlyLimitUsd=1;allowedModels=@($model);allowedProviders=@($providerId)}
    $user=Write-Json '/admin/users' Post @{username="budget_$suffix";password="Budget-test-$suffix-pass";email='';role='user';status='active'}
    $key=Write-Json '/admin/api-keys' Post @{name='Budget verification';userId=$user.id;teamId=$team.id}
    $body=@{model=$model;max_tokens=8;messages=@(@{role='user';content='ping'})}|ConvertTo-Json -Depth 8
    $invoke={Invoke-WebRequest "$base/v1/messages" -Method Post -Headers @{'x-api-key'=$key.key} -ContentType 'application/json' -Body $body}
    Assert-True ((Status-Of $invoke)-eq 503) 'Missing price card did not fail closed for a budgeted project'
    $price=@{inputPerMillion=1;outputPerMillion=2;cacheReadPerMillion=0.5;cacheWritePerMillion=1.5}
    $null=Write-Json "/admin/providers/$providerId" Put @{pricing=$price}
    $null=Write-Json "/admin/teams/$($team.id)" Put @{dailyLimitUsd=0.000001}
    Assert-True ((Status-Of $invoke)-eq 429) 'Project budget did not reject an over-limit request'
    $null=Write-Json "/admin/teams/$($team.id)" Put @{dailyLimitUsd=1}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{dailyLimitUsd=0.000001}
    Assert-True ((Status-Of $invoke)-eq 429) 'API key budget did not reject an over-limit request'
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{dailyLimitUsd=1}
    Assert-True ((Status-Of $invoke)-eq 200) 'A funded request failed'
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    Assert-True ($logs.total-eq 1 -and [decimal]$logs.logs[0].costEstimate-eq [decimal]'0.000005') 'Usage did not settle against the price card'
    $firstLog=$logs.logs[0].id
    $price.inputPerMillion=2;$price.outputPerMillion=4
    $null=Write-Json "/admin/providers/$providerId" Put @{pricing=$price}
    $historic=Invoke-RestMethod "$base/admin/logs/$firstLog" -WebSession $adminSession
    Assert-True ([decimal]$historic.costEstimate-eq [decimal]'0.000005') 'Changing a price altered an existing request'
    Assert-True ((Status-Of $invoke)-eq 200) 'Request failed after the price change'
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    Assert-True ([decimal]$logs.logs[0].costEstimate-eq [decimal]'0.000010') 'New requests did not use the updated price'
    $null=Write-Json "/admin/teams/$($team.id)" Put @{status='disabled'}
    Assert-True ((Status-Of $invoke)-eq 403) 'Disabled project retained API access'
    $null=Write-Json "/admin/teams/$($team.id)" Put @{status='active'}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{ipRestricted=$true;allowedIps=@('192.0.2.0/24')}
    Assert-True ((Status-Of $invoke)-eq 403) 'IP policy did not reject the client address'
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{ipRestricted=$false;allowedProviders=@('other-provider')}
    Assert-True ((Status-Of $invoke)-eq 403) 'Provider allowlist was not enforced'
    $models=Invoke-RestMethod "$base/v1/models" -Headers @{'x-api-key'=$key.key}
    Assert-True ($models.data.Count-eq 0) 'Provider restriction leaked a model catalog entry'
    $backup=Invoke-RestMethod "$base/admin/backup" -Method Post -WebSession $adminSession -Headers $h
    Assert-True (-not $backup.containsSecrets) 'Backup reports that it contains secrets'
    Assert-True (-not ($backup|ConvertTo-Json -Depth 30).Contains($key.key)) 'Backup exposed an API key'
    $audit=Invoke-RestMethod "$base/admin/audit" -WebSession $adminSession
    Assert-True ($audit.total-gt 0) 'Management operations were not audited'
    $originalSettings=Invoke-RestMethod "$base/admin/settings" -WebSession $adminSession
    $null=Write-Json '/admin/settings' Put @{rateLimits=@{maxRequestBodyBytes=1024}}
    $large=@{model='demo-model';max_tokens=8;messages=@(@{role='user';content=('x'*2048)})}|ConvertTo-Json -Depth 8
    $largeStatus=Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers @{'x-api-key'='local-test-client-token'} -ContentType 'application/json' -Body $large}
    Assert-True ($largeStatus-eq 413) 'Configured request body limit was not applied'
    Write-Output 'PASS missing-price rejection, project/key budgets, cost settlement, immutable price snapshot, project/provider/IP restrictions, secret-free backup, audit, live body-size setting'
}finally{
    if($originalSettings){$null=Write-Json '/admin/settings' Put @{rateLimits=$originalSettings.rateLimits}}
    if($user){$null=Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -WebSession $adminSession -Headers $h}
    if($team){$null=Invoke-WebRequest "$base/admin/teams/$($team.id)" -Method Delete -WebSession $adminSession -Headers $h}
    $null=Invoke-WebRequest "$base/admin/providers/${providerId}?force=true" -Method Delete -WebSession $adminSession -Headers $h
}
