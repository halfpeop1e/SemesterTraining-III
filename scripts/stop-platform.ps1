[CmdletBinding()]
param(
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$composeArgs = @('compose', 'down')
if ($RemoveData) { $composeArgs += '--volumes' }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose shutdown failed.' }
