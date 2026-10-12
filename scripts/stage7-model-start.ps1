# Controlled contract fixture, never a production model or quality benchmark.
$env:QA_TEST_PORT = [string](Get-FreeLocalPort)
$env:QA_ENABLED = 'true'
$env:QA_COMPLETIONS_URL = "http://127.0.0.1:$env:QA_TEST_PORT/v1/chat/completions"
$env:QA_MODEL = 'controlled-contract-fixture'
$env:QA_API_KEY = $null
$env:QA_TIMEOUT_SECONDS = '5'
$modelFixture = Join-Path $workspacePath 'scripts\stage7-model-fixture.mjs'
$questionModelProcess = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @(('"' + $modelFixture + '"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'question-model-out.log') -RedirectStandardError (Join-Path $runPath 'question-model-error.log')
$modelReady = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    if ($questionModelProcess.HasExited) { throw 'Controlled model fixture exited' }
    try { Invoke-WebRequest "http://127.0.0.1:$env:QA_TEST_PORT/health" -TimeoutSec 2 | Out-Null; $modelReady = $true; break } catch { Start-Sleep -Milliseconds 250 }
}
if (-not $modelReady) { throw 'Controlled model fixture not ready' }
