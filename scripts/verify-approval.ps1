$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
function Wsl-Path($value){$value=[IO.Path]::GetFullPath($value);if($value-match '^([A-Za-z]):\\(.*)$'){return '/mnt/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2].Replace('\','/')};throw 'A local Windows drive path is required'}
$linuxRoot=Wsl-Path $root
$base=if($env:POLYGATE_TEST_BASE){$env:POLYGATE_TEST_BASE}else{'http://127.0.0.1:18084'};$strict='http://127.0.0.1:18081'
$secretLine=Get-Content (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\runtime-db.env') | Where-Object {$_.StartsWith('POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=')}
$password=$secretLine.Substring($secretLine.IndexOf('=')+1)
$null=Invoke-WebRequest "$base/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable adminSession
$h=@{'x-polygate-csrf'='1'};$created=@();$started=$false
function Assert-True($condition,$message){if(-not $condition){throw $message}}
function Status-Of($action){try{$r=& $action;return [int]$r.StatusCode}catch{return [int]$_.Exception.Response.StatusCode}}
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8);$reviewPassword="Approval-test-$suffix-pass"
$reviewer=Invoke-RestMethod "$base/admin/users" -Method Post -WebSession $adminSession -Headers $h -ContentType 'application/json' -Body (@{username="approval_admin_$suffix";password=$reviewPassword;email='';role='admin';status='active'}|ConvertTo-Json)
try{
    $null=& wsl.exe -d Ubuntu-24.04 -- docker run --rm -d --name polygate-approval-test --network polygate_default --env-file $linuxRoot/tools/runtime-db.env -e POLYGATE_REQUIRE_DUAL_APPROVAL=true -e POLYGATE_BIND=0.0.0.0 -e POLYGATE_CLIENT_TOKEN=approval-test-client -e POLYGATE_UPSTREAM_KEY=local-mock-key -e POLYGATE_UPSTREAM_URL=http://polygate-mock:19090 -e POLYGATE_UPSTREAM_ALLOW_HTTP_MOCK=true -p 127.0.0.1:18081:18080 polygate:dev
    if($LASTEXITCODE-ne 0){throw 'Unable to start approval test instance'};$started=$true
    $ready=$false;foreach($attempt in 1..30){try{if((Invoke-RestMethod "$strict/actuator/health" -TimeoutSec 2).status-eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1};Assert-True $ready 'Approval test service did not become ready'
    $null=Invoke-WebRequest "$strict/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$password}|ConvertTo-Json) -SessionVariable strictSession
    $null=Invoke-WebRequest "$strict/admin/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$reviewer.username;password=$reviewPassword}|ConvertTo-Json) -SessionVariable reviewerSession
    $mode=Invoke-RestMethod "$strict/admin/governance" -WebSession $strictSession
    Assert-True $mode.dualApprovalRequired 'Strict instance did not enable dual approval'
    foreach($run in 1..2){
        $permission=@{username="approved_${suffix}_$run";email='';role='user';status='active'};$actual=$permission.Clone();$actual.password=$reviewPassword
        $unapprovedStatus=Status-Of {Invoke-WebRequest "$strict/admin/users" -Method Post -WebSession $strictSession -Headers $h -ContentType 'application/json' -Body ($actual|ConvertTo-Json)}
        Assert-True ($unapprovedStatus-eq 403) "Mandatory approval failed: HTTP $unapprovedStatus"
        $change=Invoke-RestMethod "$strict/admin/governance/change-requests" -Method Post -WebSession $strictSession -Headers $h -ContentType 'application/json' -Body (@{action='identity.permission';target="user:$($permission.username)";payload=$permission;reason='Automated dual-approval verification'}|ConvertTo-Json -Depth 10)
        Assert-True ($change.status-eq 'pending_second_approval') 'Mandatory change was approved without a second administrator'
        Assert-True ((Status-Of {Invoke-WebRequest "$strict/admin/governance/change-requests/$($change.id)/approve" -Method Post -WebSession $strictSession -Headers $h})-eq 403) 'Requester approved their own change'
        $null=Invoke-RestMethod "$strict/admin/governance/change-requests/$($change.id)/approve" -Method Post -WebSession $reviewerSession -Headers $h
        $approvedHeaders=@{'x-polygate-csrf'='1';'x-polygate-change-request-id'=$change.id}
        $tampered=$actual.Clone();$tampered.role='admin'
        Assert-True ((Status-Of {Invoke-WebRequest "$strict/admin/users" -Method Post -WebSession $strictSession -Headers $approvedHeaders -ContentType 'application/json' -Body ($tampered|ConvertTo-Json)})-eq 403) 'Approval accepted a substituted permission payload'
        if($run-eq 2){
            $bad=$actual.Clone();$bad.password='short'
            Assert-True ((Status-Of {Invoke-WebRequest "$strict/admin/users" -Method Post -WebSession $strictSession -Headers $approvedHeaders -ContentType 'application/json' -Body ($bad|ConvertTo-Json)})-eq 400) 'Invalid password unexpectedly passed validation'
            $changes=Invoke-RestMethod "$strict/admin/governance/change-requests" -WebSession $strictSession
            Assert-True (($changes|Where-Object {$_.id-eq $change.id}).status-eq 'approved') 'Failed mutation consumed its approval'
        }
        $account=Invoke-RestMethod "$strict/admin/users" -Method Post -WebSession $strictSession -Headers $approvedHeaders -ContentType 'application/json' -Body ($actual|ConvertTo-Json);$created+=,$account
        Assert-True ($account.role-eq 'user') 'Approved permission was not applied correctly'
        Assert-True ((Status-Of {Invoke-WebRequest "$strict/admin/users" -Method Post -WebSession $strictSession -Headers $approvedHeaders -ContentType 'application/json' -Body ($actual|ConvertTo-Json)})-eq 403) 'An applied approval was replayed'
    }
    Write-Output 'PASS mandatory dual approval, independent approver, exact payload binding, atomic approval consumption, rollback on failed mutation, replay rejection'
}finally{
    if($started){$null=& wsl.exe -d Ubuntu-24.04 -- docker stop polygate-approval-test}
    foreach($account in $created){$null=Invoke-WebRequest "$base/admin/users/$($account.id)" -Method Delete -WebSession $adminSession -Headers $h}
    $allAccounts=Invoke-RestMethod "$base/admin/users" -WebSession $adminSession
    $remaining=@($allAccounts | Where-Object {$_.username.StartsWith("approved_${suffix}_")})
    foreach($account in $remaining){$null=Invoke-WebRequest "$base/admin/users/$($account.id)" -Method Delete -WebSession $adminSession -Headers $h}
    $null=Invoke-WebRequest "$base/admin/users/$($reviewer.id)" -Method Delete -WebSession $adminSession -Headers $h
}
