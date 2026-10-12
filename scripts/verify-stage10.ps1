param([string]$MySqlBin = (Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source))
$ErrorActionPreference = 'Stop'
# Isolated MySQL + AMQP/Redis client contracts + HTTP/browser regression.
# Real broker/Redis and throughput acceptance are separate; see stage 10 acceptance.
& (Join-Path $PSScriptRoot 'verify-stage1.ps1') -MySqlBin $MySqlBin -Stage10
