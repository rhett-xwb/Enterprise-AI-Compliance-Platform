# Readiness fixtures belong only to this isolated acceptance database, never a normal business startup.
if (-not $env:DB_URL.Contains('/compliance_platform_auth_acceptance?')) { throw 'Stage 4 fixtures require isolated auth acceptance database' }
& node (Join-Path $PSScriptRoot 'stage4-fixtures.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Stage 4 HTTP upload fixtures failed' }
$fixture = Get-Content -Raw (Join-Path $runPath 'stage4-fixtures.json') | ConvertFrom-Json
foreach ($versionId in @($fixture.v1,$fixture.v2,$fixture.restrictedVersion)) {
    $fixtureId = [long]$versionId
    $fixtureSql = "UPDATE document_version SET processing_status='READY' WHERE id=$fixtureId; INSERT INTO document_chunk(document_version_id,chunk_index,text_content,index_status,vector_key,embedding_model) VALUES($fixtureId,0,'Stage 4 readiness fixture','INDEXED','fixture-$fixtureId','fixture-embedding');"
    $fixtureSql | & $mysqlClient --no-defaults --protocol=tcp -h 127.0.0.1 "-P$mysqlPort" -u root --default-character-set=utf8mb4 compliance_platform_auth_acceptance
    if ($LASTEXITCODE -ne 0) { throw 'Readiness fixture setup failed' }
}
Invoke-RestMethod "$appUrl/api/documents/$($fixture.id)/versions/$($fixture.v1)/publish" -Method Post -Headers $adminHeaders | Out-Null
Invoke-RestMethod "$appUrl/api/documents/$($fixture.restrictedId)/versions/$($fixture.restrictedVersion)/publish" -Method Post -Headers $adminHeaders | Out-Null
