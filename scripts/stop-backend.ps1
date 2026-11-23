. "$PSScriptRoot\environment.ps1"
$pidFile = Join-Path $projectRoot '.runtime\backend.pid'
if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host 'No local development backend PID file. Stop external-database mode with Ctrl+C in its terminal.'
    exit 0
}
$backendProcessId = [int](Get-Content -LiteralPath $pidFile -Raw)
$backendProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$backendProcessId" -ErrorAction SilentlyContinue
if (-not $backendProcess -or $backendProcess.CommandLine -notmatch 'dev\.messageboard\.DevApplication') {
    throw 'PID is stale or not the development backend. No process was stopped.'
}
# The dev entry point closes HTTP and the connection pool before PostgreSQL.
Set-Content -LiteralPath (Join-Path $projectRoot '.runtime\stop-backend') -Value 'stop' -Encoding Ascii
for ($attempt=0; $attempt -lt 30; $attempt++) {
    if (-not (Test-Path -LiteralPath $pidFile)) { Write-Host 'Backend and local PostgreSQL stopped.'; exit 0 }
    Start-Sleep -Milliseconds 500
}
throw 'Graceful shutdown is taking longer than expected. Check the backend terminal.'
