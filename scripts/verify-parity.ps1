$ErrorActionPreference='Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$root=Split-Path -Parent $PSScriptRoot
$line=Get-Content (Join-Path $root 'tools\runtime-db.env')|Where-Object{$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')};$password=$line.Substring($line.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType application/json -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable session
$h=@{'x-polygate-csrf'='1'};$user=$null;$provider=$null;$alias=$null
function Write-Json($p,$method,$body){Invoke-RestMethod "$base$p" -Method $method -Headers $h -WebSession $session -ContentType application/json -Body ($body|ConvertTo-Json -Depth 24)}
function Assert($condition,$message){if(-not $condition){throw $message}}
$suffix=[guid]::NewGuid().ToString('N').Substring(0,8)
try{
 $user=Write-Json '/admin/users' Post @{username="parity_$suffix";password="Parity-user-$suffix-password";role='user';status='active'}
 $providerId="parity-$suffix";$alias="parity-deep-$suffix"
 $sampling=@{mode='llama_cpp';profiles=@{}};$sampling.profiles[$alias]=@{temperature=0.6;min_p=0.05}
 $provider=Write-Json '/admin/providers' Post @{id=$providerId;displayName='Parity mock local';protocol='openai-compat';baseUrl='http://polygate-mock:19090';apiKeyEnv='POLYGATE_UPSTREAM_KEY';models=@('qwen-fixture');reasoning=@{mode='llama_cpp';defaultEnabled=$true;defaultBudgetTokens=64};sampling=$sampling;tokenCounting=@{mode='anthropic'}}
 $null=Write-Json '/admin/aliases' Post @{alias=$alias;target="${providerId}:qwen-fixture"}
 $key=Write-Json '/admin/api-keys' Post @{userId=$user.id;name='Parity regression';allowedModels=@($alias);allowedProviders=@($providerId)}
 $api=@{'x-api-key'=$key.key;'x-polygate-traffic-class'='diagnostic'}
 $r=Invoke-RestMethod "$base/v1/chat/completions" -Method Post -Headers $api -ContentType application/json -Body (@{model=$alias;max_tokens=128;temperature=0.2;messages=@(@{role='user';content='inspect_generation'})}|ConvertTo-Json -Depth 8)
 $rendered=$r.choices[0].message.content|ConvertFrom-Json;Assert ($rendered.chat_template_kwargs.enable_thinking-and $rendered.thinking_budget_tokens-eq 64-and $rendered.temperature-eq 0.2-and $rendered.min_p-eq 0.05) 'Configured llama.cpp reasoning/sampling did not reach upstream with client precedence'
 $r=Invoke-RestMethod "$base/v1/messages" -Method Post -Headers $api -ContentType application/json -Body (@{model=$alias;max_tokens=128;thinking=@{type='enabled';budget_tokens=32};messages=@(@{role='user';content='inspect_generation'})}|ConvertTo-Json -Depth 8)
 $rendered=$r.content[0].text|ConvertFrom-Json;Assert ($rendered.thinking_budget_tokens-eq 32) 'Anthropic thinking budget was not translated to selected runtime'
 $count=Invoke-RestMethod "$base/v1/messages/count_tokens" -Method Post -Headers $api -ContentType application/json -Body (@{model=$alias;messages=@(@{role='user';content='count'})}|ConvertTo-Json -Depth 8)
 Assert ($count.input_tokens-eq 3) 'OpenAI-compatible token-count endpoint did not work'
 $metrics=(Invoke-WebRequest "$base/metrics" -Headers $api).Content
 foreach($metric in @('polygate_message_latency_seconds_bucket','polygate_messages_total','polygate_routing_decisions_total','polygate_ledger_operations_total','polygate_rejections_total')){Assert ($metrics.Contains($metric)) "Missing metric $metric"}
 $null=Invoke-RestMethod "$base/admin/router/status" -WebSession $session
 Write-Output 'PASS real upstream payload translation, client sampling precedence, Anthropic-to-llama.cpp thinking, OpenAI-compatible token-count path, expanded Prometheus metrics and EWMA status'
}finally{
 if($alias){$null=Invoke-WebRequest "$base/admin/aliases/$alias" -Method Delete -Headers $h -WebSession $session}
 if($user){$null=Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -Headers $h -WebSession $session}
 if($provider){$null=Invoke-WebRequest "$base/admin/providers/$($provider.id)?force=true" -Method Delete -Headers $h -WebSession $session}
}
