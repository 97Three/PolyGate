$ErrorActionPreference = 'Stop'
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'}
$environmentFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env'
$secretLine = Get-Content -LiteralPath $environmentFile | Where-Object { $_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=') }
$password = $secretLine.Substring($secretLine.IndexOf('=') + 1)
$null = Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username='admin';password=$password } | ConvertTo-Json) -SessionVariable adminSession
$writeHeaders = @{ 'x-polygate-csrf'='1' }
function Assert-True($condition, $message) { if (-not $condition) { throw $message } }
function Status-Of($action) { try { $r = & $action; return [int]$r.StatusCode } catch { return [int]$_.Exception.Response.StatusCode } }
$suffix = [Guid]::NewGuid().ToString('N').Substring(0,10)
$user = Invoke-RestMethod "$base/admin/users" -Method Post -WebSession $adminSession -Headers $writeHeaders -ContentType 'application/json' -Body (@{ username="verify_$suffix";email='';password="Java-test-$suffix-pass";role='user';status='active' } | ConvertTo-Json)
try {
    $key = Invoke-RestMethod "$base/admin/api-keys" -Method Post -WebSession $adminSession -Headers $writeHeaders -ContentType 'application/json' -Body (@{ userId=$user.id;name="verify_$suffix";allowedModels=@('demo-model') } | ConvertTo-Json)
    $body = @{ model='demo-model';max_tokens=8;messages=@(@{role='user';content='ping'}) } | ConvertTo-Json -Depth 8
    $pending = Invoke-RestMethod "$base/admin/api-keys/$($key.id)/rotate" -Method Post -WebSession $adminSession -Headers $writeHeaders
    $pendingStatus = Status-Of { Invoke-WebRequest "$base/v1/models" -Headers @{'x-api-key'=$pending.key} }
    Assert-True ($pendingStatus -eq 401) 'Pending replacement key was accepted before confirmation'
    $oldStatus = Status-Of { Invoke-WebRequest "$base/v1/models" -Headers @{'x-api-key'=$key.key} }
    Assert-True ($oldStatus -eq 200) 'Source key stopped working before rotation confirmation'
    $null = Invoke-RestMethod "$base/admin/api-keys/$($key.id)/rotate/$($pending.id)" -Method Post -WebSession $adminSession -Headers $writeHeaders
    $oldStatus = Status-Of { Invoke-WebRequest "$base/v1/models" -Headers @{'x-api-key'=$key.key} }
    Assert-True ($oldStatus -eq 401) 'Superseded key remained active'
    $models = Invoke-RestMethod "$base/v1/models" -Headers @{'x-api-key'=$pending.key}
    Assert-True ($models.data.Count -eq 1 -and $models.data[0].id -eq 'demo-model') 'API-key model catalog scope was not enforced'
    $quota = Invoke-RestMethod "$base/admin/quotas" -Method Post -WebSession $adminSession -Headers $writeHeaders -ContentType 'application/json' -Body (@{userId=$user.id;quotaType='requests';limit=3;period='daily'} | ConvertTo-Json)
    $client = [System.Net.Http.HttpClient]::new()
    $tasks = @()
    try {
        foreach ($i in 1..8) {
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,"$base/v1/messages")
            $request.Headers.Add('x-api-key',$pending.key)
            $request.Content = [System.Net.Http.StringContent]::new($body,[System.Text.Encoding]::UTF8,'application/json')
            $tasks += $client.SendAsync($request)
        }
        $codes = @($tasks | ForEach-Object { $response=$_.GetAwaiter().GetResult(); try { [int]$response.StatusCode } finally { $response.Dispose() } })
    } finally { $client.Dispose() }
    $accepted = @($codes | Where-Object { $_ -eq 200 }).Count
    $limited = @($codes | Where-Object { $_ -eq 429 }).Count
    Assert-True ($accepted -eq 3 -and $limited -eq 5) "Concurrent quota failed: accepted=$accepted limited=$limited"
    $logs = Invoke-RestMethod "$base/admin/logs?userId=$($user.id)&pageSize=50" -WebSession $adminSession
    Assert-True ($logs.total -eq 3) 'Rejected requests were counted as admitted requests'
    $null = Invoke-RestMethod "$base/admin/users/$($user.id)" -Method Put -WebSession $adminSession -Headers $writeHeaders -ContentType 'application/json' -Body '{"status":"suspended"}'
    $disabledStatus = Status-Of { Invoke-WebRequest "$base/v1/models" -Headers @{'x-api-key'=$pending.key} }
    Assert-True ($disabledStatus -eq 401) 'Suspended user retained API access'
    $dashboard = Invoke-RestMethod "$base/admin/dashboard?range=1d" -WebSession $adminSession
    Assert-True ($dashboard.totalRequests -ge 3) 'Dashboard did not reflect persisted requests'
    $latency = Invoke-RestMethod "$base/admin/latency" -WebSession $adminSession
    Assert-True ($latency.sampleCount -ge 3) 'Latency statistics did not reflect requests'
    Write-Output "PASS rotation, pending-key isolation, catalog scope, concurrent quota (3 accepted / 5 rejected), caller logs, suspended-user access, dashboard, latency"
} finally {
    $null = Invoke-WebRequest "$base/admin/users/$($user.id)" -Method Delete -WebSession $adminSession -Headers $writeHeaders
}
