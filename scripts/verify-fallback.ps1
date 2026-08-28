$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object {$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')}
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'}
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
function Write-Json($path,$method,$value){Invoke-RestMethod "$base$path" -Method $method -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body ($value|ConvertTo-Json -Depth 25)}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8);$user=$null;$providers=@();$alias="fallback-$suffix"
$before=Invoke-RestMethod "$base/admin/settings" -WebSession $adminSession
try {
  $user=Write-Json '/admin/users' Post @{username="fallback_$suffix";password="Fallback-test-$suffix-pass";email='';role='user';status='active'}
  $a="fallback-a-$suffix";$b="fallback-b-$suffix"
  foreach($protocol in @('anthropic','openai-compat')){
    $card=@{inputPerMillion=1;outputPerMillion=2;cacheReadPerMillion=0;cacheWritePerMillion=0;version='fixture-fallback';effectiveAt='2020-01-01T00:00:00Z';currency='USD';source='internal_chargeback';serviceTier='standard';region='global';evidence='test-fixture:verify-fallback.ps1'}
    $rateB=$card.Clone();$rateB.inputPerMillion=10;$rateB.outputPerMillion=20
    $primary=Write-Json '/admin/providers' Post @{id=$a;displayName='Failing primary';protocol=$protocol;baseUrl='http://polygate-mock:19090/unavailable';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('fallback-model');modelPricing=@{'fallback-model'=$card}}
    $providers+=,$primary
    $secondary=Write-Json '/admin/providers' Post @{id=$b;displayName='Working secondary';protocol=$protocol;baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('fallback-model');modelPricing=@{'fallback-model'=$rateB}}
    $providers+=,$secondary
    $null=Write-Json '/admin/aliases' Post @{alias=$alias;target="${a}:fallback-model"}
    $order=@($a,$b)+@($before.gateway.providerOrder)
    $null=Write-Json '/admin/settings' Put @{gateway=@{providerOrder=$order;defaultProvider=$a}}
    $key=Write-Json '/admin/api-keys' Post @{userId=$user.id;name="Fallback $protocol";allowedModels=@($alias);allowedProviders=@($a,$b)}
    $api=@{'x-api-key'=$key.key;'x-polygate-traffic-class'='diagnostic'}
    foreach($stream in @($false,$true)){
      $body=@{model=$alias;max_tokens=8;stream=$stream;messages=@(@{role='user';content='ping'})}|ConvertTo-Json -Depth 8
      $path=if($protocol-eq 'anthropic'){'/v1/messages'}else{'/v1/chat/completions'}
      $response=Invoke-WebRequest "$base$path" -Method Post -Headers $api -ContentType 'application/json' -Body $body
      Assert-True (($response.Headers['x-polygate-resolved-provider']-join '')-eq $b) 'Fallback response did not identify the actual provider'
      if($stream){Assert-True ($response.Content.Contains('message_stop') -or $response.Content.Contains('[DONE]')) 'Fallback SSE did not finish'}
      $requestId=$response.Headers['x-polygate-request-id']-join ''
      $detail=Invoke-RestMethod "$base/admin/enterprise/requests/$requestId" -WebSession $adminSession
      foreach($settle in 1..20){if($detail.request.state-ne 'started' -and $detail.attempts.Count-ge 2){break};Start-Sleep -Milliseconds 100;$detail=Invoke-RestMethod "$base/admin/enterprise/requests/$requestId" -WebSession $adminSession}
      Assert-True ($detail.request.fallbackFromProvider-eq $a -and $detail.attempts.Count-eq 2) 'Fallback identity or attempts were lost'
      Assert-True ($detail.attempts[0].statusCode-eq 503 -and $detail.attempts[1].state-eq 'completed') 'Fallback attempted after an ambiguous transport failure'
      $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
      $row=@($logs.logs|Where-Object{$_.id-eq $requestId})[0]
      Assert-True ([decimal]$row.billableCost-eq [decimal]'0.000050') 'Fallback used the primary provider price instead of its pinned own price'
    }
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedProviders=@($a)}
    $body=@{model=$alias;max_tokens=8;messages=@(@{role='user';content='ping'})}|ConvertTo-Json -Depth 8
    $denied=Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body $body}
    Assert-True ($denied-eq 502) 'Fallback ignored the provider allowlist'
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    Assert-True ([decimal]$logs.logs[0].costEstimate-eq 0) 'Explicit provider rejection consumed the full generation reservation'
    $null=Invoke-WebRequest "$base/admin/aliases/$alias" -Method Delete -WebSession $adminSession -Headers $h
    $null=Write-Json '/admin/settings' Put @{gateway=@{providerOrder=@($before.gateway.providerOrder);defaultProvider=$before.gateway.defaultProvider}}
    foreach($provider in $providers){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -WebSession $adminSession -Headers $h};$providers=@()
  }
  Write-Output 'PASS Anthropic and OpenAI JSON/SSE fallback, pinned final-provider pricing, individual failed/successful attempts, provider allowlist isolation, zero cost for explicit rejection'
} finally {
  $null=Write-Json '/admin/settings' Put @{gateway=@{providerOrder=@($before.gateway.providerOrder);defaultProvider=$before.gateway.defaultProvider}}
  try{$null=Invoke-WebRequest "$base/admin/aliases/$alias" -Method Delete -WebSession $adminSession -Headers $h}catch{}
  if($user){$null=Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -WebSession $adminSession -Headers $h}
  foreach($provider in $providers){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -WebSession $adminSession -Headers $h}
}
