$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot;$release=Join-Path $root 'release';$keyFile=Join-Path $root 'tools\release-signing-key.pem'
$key=[Security.Cryptography.ECDsa]::Create([Security.Cryptography.ECCurve+NamedCurves]::nistP256)
try{
 if(Test-Path -LiteralPath $keyFile){$key.ImportFromPem([IO.File]::ReadAllText($keyFile))}else{[IO.File]::WriteAllText($keyFile,$key.ExportPkcs8PrivateKeyPem())}
 [IO.File]::WriteAllText((Join-Path $release 'release-public-key.pem'),$key.ExportSubjectPublicKeyInfoPem())
 & (Join-Path $PSScriptRoot 'package-release.ps1')
 $data=[IO.File]::ReadAllBytes((Join-Path $release 'SHA256SUMS.txt'))
 $signature=$key.SignData($data,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
 [IO.File]::WriteAllBytes((Join-Path $release 'SHA256SUMS.sig'),$signature)
 if(-not $key.VerifyData($data,$signature,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)){throw 'Release signing verification failed'}
 Write-Output 'Release checksum manifest signed and verified locally; private key remains in ignored tools directory'
}finally{$key.Dispose()}
