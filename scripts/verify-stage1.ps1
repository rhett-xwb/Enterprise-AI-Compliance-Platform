param(
    [string]$MySqlBin = (Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source)
)

$ErrorActionPreference = 'Stop'
$workspacePath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runPath = Join-Path $workspacePath ('.cache\stage1-' + [guid]::NewGuid().ToString('N'))
$dataPath = Join-Path $runPath 'mysql-data'
New-Item -ItemType Directory -Path $dataPath -Force | Out-Null
$mysqlServer = Join-Path $MySqlBin 'mysqld.exe'
$mysqlClient = Join-Path $MySqlBin 'mysql.exe'
$mysqlAdmin = Join-Path $MySqlBin 'mysqladmin.exe'
$mysqlProcess = $null
$appProcess = $null
$changedNames = @('TEST_DB_URL','TEST_DB_USERNAME','TEST_DB_PASSWORD','DB_URL','DB_USERNAME','DB_PASSWORD','BOOTSTRAP_ENABLED','BOOTSTRAP_USERNAME','BOOTSTRAP_PASSWORD','SERVER_ADDRESS','SERVER_PORT','LOG_FILE','MYSQL_PWD')
$savedEnvironment = @{}
foreach ($name in $changedNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Get-FreeLocalPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

function Wait-Health([string]$Url) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($appProcess.HasExited) { throw "Application exited; see $runPath" }
        try {
            $reply = Invoke-RestMethod -Uri $Url -TimeoutSec 2
            if ($reply.data -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "Application did not become ready; see $runPath"
}

try {
    $mysqlPort = Get-FreeLocalPort
    & $mysqlServer --no-defaults --initialize-insecure "--datadir=$dataPath" --console 2>&1 | Out-File (Join-Path $runPath 'mysql-init.log')
    if ($LASTEXITCODE -ne 0) { throw "MySQL initialization failed; see $runPath" }
    $mysqlArguments = @('--no-defaults', ('--datadir="' + $dataPath + '"'), '--bind-address=127.0.0.1', "--port=$mysqlPort", '--mysqlx=0', '--skip-log-bin', '--innodb-buffer-pool-size=64M', '--console')
    $mysqlProcess = Start-Process -FilePath $mysqlServer -ArgumentList $mysqlArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'mysql-out.log') -RedirectStandardError (Join-Path $runPath 'mysql-error.log')
    $env:MYSQL_PWD = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($mysqlProcess.HasExited) { throw "MySQL exited; see $runPath" }
        & $mysqlAdmin --no-defaults --protocol=tcp -h 127.0.0.1 "-P$mysqlPort" -u root ping 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE -ne 0) { throw "MySQL did not become ready; see $runPath" }
    $testPassword = [guid]::NewGuid().ToString('N')
    $initSql = "CREATE DATABASE compliance_platform_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'stage1_test'@'127.0.0.1' IDENTIFIED BY '$testPassword'; GRANT ALL PRIVILEGES ON compliance_platform_test.* TO 'stage1_test'@'127.0.0.1';"
    $initSql | & $mysqlClient --no-defaults --protocol=tcp -h 127.0.0.1 "-P$mysqlPort" -u root --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw 'Test database setup failed' }
    $env:TEST_DB_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/compliance_platform_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    $env:TEST_DB_USERNAME = 'stage1_test'
    $env:TEST_DB_PASSWORD = $testPassword
    Push-Location (Join-Path $workspacePath 'backend')
    try {
        & mvn "-Dmaven.repo.local=$workspacePath\.cache\maven" -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' }
    } finally { Pop-Location }

    # Exercise the packaged application over real HTTP, first with CRUD disabled.
    $env:DB_URL = $env:TEST_DB_URL
    $env:DB_USERNAME = $env:TEST_DB_USERNAME
    $env:DB_PASSWORD = $env:TEST_DB_PASSWORD
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = [string](Get-FreeLocalPort)
    $env:LOG_FILE = Join-Path $runPath 'application.log'
    $env:BOOTSTRAP_ENABLED = 'false'
    $appJar = Join-Path $workspacePath 'backend\target\compliance-platform-0.1.0-SNAPSHOT.jar'
    $appProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar', ('"' + $appJar + '"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'app-locked-out.log') -RedirectStandardError (Join-Path $runPath 'app-locked-error.log')
    $appUrl = "http://127.0.0.1:$env:SERVER_PORT"
    Wait-Health "$appUrl/health"
    try {
        Invoke-WebRequest "$appUrl/api/bootstrap/users" -TimeoutSec 5 | Out-Null
        throw 'Disabled CRUD unexpectedly accessible'
    } catch {
        if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401) { throw }
    }
    Stop-Process -Id $appProcess.Id
    $appProcess.WaitForExit()
    $appProcess = $null

    $env:BOOTSTRAP_ENABLED = 'true'
    $env:BOOTSTRAP_USERNAME = 'stage1-check'
    $env:BOOTSTRAP_PASSWORD = [guid]::NewGuid().ToString('N')
    $appProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar', ('"' + $appJar + '"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runPath 'app-enabled-out.log') -RedirectStandardError (Join-Path $runPath 'app-enabled-error.log')
    Wait-Health "$appUrl/health"
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${env:BOOTSTRAP_USERNAME}:${env:BOOTSTRAP_PASSWORD}"))
    $headers = @{Authorization = "Basic $basic"}
    $userPassword = [guid]::NewGuid().ToString('N')
    $body = @{username='http_acceptance';password=$userPassword;displayName='HTTP验收员工';email='acceptance@example.test'} | ConvertTo-Json
    $created = Invoke-RestMethod "$appUrl/api/bootstrap/users" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 10
    if ($created.code -ne 0 -or -not $created.data.id -or ($created | ConvertTo-Json -Depth 5).Contains($userPassword)) { throw 'Create HTTP acceptance failed' }
    $id = $created.data.id
    $read = Invoke-RestMethod "$appUrl/api/bootstrap/users/$id" -Headers $headers -TimeoutSec 5
    if ($read.data.username -ne 'http_acceptance') { throw 'Read HTTP acceptance failed' }
    $updatedBody = @{displayName='HTTP Updated';email=$null;departmentId=$null;enabled=$false} | ConvertTo-Json
    $updated = Invoke-RestMethod "$appUrl/api/bootstrap/users/$id" -Method Put -Headers $headers -ContentType 'application/json' -Body $updatedBody -TimeoutSec 5
    if ($updated.data.enabled -ne $false) { throw 'Update HTTP acceptance failed' }
    $deleted = Invoke-RestMethod "$appUrl/api/bootstrap/users/$id" -Method Delete -Headers $headers -TimeoutSec 5
    if ($deleted.code -ne 0) { throw 'Delete HTTP acceptance failed' }
    $passwordLeak = Get-ChildItem $runPath -Filter '*.log' | Select-String -SimpleMatch -Pattern $userPassword,$env:BOOTSTRAP_PASSWORD,$testPassword
    if ($passwordLeak) { throw 'Unexpected credentials in application logs' }
    Write-Output 'STAGE1_VERIFIED: fresh MySQL migrations, integration tests, packaged startup, default deny, HTTP CRUD, credential log check'
    Write-Output "Verification evidence: $runPath"
} finally {
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id }
    if ($mysqlProcess -and -not $mysqlProcess.HasExited) {
        $env:MYSQL_PWD = $null
        & $mysqlAdmin --no-defaults --protocol=tcp -h 127.0.0.1 "-P$mysqlPort" -u root shutdown 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0 -and -not $mysqlProcess.HasExited) { Stop-Process -Id $mysqlProcess.Id }
    }
    foreach ($name in $changedNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
