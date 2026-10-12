# 在阶段 2 创建的隔离数据库和服务中执行浏览器验收。
$frontendProcess = $null
$stage3Names = @('API_PROXY_TARGET','E2E_ADMIN_USERNAME','E2E_ADMIN_PASSWORD','E2E_USER_PASSWORD','E2E_BASE_URL','PLAYWRIGHT_BROWSERS_PATH','E2E_EVIDENCE')
$stage3Names += @('E2E_STAGE4','E2E_STAGE5','E2E_STAGE6','E2E_STAGE7','E2E_STAGE8','E2E_STAGE9','E2E_STAGE10')
$stage3Saved = @{}
foreach ($name in $stage3Names) { $stage3Saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:E2E_ADMIN_USERNAME = 'acceptance-admin'
    $env:E2E_ADMIN_PASSWORD = $adminPassword
    $env:E2E_USER_PASSWORD = [guid]::NewGuid().ToString('N')
    foreach ($role in @('EMPLOYEE','LEGAL')) {
        $payload = @{username="browser-$($role.ToLower())";password=$env:E2E_USER_PASSWORD;displayName="Browser $role"} | ConvertTo-Json
        $created = Invoke-RestMethod "$appUrl/api/users" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body $payload
        $roleBody = @{roles=@($role)} | ConvertTo-Json
        Invoke-RestMethod "$appUrl/api/users/$($created.data.id)/roles" -Method Put -Headers $adminHeaders -ContentType 'application/json' -Body $roleBody | Out-Null
    }
    $env:API_PROXY_TARGET = $appUrl
    $env:E2E_BASE_URL = "http://127.0.0.1:$(Get-FreeLocalPort)"
    $env:E2E_EVIDENCE = $runPath
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $workspacePath '.cache\playwright'
    $env:E2E_STAGE4 = if ($Stage4) { 'true' } else { 'false' }
    $env:E2E_STAGE5 = if ($Stage5) { 'true' } else { 'false' }
    $env:E2E_STAGE6 = if ($Stage6) { 'true' } else { 'false' }
    $env:E2E_STAGE7 = if ($Stage7) { 'true' } else { 'false' }
    $env:E2E_STAGE8 = if ($Stage8) { 'true' } else { 'false' }
    $env:E2E_STAGE9 = if ($Stage9) { 'true' } else { 'false' }
    $env:E2E_STAGE10 = if ($Stage10) { 'true' } else { 'false' }
    if ($Stage4) { . (Join-Path $PSScriptRoot 'stage4-fixtures.ps1') }
    $port = ([uri]$env:E2E_BASE_URL).Port
    $vitePath = Join-Path $workspacePath 'frontend\node_modules\vite\bin\vite.js'
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source -WorkingDirectory (Join-Path $workspacePath 'frontend') -ArgumentList @(('"' + $vitePath + '"'),'--port',"$port",'--strictPort') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'frontend-out.log') -RedirectStandardError (Join-Path $runPath 'frontend-error.log')
    $ready = $false
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        if ($frontendProcess.HasExited) { throw 'Frontend exited before readiness' }
        try { Invoke-WebRequest $env:E2E_BASE_URL -TimeoutSec 2 | Out-Null; $ready=$true; break } catch { Start-Sleep -Milliseconds 300 }
    }
    if (-not $ready) { throw 'Frontend did not become ready' }
    Push-Location (Join-Path $workspacePath 'frontend')
    try {
        & npm run test:e2e
        if ($LASTEXITCODE -ne 0) { throw "Browser acceptance failed; see $runPath" }
    } finally { Pop-Location }
    $secretPatterns = @($env:E2E_ADMIN_PASSWORD, $env:E2E_USER_PASSWORD, $env:JWT_SECRET)
    if ($Stage5) { $secretPatterns += $env:PARSER_SECRET }
    if ($Stage6) { $secretPatterns += $env:RETRIEVAL_SECRET }
    if (Get-ChildItem $runPath -Filter '*.log' | Select-String -SimpleMatch -Pattern $secretPatterns) { throw 'Browser acceptance credentials found in service logs' }
    Write-Output 'STAGE3_VERIFIED: live browser login, three roles, route authorization, backend deny, session restoration, logout, error recovery, responsive layout'
    if ($Stage4) { Write-Output 'STAGE4_VERIFIED: upload validation, immutable versions, authorized download, publication replacement, retirement, audit, browser CRUD; readiness uses isolated fixtures only' }
    if ($Stage5) { Write-Output 'STAGE5_VERIFIED: electronic/scanned PDF, PNG/JPEG PaddleOCR, DOCX sources, async task polling, idempotency, failure retry, governance and preview' }
    if ($Stage6) { Write-Output 'STAGE6_VERIFIED: real Chinese embeddings, persistent Qdrant, indexing/rebuild, current-version ACL retrieval, publication and source download' }
    if ($Stage7) { Write-Output 'STAGE7_VERIFIED: controlled Chat Completions contract, real retrieval, citations/history, ACL invalidation, failure recovery; live model quality remains pending' }
    if ($Stage8) { Write-Output 'STAGE8_VERIFIED: travel drafts, attachments, current-content review, invalidation, immutable submission, ownership; real model quality and human workflow remain pending' }
} finally {
    if ($frontendProcess -and -not $frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id }
    foreach ($name in $stage3Names) { [Environment]::SetEnvironmentVariable($name, $stage3Saved[$name], 'Process') }
}
