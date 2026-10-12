param([string]$MySqlBin = (Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source))
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify-stage1.ps1') -MySqlBin $MySqlBin -Stage3
