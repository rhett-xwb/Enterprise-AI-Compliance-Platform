$env:RETRIEVAL_ENABLED = 'true'
$env:RETRIEVAL_URL = "http://127.0.0.1:$(Get-FreeLocalPort)"
$env:RETRIEVAL_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:VECTOR_STORAGE_ROOT = Join-Path $runPath 'vectors'
$env:EMBEDDING_CACHE = Join-Path $workspacePath '.cache\embedding'
$env:E2E_STAGE6 = 'true'
$env:HF_HUB_OFFLINE = '1'
$retrievalProcess = Start-Process -FilePath $python -WorkingDirectory (Join-Path $workspacePath 'ai-service') -ArgumentList @('-m','uvicorn','app.retrieval:app','--host','127.0.0.1','--port',([uri]$env:RETRIEVAL_URL).Port,'--no-access-log') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'retrieval-out.log') -RedirectStandardError (Join-Path $runPath 'retrieval-error.log')
$ready = $false
for ($attempt=0; $attempt -lt 100; $attempt++) {
    if ($retrievalProcess.HasExited) { throw "Retrieval service exited; see $runPath" }
    try { Invoke-WebRequest "$env:RETRIEVAL_URL/health" -TimeoutSec 2 | Out-Null; $ready=$true; break } catch { Start-Sleep -Milliseconds 300 }
}
if (-not $ready) { throw "Retrieval service not ready; see $runPath" }
