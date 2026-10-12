param([string]$MySqlBin = (Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source))
$ErrorActionPreference = 'Stop'
# Workflow does not require a remote model; UNKNOWN pre-review stays explicit.
& (Join-Path $PSScriptRoot 'verify-stage1.ps1') -MySqlBin $MySqlBin -Stage9
