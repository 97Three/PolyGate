$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object {$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')}
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'}
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
function Write-Json($path,$method,$value){Invoke-RestMethod "$base$path" -Method $method -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body ($value|ConvertTo-Json -Depth 25)}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8);$user=$null;$providers=@()
$before=Invoke-RestMethod "$base/admin/ops/configuration" -WebSession $adminSession
try{
  $user=Write-Json '/admin/users' Post @{username="agent_$suffix";password="Agent-test-$suffix-pass";email='';role='user';status='active'}
  $analysisId="agent-model-$suffix";$failureId="agent-fail-$suffix"
  $analysis=Write-Json '/admin/providers' Post @{id=$analysisId;displayName='Free local analysis fixture';protocol='anthropic';baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('mock-model');pricing=@{inputPerMillion=1;outputPerMillion=2;cacheReadPerMillion=0;cacheWritePerMillion=0}}
  $providers+=,$analysis
  $failure=Write-Json '/admin/providers' Post @{id=$failureId;displayName='Failure detector fixture';protocol='anthropic';baseUrl='http://polygate-mock:19090/unavailable';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('mock-model')}
  $providers+=,$failure
  $key=Write-Json '/admin/api-keys' Post @{userId=$user.id;name='Detector input fixture';allowedModels=@("${failureId}:mock-model");allowedProviders=@($failureId)}
  $api=@{'x-api-key'=$key.key}
  $payload=@{model="${failureId}:mock-model";max_tokens=8;messages=@(@{role='user';content='ping'})}|ConvertTo-Json -Depth 8
  foreach($i in 1..3){Assert-True ((Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body $payload})-eq 502) 'Failure detector fixture did not reject'}
  $null=Write-Json '/admin/ops/configuration' Put @{enabled=$true;analysisEnabled=$true;selectedModel="${analysisId}:mock-model";preferLocal=$true}
  $run=Write-Json '/admin/ops/run' Post @{}
  Assert-True ($run.checked -and $run.activeConditions-ge 1 -and $run.modelStatus-eq 'configured') 'Built-in Java detector or gated model analysis did not run'
  $list=Invoke-RestMethod "$base/admin/ops/incidents" -WebSession $adminSession
  $incident=@($list.items|Where-Object{$_.eventKey-eq "java.provider.$failureId"})[0]
  Assert-True ($incident.status-eq 'open') 'Built-in Java detector did not open the incident'
  $detail=Invoke-RestMethod "$base/admin/ops/incidents/$($incident.id)" -WebSession $adminSession
  $evidence=@($detail.evidence|Where-Object{$_.evidence.kind-eq 'model_analysis'})[0].evidence
  Assert-True ($evidence.analysis-eq 'hello' -and $evidence.requestId) 'Model analysis evidence does not link to its gateway request'
  $request=Invoke-RestMethod "$base/admin/enterprise/requests/$($evidence.requestId)" -WebSession $adminSession
  Assert-True ($request.request.trafficClass-eq 'diagnostic' -and $request.request.state-eq 'completed') 'Analysis bypassed gateway accounting or traffic classification'
  $agent=@($list.agents|Where-Object{$_.instanceId-eq '00000000-0000-0000-0000-000000010001'})[0]
  Assert-True ($agent.online -and $agent.analysisEnabled -and $agent.modelLastSuccessAtMs) 'Built-in heartbeat did not reflect actual analysis'
  $null=Write-Json '/admin/ops/run' Post @{}
  $again=Invoke-RestMethod "$base/admin/ops/incidents/$($incident.id)" -WebSession $adminSession
  Assert-True (@($again.evidence|Where-Object{$_.evidence.kind-eq 'model_analysis_attempt'}).Count-eq 1) 'Detector repeated model analysis inside the incident cooldown'
  $null=Write-Json "/admin/providers/$failureId" Put @{baseUrl='http://polygate-mock:19090'}
  foreach($i in 1..8){$null=Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body $payload}
  $null=Write-Json '/admin/ops/run' Post @{}
  $recovered=Invoke-RestMethod "$base/admin/ops/incidents/$($incident.id)" -WebSession $adminSession
  Assert-True ($recovered.status-eq 'resolved') 'Built-in detector did not confirm recovery'
  Write-Output 'PASS pure-Java scheduled detector, bounded model analysis via ordinary gateway admission, diagnostic traffic accounting, managed credential scope, heartbeat, analysis cooldown, automatic recovery'
}finally{
  $null=Write-Json '/admin/ops/configuration' Put @{enabled=$before.enabled;analysisEnabled=$before.analysisEnabled;selectedModel=$before.selectedModel;preferLocal=$before.preferLocal}
  $null=Write-Json '/admin/ops/run' Post @{}
  if($user){$null=Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -WebSession $adminSession -Headers $h}
  foreach($provider in $providers){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -WebSession $adminSession -Headers $h}
}
