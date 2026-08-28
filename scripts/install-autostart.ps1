$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'keep-wsl.ps1'
$hostExe=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments='-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$helper+'"'
$runPath='HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
if(-not(Test-Path $runPath)){New-Item -Path $runPath -ErrorAction Stop | Out-Null}
New-ItemProperty -Path $runPath -Name PolyGateWsl -Value ('"'+$hostExe+'" '+$arguments) -PropertyType String -Force -ErrorAction Stop | Out-Null
Start-Process -FilePath $hostExe -ArgumentList $arguments -WindowStyle Hidden -ErrorAction Stop
Write-Output 'Configured current-user logon background startup for PolyGate Java WSL'
