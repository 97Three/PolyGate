param([switch]$CheckOnly)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$tools=Join-Path $root 'tools'
$jdk=Join-Path $tools 'jdk-21.0.12.1+1'
$node=Join-Path $tools 'node-v24.19.0-win-x64'
if($CheckOnly){if(-not(Test-Path "$jdk\bin\java.exe")-or -not(Test-Path "$node\npm.cmd")){throw 'Run scripts/bootstrap.ps1 to install Java and Node inside this project'};Write-Output 'Project-local Java 21 and Node 24 are available';return}
New-Item -ItemType Directory -Force $tools,(Join-Path $root 'tmp')|Out-Null
function Verify-Download($path,$expected){if(-not $expected -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()-ne $expected.ToLowerInvariant()){throw 'Downloaded archive checksum did not match the official release'}}
if(-not(Test-Path "$jdk\bin\java.exe")){
  $assets=Invoke-RestMethod 'https://api.adoptium.net/v3/assets/version/21.0.12.1%2B1?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse'
  $packages=@(foreach($asset in $assets){if($asset.binary){$asset.binary.package}else{foreach($binary in $asset.binaries){if($binary.os-eq 'windows'-and $binary.architecture-eq 'x64'-and $binary.image_type-eq 'jdk'){$binary.package}}}})
  $package=$packages|Where-Object{$_.name-like '*.zip'}|Select-Object -First 1
  if(-not $package){throw 'Official Java release metadata is unavailable'}
  $archive=Join-Path $tools 'java-21-install.zip';Invoke-WebRequest $package.link -OutFile $archive;Verify-Download $archive $package.checksum;Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
  if(-not(Test-Path "$jdk\bin\java.exe")){throw 'The Java archive did not contain the expected JDK directory'}
}
if(-not(Test-Path "$node\npm.cmd")){
  $name='node-v24.19.0-win-x64.zip';$base='https://nodejs.org/dist/v24.19.0'
  $sums=(Invoke-WebRequest "$base/SHASUMS256.txt").Content
  $match=[regex]::Match($sums,'(?m)^([a-f0-9]{64})\s+node-v24\.19\.0-win-x64\.zip\s*$')
  $archive=Join-Path $tools $name;Invoke-WebRequest "$base/$name" -OutFile $archive;Verify-Download $archive $match.Groups[1].Value;Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
}
Write-Output 'Java and Node installed inside this project; Maven Wrapper installs Maven on first build'
