$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object {$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')}
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'}
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
function Write-Json($path,$method,$value){Invoke-RestMethod "$base$path" -Method $method -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body ($value|ConvertTo-Json -Depth 20)}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8)
$user=$null;$viewer=$null;$reviewer=$null;$provider=$null
try{
    $userPassword="Tenant-test-$suffix-pass"
    $user=Write-Json '/admin/users' Post @{username="tenant_$suffix";password=$userPassword;email='';role='user';status='active'}
    $viewer=Write-Json '/admin/users' Post @{username="viewer_$suffix";password=$userPassword;email='';role='viewer';status='active'}
    $reviewer=Write-Json '/admin/users' Post @{username="reviewer_$suffix";password=$userPassword;email='';role='admin';status='active'}
    $null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$user.username;password=$userPassword}|ConvertTo-Json) -SessionVariable userSession
    $selfKey=Invoke-RestMethod "$base/admin/api-keys" -Method Post -WebSession $userSession -Headers $h -ContentType 'application/json' -Body (@{name='Self-service test';allowedModels=@('demo-model')}|ConvertTo-Json)
    Assert-True (([DateTimeOffset]::Parse($selfKey.expiresAt)-[DateTimeOffset]::UtcNow).TotalDays-le 30.01) 'Self-service key expiry was not capped'
    $own=Invoke-RestMethod "$base/admin/api-keys" -WebSession $userSession
    Assert-True (@($own).Count-eq 1 -and $own[0].userId-eq $user.id) 'User key list contains another owner'
    $forbidden=Status-Of {Invoke-WebRequest "$base/admin/api-keys" -Method Post -WebSession $userSession -Headers $h -ContentType 'application/json' -Body (@{userId=$reviewer.id;name='forbidden'}|ConvertTo-Json)}
    Assert-True ($forbidden-eq 403) 'User created a key for another account'
    Assert-True ((Status-Of {Invoke-WebRequest "$base/admin/settings" -WebSession $userSession})-eq 403) 'User accessed administrator settings'
    $null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$viewer.username;password=$userPassword}|ConvertTo-Json) -SessionVariable viewerSession
    Assert-True ((Status-Of {Invoke-WebRequest "$base/admin/api-keys" -Method Post -WebSession $viewerSession -Headers $h -ContentType 'application/json' -Body '{"name":"forbidden"}'})-eq 403) 'Viewer created a key'
    $providerId="tenant-$suffix";$model="${providerId}:mock-model"
    $provider=Write-Json '/admin/providers' Post @{id=$providerId;displayName='Tenant verification';protocol='anthropic';baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('mock-model');pricing=@{inputPerMillion=1;outputPerMillion=2;cacheReadPerMillion=0;cacheWritePerMillion=0}}
    $scope=@{organizationId='org_verify';projectId="prj_$suffix";environmentId='env_test'}
    $key=Write-Json '/admin/api-keys' Post @{userId=$user.id;name='Scoped verification'}
    $null=Write-Json "/admin/api-keys/$($key.id)/scope" Put $scope
    $policy=$scope.Clone();$policy.maximumMode='local_strict';$policy.defaultClassification='internal';$policy.cloudEnabled=$false;$policy.allowedProviders=@($providerId);$policy.allowedModels=@('mock-model');$policy.allowedRegions=@('global');$policy.allowedApiVersions=@('anthropic-v1')
    $target="$($scope.organizationId)/$($scope.projectId)/$($scope.environmentId)"
    $change=Write-Json '/admin/governance/change-requests' Post @{action='project_policy.upsert';target=$target;payload=$policy;reason='Automated tenant policy verification'}
    Assert-True ($change.status-eq 'approved') 'Local single-administrator mode did not approve the recorded change'
    Assert-True ((Status-Of {Invoke-WebRequest "$base/admin/governance/change-requests/$($change.id)/approve" -Method Post -WebSession $adminSession -Headers $h})-eq 403) 'Requester approved their own change'
    $null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$reviewer.username;password=$userPassword}|ConvertTo-Json) -SessionVariable reviewerSession
    $null=Invoke-RestMethod "$base/admin/governance/change-requests/$($change.id)/approve" -Method Post -WebSession $reviewerSession -Headers $h
    $applied=Write-Json "/admin/governance/change-requests/$($change.id)/apply" Post @{}
    Assert-True $applied.ok 'Policy application failed'
    $body=@{model=$model;max_tokens=8;messages=@(@{role='user';content='ping'})}|ConvertTo-Json -Depth 8
    $invoke={Invoke-WebRequest "$base/v1/messages" -Method Post -Headers @{'x-api-key'=$key.key} -ContentType 'application/json' -Body $body}
    $mismatch=Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers @{'x-api-key'=$key.key;'x-polygate-organization-id'='other';'x-polygate-project-id'='other';'x-polygate-environment-id'='other'} -ContentType 'application/json' -Body $body}
    Assert-True ($mismatch-eq 403) 'Forged tenant headers were accepted'
    $cap=$scope.Clone();$cap.unlimited=$false;$cap.limitMicrounits=1
    $null=Write-Json '/admin/enterprise/budget' Put $cap
    Assert-True ((Status-Of $invoke)-eq 429) 'Hard tenant budget was not enforced'
    $cap.limitMicrounits=1000000;$null=Write-Json '/admin/enterprise/budget' Put $cap
    Assert-True ((Status-Of $invoke)-eq 200) 'Valid tenant request failed'
    $budget=Invoke-RestMethod "$base/admin/enterprise/budget?organizationId=org_verify&projectId=$($scope.projectId)&environmentId=env_test" -WebSession $adminSession
    Assert-True ($budget.account.settledMicrounits-eq 5 -and $budget.account.reservedMicrounits-eq 0) 'Budget reservation did not settle and release exactly'
    Assert-True ($budget.recentEvents.eventType-contains 'reservation_created' -and $budget.recentEvents.eventType-contains 'settled') 'Budget evidence is missing'
    $page=Invoke-RestMethod "$base/admin/enterprise/requests?projectId=$($scope.projectId)" -WebSession $adminSession
    Assert-True ($page.total-eq 1 -and $page.requests[0].projectId-eq $scope.projectId) 'Enterprise request scope filter failed'
    $detail=Invoke-RestMethod "$base/admin/enterprise/requests/$($page.requests[0].ledgerId)" -WebSession $adminSession
    Assert-True ($detail.attempts.Count-eq 1) 'Enterprise attempt detail is missing'
    $ownLogs=Invoke-RestMethod "$base/admin/logs" -WebSession $userSession
    Assert-True (@($ownLogs.logs | Where-Object {$_.userId-ne $user.id}).Count-eq 0) 'User log view leaked another account'
    $adjustment=$scope.Clone();$adjustment.deltaMicrounits=-5;$adjustment.reason='Refund of automated verification estimate';$adjustment.evidenceReference='verify-governance.ps1'
    $adjusted=Write-Json '/admin/enterprise/budget/adjustments' Post $adjustment
    Assert-True ($adjusted.account.settledMicrounits-eq 0) 'Budget adjustment was not recorded'
    Write-Output 'PASS self-service ownership/expiry, viewer isolation, project policy review, tenant header isolation, hard budget, exact reserve/settle, immutable event flow, enterprise detail, user log privacy, evidenced adjustment'
}finally{
    foreach($account in @($user,$viewer,$reviewer)){if($account){$null=Invoke-WebRequest "$base/admin/users/$($account.id)" -Method Delete -WebSession $adminSession -Headers $h}}
    if($provider){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -WebSession $adminSession -Headers $h}
}
