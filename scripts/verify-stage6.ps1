param([string]$MySqlBin = (Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source))
$ErrorActionPreference = 'Stop'
$workspacePath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Join-Path $workspacePath '.cache\ai-venv\Scripts\python.exe'
Push-Location (Join-Path $workspacePath 'ai-service')
try {
    & $python -m pytest -q "--basetemp=$workspacePath\.cache\pytest-$([guid]::NewGuid().ToString('N'))"
    if ($LASTEXITCODE -ne 0) { throw 'Python checks failed' }
} finally { Pop-Location }
& (Join-Path $PSScriptRoot 'verify-stage1.ps1') -MySqlBin $MySqlBin -Stage6
