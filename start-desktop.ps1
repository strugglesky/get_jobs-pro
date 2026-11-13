$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$port = 9529
$healthUrl = "http://127.0.0.1:$port/api/auth/session"
$logPath = Join-Path $env:TEMP 'get-jobs-desktop.log'
$errorLogPath = Join-Path $env:TEMP 'get-jobs-desktop-error.log'

function Test-ApplicationHealthy {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        return ($response.authenticated -is [bool]) -and ($response.configured -is [bool])
    }
    catch {
        return $false
    }
}

if (Test-ApplicationHealthy) {
    $answer = Read-Host 'The application is running normally. Close it? (Y/N)'
    if ($answer -notmatch '^(Y|YES)$') {
        Write-Host 'Keeping the application running.'
        exit 0
    }

    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $listener) {
        throw 'Could not find the application process listening on port 9529.'
    }

    $applicationProcess = Get-Process -Id $listener.OwningProcess
    Stop-Process -InputObject $applicationProcess -Force
    if (-not $applicationProcess.WaitForExit(15000)) {
        throw 'The application did not stop.'
    }
    Write-Host 'Application stopped.'
    exit 0
}

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    throw 'Port 9529 is already in use, but the application health check failed. Not starting another instance.'
}

if (-not (Test-Path 'db/getjobs.db')) {
    throw 'Missing db/getjobs.db. Download the database template and see README.'
}

$scriptPath = Join-Path $PSScriptRoot 'start-local.ps1'
$process = Start-Process -FilePath 'powershell.exe' `
    -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -PrebuildFrontend" `
    -WorkingDirectory $PSScriptRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $logPath `
    -RedirectStandardError $errorLogPath `
    -PassThru

Write-Host 'Starting the application in the background...'
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    Start-Sleep -Seconds 1
    if (Test-ApplicationHealthy) {
        Write-Host 'Application is running at http://127.0.0.1:9529'
        exit 0
    }
    if ($process.HasExited) {
        break
    }
}

Write-Host 'Application did not become healthy. Recent startup output:'
if (Test-Path $logPath) { Get-Content $logPath -Tail 30 }
if (Test-Path $errorLogPath) { Get-Content $errorLogPath -Tail 30 }
exit 1
