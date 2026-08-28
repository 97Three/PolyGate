$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object {$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')}
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'}
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
function Write-Json($path,$method,$value){Invoke-RestMethod "$base$path" -Method $method -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body ($value|ConvertTo-Json -Depth 25)}
function Invoke-Model($model,$text,$headers,$stream=$false,$openai=$false){$payload=@{model=$model;max_tokens=8;stream=$stream;messages=@(@{role='user';content=$text})};$path=if($openai){'/v1/chat/completions'}else{'/v1/messages'};Invoke-WebRequest "$base$path" -Method Post -Headers $headers -ContentType 'application/json' -Body ($payload|ConvertTo-Json -Depth 10)}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8);$user=$null;$providers=@()
try{
    $user=Write-Json '/admin/users' Post @{username="transport_$suffix";password="Transport-test-$suffix-pass";email='';role='user';status='active'}
    $card=@{inputPerMillion=1;outputPerMillion=2;cacheReadPerMillion=0;cacheWritePerMillion=0;version='fixture-v1';effectiveAt='2020-01-01T00:00:00Z';currency='USD';source='internal_chargeback';serviceTier='standard';region='global';evidence='test-fixture:verify-transport.ps1'}
    $priceMap=@{'mock-model'=$card}
    $providerId="transport-$suffix";$model="${providerId}:mock-model"
    $provider=Write-Json '/admin/providers' Post @{id=$providerId;displayName='Transport verification';protocol='anthropic';baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('mock-model');modelPricing=$priceMap;tokenCounting=@{mode='anthropic'};retry=@{max_attempts=3;initial_delay_ms=10;max_delay_ms=20;jitter_ratio=0}}
    $providers+=,$provider
    $key=Write-Json '/admin/api-keys' Post @{userId=$user.id;name='Transport verification'}
    $api=@{'x-api-key'=$key.key;'x-polygate-traffic-class'='diagnostic'}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedModels=@('mock-model');allowedProviders=@($providerId)}
    Assert-True ((Status-Of {Invoke-Model $model 'resolved-model-policy' $api})-eq 200) 'Original upstream-name allowlist compatibility failed'
    Assert-True ((Status-Of {Invoke-Model "${providerId}:private-model" 'must-not-forward' $api})-eq 403) 'Resolved model policy authorized a private model'
    $catalog=Invoke-RestMethod "$base/v1/models" -Headers $api
    Assert-True (@($catalog.data|Where-Object{$_.owned_by-ne $providerId}).Count-eq 0) 'Scoped catalog disclosed an unauthorized provider'
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedModels=@();allowedProviders=@()}
    $once=$api.Clone();$once['Idempotency-Key']="once-$suffix"
    Assert-True ((Status-Of {Invoke-Model $model 'idempotent' $once})-eq 200) 'Initial idempotent request failed'
    Assert-True ((Status-Of {Invoke-Model $model 'idempotent' $once})-eq 409) 'Terminal idempotent request was replayed'
    Assert-True ((Status-Of {Invoke-Model $model 'different' $once})-eq 409) 'Idempotency key accepted a different payload'
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    Assert-True ($logs.total-eq 2) 'Idempotency conflicts created extra requests'
    $first=$logs.logs[0]
    Assert-True ([decimal]$first.actualCost-eq [decimal]'0.000005' -and [decimal]$first.billableCost-eq [decimal]'0.000005') 'Evidence-backed usage did not produce exact actual/billable costs'
    Assert-True ($first.pricingEvidence.method-eq 'exact_rate_card') 'Pricing evidence was not retained'
    $client=[System.Net.Http.HttpClient]::new();$tasks=@()
    try{
        $body=@{model=$model;max_tokens=8;messages=@(@{role='user';content="slow:$suffix"})}|ConvertTo-Json -Depth 8
        foreach($i in 1..8){$request=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,"$base/v1/messages");$request.Headers.Add('x-api-key',$key.key);$request.Headers.Add('Idempotency-Key',"concurrent-$suffix");$request.Headers.Add('x-polygate-traffic-class','diagnostic');$request.Content=[System.Net.Http.StringContent]::new($body,[System.Text.Encoding]::UTF8,'application/json');$tasks+=,$client.SendAsync($request)}
        $codes=@($tasks|ForEach-Object{$response=$_.GetAwaiter().GetResult();try{[int]$response.StatusCode}finally{$response.Dispose()}})
    }finally{$client.Dispose()}
    Assert-True (@($codes|Where-Object{$_-eq 200}).Count-eq 1 -and @($codes|Where-Object{$_-eq 409}).Count-eq 7) 'Concurrent idempotency did not admit exactly one request'
    $credentialA="cred_a_$suffix";$credentialB="cred_b_$suffix"
    $null=Write-Json "/admin/providers/$providerId/credentials" Post @{id=$credentialA;name='A';apiKeyEnv='POLYGATE_UPSTREAM_KEY'}
    $null=Write-Json "/admin/providers/$providerId/credentials" Post @{id=$credentialB;name='B';apiKeyEnv='POLYGATE_UPSTREAM_KEY'}
    $null=Write-Json "/admin/providers/$providerId/credential-pool" Put @{mode='round_robin'}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedModels=@($model)}
    $null=Invoke-RestMethod "$base/v1/models" -Headers $api
    foreach($i in 1..3){Assert-True ((Status-Of {Invoke-Model $model "round-robin-$i" $api})-eq 200) 'Credential rotation request failed'}
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    $selected=@($logs.logs|Select-Object -First 3|ForEach-Object{$_.credentialId})
    Assert-True (($selected-join ',')-eq "$credentialA,$credentialB,$credentialA") 'Credential round robin or read-only catalog selection failed'
    $updated=Write-Json "/admin/providers/$providerId" Put @{displayName='Updated transport verification'}
    Assert-True ($updated.credentialPoolMode-eq 'round_robin') 'Provider edit discarded credential pool mode'
    $null=Write-Json "/admin/providers/$providerId/credential-pool" Put @{mode='manual'}
    $null=Write-Json "/admin/providers/$providerId/credentials/$credentialA" Put @{status='disabled'}
    Assert-True ((Status-Of {Invoke-Model $model 'manual-unavailable' $api})-eq 503) 'Manual pool used an unselected fallback credential'
    $null=Invoke-RestMethod "$base/admin/providers/$providerId/credentials/$credentialB/select" -Method Post -WebSession $adminSession -Headers $h
    Assert-True ((Status-Of {Invoke-Model $model 'manual-selected' $api})-eq 200) 'Selected credential was not used'
    $null=Invoke-RestMethod "$base/admin/providers/$providerId/credentials/$credentialB" -Method Delete -WebSession $adminSession -Headers $h
    $null=Invoke-RestMethod "$base/admin/providers/$providerId/credentials/$credentialA" -Method Delete -WebSession $adminSession -Headers $h
    Assert-True ((Status-Of {Invoke-Model $model 'empty-pool' $api})-eq 503) 'Empty pool silently restored the legacy credential'
    $null=Write-Json "/admin/providers/$providerId/credentials" Post @{id=$credentialB;name='B';apiKeyEnv='POLYGATE_UPSTREAM_KEY'}
    Assert-True ((Status-Of {Invoke-Model $model "retry503:$suffix" $api})-eq 200) 'Explicit upstream rejection did not retry successfully'
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    Assert-True ($logs.logs[0].retryCount-eq 1) 'Retry count was not recorded'
    $detail=Invoke-RestMethod "$base/admin/enterprise/requests/$($logs.logs[0].id)" -WebSession $adminSession
    Assert-True ($detail.attempts.Count-eq 2 -and $detail.attempts[0].statusCode-eq 503 -and $detail.attempts[1].state-eq 'completed') 'Individual provider attempts were not retained'
    $credentialAuth="cred_auth_$suffix"
    $null=Write-Json "/admin/providers/$providerId/credentials" Post @{id=$credentialAuth;name='Rejected credential fixture';apiKeyEnv='POLYGATE_UPSTREAM_KEY';baseUrl='http://polygate-mock:19090/unauthorized'}
    $null=Invoke-RestMethod "$base/admin/providers/$providerId/credentials/$credentialAuth/select" -Method Post -WebSession $adminSession -Headers $h
    $null=Write-Json "/admin/providers/$providerId/credential-pool" Put @{mode='failover'}
    $recovered=Invoke-Model $model 'credential-auth-recovery' $api
    $credentialRequestId=$recovered.Headers['x-polygate-request-id']-join ''
    $credentialDetail=Invoke-RestMethod "$base/admin/enterprise/requests/$credentialRequestId" -WebSession $adminSession
    Assert-True ($credentialDetail.attempts.Count-eq 2 -and $credentialDetail.attempts[0].statusCode-eq 401 -and $credentialDetail.attempts[1].credentialId-eq $credentialB) 'Credential rejection did not switch accounts within the same request'
    $providerHealth=Invoke-RestMethod "$base/admin/providers" -WebSession $adminSession
    $ownProvider=@($providerHealth|Where-Object{$_.id-eq $providerId})[0]
    $badAccount=@($ownProvider.credentials|Where-Object{$_.id-eq $credentialAuth})[0]
    Assert-True ($badAccount.health.status-eq 'cooldown' -and $badAccount.health.accountIssue-eq 'auth' -and $badAccount.health.requestsTotal-eq 1) 'Credential health does not reflect the observed authentication failure'
    $null=Write-Json "/admin/providers/$providerId/credential-pool" Put @{mode='manual'}
    Assert-True ((Status-Of {Invoke-Model $model 'manual-must-not-switch' $api})-eq 502) 'Manual credential mode silently switched accounts'
    $credentialStream="cred_stream_$suffix"
    $null=Write-Json "/admin/providers/$providerId/credentials" Post @{id=$credentialStream;name='Stream rejection fixture';apiKeyEnv='POLYGATE_UPSTREAM_KEY';baseUrl='http://polygate-mock:19090/unauthorized'}
    $null=Invoke-RestMethod "$base/admin/providers/$providerId/credentials/$credentialStream/select" -Method Post -WebSession $adminSession -Headers $h
    $null=Write-Json "/admin/providers/$providerId/credential-pool" Put @{mode='failover'}
    $streamRecovery=Invoke-Model $model 'stream-account-recovery' $api $true
    Assert-True ($streamRecovery.Content.Contains('message_stop')) 'Credential failover during stream handshake failed'
    $probe=Invoke-RestMethod "$base/admin/providers/$providerId/models" -Method Post -WebSession $adminSession -Headers $h
    Assert-True ($probe.success -and $probe.modelCount-eq 2) 'Provider model discovery failed'
    $count=Invoke-RestMethod "$base/v1/messages/count_tokens" -Method Post -Headers $api -ContentType 'application/json' -Body (@{model=$model;messages=@(@{role='user';content='ping'})}|ConvertTo-Json -Depth 8)
    Assert-True ($count.input_tokens-eq 3) 'Explicit provider token-count capability failed'
    $null=Write-Json "/admin/providers/$providerId" Put @{toolUse=@{responseValidation='strict';streamingArguments='native'}}
    $toolRequest=@{model=$model;max_tokens=8;messages=@(@{role='user';content='call_tool'});tools=@(@{name='echo';input_schema=@{type='object';properties=@{value=@{type='string'}};required=@('value');additionalProperties=$false}})}
    $toolResponse=Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($toolRequest|ConvertTo-Json -Depth 16)
    Assert-True ($toolResponse.StatusCode-eq 200) 'Strict tool schema rejected valid arguments'
    $toolRequest.tools[0].input_schema.properties.value.type='number'
    Assert-True ((Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($toolRequest|ConvertTo-Json -Depth 16)})-eq 502) 'Strict tool schema accepted wrong argument types'
    $toolRequest.stream=$true
    $invalidToolStream=Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($toolRequest|ConvertTo-Json -Depth 16)
    Assert-True ($invalidToolStream.Content.Contains('event:error') -or $invalidToolStream.Content.Contains('event: error')) 'Strict streamed tool violation was not reported'
    $null=Write-Json "/admin/providers/$providerId" Put @{toolUse=@{responseValidation='best_effort';streamingArguments='native'}}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{spendLimitUsd=1}
    $mediaBody=@{model=$model;max_tokens=8;messages=@(@{role='user';content=@(@{type='image';source=@{type='base64';media_type='image/png';data='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4V8AAAAASUVORK5CYII='}},@{type='text';text='ping'})})}|ConvertTo-Json -Depth 15
    Assert-True ((Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body $mediaBody})-eq 200) 'Media input with exact provider token counting failed'
    $null=Write-Json "/admin/providers/$providerId" Put @{tokenCounting=@{mode='disabled'}}
    Assert-True ((Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body $mediaBody})-eq 503) 'Capped media input proceeded using an unsafe byte estimate'
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{spendLimitUsd=0}
    $null=Write-Json "/admin/providers/$providerId" Put @{tokenCounting=@{mode='anthropic'}}
    Assert-True ((Status-Of {Invoke-Model $model 'missing_usage' $api})-eq 200) 'Missing-usage fixture request failed'
    $logs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    Assert-True ($logs.logs[0].usageEstimated -and $null-eq $logs.logs[0].billableCost -and $logs.logs[0].costEstimate-gt 0) 'Missing usage was treated as proven billable usage'
    $nativeId="native-$suffix";$nativeModel="${nativeId}:mock-model"
    $native=Write-Json '/admin/providers' Post @{id=$nativeId;displayName='Native OpenAI verification';protocol='openai-compat';baseUrl='http://polygate-mock:19090/v1';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('mock-model');modelPricing=$priceMap}
    $providers+=,$native;$null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedModels=@($nativeModel)}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedModels=@('mock-model');allowedProviders=@($nativeId)}
    Assert-True ((Status-Of {Invoke-Model $model 'provider-policy-must-deny' $api})-eq 403) 'Scoped upstream-name policy authorized another provider'
    $nativeCatalog=Invoke-RestMethod "$base/v1/models" -Headers $api
    Assert-True (@($nativeCatalog.data|Where-Object{$_.owned_by-ne $nativeId}).Count-eq 0) 'Scoped upstream-name catalog exposed another provider'
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{allowedModels=@($nativeModel);allowedProviders=@()}
    Assert-True ((Status-Of {Invoke-Model $nativeModel 'ping' $api $false $true})-eq 200) 'Native OpenAI provider failed'
    $sse=Invoke-Model $nativeModel 'ping' $api $true $true
    Assert-True ($sse.Content.Contains('[DONE]')) 'Native OpenAI SSE did not finish'
    $cross=Invoke-Model $nativeModel 'ping' $api $true $false
    Assert-True ($cross.Content.Contains('message_stop')) 'Anthropic-to-OpenAI streaming conversion did not finish'
    $null=Write-Json "/admin/providers/$nativeId" Put @{toolUse=@{responseValidation='strict';streamingArguments='native';repairInvalidArguments=$true}}
    $repairRequest=@{model=$nativeModel;max_tokens=8;messages=@(@{role='user';content='call_tool'});tools=@(@{name='echo';input_schema=@{type='object';properties=@{value=@{type='number'}};required=@('value');additionalProperties=$false}})}
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{spendLimitUsd=[decimal]'0.001'}
    Assert-True ((Status-Of {Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($repairRequest|ConvertTo-Json -Depth 16)})-eq 429) 'Tool repair was admitted without reserving both attempts and instruction margin'
    $null=Write-Json "/admin/api-keys/$($key.id)" Put @{spendLimitUsd=1}
    $repairResponse=Invoke-WebRequest "$base/v1/messages" -Method Post -Headers $api -ContentType 'application/json' -Body ($repairRequest|ConvertTo-Json -Depth 16)
    $repaired=$repairResponse.Content|ConvertFrom-Json
    Assert-True ($repaired.content[0].input.value-eq 1) 'Bounded tool argument repair did not recover the invalid schema response'
    $repairId=$repairResponse.Headers['x-polygate-request-id']-join ''
    $repairDetail=Invoke-RestMethod "$base/admin/enterprise/requests/$repairId" -WebSession $adminSession
    Assert-True ($repairDetail.request.toolRepairAttempted -and $repairDetail.request.toolRepairRecovered -and $repairDetail.attempts.Count-eq 2) 'Repair outcome or individual attempts were not recorded'
    $repairLogs=Invoke-RestMethod "$base/admin/logs?apiKeyId=$($key.id)" -WebSession $adminSession
    $repairLog=@($repairLogs.logs|Where-Object{$_.id-eq $repairId})[0]
    Assert-True ($repairLog.inputTokens-eq 6 -and $repairLog.outputTokens-eq 2 -and [decimal]$repairLog.billableCost-eq [decimal]'0.000010') 'Repair usage or cost was lost or charged twice'
    $ready=Invoke-RestMethod "$base/readyz" -Headers $api
    Assert-True ($ready.status-eq 'ok') 'Authenticated readiness check failed'
    $metrics=Invoke-WebRequest "$base/metrics" -Headers $api
    Assert-True ($metrics.Content.Contains('polygate_requests_total')) 'Prometheus metrics are missing'
    $ui=Invoke-WebRequest "$base/login"
    Assert-True ($ui.Content.Contains('PolyGate Java')) 'Executable Java JAR does not serve the embedded interface'
    Write-Output 'PASS exact pricing evidence, sequential/concurrent idempotency, credential round robin/manual isolation, pool persistence, retry attempt history, model discovery, token counting, conservative unknown usage, native/cross-protocol SSE, readiness, metrics, embedded UI'
}finally{
    if($user){$null=Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -WebSession $adminSession -Headers $h}
    foreach($provider in $providers){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -WebSession $adminSession -Headers $h}
}
