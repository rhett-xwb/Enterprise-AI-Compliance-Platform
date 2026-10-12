$python = Join-Path $workspacePath '.cache\ai-venv\Scripts\python.exe'
$env:PARSER_ENABLED = 'true'
$env:PARSER_URL = "http://127.0.0.1:$(Get-FreeLocalPort)"
$env:PARSER_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:PARSER_TIMEOUT_SECONDS = '180'
$env:PARSER_CLIENT_TIMEOUT_SECONDS = '190'
$env:PYTHONUTF8 = '1'
$env:PADDLE_PDX_CACHE_HOME = Join-Path $workspacePath '.cache\paddle'
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = 'True'
$env:E2E_STAGE5 = 'true'
$env:E2E_STAGE5_SAMPLES = Join-Path $runPath 'parser-samples'
& $python (Join-Path $workspacePath 'ai-service\tests\create_samples.py') $env:E2E_STAGE5_SAMPLES
if ($LASTEXITCODE -ne 0) { throw 'Parser sample generation failed' }
$parserProcess = Start-Process -FilePath $python -WorkingDirectory (Join-Path $workspacePath 'ai-service') -ArgumentList @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',([uri]$env:PARSER_URL).Port,'--no-access-log') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'parser-out.log') -RedirectStandardError (Join-Path $runPath 'parser-error.log')
$parserReady = $false
for ($attempt=0; $attempt -lt 60; $attempt++) {
    if ($parserProcess.HasExited) { throw "Parser exited; see $runPath" }
    try { Invoke-WebRequest "$env:PARSER_URL/health" -TimeoutSec 2 | Out-Null; $parserReady=$true; break } catch { Start-Sleep -Milliseconds 300 }
}
if (-not $parserReady) { throw "Parser not ready; see $runPath" }
