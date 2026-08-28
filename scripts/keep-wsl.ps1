$ErrorActionPreference='Stop'
$mutex=[Threading.Mutex]::new($false,'Local\PolyGateWslKeepAlive')
if(-not $mutex.WaitOne(0)){exit 0}
try { while($true){& "$env:SystemRoot\System32\wsl.exe" -d Ubuntu-24.04 --exec /bin/sleep infinity;Start-Sleep -Seconds 10} } finally {$mutex.ReleaseMutex();$mutex.Dispose()}
