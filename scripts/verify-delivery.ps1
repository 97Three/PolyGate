param([string]$Directory)
$ErrorActionPreference='Stop'
if(-not $Directory){$Directory=Join-Path (Split-Path -Parent $PSScriptRoot) 'release'}
$Directory=[IO.Path]::GetFullPath($Directory);$key=[Security.Cryptography.ECDsa]::Create()
try{
 $key.ImportFromPem([IO.File]::ReadAllText((Join-Path $Directory 'release-public-key.pem')))
 if(-not $key.VerifyData([IO.File]::ReadAllBytes((Join-Path $Directory 'SHA256SUMS.txt')),[IO.File]::ReadAllBytes((Join-Path $Directory 'SHA256SUMS.sig')),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)){throw 'Release signature invalid'}
 foreach($line in Get-Content -LiteralPath (Join-Path $Directory 'SHA256SUMS.txt')){
  if($line-notmatch '^([a-f0-9]{64})  ([^/\\]+)$'){throw 'Invalid checksum manifest line'}
  $expected=$Matches[1];$name=$Matches[2];if($name.Contains('..')){throw 'Invalid artifact name'}
  if((Get-FileHash -LiteralPath (Join-Path $Directory $name) -Algorithm SHA256).Hash-ne $expected){throw "Artifact checksum invalid: $name"}
 }
 Write-Output 'PASS detached release signature and all artifact hashes'
}finally{$key.Dispose()}
