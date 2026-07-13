[CmdletBinding()]
param(
    [switch]$Lab,
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$TrainId = 'LB',
    [switch]$Build,
    [int]$WaitSeconds = 90
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Get-ComposeSetting([string]$Name, [string]$DefaultValue) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value) -and (Test-Path '.env')) {
        $line = Get-Content '.env' | Where-Object {
            $_ -match ("^\s*" + [regex]::Escape($Name) + "\s*=")
        } | Select-Object -Last 1
        if ($line) {
            $value = ($line -replace ("^\s*" + [regex]::Escape($Name) + "\s*=\s*"), '').Trim()
            $value = $value.Trim('"').Trim("'")
        }
    }
    if ([string]::IsNullOrWhiteSpace($value)) { return $DefaultValue }
    return $value
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop was not found. Start Docker Desktop and try again.'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $desktopExe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $desktopExe)) {
        throw 'Docker Desktop is not running and its executable was not found.'
    }
    Start-Process -FilePath $desktopExe -WindowStyle Hidden
    $dockerDeadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Seconds 3
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
    } while ((Get-Date) -lt $dockerDeadline)
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop did not become ready within 90 seconds.'
    }
}

$backendPort = [int](Get-ComposeSetting 'BACKEND_PORT' '8080')
$controlCenterPort = [int](Get-ComposeSetting 'CONTROL_CENTER_PORT' '5173')
$onboardPort = [int](Get-ComposeSetting 'ONBOARD_PORT' '5174')
foreach ($port in $backendPort, $controlCenterPort, $onboardPort) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "Port $port is already in use by process $($listener.OwningProcess). Stop the existing local service, or change the matching port in .env before starting Docker."
    }
}

$env:HIL_TRAIN_ID = $TrainId
if ($Lab) {
    $env:HIL_ENABLED = 'true'
    $env:HIL_SIGNAL_SCREEN_ENABLED = 'true'
    $env:HIL_NETWORK_SCREEN_ENABLED = 'true'
    $env:HIL_VISION_ENABLED = 'true'
    $env:PLC_AUTO_START = 'false'
    $env:PLC_OUTPUT_ENABLED = 'false'
    Write-Host "Laboratory mode: train ID=$TrainId; network, signal, and Vision outputs are enabled." -ForegroundColor Cyan
    Write-Host 'PLC is not auto-connected. Create and bring the same train ID online, then click 704 Connect in the onboard page.' -ForegroundColor Yellow
} else {
    $env:HIL_ENABLED = 'false'
    $env:HIL_SIGNAL_SCREEN_ENABLED = 'false'
    $env:HIL_NETWORK_SCREEN_ENABLED = 'false'
    $env:HIL_VISION_ENABLED = 'false'
    $env:PLC_AUTO_START = 'false'
    $env:PLC_OUTPUT_ENABLED = 'false'
    Write-Host 'Local simulation mode: no laboratory device will be connected or written.' -ForegroundColor Green
}

$composeArgs = @('compose', 'up', '-d')
if ($Build) { $composeArgs += '--build' }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed. Run docker compose logs to inspect it.' }

$healthUrl = "http://localhost:$backendPort/api/health"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($health.success -eq $true) { break }
    } catch {
        Start-Sleep -Seconds 2
    }
} while ((Get-Date) -lt $deadline)

if ($health.success -ne $true) {
    docker compose ps
    throw "Backend health check did not pass within $WaitSeconds seconds. Run docker compose logs backend."
}

Write-Host ''
Write-Host 'Started:' -ForegroundColor Green
Write-Host "  Control center: http://localhost:$controlCenterPort"
Write-Host "  Onboard system: http://localhost:$onboardPort"
Write-Host "  Backend health: http://localhost:$backendPort/api/health"
if ($Lab) {
    Write-Host "  HIL status:     http://localhost:$backendPort/api/hil/status"
}
