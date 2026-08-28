$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object {$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')}
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'}
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
function Write-Json($path,$method,$value){Invoke-RestMethod "$base$path" -Method $method -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body ($value|ConvertTo-Json -Depth 25)}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8);$user=$null;$provider=$null
$before=Invoke-RestMethod "$base/admin/settings" -WebSession $adminSession
$router=Invoke-RestMethod "$base/admin/router/config" -WebSession $adminSession
$operations=Invoke-RestMethod "$base/admin/ops/configuration" -WebSession $adminSession
try {
  $user=Write-Json '/admin/users' Post @{username="management_$suffix";password="Management-test-$suffix-pass";email='';role='user';status='active'}
  $providerId="management-$suffix";$alias="auto-$suffix"
  $provider=Write-Json '/admin/providers' Post @{id=$providerId;displayName='Management verification';protocol='anthropic';baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('quality','economy')}
  $key=Write-Json '/admin/api-keys' Post @{userId=$user.id;name='Management verification'}
  $api=@{'x-api-key'=$key.key;'x-polygate-traffic-class'='diagnostic'}
  $null=Write-Json '/admin/router/config' Put @{groups=@(@{id="group-$suffix";aliases=@($alias);defaultProfile='economy';candidates=@(@{model="${providerId}:quality";quality=0.99;cost=10;latencyMs=1000},@{model="${providerId}:economy";quality=0.8;cost=1;latencyMs=500})})}
  $null=Write-Json '/admin/settings' Put @{smartRouting=@{mode='active';defaultProfile='economy';activationPercent=100;policyVersion="verify-$suffix"}}
  $payload=@{model=$alias;max_tokens=8;messages=@(@{role='user';content='ping'})}
  $response=Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($payload|ConvertTo-Json -Depth 8)
  Assert-True (($response.Headers['x-polygate-resolved-model']-join '')-eq 'economy') 'Economy routing did not select the lower-cost candidate'
  $status=Invoke-RestMethod "$base/admin/router/status" -WebSession $adminSession
  Assert-True ($status.activeDecisionsTotal-ge 1 -and $status.selectedByCandidate."${providerId}:economy"-ge 1) 'Routing decisions were not persisted'
  $null=Write-Json '/admin/settings' Put @{smartRouting=@{mode='shadow';activationPercent=100}}
  $response=Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($payload|ConvertTo-Json -Depth 8)
  Assert-True (($response.Headers['x-polygate-resolved-model']-join '')-eq 'quality') 'Shadow mode changed the static selection'
  $status=Invoke-RestMethod "$base/admin/router/status" -WebSession $adminSession
  Assert-True ($status.shadowDisagreementsTotal-ge 1) 'Shadow disagreement was not recorded'
  $preview=Write-Json '/admin/retention/run' Post @{dryRun=$true}
  Assert-True ($preview.dryRun -and -not $preview.applied -and $preview.previewToken -and $preview.immutableBudgetEventsRetained) 'Retention preview contract failed'
  $applied=Write-Json '/admin/retention/run' Post @{dryRun=$false;previewToken=$preview.previewToken}
  Assert-True ($applied.applied -and -not $applied.dryRun) 'Retention apply failed'
  Assert-True ((Status-Of {Write-Json '/admin/retention/run' Post @{dryRun=$false;previewToken=$preview.previewToken}})-eq 400) 'Retention preview token could be reused'
  Assert-True ((Status-Of {Invoke-WebRequest "$base/internal/ops/v1/snapshot" -Headers $api})-eq 403) 'Ordinary inference key accessed operations controls'
  $opsKey=Write-Json '/admin/api-keys' Post @{userId=$user.id;name='Operations verifier';principalType='service_account';purpose='polygate_ops_agent';expiresAt=[DateTimeOffset]::UtcNow.AddDays(1).ToString('O')}
  $agent=@{'x-api-key'=$opsKey.key}
  $snapshot=Invoke-RestMethod "$base/internal/ops/v1/snapshot" -Headers $agent
  Assert-True ($snapshot.databaseReady -and $snapshot.requests.windowSeconds-eq 300) 'Scoped operations snapshot failed'
  Assert-True ((Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $agent -ContentType 'application/json' -Body ($payload|ConvertTo-Json -Depth 8)})-eq 403) 'Operations-only key was usable for paid inference'
  $heartbeat=@{instanceId='spoof';agentVersion='verify';mode='read_only';ruleSetVersion='java-test-1';observedAtMs=0;queueDepth=0;intervalSeconds=30;analysisEnabled=$false;selectedModel=$null;modelStatus='disabled';modelLastSuccessAtMs=$null}
  $null=Invoke-RestMethod "$base/internal/ops/v1/heartbeats" -Method Post -Headers $agent -ContentType 'application/json' -Body ($heartbeat|ConvertTo-Json)
  $observation=@{eventKey="fixture-$suffix";detectorType='provider_error_ratio';severity='SEV-3';title='Verification condition';summary='Synthetic verification, no user content';affectedScope=@{providerId=$providerId};evidence=@{failures=3;total=4};observedAtMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();active=$true;recoveryCriteria='Successful detector recovery'}
  $observed=Invoke-RestMethod "$base/internal/ops/v1/observations" -Method Post -Headers $agent -ContentType 'application/json' -Body ($observation|ConvertTo-Json -Depth 8)
  $incidentId=$observed.incident.id
  $null=Write-Json "/admin/ops/incidents/$incidentId/status" Post @{status='acknowledged';reason='Verification acknowledged'}
  $null=Write-Json "/admin/ops/incidents/$incidentId/feedback" Post @{outcome='true_positive';note='Fixture feedback'}
  $observation.active=$false
  $recovered=Invoke-RestMethod "$base/internal/ops/v1/observations" -Method Post -Headers $agent -ContentType 'application/json' -Body ($observation|ConvertTo-Json -Depth 8)
  Assert-True ($recovered.incident.status-eq 'resolved' -and $recovered.incident.occurrenceCount-eq 2) 'Incident recovery/upsert failed'
  $detail=Invoke-RestMethod "$base/admin/ops/incidents/$incidentId" -WebSession $adminSession
  Assert-True ($detail.evidence.Count-eq 2 -and $detail.timeline.Count-ge 4) 'Incident evidence or timeline was lost'
  $null=Write-Json '/admin/ops/configuration' Put @{enabled=$true;analysisEnabled=$false;selectedModel="${providerId}:economy";preferLocal=$true}
  $list=Invoke-RestMethod "$base/admin/ops/incidents" -WebSession $adminSession
  Assert-True (@($list.agents|Where-Object {$_.instanceId-eq $opsKey.id -and $_.online}).Count-eq 1) 'Agent heartbeat identity or freshness failed'
  foreach($i in 1..5){Assert-True ((Status-Of {Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username="missing_$suffix";password='Invalid-test-pass'}|ConvertTo-Json)})-eq 401) 'Unexpected early login failure status'}
  Assert-True ((Status-Of {Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username="missing_$suffix";password='Invalid-test-pass'}|ConvertTo-Json)})-eq 429) 'Repeated login failures were not limited'
  $dashboard=Invoke-RestMethod "$base/admin/dashboard" -WebSession $adminSession
  Assert-True ($null-ne $dashboard.rangeSummary.totalActualCost) 'Actual cost aggregation is missing'
  Write-Output 'PASS active/shadow routing, decision history, retention preview/apply/replay protection, operations-only credentials, scoped snapshots, heartbeat identity, incident evidence/status/recovery/feedback, login failure protection, real cost aggregates'
} finally {
  $null=Write-Json '/admin/settings' Put @{smartRouting=@{mode='off'}}
  $null=Write-Json '/admin/router/config' Put $router
  $restore=@{mode=$before.smartRouting.mode;defaultProfile=$before.smartRouting.defaultProfile;activationPercent=$before.smartRouting.activationPercent;policyVersion=$before.smartRouting.policyVersion}
  $null=Write-Json '/admin/settings' Put @{smartRouting=$restore}
  $null=Write-Json '/admin/ops/configuration' Put @{enabled=$operations.enabled;analysisEnabled=$operations.analysisEnabled;selectedModel=$operations.selectedModel;preferLocal=$operations.preferLocal}
  if($user){$null=Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -WebSession $adminSession -Headers $h}
  if($provider){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -WebSession $adminSession -Headers $h}
}
