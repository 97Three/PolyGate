$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$release=Join-Path $root 'release'
New-Item -ItemType Directory -Force $release | Out-Null
$jar=Join-Path $root 'target\polygate-1.0.0.jar'
if(-not(Test-Path $jar)){throw 'Build the Java release first'}
Copy-Item -LiteralPath $jar -Destination (Join-Path $release 'polygate-1.0.0.jar') -Force
if(Test-Path (Join-Path $root 'target\classes\META-INF\sbom\application.cdx.json')){Copy-Item -LiteralPath (Join-Path $root 'target\classes\META-INF\sbom\application.cdx.json') -Destination (Join-Path $root 'target\bom.json') -Force}
foreach($sbom in @('bom.json','dashboard-sbom.cdx.json')){if(Test-Path (Join-Path $root ('target\'+$sbom))){Copy-Item -LiteralPath (Join-Path $root ('target\'+$sbom)) -Destination (Join-Path $release $sbom) -Force}}
$files=[Collections.Generic.List[string]]::new()
foreach($name in @('pom.xml','mvnw','mvnw.cmd','Dockerfile','.dockerignore','.gitignore','.env.example','compose.yml','README.md','MIGRATION_STATUS.md','PARITY_COMPLETION.md','LICENSE')){$files.Add((Join-Path $root $name))}
foreach($folder in @('src','scripts','.mvn','.github','deploy','dashboard\src','dashboard\public','dashboard\e2e')){Get-ChildItem -LiteralPath (Join-Path $root $folder) -Recurse -File | ForEach-Object {$files.Add($_.FullName)}}
Get-ChildItem -LiteralPath (Join-Path $root 'dashboard') -File -Force | ForEach-Object {$files.Add($_.FullName)}
$archive=Join-Path $release 'polygate-1.0.0-source.zip'
$stream=[IO.File]::Open($archive,[IO.FileMode]::Create)
$zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try { foreach($file in $files){$relative=[IO.Path]::GetRelativePath($root,$file).Replace('\','/');if($relative-match '(^|/)(tools|target|node_modules|\.m2|\.npm|tmp|release|\.git)/'-or ($relative.EndsWith('.env') -and $relative-ne '.env.example')){throw 'Unexpected private/generated file in package'};$null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file,'PolyGate-Java/'+$relative,[IO.Compression.CompressionLevel]::Optimal)} } finally {$zip.Dispose();$stream.Dispose()}
# Validate archive structure and check actual local secrets without printing any values.
$secrets=@(foreach($envFile in @('tools\release.env','tools\runtime-db.env','tools\local-access.env')){if(Test-Path (Join-Path $root $envFile)){foreach($line in Get-Content (Join-Path $root $envFile)){$p=$line-split '=',2;if($p.Count-eq 2-and $p[1].Length-ge 20-and $p[0]-match 'PASSWORD|TOKEN|KEY'){$p[1]}}}})
foreach($artifact in @($archive,(Join-Path $release 'polygate-1.0.0.jar'))){$check=[IO.Compression.ZipFile]::OpenRead($artifact);try{foreach($entry in $check.Entries){if($entry.FullName-match '(^|/)(release\.env|runtime-db\.env|local-access\.env|backups)/?'){throw 'Private file found in delivery'};if($entry.Length-gt 0-and $entry.Length-lt 2000000-and $entry.FullName-notmatch '\.(jar|png|jpg|woff2?|zip)$'){$reader=[IO.StreamReader]::new($entry.Open());try{$content=$reader.ReadToEnd();foreach($secret in $secrets){if($content.Contains($secret)){throw ('A local credential appeared in artifact entry '+$entry.FullName)}}}finally{$reader.Dispose()}}}}finally{$check.Dispose()}}
Copy-Item -LiteralPath (Join-Path $root 'PARITY_COMPLETION.md') -Destination (Join-Path $release 'PARITY_COMPLETION.md') -Force
Copy-Item -LiteralPath (Join-Path $root 'README.md') -Destination (Join-Path $release '交付说明.md') -Force
Copy-Item -LiteralPath (Join-Path $root 'MIGRATION_STATUS.md') -Destination (Join-Path $release '功能与兼容范围.md') -Force
Get-ChildItem -LiteralPath $release -File | Where-Object {$_.Name-notin @('SHA256SUMS.txt','SHA256SUMS.sig')} | Sort-Object Name | ForEach-Object {$hash=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();$hash+'  '+$_.Name} | Set-Content -LiteralPath (Join-Path $release 'SHA256SUMS.txt') -Encoding utf8
Write-Output 'Release JAR/source ZIP validated; local credentials excluded; SHA256 manifest written'
