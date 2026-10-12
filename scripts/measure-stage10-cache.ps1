param(
    [Parameter(Mandatory)][long]$DocumentId,
    [Parameter(Mandatory)][long]$VersionId,
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(10,1000)][int]$Samples = 50,
    [ValidateSet('cache-off','cache-on')][string]$Label = 'cache-off'
)
$ErrorActionPreference = 'Stop'
if (-not $env:BENCHMARK_USERNAME -or -not $env:BENCHMARK_PASSWORD) { throw 'Set BENCHMARK_USERNAME and BENCHMARK_PASSWORD to an authorized governance account.' }
if (([uri]$BaseUrl).Host -notin @('127.0.0.1','localhost')) { throw 'This benchmark is restricted to a local service.' }
$loginBody = @{ username=$env:BENCHMARK_USERNAME; password=$env:BENCHMARK_PASSWORD } | ConvertTo-Json
$session = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization="Bearer $($session.data.accessToken)" }
try {
    $url = "$BaseUrl/api/documents/$DocumentId/versions/$VersionId/sources"
    1..5 | ForEach-Object { Invoke-RestMethod $url -Headers $headers | Out-Null }
    $times = for ($i=0; $i -lt $Samples; $i++) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $reply = Invoke-RestMethod $url -Headers $headers
        $watch.Stop()
        if ($reply.code -ne 0 -or $reply.data.Count -eq 0) { throw 'Expected successful immutable extraction sources.' }
        $watch.Elapsed.TotalMilliseconds
    }
    $sorted = @($times | Sort-Object)
    [ordered]@{ label=$Label; samples=$Samples; documentId=$DocumentId; versionId=$VersionId; medianMs=[Math]::Round($sorted[[int]($Samples/2)],2); p95Ms=[Math]::Round($sorted[[int][Math]::Ceiling($Samples*0.95)-1],2); meanMs=[Math]::Round(($times | Measure-Object -Average).Average,2) } | ConvertTo-Json
} finally {
    Invoke-RestMethod "$BaseUrl/api/auth/logout" -Method Post -Headers $headers | Out-Null
    $session = $null; $headers = $null
}
