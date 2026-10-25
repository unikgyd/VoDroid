param([switch]$Offline, [switch]$DirectNetwork)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskArgs = @(':core:test', ':app:assembleDebug', ':app:lintDebug')
    if ($Offline) { $taskArgs = @('--offline') + $taskArgs }
    if ($DirectNetwork) { $taskArgs = @('-Dhttp.nonProxyHosts=*', '-Dhttps.nonProxyHosts=*') + $taskArgs }
    & .\gradlew.bat @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed' }
    python scripts/package.py --variant debug
    if ($LASTEXITCODE -ne 0) { throw 'Packaging or APK verification failed' }
} finally { Pop-Location }
