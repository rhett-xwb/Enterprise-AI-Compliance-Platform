# Internal helper: dot-sourced by verify-stage1.ps1 in its isolated test environment.
Stop-Process -Id $appProcess.Id
$appProcess.WaitForExit()
$appProcess = $null
$authSql = "CREATE DATABASE compliance_platform_auth_acceptance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON compliance_platform_auth_acceptance.* TO 'stage1_test'@'127.0.0.1';"
$authSql | & $mysqlClient --no-defaults --protocol=tcp -h 127.0.0.1 "-P$mysqlPort" -u root
if ($LASTEXITCODE -ne 0) { throw 'Authentication database setup failed' }
$env:DB_URL = $env:TEST_DB_URL.Replace('/compliance_platform_test?', '/compliance_platform_auth_acceptance?')
$env:BOOTSTRAP_ENABLED = 'false'
$env:INITIAL_ADMIN_ENABLED = 'true'
$env:INITIAL_ADMIN_USERNAME = 'acceptance-admin'
$adminPassword = [guid]::NewGuid().ToString('N')
$env:INITIAL_ADMIN_PASSWORD = $adminPassword

function Start-AuthApp([string]$Suffix) {
    $script:appProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar', ('"' + $appJar + '"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath "auth-$Suffix-out.log") -RedirectStandardError (Join-Path $runPath "auth-$Suffix-error.log")
    Wait-Health "$appUrl/health"
}
function Login-Auth([string]$Username, [string]$Password) {
    $payload = @{username=$Username;password=$Password} | ConvertTo-Json
    Invoke-RestMethod "$appUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $payload -TimeoutSec 10
}
function Expect-AuthStatus([string]$Path, [hashtable]$Headers, [int]$Status) {
    $reply = Invoke-WebRequest "$appUrl$Path" -Headers $Headers -SkipHttpErrorCheck -TimeoutSec 5
    if ([int]$reply.StatusCode -ne $Status) { throw "Unexpected HTTP status on $Path" }
}

Start-AuthApp 'first'
# The HTTP server starts before ApplicationRunner completes the initial account transaction.
$adminLogin = $null
for ($attempt=0; $attempt -lt 20; $attempt++) {
    try { $adminLogin = Login-Auth $env:INITIAL_ADMIN_USERNAME $adminPassword; break }
    catch { if ($appProcess.HasExited) { throw }; Start-Sleep -Milliseconds 300 }
}
if (-not $adminLogin.data.accessToken) { throw 'Initial administrator login failed' }
$adminHeaders = @{Authorization="Bearer $($adminLogin.data.accessToken)"}
$me = Invoke-RestMethod "$appUrl/api/auth/me" -Headers $adminHeaders
if ($me.data.roles -notcontains 'ADMIN') { throw 'Initial administrator role missing' }
Expect-AuthStatus '/api/users' @{} 401
Expect-AuthStatus '/api/bootstrap/users' @{} 401
$employeePassword = [guid]::NewGuid().ToString('N')
$payload = @{username='auth-employee';password=$employeePassword;displayName='Auth Employee'} | ConvertTo-Json
$employee = Invoke-RestMethod "$appUrl/api/users" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body $payload
$employeeLogin = Login-Auth 'auth-employee' $employeePassword
$employeeHeaders = @{Authorization="Bearer $($employeeLogin.data.accessToken)"}
Expect-AuthStatus '/api/users' $employeeHeaders 403
$rolesBody = @{roles=@('LEGAL')} | ConvertTo-Json
Invoke-RestMethod "$appUrl/api/users/$($employee.data.id)/roles" -Method Put -Headers $adminHeaders -ContentType 'application/json' -Body $rolesBody | Out-Null
$me = Invoke-RestMethod "$appUrl/api/auth/me" -Headers $employeeHeaders
if ($me.data.roles -notcontains 'LEGAL' -or $me.data.roles -contains 'EMPLOYEE') { throw 'Live role refresh failed' }
Invoke-RestMethod "$appUrl/api/auth/logout" -Method Post -Headers $employeeHeaders | Out-Null
Expect-AuthStatus '/api/auth/me' $employeeHeaders 401

# Restart with a different bootstrap password: existing accounts must remain intact.
Stop-Process -Id $appProcess.Id
$appProcess.WaitForExit()
$env:INITIAL_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
Start-AuthApp 'restart'
Expect-AuthStatus '/api/auth/me' $adminHeaders 200
$secondLogin = Login-Auth 'acceptance-admin' $adminPassword
if (-not $secondLogin.data.accessToken) { throw 'Administrator password was overwritten' }
$secretPatterns = @($adminPassword,$employeePassword,$env:INITIAL_ADMIN_PASSWORD,$env:JWT_SECRET,$adminLogin.data.accessToken,$employeeLogin.data.accessToken,$secondLogin.data.accessToken)
if (Get-ChildItem $runPath -Filter '*.log' | Select-String -SimpleMatch -Pattern $secretPatterns) { throw 'Authentication secret found in logs' }
Write-Output 'STAGE2_VERIFIED: administrator initialization, Bearer login, role refresh, logout revocation, restart persistence, credential log check'
